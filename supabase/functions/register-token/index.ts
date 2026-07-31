// T15: Register Token FCM - Edge Function
// Upsert de device_push_tokens
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
    const message = error instanceof Error ? error.message : String(error);
    console.error("Register token error:", message);
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

if (import.meta.main) {
  serve(handleRequest);
}
