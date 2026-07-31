// T15: Verify Play Integrity - Edge Function
// Verifica token de integridad contra Google Play
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

// Play Integrity API endpoint
const PLAY_INTEGRITY_URL = "https://playintegritymanager.googleapis.com/v1";

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
    // Log the full error server-side for operators. Return a stable,
    // generic message to the client — internal exception strings can
    // leak provider/parser details that aren't actionable for the
    // caller and may hint at the verifier's internals.
    console.error("Verify integrity error:", error);
    return new Response(
      JSON.stringify({ error: "INTERNAL_ERROR" }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

if (import.meta.main) {
  serve(handleRequest);
}

/**
 * Stable, client-facing failure codes. Logged errors include the full
 * provider/parse exception; the client only ever sees one of these
 * codes so we never leak internal service-account details, parse
 * errors, or arbitrary provider error bodies. New entries must be
 * additive — clients parse `failure_reason` as a string.
 */
type IntegrityFailureReason =
  | "INTEGRITY_PROVIDER_ERROR"
  | "INTEGRITY_VERDICT_REJECTED";

interface IntegrityVerdict {
  is_valid: boolean;
  device_integrity?: string;
  app_integrity?: string;
  account_details?: string;
  failure_reason?: IntegrityFailureReason;
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
      // Log the full provider response body for operators; the client
      // only sees the stable failure code so we don't leak Google-side
      // error envelopes or HTTP status noise into the verdict envelope.
      const errorText = await response.text();
      console.error("Play Integrity API error:", errorText);
      return {
        is_valid: false,
        failure_reason: "INTEGRITY_PROVIDER_ERROR",
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
      // The Play verdict labels (device/app/account) are not
      // sensitive; surfacing them via `details` lets the client UI
      // branch on the rejected arm without leaking provider
      // internals. The `failure_reason` is the stable code only.
      failure_reason: !isValid ? "INTEGRITY_VERDICT_REJECTED" : undefined,
    };
  } catch (error) {
    // `error` is typed `unknown` by the ECMAScript spec. Log the raw
    // value server-side for diagnosis but never include `error.message`
    // (or any other property) in the client envelope — those can
    // surface provider stack frames, JSON parse positions, or PEM
    // fragments from the service-account key.
    console.error("Google verification error:", error);
    return {
      is_valid: false,
      failure_reason: "INTEGRITY_PROVIDER_ERROR",
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

  // `crypto.subtle.importKey` accepts `BufferSource`. Our helper
  // returns `Uint8Array<ArrayBufferLike>`, and TypeScript ≥ 5.7's
  // stricter `ArrayBufferLike` (which now includes `SharedArrayBuffer`)
  // cannot be widened into the `BufferSource` (typed as `Uint8Array<ArrayBuffer>`)
  // overload. Copy into a fresh, owned ArrayBuffer so the type narrows
  // back to `ArrayBuffer` (not the `SharedArrayBuffer` union).
  const keyBytes = strToArrayBuffer(
    atob(privateKeyBuffer.replace(/-----.*-----/g, "")),
  );
  const keyBuffer = new ArrayBuffer(keyBytes.byteLength);
  new Uint8Array(keyBuffer).set(keyBytes);

  const key = await crypto.subtle.importKey(
    "pkcs8",
    keyBuffer,
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
