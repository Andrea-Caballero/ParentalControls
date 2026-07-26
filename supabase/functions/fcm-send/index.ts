// T15: FCM Fan-out - Edge Function
// Envía notificación push a dispositivo ante cambios de política

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

interface FcmPayload {
  type: string;
  [key: string]: unknown;
}

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Solo backend (service_role) puede invocar esta función
    const authHeader = req.headers.get("Authorization");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

    if (authHeader !== `Bearer ${serviceKey}`) {
      return new Response(
        JSON.stringify({ error: "No autorizado" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const { device_id, payload, priority = "high" } = (await req.json()) as {
      device_id: string;
      payload: FcmPayload;
      priority?: "high" | "normal";
    };

    if (!device_id || !payload) {
      return new Response(
        JSON.stringify({ error: "device_id y payload son requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      serviceKey ?? ""
    );

    // Buscar token activo
    const { data: tokenRecord, error } = await supabase
      .from("device_push_tokens")
      .select("token")
      .eq("device_id", device_id)
      .eq("is_active", true)
      .limit(1)
      .single();

    if (error || !tokenRecord) {
      return new Response(
        JSON.stringify({
          success: false,
          reason: "No se encontró token FCM activo",
          device_id,
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Enviar a FCM
    const result = await sendFcm(tokenRecord.token, payload, priority);

    return new Response(
      JSON.stringify({
        success: true,
        sent_to: device_id,
        fcm_response: result,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("FCM send error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

async function sendFcm(
  token: string,
  payload: Record<string, unknown>,
  priority: "high" | "normal"
): Promise<Record<string, unknown>> {
  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  if (!serverKey) {
    console.warn("FCM_SERVER_KEY not configured, returning mock response");
    return { success: true, mock: true };
  }

  try {
    const response = await fetch(fcmUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `key=${serverKey}`,
      },
      body: JSON.stringify({
        to: token,
        priority: priority,
        data: {
          ...payload,
          sent_at: new Date().toISOString(),
        },
      }),
    });

    const result = await response.json();

    if (!response.ok) {
      console.error("FCM error response:", result);
      return { success: false, error: result };
    }

    // Verificar si FCM procesó correctamente
    if (result.failure > 0 && result.results?.[0]?.error) {
      return { success: false, fcm_error: result.results[0].error };
    }

    return {
      success: true,
      message_id: result.results?.[0]?.message_id,
    };
  } catch (error) {
    console.error("FCM network error:", error);
    return { success: false, network_error: String(error) };
  }
}
