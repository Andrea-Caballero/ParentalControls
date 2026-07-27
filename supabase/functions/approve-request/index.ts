// T15: Approve Request - Edge Function
// Aprueba o rechaza un time_request de forma ATÓMICA vía la RPC
// `approve_request_atomic` (supabase/migrations/012_approve_request_atomic.sql).
//
//   - action: "APPROVE" (default)  -> la RPC inserta grant + flip verdict
//   - action: "DENY"               -> la RPC flip verdict sin grant
//
// Garantías:
//   - Verdict + grant commitean en UNA transacción Postgres (un
//     solo commit boundary). Un retry tras un fallo de red no
//     puede crear un grant duplicado ni dejar el verdict
//     aprobado sin grant.
//   - `UNIQUE(grants.request_id)` refuerza idempotencia en DB.
//   - FCM solo se dispara DESPUÉS de un commit exitoso.
//   - Auth y ownership se validan en el handler Y en la RPC
//     (defense in depth: si un caller futuro skipea el JWT
//     precheck, la RPC todavía rechaza).

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { decodeJwtPayload } from "../_shared/jwt.ts";
import { errorMessage } from "../_shared/error.ts";

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

  try {
    // Solo padres pueden aprobar/rechazar
     const authHeader = req.headers.get("Authorization");
     if (!authHeader) {
       return new Response(
         JSON.stringify({ error: "Token requerido" }),
         { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
       );
     }

     const jwtPayload = decodeJwtPayload(authHeader);
     const parentId = jwtPayload?.sub;

     if (!parentId) {

      return new Response(
        JSON.stringify({ error: "Usuario no autenticado" }),
        { status: 401, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

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
      // La RPC falló a nivel de transporte o Postgres. Por el
      // contrato atómico, NINGÚN cambio de estado se persistió:
      // o la RPC commitea todo o nada. Devolvemos 500 sin enviar
      // FCM para no notificar al niño de un cambio que no ocurrió.
      throw new Error(`Error en approve_request_atomic: ${errorMessage(rpcError)}`);
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
      // La RPC devolvió un payload inesperado. No podemos confiar en
      // que el commit ocurrió; no notificamos al niño.
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

/**
 * Slice A — 1h auto-DENY sweep (per `time-request-approval/spec.md`
 * ADDED Requirement + tasks.md A.2.7).
 *
 * Updates every PENDING `time_requests` row on [deviceId] whose
 * `created_at` is older than 1 hour to:
 *   status       = "DENIED"
 *   denied_at    = NOW()
 *   response_text = "Auto-denied: no parent response within 1h"
 *
 * The PATCH is scoped by device_id (the parent on the device) and
 * idempotent (the WHERE status='PENDING' predicate makes a second
 * call within the same window a no-op). Best-effort: if the update
 * fails (network blip, RLS misconfig), the atomic approval RPC
 * still runs — the sweep failure is logged but does not block the
 * parent's decision.
 *
 * The PostgREST filter shape is
 *   /rest/v1/time_requests?status=eq.PENDING&device_id=eq.{deviceId}&created_at=lt.{iso}
 * which the production Supabase client translates into the SQL
 *   UPDATE time_requests SET ...
 *   WHERE status = 'PENDING'
 *     AND device_id = '{deviceId}'
 *     AND created_at < '{iso}';
 *
 * @param supabase  The service-role Supabase client (bypasses RLS).
 * @param deviceId  The device_id of the current approve-request call;
 *                  the sweep only touches PENDING rows on THIS device.
 */
async function autoDenyStaleRequests(
  supabase: SupabaseClient,
  deviceId: string,
): Promise<void> {
  const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
  try {
    const { error } = await supabase
      .from("time_requests")
      .update({
        status: "DENIED",
        denied_at: new Date().toISOString(),
        response_text: "Auto-denied: no parent response within 1h",
      })
      .eq("status", "PENDING")
      .eq("device_id", deviceId)
      .lt("created_at", oneHourAgo);
    if (error) {
      console.error("autoDenyStaleRequests failed:", errorMessage(error));
    }
  } catch (e) {
    console.error("autoDenyStaleRequests threw:", e);
  }
}

if (import.meta.main) {
  serve(handleRequest);
}
