// PR 2 of openspec/changes/wire-pairing-and-approval-end-to-end.
//
// Edge function: GET /functions/v1/get-devices-for-parent
//
// Returns the list of `devices` rows owned by the authenticated parent.
// Uses the parent user's JWT with the ANON key so RLS does the filtering
// (the `devices_parent_select` policy in supabase/migrations/002_rls_policies.sql
// scopes by `parent_id = auth.uid()`). The service-role key is intentionally
// NOT used here — using it would bypass RLS and defeat the security model.
//
// Wire format: POST ${SUPABASE_URL}/functions/v1/get-devices-for-parent
//   Headers: Authorization: Bearer <parent JWT>, apikey: <anon>
//   Body: {} (ignored — POST is used for parity with the other edge
//   functions and to allow Supabase's gateway to forward the call).
//   Response 200: JSON array of devices.
//   Response 401: { "error": "Token requerido" | "Token inválido o expirado" }
//
// Deploy (manual step, not run from agent):
//   supabase functions deploy get-devices-for-parent --project-ref <ref>

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

/**
 * Handle an incoming request to the edge function. Exported so the
 * accompanying `index_test.ts` can drive it with a synthetic Request and
 * assert on the returned Response.
 */
export async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return new Response(
      JSON.stringify({ error: "Token requerido" }),
      { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }

  const token = authHeader.replace("Bearer ", "");

  // Use ANON key with the caller's JWT forwarded as Authorization. RLS
  // (devices_parent_select: parent_id = auth.uid()) scopes the rows.
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    {
      auth: { persistSession: false },
      global: { headers: { Authorization: authHeader } },
    }
  );

  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData?.user) {
    return new Response(
      JSON.stringify({ error: "Token inválido o expirado" }),
      { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }

  // The RLS policy `devices_parent_select` filters rows by `parent_id = auth.uid()`.
  // We do NOT add an explicit `.eq("parent_id", ...)` filter — RLS is the
  // security boundary, per the design at
  // openspec/changes/wire-pairing-and-approval-end-to-end/design.md §2.
  //
  // Change A of feat-multi-child-picker (design §A.8): extend the `.select`
  // to include `child_id` + the nested resource embedding `child:children(id, first_name)`.
  // Supabase returns `child` as either a nested object (when child_id is set)
  // or `null` (for back-filling orphans). The Kotlin parser handles both shapes.
  const { data: devices, error: devicesError } = await supabase
    .from("devices")
    .select(
      "id, device_name, device_model, os_version, app_version, device_state, " +
        "policy_version, last_seen_at, child_id, child:children(id, first_name)"
    )
    .order("last_seen_at", { ascending: false });

  if (devicesError) {
    return new Response(
      JSON.stringify({ error: devicesError.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }

  return new Response(
    JSON.stringify(devices ?? []),
    { headers: { ...corsHeaders, "Content-Type": "application/json" } }
  );
}

if (import.meta.main) {
  serve(handleRequest);
}