// T15: Approve Request - Edge Function
// Aprueba o rechaza un time_request:
//   - action: "APPROVE" (default, backward compat) -> crea grant, FCM POLICY_UPDATED
//   - action: "DENY"                              -> marca DENIED, sin grant, FCM REQUEST_DENIED

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

    const token = authHeader.replace("Bearer ", "");
    const jwtPayload = JSON.parse(atob(token.split(".")[1]));
    const parentId = jwtPayload.sub;

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

    // minutes solo es requerido para APPROVE
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

    // 1. Verificar que la solicitud existe y pertenece a un dispositivo del padre
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
    // Before the main APPROVE/DENY logic runs, sweep any PENDING
    // time_request on the SAME device that has been waiting for a
    // parent response for more than 1 hour. Auto-deny it so the parent
    // UI does not show stale requests that they have likely forgotten
    // about. The sweep is idempotent (WHERE status='PENDING' is the
    // gate), so a second call within the same window is a no-op.
    //
    // Scope: device_id matches the request's device_id. Other devices
    // are not affected (we only clean up THIS parent's stale requests).
    await autoDenyStaleRequests(
      supabaseAdmin,
      timeRequest.device_id,
    );

    // 2. Branch DENY
    if (effectiveAction === "DENY") {
      // Idempotencia: si ya está DENIED, devolver éxito sin re-procesar.
      if (timeRequest.status === "DENIED") {
        return new Response(
          JSON.stringify({
            success: true,
            decision: "DENIED",
            idempotent: true,
          }),
          { headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      // Si ya fue APPROVED no se puede revertir a DENIED (semánticamente confuso
      // y bloquearía inconsistencias con grants ya entregados).
      if (timeRequest.status === "APPROVED") {
        return new Response(
          JSON.stringify({ error: "Solicitud ya aprobada, no se puede denegar" }),
          { status: 409, headers: { ...corsHeaders, "Content-Type": "application/json" } }
        );
      }

      // Actualizar estado a DENIED
      const { error: updateError } = await supabaseAdmin
        .from("time_requests")
        .update({
          status: "DENIED",
          parent_response: response_text || null,
          responded_at: new Date().toISOString(),
        })
        .eq("id", request_id);

      if (updateError) {
        throw new Error(`Error actualizando solicitud: ${updateError.message}`);
      }

      // FCM al dispositivo del niño
      await sendFcmToDevice(supabaseAdmin, timeRequest.device_id, {
        type: "REQUEST_DENIED",
        request_id: request_id,
        response_text: response_text || null,
      });

      return new Response(
        JSON.stringify({ success: true, decision: "DENIED" }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 3. Branch APPROVE (legacy behavior)
    // Verificar que no está ya aprobada (idempotencia)
    if (timeRequest.status === "APPROVED") {
      // Ya fue aprobada, buscar grant existente
      const { data: existingGrant } = await supabaseAdmin
        .from("grants")
        .select("*")
        .eq("request_id", request_id)
        .eq("source", "EXTRA_TIME")
        .single();

      return new Response(
        JSON.stringify({
          success: true,
          idempotent: true,
          grant_id: existingGrant?.id,
          message: "Solicitud ya aprobada anteriormente",
        }),
        { headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // 4. Actualizar estado de la solicitud
    await supabaseAdmin
      .from("time_requests")
      .update({
        status: "APPROVED",
        parent_response: response_text || null,
        responded_at: new Date().toISOString(),
      })
      .eq("id", request_id);

    // 5. Crear grant (idempotente por request_id único)
    const expiresAt = new Date();
    expiresAt.setMinutes(expiresAt.getMinutes() + minutes);

    const { data: grant, error: grantError } = await supabaseAdmin
      .from("grants")
      .insert({
        device_id: timeRequest.device_id,
        request_id: request_id,
        scope: timeRequest.package_name || "device",
        minutes: minutes,
        source: "EXTRA_TIME",
        status: "APPROVED",
        expires_at: expiresAt.toISOString(),
        granted_at: new Date().toISOString(),
      })
      .select()
      .single();

    if (grantError) {
      throw new Error(`Error creando grant: ${grantError.message}`);
    }

    // 6. Bump de versión (trigger automático en grants)
    // El trigger AFTER INSERT en grants ya llama a bump_policy_version

    // 7. Obtener versión actualizada
    const { data: device } = await supabaseAdmin
      .from("devices")
      .select("policy_version")
      .eq("id", timeRequest.device_id)
      .single();

    // 8. Enviar FCM al dispositivo
    await sendFcmToDevice(supabaseAdmin, timeRequest.device_id, {
      type: "POLICY_UPDATED",
      grant_id: grant.id,
      minutes: minutes,
      expires_at: expiresAt.toISOString(),
      new_policy_version: device?.policy_version,
    });

    return new Response(
      JSON.stringify({
        success: true,
        grant_id: grant.id,
        minutes: minutes,
        expires_at: expiresAt.toISOString(),
        policy_version: device?.policy_version,
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
 * fails (network blip, RLS misconfig), the main APPROVE/DENY logic
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
      console.error("autoDenyStaleRequests failed:", error.message);
    }
  } catch (e) {
    console.error("autoDenyStaleRequests threw:", e);
  }
}

if (import.meta.main) {
  serve(handleRequest);
}