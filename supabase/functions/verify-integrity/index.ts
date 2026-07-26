// T15: Verify Play Integrity - Edge Function
// Verifica token de integridad contra Google Play

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// Play Integrity API endpoint
const PLAY_INTEGRITY_URL = "https://playintegritymanager.googleapis.com/v1";

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

    const { integrity_token } = await req.json();

    if (!integrity_token) {
      return new Response(
        JSON.stringify({ error: "integrity_token es requerido" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Verificar token contra Google Play
    const verificationResult = await verifyWithGoogle(integrity_token);

    // Registrar veredicto
    await supabaseAdmin.from("outbox").insert({
      device_id: deviceId,
      tipo: "integrity_verification",
      payload: {
        integrity_token_hash: await hashString(integrity_token),
        verdict: verificationResult,
        verified_at: new Date().toISOString(),
      },
      dedup_key: `integrity_${deviceId}_${Math.floor(Date.now() / 3600000)}`,
    });

    // Si el veredicto es negativo, crear alerta
    if (!verificationResult.is_valid) {
      await supabaseAdmin.from("outbox").insert({
        device_id: deviceId,
        tipo: "integrity_failure_alert",
        payload: {
          reason: verificationResult.failure_reason,
          details: verificationResult,
          flagged_at: new Date().toISOString(),
        },
        dedup_key: `integrity_alert_${deviceId}_${Math.floor(Date.now() / 3600000)}`,
      });
    }

    return new Response(
      JSON.stringify({
        valid: verificationResult.is_valid,
        details: verificationResult,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Verify integrity error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
});

interface IntegrityVerdict {
  is_valid: boolean;
  device_integrity?: string;
  app_integrity?: string;
  account_details?: string;
  failure_reason?: string;
}

async function verifyWithGoogle(integrityToken: string): Promise<IntegrityVerdict> {
  const packageName = Deno.env.get("PLAY_PACKAGE_NAME");
  const serviceAccountJson = Deno.env.get("GOOGLE_SERVICE_ACCOUNT");

  if (!packageName || !serviceAccountJson) {
    console.warn("Play Integrity not configured, returning mock result");
    // En desarrollo, devolver resultado mock
    return {
      is_valid: true,
      device_integrity: "MEETS_PLAY_INTEGRITY",
      app_integrity: "VALID",
      account_details: "PLAY_RECOGNIZED",
    };
  }

  try {
    // Obtener access token desde service account
    const serviceAccount = JSON.parse(serviceAccountJson);
    const accessToken = await getGoogleAccessToken(serviceAccount);

    // Decodificar token (base64url)
    const tokenPayload = JSON.parse(atob(integrityToken.split(".")[1]));

    // Llamar a Play Integrity API
    const response = await fetch(
      `${PLAY_INTEGRITY_URL}/players/${packageName}/token:decode`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          integrity_token: integrityToken,
        }),
      }
    );

    if (!response.ok) {
      const errorText = await response.text();
      console.error("Play Integrity API error:", errorText);
      return {
        is_valid: false,
        failure_reason: `API_ERROR: ${response.status}`,
      };
    }

    const result = await response.json();

    // Analizar veredicto
    const payload = result.tokenPayloadExternal || {};
    const device = payload.deviceIntegrity?.deviceRecognitionVerdict?.[0] || "";
    const app = payload.appIntegrity?.appRecognitionVerdict || "";
    const account = payload.accountDetails?.appLicensingVerdict || "";

    const isValid =
      device === "MEETS_PLAY_INTEGRITY" &&
      app === "APP_VALID" &&
      account === "LICENSED";

    return {
      is_valid: isValid,
      device_integrity: device,
      app_integrity: app,
      account_details: account,
      failure_reason: !isValid
        ? `device=${device}, app=${app}, account=${account}`
        : undefined,
    };
  } catch (error) {
    console.error("Google verification error:", error);
    return {
      is_valid: false,
      failure_reason: `EXCEPTION: ${error.message}`,
    };
  }
}

async function getGoogleAccessToken(serviceAccount: {
  client_email: string;
  private_key: string;
}): Promise<string> {
  const now = Math.floor(Date.now() / 1000);

  const header = btoa(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = btoa(
    JSON.stringify({
      iss: serviceAccount.client_email,
      scope: "https://www.googleapis.com/auth/playintegrity",
      aud: "https://oauth2.googleapis.com/token",
      exp: now + 3600,
      iat: now,
    })
  );

  const signatureInput = `${header}.${payload}`;

  // Firmar con PKCS1v1.5 (necesario para Google)
  const privateKeyBuffer = derToPem(serviceAccount.private_key);

  const key = await crypto.subtle.importKey(
    "pkcs8",
    strToArrayBuffer(atob(privateKeyBuffer.replace(/-----.*-----/g, ""))),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signatureInput)
  );

  const jwt = `${signatureInput}.${btoa(String.fromCharCode(...new Uint8Array(signature)))}`;

  // Intercambiar por access token
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  const tokenData = await tokenResponse.json();
  return tokenData.access_token;
}

function derToPem(der: string): string {
  const base64 = btoa(der);
  const chunks = base64.match(/.{1,64}/g) || [];
  return `-----BEGIN PRIVATE KEY-----\n${chunks.join("\n")}\n-----END PRIVATE KEY-----`;
}

function strToArrayBuffer(str: string): Uint8Array {
  return new Uint8Array(
    atob(str)
      .split("")
      .map((c) => c.charCodeAt(0))
  );
}

async function hashString(str: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(str);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
