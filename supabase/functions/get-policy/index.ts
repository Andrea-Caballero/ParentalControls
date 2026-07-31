// T15: GET Policy - Edge Function
// Devuelve el JSON de política ensamblado según §0.3
//
// BLK-01 hardening: this handler used to derive `device_id` from a
// manually base64-decoded JWT payload — that decode does NOT verify
// the signature, expiration, or revocation. An attacker could craft
// any `device_id` they wanted. The handler now uses
// `supabase.auth.getUser(token)` to perform cryptographic
// verification (via the shared `_shared/jwt.ts` helper) and reads
// `device_id` from the server-controlled `user.app_metadata.device_id`
// (set by the pairing RPC). The service-role client is only created
// AFTER a verified identity has been returned, so failed
// authentication cannot reach privileged code paths.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verifyAuth } from "../_shared/jwt.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

/** Exported for the accompanying test in `index_test.ts`. */
export async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const auth = await verifyAuth({
    authHeader: req.headers.get("Authorization"),
    corsHeaders,
    env: {
      url: Deno.env.get("SUPABASE_URL") ?? "",
      anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    },
    requireDevice: true,
  });
  if (!auth.ok) return auth.response;
  // auth.deviceId is guaranteed to be a non-empty string when
  // requireDevice:true and verification succeeded.
  const deviceId = auth.deviceId as string;

  try {
    // Cliente con service_role para bypass RLS en get_device_policy.
    // Constructed ONLY after the JWT has been cryptographically
    // verified — see BLK-01.
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Llamar a la función SQL get_device_policy
    const { data: policy, error } = await supabaseAdmin.rpc("get_device_policy", {
      target_device_id: deviceId,
    });

    if (error) {
      throw new Error(`Error obteniendo política: ${error.message}`);
    }

    if (!policy) {
      return new Response(
        JSON.stringify({ error: "Dispositivo no encontrado" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Enrich con datos adicionales del dispositivo
    const { data: device } = await supabaseAdmin
      .from("devices")
      .select("device_name, app_version, last_seen_at")
      .eq("id", deviceId)
      .single();

    const enrichedPolicy = {
      ...policy,
      device_name: device?.device_name,
      app_version: device?.app_version,
      last_sync_at: device?.last_seen_at,
      fetched_at: new Date().toISOString(),
    };

    return new Response(
      JSON.stringify(enrichedPolicy),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("Get policy error:", message);
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

if (import.meta.main) {
  serve(handleRequest);
}
