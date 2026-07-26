// T15: Create Pairing Code - Edge Function
// Genera código de emparejamiento para el padre

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

    // Cliente admin (se crea antes para validar el JWT del padre)
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Validar el JWT contra la clave publica de Supabase (no decodificar
    // a mano como antes — Bug #1 de la auditoria de emparejamiento).
    // auth.getUser() verifica firma, expiracion y revocacion; un token
    // forjado o expirado devuelve error.
    const { data: userData, error: userError } = await supabaseAdmin.auth.getUser(token);

    if (userError || !userData?.user) {
      return new Response(
        JSON.stringify({ error: "Token invalido o expirado" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const parentId = userData.user.id;

    const { device_name, age_band = "7-12", ttl_minutes = 10 } = await req.json();

    // Generar código aleatorio (6 caracteres alfanuméricos)
    const code = generatePairingCode();

    // Calcular expiración
    const expiresAt = new Date();
    expiresAt.setMinutes(expiresAt.getMinutes() + ttl_minutes);

    // Crear registro
    const { data: pairingRecord, error } = await supabaseAdmin
      .from("pairing_codes")
      .insert({
        code: code,
        parent_id: parentId,
        device_name: device_name || `${age_band}-device`,
        expires_at: expiresAt.toISOString(),
        status: "ACTIVE",
      })
      .select()
      .single();

    if (error) {
      throw new Error(`Error creando código: ${error.message}`);
    }

    // Generar QR data URL (codificado en base64 para передачи)
    const qrData = JSON.stringify({
      code: code,
      parent: parentId,
      exp: expiresAt.getTime(),
    });
    const qrDataBase64 = btoa(qrData);

    return new Response(
      JSON.stringify({
        success: true,
        code: code,
        expires_at: expiresAt.toISOString(),
        qr_data: qrDataBase64,
        // Para debugging: QR contiene "https://app.parentalcontrol.com/pair?c=CODE"
        deeplink: `parentalcontrol://pair?code=${code}`,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Create pairing code error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

function generatePairingCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Sin O, 0, I, 1 para evitar confusión
  let code = "";
  const randomValues = new Uint8Array(8);
  crypto.getRandomValues(randomValues);
  for (let i = 0; i < 8; i++) {
    code += chars[randomValues[i] % chars.length];
  }
  return code;
}
