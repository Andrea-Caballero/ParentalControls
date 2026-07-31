// BLK-02: Custom Access Token Hook — OFFICIAL Supabase HTTP-hook contract.
//
// Copies the trusted `auth.users.app_metadata.device_id` (written by
// migration 013 `redeem_pairing_code_atomic`) into a first-level
// `device_id` JWT claim on every Supabase Auth issuance / refresh. That
// claim is the single JWT-trust source for every RLS predicate of the
// form `((auth.jwt() ->> 'device_id')::uuid = device_id)` across the
// parental-control schema.
//
// Contract:
//   Input  (HTTP POST, signed Standard Webhooks):
//     { user_id, claims, authentication_method }
//   Output (HTTP 200, JSON):
//     { claims }
//
//   Supabase env var `CUSTOM_ACCESS_TOKEN_SECRET`:
//     "v1,whsec_<base64>" → strip "v1,whsec_" → pass the base64 string to
//     standardwebhooks@1.0.0, which base64-decodes it to obtain the raw
//     HMAC-SHA256 key.
//
// Out of scope (handled elsewhere): RLS, pairing migrations, parent_id
// claim injection (Slice A in `feat-cross-device-pairing-and-approval`).
// The hook MUST remain pure — it derives claims from the signed event only.

import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

export interface HookEvent {
  user_id: string;
  claims: Record<string, unknown>;
  authentication_method: string;
}

// Stable, non-sensitive error codes. Bodies never carry the webhook
// secret, payload bytes, parser internals, or stack frames.
export const HOOK_ERROR = {
  HOOK_SECRET_MISSING: "HOOK_SECRET_MISSING",
  INVALID_SIGNATURE: "INVALID_SIGNATURE",
  INVALID_EVENT: "INVALID_EVENT",
  INVALID_DEVICE_ID: "INVALID_DEVICE_ID",
  INTERNAL_ERROR: "INTERNAL_ERROR",
} as const;

type HookErrorCode = (typeof HOOK_ERROR)[keyof typeof HOOK_ERROR];

class HookClaimsError extends Error {
  constructor(public readonly code: HookErrorCode) {
    super(code);
    this.name = "HookClaimsError";
  }
}

/**
 * Derive the post-hook claims from the official Supabase event.
 *
 *   1. Drop any pre-existing top-level `device_id` from the draft
 *      claims; the only source of truth is `app_metadata.device_id`.
 *   2. If `app_metadata.device_id` is a valid UUID, copy it to the
 *      top-level `claims.device_id`.
 *   3. If it is missing or empty, omit the top-level claim entirely.
 *   4. If it is present but non-string or invalid, fail closed with
 *      `INVALID_DEVICE_ID` (returning claims without device_id would
 *      silently downgrade a paired device to an unpaired one).
 *   5. `user_id` MUST equal `claims.sub`; a mismatch refuses to mint
 *      claims for a different subject.
 *   6. Every other draft claim is preserved verbatim.
 *   7. `user_metadata.device_id` (or any non-app_metadata source) MUST
 *      NOT influence the top-level claim — letting an attacker-
 *      controlled field set it would re-introduce the BLK-01 forgery
 *      class.
 */
export function customAccessTokenHook(event: HookEvent): { claims: Record<string, unknown> } {
  const claims = sanitizeClaims(event.claims);

  // Subject consistency: refuse to mint claims for a different subject
  // or for an event that simply doesn't carry one.
  if (
    typeof event.user_id !== "string" ||
    event.user_id.length === 0 ||
    typeof claims.sub !== "string" ||
    claims.sub.length === 0 ||
    event.user_id !== claims.sub
  ) {
    throw new HookClaimsError(HOOK_ERROR.INVALID_EVENT);
  }

  // Strip any stale top-level device_id before re-deriving; the only
  // trusted source is `app_metadata.device_id`.
  delete claims.device_id;

  const appMeta = readAppMetadata(claims.app_metadata);
  const rawDeviceId = appMeta.device_id;
  if (rawDeviceId === undefined || rawDeviceId === "") {
    // Absent or empty: omit the top-level claim entirely.
  } else if (typeof rawDeviceId !== "string") {
    // Non-string is a server-side integrity bug; refuse to pass through.
    throw new HookClaimsError(HOOK_ERROR.INVALID_DEVICE_ID);
  } else if (!isValidUuid(rawDeviceId)) {
    throw new HookClaimsError(HOOK_ERROR.INVALID_DEVICE_ID);
  } else {
    claims.device_id = rawDeviceId;
  }

  return { claims };
}

function sanitizeClaims(
  draft: Record<string, unknown> | undefined,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (draft && typeof draft === "object" && !Array.isArray(draft)) {
    for (const key of Object.keys(draft)) {
      out[key] = (draft as Record<string, unknown>)[key];
    }
  }
  return out;
}

function readAppMetadata(value: unknown): Record<string, unknown> {
  if (value === undefined || value === null) return {};
  if (typeof value !== "object" || Array.isArray(value)) {
    // Auth ALWAYS sends app_metadata as an object. Anything else is a
    // malformed event; fail closed.
    throw new HookClaimsError(HOOK_ERROR.INVALID_EVENT);
  }
  return value as Record<string, unknown>;
}

/**
 * RFC 4122 UUID validation. Accepts any version (1-8) and any variant;
 * the backend stores `gen_random_uuid()` v4, but we don't reject other
 * versions because Supabase's `auth.users.id` column is `uuid` and the
 * database is the ultimate authority. This validator exists only to
 * refuse obvious garbage (paths, prompts, SQL fragments) and to enforce
 * the UUID contract.
 */
export function isValidUuid(value: string): boolean {
  if (value.length !== 36) return false;
  for (let i = 0; i < 36; i++) {
    const c = value.charCodeAt(i);
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      if (c !== 45 /* '-' */) return false;
    } else {
      const isHex = (c >= 48 && c <= 57) || (c >= 97 && c <= 102) || (c >= 65 && c <= 70);
      if (!isHex) return false;
    }
  }
  return true;
}

export async function handleRequest(req: Request): Promise<Response> {
  if (req.method !== "POST") {
    return hookErrorResponse(HOOK_ERROR.INVALID_EVENT, 405);
  }

  // Read the signed payload as text — `Webhook.verify` hashes the
  // exact bytes, so we MUST NOT call `req.json()` first (parsing +
  // reserializing would change whitespace and break the signature).
  const payload = await req.text();

  const secret = Deno.env.get("CUSTOM_ACCESS_TOKEN_SECRET");
  if (!secret) {
    console.error("custom-access-token-hook: CUSTOM_ACCESS_TOKEN_SECRET missing");
    return hookErrorResponse(HOOK_ERROR.HOOK_SECRET_MISSING, 500);
  }

  const base64Secret = secret.replace(/^v1,whsec_/, "");

  // The Headers object is case-insensitive at the protocol level; the
  // library lowercases whatever it receives.
  const headers: Record<string, string> = {};
  for (const [k, v] of req.headers.entries()) headers[k] = v;

  let event: unknown;
  try {
    // Whether the failure is a bad secret, missing header, stale
    // timestamp, or forged signature, the caller-facing response is
    // the same closed envelope so we never hint at which check failed.
    event = new Webhook(base64Secret).verify(payload, headers);
  } catch (err) {
    console.warn("custom-access-token-hook: signature verification failed", err);
    return hookErrorResponse(HOOK_ERROR.INVALID_SIGNATURE, 401);
  }

  if (!isHookEvent(event)) {
    console.error("custom-access-token-hook: verified event has wrong shape");
    return hookErrorResponse(HOOK_ERROR.INVALID_EVENT, 400);
  }

  try {
    const out = customAccessTokenHook(event);
    return new Response(JSON.stringify(out), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    if (err instanceof HookClaimsError) {
      // 400 for caller-side problems (event shape, UUID contract);
      // 500 reserved for internal failures only.
      const status = err.code === HOOK_ERROR.INTERNAL_ERROR ? 500 : 400;
      return hookErrorResponse(err.code, status);
    }
    console.error("custom-access-token-hook: internal error", err);
    return hookErrorResponse(HOOK_ERROR.INTERNAL_ERROR, 500);
  }
}

function isHookEvent(value: unknown): value is HookEvent {
  if (!value || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  if (typeof v.user_id !== "string") return false;
  if (typeof v.authentication_method !== "string") return false;
  if (!v.claims || typeof v.claims !== "object" || Array.isArray(v.claims)) {
    return false;
  }
  return true;
}

function hookErrorResponse(code: HookErrorCode, status: number): Response {
  return new Response(JSON.stringify({ error: code }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
