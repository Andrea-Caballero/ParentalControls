// T15: Register Token FCM - Edge Function
// Upsert de device_push_tokens

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
    const auth = parseDeviceId(req.headers.get("Authorization"));
    if (auth.status !== 200) {
      return new Response(
        JSON.stringify({ error: auth.error }),
        { status: auth.status, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const deviceId = auth.deviceId;

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
      throw new Error(`Error registrando token: ${errorMessage(error)}`);
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
    const message = error instanceof Error ? error.message : String(error);
    console.error("Register token error:", message);
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});
