// T15: Reward Grant - Edge Function
// Crea grant de recompensa respetando topes (T29)

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// Topes por día para recompensas (configurable)
const REWARD_LIMITS = {
  daily_max_minutes: 60, // Máximo 1 hora de recompensa por día
  weekly_max_minutes: 180, // Máximo 3 horas por semana
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
    const jwtPayload = JSON.parse(atob(token.split(".")[1]));
    const parentId = jwtPayload.sub;

    if (!parentId) {
      return new Response(
        JSON.stringify({ error: "Usuario no autenticado" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { device_id, minutes, reason } = await req.json();

    if (!device_id || !minutes) {
      return new Response(
        JSON.stringify({ error: "device_id y minutes son requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Verificar que el dispositivo pertenece al padre
    const { data: device, error: deviceError } = await supabaseAdmin
      .from("devices")
      .select("*")
      .eq("id", device_id)
      .eq("parent_id", parentId)
      .single();

    if (deviceError || !device) {
      return new Response(
        JSON.stringify({ error: "Dispositivo no encontrado o no autorizado" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 2. Calcular grants de recompensa usados hoy y esta semana
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const weekAgo = new Date(today);
    weekAgo.setDate(weekAgo.getDate() - 7);

    const { data: recentRewards } = await supabaseAdmin
      .from("grants")
      .select("minutes, granted_at")
      .eq("device_id", device_id)
      .eq("source", "REWARD")
      .eq("status", "APPROVED")
      .gte("granted_at", weekAgo.toISOString());

    const dailyUsed = recentRewards
      ?.filter((g) => new Date(g.granted_at) >= today)
      .reduce((sum, g) => sum + g.minutes, 0) || 0;

    const weeklyUsed = recentRewards?.reduce((sum, g) => sum + g.minutes, 0) || 0;

    // 3. Verificar topes
    const remainingDaily = Math.max(0, REWARD_LIMITS.daily_max_minutes - dailyUsed);
    const remainingWeekly = Math.max(0, REWARD_LIMITS.weekly_max_minutes - weeklyUsed);

    if (minutes > remainingDaily) {
      return new Response(
        JSON.stringify({
          error: `Excede límite diario. Disponible: ${remainingDaily} minutos`,
          daily_limit: REWARD_LIMITS.daily_max_minutes,
          daily_used: dailyUsed,
          requested: minutes,
        }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (minutes > remainingWeekly) {
      return new Response(
        JSON.stringify({
          error: `Excede límite semanal. Disponible: ${remainingWeekly} minutos`,
          weekly_limit: REWARD_LIMITS.weekly_max_minutes,
          weekly_used: weeklyUsed,
          requested: minutes,
        }),
        { status: 429, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 4. Crear grant
    const expiresAt = new Date();
    expiresAt.setDate(expiresAt.getDate() + 7); // Rewards expiran en 7 días

    const { data: grant, error: grantError } = await supabaseAdmin
      .from("grants")
      .insert({
        device_id: device_id,
        request_id: null, // Rewards no tienen request
        scope: "device",
        minutes: minutes,
        source: "REWARD",
        status: "APPROVED",
        expires_at: expiresAt.toISOString(),
        granted_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (grantError) {
      throw new Error(`Error creando grant: ${grantError.message}`);
    }

    // 5. Bump de versión (automático via trigger)

    // 6. Enviar FCM
    await sendFcmToDevice(supabaseAdmin, device_id, {
      type: "REWARD_GRANTED",
      grant_id: grant.id,
      minutes: minutes,
      reason: reason || "Recompensa",
      expires_at: expiresAt.toISOString(),
    });

    return new Response(
      JSON.stringify({
        success: true,
        grant_id: grant.id,
        minutes: minutes,
        expires_at: expiresAt.toISOString(),
        remaining_daily: remainingDaily - minutes,
        remaining_weekly: remainingWeekly - minutes,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Reward error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

async function sendFcmToDevice(
  supabase: ReturnType<typeof createClient>,
  deviceId: string,
  payload: Record<string, unknown>
): Promise<void> {
  const { data: tokenRecord } = await supabase
    .from("device_push_tokens")
    .select("token")
    .eq("device_id", deviceId)
    .eq("is_active", true)
    .limit(1)
    .single();

  if (!tokenRecord) {
    console.log("No FCM token for device:", deviceId);
    return;
  }

  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  try {
    await fetch(fcmUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `key=${serverKey}`,
      },
      body: JSON.stringify({
        to: tokenRecord.token,
        priority: "high",
        data: payload,
      }),
    });
  } catch (e) {
    console.error("FCM send error:", e);
  }
}
