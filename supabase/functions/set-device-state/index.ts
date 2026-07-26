// WU-2 — set-device-state Edge Function.
//
// POST /functions/v1/set-device-state
// Body: { device_id: <uuid>, state: "LOCKED" | "ACTIVE" }
//
// Why a dedicated edge function instead of direct PostgREST PATCH:
//   `supabase/migrations/002_rls_policies.sql` defines
//   `devices_parent_select` (parent can read their own devices) but
//   no `devices_parent_update` — parent-side state mutation is
//   intentionally restricted. This mirrors the production design
//   in `supabase/functions/create-pairing-code/index.ts:21-30`: we
//   run as `service_role` after validating the parent's JWT and
//   confirming the parent's `parent_id = auth.uid()` ownership of
//   the device row. Without this contract, a parent could mutate
//   anyone else's device_state via direct PostgREST.
//
// Wire format:
//   Request:  Authorization: Bearer <parent JWT>, apikey: <anon>,
//             Content-Type: application/json,
//             Body: {"device_id":"<uuid>","state":"LOCKED|ACTIVE"}
//   Response: 200 with { success: true, device_id, state,
//             policy_version, updated_at }
//             401 if the JWT is missing or invalid,
//             403 if the JWT is valid but doesn't own the device,
//             422 if state is missing or not in {LOCKED, ACTIVE}.
//
// On success, `policy_version` is incremented by 1 so the child
// device's policy_sync path picks up the new state on its next
// `get-policy` call. The child enforces the new state on next-sync
// per Slice A — we deliberately do NOT introduce a separate FCM
// push or realtime channel in this work unit.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const ALLOWED_STATES = new Set(["LOCKED", "ACTIVE"]);

export async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return new Response(
      JSON.stringify({ error: "Token requerido" }),
      {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  const token = authHeader.replace("Bearer ", "");

  let body: { device_id?: string; state?: string };
  try {
    body = await req.json();
  } catch (_e) {
    return new Response(
      JSON.stringify({ error: "Body JSON inválido" }),
      {
        status: 422,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  const deviceId = body.device_id;
  const state = body.state?.toUpperCase();

  if (!deviceId) {
    return new Response(
      JSON.stringify({ error: "device_id es requerido" }),
      {
        status: 422,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }
  if (!state || !ALLOWED_STATES.has(state)) {
    return new Response(
      JSON.stringify({
        error: "state debe ser uno de LOCKED, ACTIVE",
      }),
      {
        status: 422,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  // Validate the parent's JWT against the ANON key. Mirrors
  // `create-pairing-code/index.ts:55-71`. The `service_role` client
  // (set up below) holds the actual UPDATE authority.
  const anonClient = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    {
      auth: { persistSession: false },
      global: { headers: { Authorization: authHeader } },
    }
  );

  const { data: userData, error: userError } = await anonClient.auth.getUser(token);
  if (userError || !userData?.user) {
    return new Response(
      JSON.stringify({ error: "Token inválido o expirado" }),
      {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  const parentId = userData.user.id;

  // Run as service_role so we can bypass the missing
  // `devices_parent_update` RLS policy. We re-check ownership
  // explicitly below so this isn't a privilege escalation: we only
  // mutate rows whose `parent_id` matches the validated `parentId`.
  const serviceClient = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
  );

  // Ownership check — single read so the UPDATE only fires for
  // devices the parent actually owns. Returning 403 (not 404)
  // matches REST conventions: the JWT is valid, the resource is
  // forbidden, the caller should not retry.
  const { data: existing, error: existingError } = await serviceClient
    .from("devices")
    .select("id, parent_id, policy_version")
    .eq("id", deviceId)
    .maybeSingle();

  if (existingError) {
    return new Response(
      JSON.stringify({ error: existingError.message }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }
  if (!existing) {
    return new Response(
      JSON.stringify({ error: "device_id no encontrado" }),
      {
        status: 404,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }
  if (existing.parent_id !== parentId) {
    return new Response(
      JSON.stringify({ error: "El dispositivo no pertenece al padre" }),
      {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  const newPolicyVersion = (existing.policy_version ?? 0) + 1;
  const { data: updated, error: updateError } = await serviceClient
    .from("devices")
    .update({
      device_state: state,
      policy_version: newPolicyVersion,
      updated_at: new Date().toISOString(),
    })
    .eq("id", deviceId)
    .select("id, device_state, policy_version, updated_at")
    .single();

  if (updateError || !updated) {
    return new Response(
      JSON.stringify({
        error: updateError?.message ?? "Error actualizando dispositivo",
      }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }

  return new Response(
    JSON.stringify({
      success: true,
      device_id: updated.id,
      state: updated.device_state,
      policy_version: updated.policy_version,
      updated_at: updated.updated_at,
    }),
    { headers: { ...corsHeaders, "Content-Type": "application/json" } }
  );
}

if (import.meta.main) {
  serve(handleRequest);
}
