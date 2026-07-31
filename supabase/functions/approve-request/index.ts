// T15: Approve Request - Edge Function
//
// BLK-01 hardening: this handler used to derive `parentId` from a
// manually base64-decoded JWT `sub` claim — that decode does NOT
// verify the signature, expiration, or revocation. An attacker could
// craft any `parentId` they wanted. The handler now uses
// `supabase.auth.getUser(token)` (via `_shared/jwt.ts`) to perform
// cryptographic verification and reads `parentId` from the
// server-controlled `user.id`. The service-role client is only
// constructed AFTER a verified identity has been returned.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { verifyAuth } from "../_shared/jwt.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

/**
 * Handle an incoming request to the edge function. Exported so the
 * accompanying `index_test.ts` can drive it with a synthetic Request and
 * assert on the returned Response.
 */
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
    // Parent endpoint — no device_id required. The handler explicitly
    // checks ownership of the device before any privileged work.
    requireDevice: false,
  });
  if (!auth.ok) return auth.response;
  // auth.parentId comes from the verified user record, not the JWT
  // `sub` claim. See `_shared/jwt.ts` for the cryptographic
  // verification contract.
  const parentId = auth.parentId;

  try {
    // action: "APPROVE" | "DENY" (acepta alias "decision" también)
    const {
      request_id,
      minutes,
      response_text,
      action,
      decision,
    } = await req.json();

    // Normalizar a upper case. Default = APPROVE (backward compat).
    const effectiveAction = String(action ?? decision ?? "APPROVE")
      .trim()
      .toUpperCase();

    if (!request_id) {
      return new Response(
        JSON.stringify({ error: "request_id es requerido" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // minutes solo es requerido para APPROVE. Para DENY, p_minutes
    // se pasa NULL y la RPC lo interpreta como DENY.
    const rpcMinutes = effectiveAction === "DENY" ? null : minutes;

    if (effectiveAction !== "DENY" && !minutes) {
      return new Response(
        JSON.stringify({ error: "minutes es requerido para APPROVE" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Constructed only after the JWT has been cryptographically
    // verified — see BLK-01.
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // 1. Verificar que la solicitud existe y pertenece a un dispositivo
    //    del padre. Esta lectura es previa a la RPC y permite devolver
    //    404/403 con un envelope conocido ANTES de tocar la RPC; la
    //    RPC además valida ownership en su propio cuerpo, pero el
    //    precheck temprano mantiene el contrato de error existente
    //    para clientes que ya dependen de los códigos HTTP actuales.
    const { data: timeRequest, error: requestError } = await supabaseAdmin
      .from("time_requests")
      .select("*, devices(parent_id)")
      .eq("id", request_id)
      .single();

    if (requestError || !timeRequest) {
      return new Response(
        JSON.stringify({ error: "Solicitud no encontrada" }),
        { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Verificar propiedad
    if (timeRequest.devices?.parent_id !== parentId) {
      return new Response(
        JSON.stringify({ error: "No autorizado para aprobar esta solicitud" }),
        { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Slice A — 1h auto-DENY contract (per
    // `time-request-approval/spec.md` ADDED Requirement + tasks.md A.2.7).
    // Before the atomic RPC runs, sweep any PENDING time_request on the
    // SAME device that has been waiting for a parent response for more
    // than 1 hour. Auto-deny so the parent UI does not show stale
    // requests. The sweep is idempotent (WHERE status='PENDING' is the
    // gate), so a second call within the same window is a no-op.
    await autoDenyStaleRequests(
      supabaseAdmin,
      timeRequest.device_id,
    );

    // 2. Llamar la RPC atómica. La RPC:
    //    - hace `SELECT ... FOR UPDATE` sobre el time_request para
    //      serializar retries concurrentes,
    //    - verifica `device.parent_id = p_parent_id` (defense in depth),
    //    - decide APPROVE/DENY según `p_minutes`,
    //    - en APPROVE: hace UPDATE time_requests + INSERT grant en la
    //      misma transacción; UNIQUE(grants.request_id) garantiza un
    //      solo grant por request,
    //    - en DENY: hace UPDATE time_requests a DENIED sin grant,
    //    - en retry idempotente (status ya coincide): devuelve el
    //      grant existente con `idempotent: true`,
    //    - en conflicto (DENY tras APPROVE o APPROVE tras DENY):
    //      devuelve `{ error, code }` y la Edge Function mapea a 409.
    const { data: rpcResult, error: rpcError } = await supabaseAdmin.rpc(
      "approve_request_atomic",
      {
        p_request_id: request_id,
        p_parent_id: parentId,
        p_minutes: rpcMinutes,
        p_response_text: response_text ?? null,
      },
    );

    if (rpcError) {
      throw new Error(`Error en approve_request_atomic: ${rpcError.message}`);
    }

    // rpcResult es el jsonb devuelto por la función.
    const result = (rpcResult ?? {}) as {
      success?: boolean;
      decision?: "APPROVED" | "DENIED";
      error?: string;
      code?: string;
      grant_id?: string;
      minutes?: number;
      expires_at?: string;
      policy_version?: number;
      idempotent?: boolean;
    };

    // 3. Mapear errores/conflictos de la RPC a HTTP 4xx.
    if (result.error) {
      if (result.code === "NOT_FOUND") {
        return new Response(
          JSON.stringify({ error: result.error }),
          { status: 404, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (result.code === "UNAUTHORIZED") {
        return new Response(
          JSON.stringify({ error: result.error }),
          { status: 403, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      if (
        result.code === "ALREADY_APPROVED" ||
        result.code === "ALREADY_DENIED"
      ) {
        return new Response(
          JSON.stringify({ error: result.error, code: result.code }),
          { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }
      return new Response(
        JSON.stringify({ error: result.error }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    if (!result.success || !result.decision) {
      throw new Error("Respuesta inesperada de approve_request_atomic");
    }

    // 4. FCM solo DESPUÉS de un commit exitoso. Mantenemos el
    //    contrato del handler legacy: el padre no espera al push, el
    //    push es best-effort.
    if (result.decision === "APPROVED") {
      await sendFcmToDevice(supabaseAdmin, timeRequest.device_id, {
        type: "POLICY_UPDATED",
        grant_id: result.grant_id,
        minutes: result.minutes,
        expires_at: result.expires_at,
        new_policy_version: result.policy_version,
      });
    } else if (result.decision === "DENIED") {
      await sendFcmToDevice(supabaseAdmin, timeRequest.device_id, {
        type: "REQUEST_DENIED",
        request_id: request_id,
        response_text: response_text || null,
      });
    }

    // 5. Envelope de respuesta: preserva el shape del handler legacy
    //    (success/grant_id/minutes/expires_at/policy_version) y agrega
    //    `decision` + `idempotent` para que el cliente pueda distinguir
    //    una primera aprobación de un retry idempotente.
    return new Response(
      JSON.stringify({
        success: result.success,
        decision: result.decision,
        grant_id: result.grant_id,
        minutes: result.minutes,
        expires_at: result.expires_at,
        policy_version: result.policy_version,
        idempotent: result.idempotent === true,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error("Approve request error:", message);
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

// Loose alias for the Supabase client so the helper is callable from any
// client instance (the strict ReturnType of createClient doesn't unify across
// the multiple overloads we use in this file).
// deno-lint-ignore no-explicit-any
type SupabaseClient = any;

async function sendFcmToDevice(
  supabase: SupabaseClient,
  deviceId: string,
  payload: Record<string, unknown>
): Promise<void> {
  // Buscar token FCM activo del dispositivo
  const { data: tokenRecord } = await supabase
    .from("device_push_tokens")
    .select("token")
    .eq("device_id", deviceId)
    .eq("is_active", true)
    .limit(1)
    .single();

  const record = tokenRecord as { token?: string } | null;
  if (!record?.token) {
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
        to: record.token,
        priority: "high",
        data: payload,
      }),
    });
  } catch (e) {
    console.error("FCM send error:", e);
  }
}

// Slice A — 1h auto-DENY sweep. Mark every PENDING time_request on
// the device older than 1 hour as DENIED so the parent UI does not
// surface stale requests. The sweep is idempotent — the WHERE
// clause gates on status='PENDING' AND created_at<now()-1h, so a
// second call within the same window is a no-op. See
// `time-request-approval/spec.md` ADDED Requirement + tasks.md A.2.7.
async function autoDenyStaleRequests(
  // deno-lint-ignore no-explicit-any
  supabase: any,
  deviceId: string,
): Promise<void> {
  const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
  try {
    await supabase
      .from("time_requests")
      .update({
        status: "DENIED",
        response_text: "Auto-denied: no parent response within 1h",
        denied_at: new Date().toISOString(),
      })
      .eq("device_id", deviceId)
      .eq("status", "PENDING")
      .lt("created_at", oneHourAgo);
  } catch (e) {
    // Best-effort: an auto-DENY failure must not block the main
    // approval. The user gets a logged warning and the auto-DENY
    // is retried on the next request for the same device.
    console.warn("autoDenyStaleRequests non-fatal failure:", e);
  }
}

if (import.meta.main) {
  serve(handleRequest);
}
