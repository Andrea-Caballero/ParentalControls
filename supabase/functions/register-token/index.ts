// T15: Register Token FCM - Edge Function
// Upsert de device_push_tokens

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

    const { fcm_token, platform = "ANDROID" } = await req.json();

    if (!fcm_token) {
      return new Response(
        JSON.stringify({ error: "fcm_token es requerido" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Upsert: actualizar si existe, insertar si no
    const { data: result, error } = await supabaseAdmin
      .from("device_push_tokens")
      .upsert(
        {
          device_id: deviceId,
          token: fcm_token,
          platform: platform.toUpperCase(),
          is_active: true,
          updated_at: new Date().toISOString(),
        },
        {
          onConflict: "device_id,token",
          ignoreDuplicates: false,
        }
      )
      .select()
      .single();

    if (error) {
      throw new Error(`Error registrando token: ${error.message}`);
    }

    // Desactivar tokens antiguos del mismo dispositivo
    await supabaseAdmin
      .from("device_push_tokens")
      .update({ is_active: false })
      .eq("device_id", deviceId)
      .neq("token", fcm_token);

    return new Response(
      JSON.stringify({
        success: true,
        token_id: result?.id,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Register token error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
