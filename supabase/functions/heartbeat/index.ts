// T15: Heartbeat - Edge Function
// Actualiza device_heartbeats y devices.last_seen_at

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(
        JSON.stringify({ error: "Token requerido" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const token = authHeader.replace("Bearer ", "");
    const payload = JSON.parse(atob(token.split(".")[1]));
    const deviceId = payload.device_id;

    if (!deviceId) {
      return new Response(
        JSON.stringify({ error: "device_id no encontrado en token" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

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
    console.error("Heartbeat error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
