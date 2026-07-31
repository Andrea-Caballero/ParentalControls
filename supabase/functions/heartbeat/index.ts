// T15: Heartbeat - Edge Function
// Actualiza device_heartbeats y devices.last_seen_at
//
// BLK-01 hardening: device_id now comes from the verified user's
// `app_metadata.device_id` (server-side state set by the pairing
// flow), not from a manually decoded JWT payload. The service-role
// client is constructed only after Supabase Auth has cryptographically
// validated the Bearer token via `supabase.auth.getUser`.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verifyAuth } from "../_shared/jwt.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

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
  const deviceId = auth.deviceId as string;

  try {
    const {
      battery_level,
      is_charging,
      app_in_foreground,
      enforcement_level,
      suspicion_level,
      clock_offset_ms,
      payload: extra_payload,
    } = await req.json();

    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Obtener policy_version actual
    const { data: device } = await supabaseAdmin
      .from("devices")
      .select("policy_version")
      .eq("id", deviceId)
      .single();

    if (!device) {
      return new Response(
        JSON.stringify({ error: "Dispositivo no encontrado" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Insertar heartbeat
    const { error: heartbeatError } = await supabaseAdmin
      .from("device_heartbeats")
      .insert({
        device_id: deviceId,
        timestamp: new Date().toISOString(),
        battery_level: battery_level ?? null,
        is_charging: is_charging ?? false,
        app_in_foreground: app_in_foreground ?? null,
        policy_version: device.policy_version,
        enforcement_level: enforcement_level ?? null,
        suspicion_level: suspicion_level ?? null,
        payload: extra_payload ? JSON.parse(extra_payload) : {},
      });

    if (heartbeatError) {
      console.error("Heartbeat insert error:", heartbeatError);
      // No fallar por error de heartbeat, solo log
    }

    // 3. Actualizar last_seen_at
    await supabaseAdmin
      .from("devices")
      .update({ last_seen_at: new Date().toISOString() })
      .eq("id", deviceId);

    // 4. Verificar si hay alerta de clock_offset sospechosa
    if (clock_offset_ms !== undefined && Math.abs(clock_offset_ms) > 300000) {
      // > 5 minutos
      console.warn(`Clock offset suspect: ${clock_offset_ms}ms for device ${deviceId}`);
      // Registrar en outbox
      await supabaseAdmin.from("outbox").insert({
        device_id: deviceId,
        tipo: "clock_tamper_suspected",
        payload: {
          clock_offset_ms,
          detected_at: new Date().toISOString(),
          threshold: 300000,
        },
        dedup_key: `heartbeat_clock_${deviceId}_${Math.floor(Date.now() / 3600000)}`,
      });
    }

    return new Response(
      JSON.stringify({
        success: true,
        policy_version: device.policy_version,
        server_time: new Date().toISOString(),
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("Heartbeat error:", message);
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

if (import.meta.main) {
  serve(handleRequest);
}
