// T15: GET Policy - Edge Function
// Devuelve el JSON de política ensamblado según §0.3

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { parseDeviceId } from "../_shared/jwt.ts";
import { errorMessage } from "../_shared/error.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// Re-export so the existing test suite (and any future caller that
// imports the helper by the index path) keeps working without churn.
export { parseDeviceId };

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Extraer device_id del JWT (inyectado por hook de T14)
    const auth = parseDeviceId(req.headers.get("Authorization"));
    if (auth.status !== 200) {
      return new Response(
        JSON.stringify({ error: auth.error }),
        { status: auth.status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const deviceId = auth.deviceId;

    // Cliente con service_role para bypass RLS en get_device_policy
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Llamar a la función SQL get_device_policy
    const { data: policy, error } = await supabaseAdmin.rpc("get_device_policy", {
      target_device_id: deviceId,
    });

    if (error) {
      throw new Error(`Error obteniendo política: ${errorMessage(error)}`);
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
});
