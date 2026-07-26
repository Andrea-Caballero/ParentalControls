// T15: Emparejamiento - Edge Function
// Valida pairing_code, crea/asocia device y escribe device_id en app_metadata
//
// SECURITY: the previous code did SELECT ACTIVE → side effects → UPDATE
// status=CONSUMED, allowing two concurrent claims with the same ACTIVE
// code to each create a device before either UPDATE ran. The fix is
// the atomic UPDATE … RETURNING inside `claimPairingCode` (status=ACTIVE
// AND expires_at>NOW() predicate) so only one request can claim the
// row. Downstream effects are run only by the winner; race losers get
// a deterministic 4xx with zero side effects. If downstream work fails
// after the claim, the code STAYS CONSUMED — caller must generate a
// new code.

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

    // 1. Atomic CAS claim (UPDATE … RETURNING filtered by
    //    code=eq.X AND status=ACTIVE AND expires_at>NOW()). Postgres'
    //    row-level lock serializes concurrent UPDATEs so only one
    //    predicate matches. Atomic — replaces the previous
    //    SELECT→side-effects→UPDATE race window.
    const claim = await claimPairingCode(supabaseAdmin, code);
    if (!claim.ok) {
      return new Response(
        JSON.stringify({ error: claim.error, code: claim.error }),
        { status: claim.httpStatus, headers: { ...corsHeaders, "Content-Type": "application/json" } },
      );
    }
    const pairingRecord = claim.row;

    // 2. Obtener o crear usuario anónimo del agente (auth.users)
    // El agente usa anon key, así que auth.uid() es null
    // Creamos un usuario anónimo con email device-based
    let agentUserId: string;

    // Buscar usuario existente por metadata (si el agente ya se registró antes)
    const deviceHash = await hashDeviceIdentifier(device_name, device_model);

    const { data: existingUsers } = await supabaseAdmin
      .from("auth.users")
      .select("id")
      .ilike("email", `%${deviceHash}%`)
      .limit(1);

    if (existingUsers && existingUsers.length > 0) {
      agentUserId = existingUsers[0].id;
    } else {
      // Crear usuario anónimo para el dispositivo
      const { data: newUser, error: createError } = await supabaseAdmin.auth.admin.createUser({
        email: `device_${deviceHash}@parentalcontrol.local`,
        email_confirm: true,
        user_metadata: {
          device_hash: deviceHash,
          device_name,
        },
      });

      if (createError || !newUser.user) {
        // SECURITY: code stays CONSUMED — caller must generate a new
        // code if they want to retry.
        throw new Error(`Error creando usuario: ${createError?.message}`);
      }
      agentUserId = newUser.user.id;
    }

    // 3. Resolver / crear el niño (Change A — feat-multi-child-picker §A.7).
    // El padre captura el nombre en el "name this child" prompt, lo sube a
    // pairing_codes.child_first_name, y la edge function lo promueve a la
    // tabla children con ON CONFLICT para mantener idempotencia si el
    // dispositivo vuelve a emparejarse bajo el mismo nombre. Si la fila ya
    // existe (RETURNING id es null), hacemos un SELECT por
    // (parent_id, first_name) para resolver el id existente.
    const { data: insertedChild, error: childInsertError } = await supabaseAdmin
      .from("children")
      .insert({
        parent_id: pairingRecord.parent_id,
        first_name: trimmedChildName,
      })
      .select("id")
      .single();

    let childId: string;
    if (childInsertError || !insertedChild) {
      // Posible conflicto UNIQUE (parent_id, first_name): el niño ya existe.
      // Hacemos fallback a SELECT — el padre ya tiene un hijo con este
      // nombre, lo cual es exactamente lo que queremos para idempotencia.
      const { data: existingChild, error: childSelectError } = await supabaseAdmin
        .from("children")
        .select("id")
        .eq("parent_id", pairingRecord.parent_id)
        .eq("first_name", trimmedChildName)
        .single();

      if (childSelectError || !existingChild) {
        throw new Error(
          `Error resolviendo niño: ${childInsertError?.message ?? childSelectError?.message}`
        );
      }
      childId = existingChild.id;
    } else {
      childId = insertedChild.id;
    }

    // 4. Crear dispositivo (con child_id enlazado al niño recién resuelto).
    const { data: device, error: deviceError } = await supabaseAdmin
      .from("devices")
      .insert({
        device_name,
        parent_id: pairingRecord.parent_id,
        device_model,
        os_version,
        app_version,
        device_state: "ACTIVE",
        policy_version: 1,
        child_id: childId,
      })
      .select()
      .single();

    if (deviceError || !device) {
      // SECURITY: code stays CONSUMED — caller must generate a new
      // code if they want to retry. Intentionally no rollback to
      // ACTIVE; the fail-closed tradeoff is part of the audit fix.
      throw new Error(`Error creando dispositivo: ${deviceError?.message}`);
    }

    // 4. Escribir device_id en app_metadata del usuario
    const { error: metaError } = await supabaseAdmin.auth.admin.updateUserById(
      agentUserId,
      {
        app_metadata: {
          device_id: device.id,
        },
      }
    );

    if (metaError) {
      throw new Error(`Error actualizando app_metadata: ${metaError.message}`);
    }

    // 5. Aplicar plantilla de política según age_band
    const templateAgeBand = age_band || pairingRecord.device_name?.split("-")[0] || "7-12";
    await applyPolicyTemplate(supabaseAdmin, device.id, templateAgeBand);

    // 6. (Intentionally no late `update({ status: "CONSUMED" })` here:
    //    the atomic claim in step 1 is the one and only transition
    //    to CONSUMED — adding a second UPDATE re-opens the replay race.)

    // 7. Disparar FCM para notificar al padre
    await sendFcmNotification(supabaseAdmin, pairingRecord.parent_id, {
      type: "DEVICE_PAIRED",
      device_id: device.id,
      device_name,
    });

    return new Response(
      JSON.stringify({
        success: true,
        device_id: device.id,
        parent_id: pairingRecord.parent_id,
        policy_version: 1,
      }),
      { headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  } catch (error) {
    console.error("Pairing error:", error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
    );
  }
}

// ============ Atomic claim + diagnostic ============

type ClaimOutcome =
  | { ok: true; row: { id: string; parent_id: string; device_name: string | null } }
  | { ok: false; httpStatus: 404 | 409 | 410; error: string };

/**
 * Atomic UPDATE … RETURNING (status='CONSUMED', used_at=NOW())
 * filtered by code=eq.X AND status=ACTIVE AND expires_at>NOW().
 * Postgres' row-level lock serializes concurrent UPDATEs; only the
 * request whose predicate matches sees the row. On 0 rows, the
 * diagnostic SELECT below is read-only (NO mutation) so rows still
 * ACTIVE-past-TTL stay ACTIVE for the pg_cron cleanup job.
 */
// deno-lint-ignore no-explicit-any
async function claimPairingCode(supabase: any, code: string): Promise<ClaimOutcome> {
  const nowIso = new Date().toISOString();

  const { data: claimed, error: claimError } = await supabase
    .from("pairing_codes")
    .update({ status: "CONSUMED", used_at: nowIso })
    .eq("code", code)
    .eq("status", "ACTIVE")
    .gt("expires_at", nowIso)
    .select("*")
    .maybeSingle();

  if (claimError) {
    throw new Error(`pairing claim failed: ${claimError.message}`);
  }
  if (claimed) {
    return {
      ok: true,
      row: claimed as { id: string; parent_id: string; device_name: string | null },
    };
  }

  // Read-only diagnostic — classify the 0-row outcome.
  const { data: diag } = await supabase
    .from("pairing_codes")
    .select("*")
    .eq("code", code)
    .maybeSingle();

  if (!diag) return { ok: false, httpStatus: 404, error: "INVALID_CODE" };
  const d = diag as { status: string; expires_at: string };
  if (d.status === "CONSUMED") return { ok: false, httpStatus: 409, error: "ALREADY_USED" };
  if (d.status === "EXPIRED" || d.expires_at <= nowIso) return { ok: false, httpStatus: 410, error: "EXPIRED_CODE" };
  if (d.status === "REVOKED") return { ok: false, httpStatus: 409, error: "REVOKED_CODE" };
  return { ok: false, httpStatus: 409, error: "INACTIVE_CODE" };
}

// ============ Helpers ============

async function hashDeviceIdentifier(name: string, model: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`${name}-${model}-${Date.now()}`);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}

async function applyPolicyTemplate(
  supabase: ReturnType<typeof createClient>,
  deviceId: string,
  ageBand: string
): Promise<void> {
  // Buscar plantilla por age_band
  const { data: template } = await supabase
    .from("policy_templates")
    .select("*")
    .eq("age_band", ageBand)
    .single();

  if (!template) {
    console.warn(`No template found for age_band: ${ageBand}`);
    return;
  }

  const config = template.config;

  // Aplicar schedules
  if (config.schedules && Array.isArray(config.schedules)) {
    for (const schedule of config.schedules) {
      await supabase.from("schedules").insert({
        device_id: deviceId,
        name: schedule.id,
        days: schedule.days,
        from_time: schedule.from,
        to_time: schedule.to,
        action: schedule.action,
        allow_list: schedule.allow_list || null,
        is_active: true,
      });
    }
  }

  // Aplicar app_policies
  if (config.app_policies && Array.isArray(config.app_policies)) {
    for (const policy of config.app_policies) {
      await supabase.from("app_policies").insert({
        device_id: deviceId,
        package_name: policy.package_name,
        state: policy.state,
        daily_limit_minutes: policy.daily_limit_minutes || null,
        category: policy.category || null,
      }).onConflict(["device_id", "package_name"]).merge();
    }
  }
}

async function sendFcmNotification(
  supabase: ReturnType<typeof createClient>,
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

// Only `serve` when this file is the program entry point. Importing
// from `index_test.ts` does NOT open a listener — same gating
// pattern as `get-devices-for-parent/index.ts`.
if (import.meta.main) {
  serve(handleRequest);
}
