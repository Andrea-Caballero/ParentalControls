// T15: Emparejamiento - Edge Function
// Valida pairing_code, crea/asocia device y escribe device_id en app_metadata
//
// Pairing redemption is committed by `redeem_pairing_code_atomic`.
// The database function locks the ACTIVE code, performs the child/device/
// metadata/policy writes in one transaction, and transitions the code to
// CONSUMED only after those writes succeed. Any downstream error rolls the
// transaction back, leaving the code ACTIVE and retryable. Race losers receive
// a deterministic 4xx without creating duplicate pairing state.

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// Generator alphabet shared with `create-pairing-code/index.ts`
// (A-H, J-N, P-Z, 2-9 — excludes I/O/0/1). 8 chars exactly; any
// mismatch → 400 INVALID_CODE_FORMAT before any DB hit.
const PAIRING_CODE_REGEX = /^[A-HJ-NP-Z2-9]{8}$/;

/** Exported for `index_test.ts`; the HTTP listener runs only when this
 *  file is the program entry point (gated by `import.meta.main` below). */
export async function handleRequest(req: Request): Promise<Response> {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { code, device_name, device_model, os_version, app_version, age_band, child_first_name } =
      await req.json();

    // Validar input
    if (!code || !device_name || !app_version) {
      return new Response(
        JSON.stringify({ error: "Faltan campos requeridos" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Format validation — reject anything that doesn't match the
    // `create-pairing-code` generator alphabet before any DB or auth
    // traffic. Returning 400 here keeps the deterministic shape of
    // the audit contract: format errors are 4xx-client, distinct
    // from INVALID_CODE (404 — well-formed but unknown) and from
    // ALREADY_USED / EXPIRED_CODE (race-loss / TTL-lost).
    if (typeof code !== "string" || !PAIRING_CODE_REGEX.test(code)) {
      return new Response(
        JSON.stringify({ error: "INVALID_CODE_FORMAT", code: "INVALID_CODE_FORMAT" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // feat-multi-child-picker (Change A — schema + domain + pairing,
    // design §A.7): the parent-side "name this child" prompt captures
    // child_first_name into pairing_codes before the child scans. From
    // there, the pairing edge function creates the children row and links
    // the device in the same transaction.
    //
    // Validation: trimmed length 1..32 (mirrors the children_first_name
    // CHECK constraint added in supabase/migrations/005_children_table.sql).
    // Empty / blank → HTTP 400. Without this, the dashboard surfaces
    // anonymous devices ("Sin asignar") that the parent has to rename
    // out-of-band; the rename flow's research-shape prompt (PR B §B.6)
    // handles the orphan case, but pairing-time capture is the cleaner
    // happy path.
    const trimmedChildName = (child_first_name ?? "").trim();
    if (trimmedChildName.length < 1 || trimmedChildName.length > 32) {
      return new Response(
        JSON.stringify({ error: "child_first_name es requerido (1..32 caracteres)" }),
        { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    }

    // Cliente con service_role para bypass RLS
    const supabaseAdmin = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
    );

    // Read-only preflight avoids creating an agent user for a code that is
    // already invalid. The database RPC repeats these checks while holding a
    // row lock; this preflight is not the commit boundary.
    const preflight = await inspectPairingCode(supabaseAdmin, code);
    if (!preflight.ok) {
      return pairingErrorResponse(preflight.error, preflight.httpStatus);
    }

    // Resolve or create the anonymous agent user before the database
    // transaction. The RPC writes device_id into this user's app metadata as
    // part of the same transaction as the pairing rows.
    let agentUserId: string;
    let createdAgentUser = false;
    const deviceHash = await hashDeviceIdentifier(device_name, device_model);
    const agentEmail = `device_${deviceHash}@parentalcontrol.local`;

    // Auth admin lookup — `auth.users` is not exposed through PostgREST, so
    // the previous `from("auth.users").select("id")...` lookup would only
    // work if the project granted the service_role key access to the auth
    // schema via the API. Use `auth.admin.listUsers` instead and filter
    // client-side by the deterministic device email. listUsers only
    // supports page/perPage pagination (no email filter), so we page
    // through results with a small safety cap and stop early on a match.
    const existingAgentId = await findAgentUserIdByEmail(supabaseAdmin, agentEmail);

    if (existingAgentId) {
      agentUserId = existingAgentId;
    } else {
      const { data: newUser, error: createError } = await supabaseAdmin.auth.admin.createUser({
        email: agentEmail,
        email_confirm: true,
        user_metadata: {
          device_hash: deviceHash,
          device_name,
        },
      });

      if (createError || !newUser.user) {
        throw new Error(`Error creando usuario: ${createError?.message}`);
      }
      agentUserId = newUser.user.id;
      createdAgentUser = true;
    }

    // The RPC is the sole commit boundary. It locks the pairing code, writes
    // child/device/app_metadata/policy state, and consumes the code last. A
    // raised database error rolls every one of those writes back.
    const { data: redemptionData, error: redemptionError } = await supabaseAdmin.rpc(
      "redeem_pairing_code_atomic",
      {
        p_code: code,
        p_agent_user_id: agentUserId,
        p_device_name: device_name,
        p_device_model: device_model ?? null,
        p_os_version: os_version ?? null,
        p_app_version: app_version,
        p_child_first_name: trimmedChildName,
        p_age_band: age_band ?? null,
      },
    );

    if (redemptionError) {
      if (createdAgentUser) {
        await deleteAgentUser(supabaseAdmin, agentUserId);
      }
      throw new Error(`Error completando pairing: ${redemptionError.message}`);
    }

    const redemption = redemptionData as PairingRedemptionResult | null;
    if (!redemption) {
      throw new Error("Error completando pairing: respuesta vacía");
    }
    if (redemption.error) {
      const failure = classifyPairingError(redemption.error);
      if (createdAgentUser) {
        await deleteAgentUser(supabaseAdmin, agentUserId);
      }
      return pairingErrorResponse(failure.error, failure.httpStatus);
    }
    if (!redemption.success || !redemption.device_id || !redemption.parent_id) {
      if (createdAgentUser) {
        await deleteAgentUser(supabaseAdmin, agentUserId);
      }
      throw new Error("Error completando pairing: respuesta inválida");
    }

    // Notification remains best effort and happens only after the pairing
    // transaction commits. A push failure must not roll back a valid pairing.
    await sendFcmNotification(supabaseAdmin, redemption.parent_id, {
      type: "DEVICE_PAIRED",
      device_id: redemption.device_id,
      device_name,
    });

    return new Response(
      JSON.stringify({
        success: true,
        device_id: redemption.device_id,
        parent_id: redemption.parent_id,
        policy_version: redemption.policy_version ?? 1,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Pairing error:", error);
    const message = error instanceof Error ? error.message : "Error interno de pairing";
    return new Response(
      JSON.stringify({ error: message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

// ============ Pairing validation + RPC result contract ============

type PairingFailure = { ok: false; httpStatus: 404 | 409 | 410; error: string };
type PairingPreflight = { ok: true } | PairingFailure;

type PairingRedemptionResult = {
  success?: boolean;
  error?: string;
  device_id?: string;
  parent_id?: string;
  policy_version?: number;
};

// deno-lint-ignore no-explicit-any
async function inspectPairingCode(supabase: any, code: string): Promise<PairingPreflight> {
  const { data, error } = await supabase
    .from("pairing_codes")
    .select("status,expires_at")
    .eq("code", code)
    .maybeSingle();

  if (error) {
    throw new Error(`pairing preflight failed: ${error.message}`);
  }
  if (!data) {
    return { ok: false, httpStatus: 404, error: "INVALID_CODE" };
  }

  const row = data as { status: string; expires_at: string };
  if (row.status === "ACTIVE" && row.expires_at > new Date().toISOString()) {
    return { ok: true };
  }
  return { ok: false, ...classifyPairingError(
    row.status === "ACTIVE" ? "EXPIRED_CODE" : statusToPairingError(row.status),
  ) };
}

function statusToPairingError(status: string): string {
  if (status === "CONSUMED") return "ALREADY_USED";
  if (status === "EXPIRED") return "EXPIRED_CODE";
  if (status === "REVOKED") return "REVOKED_CODE";
  return "INACTIVE_CODE";
}

function classifyPairingError(error: string): Omit<PairingFailure, "ok"> {
  if (error === "INVALID_CODE") return { httpStatus: 404, error };
  if (error === "EXPIRED_CODE") return { httpStatus: 410, error };
  if (error === "ALREADY_USED" || error === "REVOKED_CODE" || error === "INACTIVE_CODE") {
    return { httpStatus: 409, error };
  }
  return { httpStatus: 409, error: "INACTIVE_CODE" };
}

function pairingErrorResponse(error: string, httpStatus: number): Response {
  return new Response(
    JSON.stringify({ error, code: error }),
    { status: httpStatus, headers: { ...corsHeaders, "Content-Type": "application/json" } },
  );
}

// ============ Helpers ============

export async function hashDeviceIdentifier(name: string, model: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`${name}-${model}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function sendFcmNotification(
  // deno-lint-ignore no-explicit-any
  supabase: any,
  parentId: string,
  payload: Record<string, unknown>
): Promise<void> {
  // Bug #3 de la auditoria: enviar push al padre cuando su hijo empareja
  // un dispositivo. device_push_tokens tiene la columna parent_id desde
  // la migration 008, asi que resolvemos los tokens directamente por el
  // parent_id del codigo de emparejamiento (no necesitamos el device_id
  // del padre como decia el TODO original).
  const { data: tokens, error: tokensError } = await supabase
    .from("device_push_tokens")
    .select("token")
    .eq("parent_id", parentId)
    .eq("is_active", true);

  if (tokensError) {
    // No fallamos el pairing si la notificacion falla — el dispositivo ya
    // quedo emparejado, el padre se entera al abrir la app de todos modos.
    console.error(`Error fetching push tokens for parent ${parentId}:`, tokensError);
    return;
  }

  if (!tokens || tokens.length === 0) {
    console.log(`No active FCM tokens for parent ${parentId}; skipping notification`);
    return;
  }

  const fcmUrl = "https://fcm.googleapis.com/fcm/send";
  const serverKey = Deno.env.get("FCM_SERVER_KEY");

  if (!serverKey) {
    console.warn("FCM_SERVER_KEY not configured; skipping FCM dispatch");
    return;
  }

  const fcmBody = {
    priority: "high",
    data: {
      ...payload,
      sent_at: new Date().toISOString(),
    },
  };

  for (const { token } of tokens) {
    try {
      const response = await fetch(fcmUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `key=${serverKey}`,
        },
        body: JSON.stringify({ ...fcmBody, to: token }),
      });

      if (!response.ok) {
        console.error(`FCM send non-OK for token ...${token.slice(-6)}:`, await response.text());
      }
    } catch (e) {
      console.error("FCM send network error:", e);
    }
  }
}

// Page size for the auth admin user list lookup. 1000 is well above the
// expected agent-user volume for a single Supabase project, so we expect a
// single page in practice.
const AGENT_LOOKUP_PAGE_SIZE = 1000;
// Safety cap against runaway pagination in a pathological project. Agent
// emails are unique per device hash, so the worst-case cost is bounded by
// this many pages of users.
const AGENT_LOOKUP_MAX_PAGES = 5;

// deno-lint-ignore no-explicit-any
async function findAgentUserIdByEmail(supabase: any, email: string): Promise<string | null> {
  let page = 1;
  for (let i = 0; i < AGENT_LOOKUP_MAX_PAGES; i++) {
    const { data, error } = await supabase.auth.admin.listUsers({
      page,
      perPage: AGENT_LOOKUP_PAGE_SIZE,
    });
    if (error) {
      throw new Error(`agent-user lookup failed: ${error.message}`);
    }
    const users = (data?.users ?? []) as Array<{ id: string; email?: string }>;
    const match = users.find((u) => u.email === email);
    if (match) return match.id;
    if (!data?.nextPage) return null;
    page = data.nextPage;
  }
  throw new Error("agent-user lookup exceeded pagination safety cap");
}

// deno-lint-ignore no-explicit-any
async function deleteAgentUser(supabase: any, userId: string): Promise<void> {
  try {
    const { error } = await supabase.auth.admin.deleteUser(userId);
    if (error) {
      console.warn(`Could not delete orphan pairing user ${userId}:`, error);
    }
  } catch (e) {
    console.warn(`Could not delete orphan pairing user ${userId}:`, e);
  }
}

// Only `serve` when this file is the program entry point. Importing
// from `index_test.ts` does NOT open a listener — same gating
// pattern as `get-devices-for-parent/index.ts`.
if (import.meta.main) {
  serve(handleRequest);
}
