// BLK-01 — shared Supabase Auth verification helper.
//
// Replaces the historical pattern where Edge Functions trusted a JWT
// payload they decoded with `atob(token.split(".")[1])`. That decoding
// only base64-decodes a string — it does NOT verify the signature, the
// expiration, or the revocation state. An attacker can craft a payload
// claiming `device_id` of any device and the old handlers would have
// served the request.
//
// This module wraps `supabase.auth.getUser(token)`, which round-trips
// the JWT to the project's `/auth/v1/user` endpoint. The Supabase Auth
// server validates the JWT against its signing keys and only returns
// the user record when the token is valid. The returned `User` object's
// `app_metadata` is the database's authoritative record (set by the
// pairing RPC), so even a forged JWT cannot fake a device_id — the
// server-side `app_metadata.device_id` will be empty/missing for an
// attacker and the call returns 403.
//
// The contract this module enforces:
//
//   1. Bearer token must be present and parse to a real JWT.
//   2. `supabase.auth.getUser(token)` must succeed — this is the
//      cryptographic verification step (signature, expiration, revocation).
//   3. For device handlers, `user.app_metadata.device_id` must be a
//      non-empty string. The pairing flow (T15) writes this value when
//      the child device is paired, so a verified user without one is
//      either a parent who has not paired a device yet (legitimate) or
//      a forged token (the helper does not distinguish; the caller
//      decides which handlers require a device).
//   4. The helper returns a discriminated union — either the verified
//      identity or a ready-to-return `Response`. The function making
//      the call simply checks `result.ok` and either continues or
//      returns `result.response`.
//   5. Every failure response carries a `WWW-Authenticate: Bearer`
//      challenge (RFC 7235 §4.1) so the client can debug the
//      authentication failure: 401 with the bare Bearer realm, 403
//      with `error="insufficient_scope"` for the missing verified
//      device_id case.
//
// Service-role privileges: this module NEVER instantiates a service-role
// client. It uses the anon key only (matching the reference pattern in
// `create-pairing-code/index.ts` and `get-devices-for-parent/index.ts`).
// Each handler constructs its own service-role client AFTER receiving
// `result.ok === true`, so an unverifiable token cannot reach privileged
// code paths.

import { createClient, type SupabaseClient, type User } from "https://esm.sh/@supabase/supabase-js@2";

/** Parsed verified identity returned by `verifyAuth`. */
export type VerifiedAuth =
  | {
    ok: true;
    user: User;
    /** auth.uid() — server-controlled, NOT the JWT `sub` claim. */
    parentId: string;
    /**
     * device_id read from the verified user's `app_metadata`. The hook at
     * `custom-access-token-hook/index.ts` writes this same value as a
     * top-level claim, but the JWT claim is forgeable; this field is
     * the database-authoritative value and is what handlers must trust.
     */
    deviceId: string | null;
  }
  | {
    ok: false;
    response: Response;
  };

export interface VerifyAuthOptions {
  /** Raw `Authorization` header (e.g. "Bearer eyJhbGc..."). */
  authHeader: string | null;
  /** CORS headers that must be attached to error responses. */
  corsHeaders: Record<string, string>;
  /**
   * Environment used to construct the auth client. `url` is
   * `SUPABASE_URL`; `anonKey` is `SUPABASE_ANON_KEY`. The anon key
   * is sufficient because `/auth/v1/user` validates the JWT against
   * the project's signing keys and only returns the user record when
   * the token is valid — no RLS bypass is involved.
   */
  env: { url: string; anonKey: string };
  /**
   * Optional pre-built Supabase client (test injection). When omitted,
   * a new client is created from `env` + a global `Authorization`
   * header override so the JWT round-trips.
   */
  client?: SupabaseClient;
  /**
   * When `true`, missing/empty `app_metadata.device_id` returns
   * `403 "device_id no encontrado en token"` (the historical device
   * handler contract). When `false` (default), missing `device_id` is
   * allowed — `parentId` is still derived from the verified user.
   */
  requireDevice?: boolean;
}

const BEARER_RE = /^Bearer\s+(\S+)$/i;

/**
 * Verify the Bearer token via Supabase Auth and return the verified
 * identity. The function never throws — every failure path returns
 * a `Response` with a stable error envelope so handlers can use the
 * short-circuit pattern:
 *
 *     const auth = await verifyAuth({...});
 *     if (!auth.ok) return auth.response;
 *     const { user, parentId, deviceId } = auth;
 *
 * Failure responses carry a `WWW-Authenticate: Bearer` challenge (RFC
 * 7235 §4.1). 401 ("missing/invalid token") replies with the bare
 * Bearer realm; 403 ("authenticated but missing verified device_id")
 * replies with `error="insufficient_scope"`.
 */
export async function verifyAuth(
  opts: VerifyAuthOptions,
): Promise<VerifiedAuth> {
  const { authHeader, corsHeaders, env, requireDevice = false } = opts;

  if (!authHeader) {
    return unauthorized(corsHeaders, "Token requerido");
  }

  const match = BEARER_RE.exec(authHeader);
  if (!match) {
    return unauthorized(corsHeaders, "Token requerido");
  }
  const token = match[1];

  const client = opts.client ?? createClient(env.url, env.anonKey, {
    auth: { persistSession: false },
    global: { headers: { Authorization: authHeader } },
  });

  let user: User | null = null;
  try {
    // supabase-js returns `{ data: { user }, error }`. The error path
    // fires when the SDK rejects the token shape or the server returns
    // a 4xx envelope — both are authentication failures, not 5xx.
    const { data, error } = await client.auth.getUser(token);
    if (!error && data?.user) {
      user = data.user;
    } else if (error) {
      // Log the underlying error for operators but do not leak the
      // envelope to the caller — a uniform 401 hides whether the
      // failure was expiration, signature, or revocation.
      console.error("auth.getUser rejected:", error.message ?? error);
    }
  } catch (err) {
    // The Supabase JS client rejects with an error when the network
    // call itself fails (e.g. malformed token shape that the SDK
    // rejects before reaching the server). We treat this as an
    // authentication failure, not a 500 — leaking a stack trace or
    // a transport error here would help an attacker probe the
    // verifier. Log the underlying error for operators.
    console.error("auth.getUser transport error:", err);
  }

  if (!user) {
    return unauthorized(corsHeaders, "Token inválido o expirado");
  }

  const parentId = user.id;
  const deviceId = readDeviceIdFromUser(user);

  if (requireDevice && !deviceId) {
    // The token is cryptographically valid (signature + expiration
    // pass server-side), but the verified user has no device_id in
    // their app_metadata. This is the "authenticated caller lacking
    // required device identity" branch — 403 per the BLK-01 spec.
    return forbidden(corsHeaders, "device_id no encontrado en token");
  }

  return { ok: true, user, parentId, deviceId };
}

/**
 * Read device_id from the verified user's `app_metadata`. The custom
 * access token hook writes the same value as a top-level JWT claim,
 * but the JWT claim is forgeable — `app_metadata` is the
 * database-authoritative source. Returns `null` when the value is
 * missing, empty, or not a non-empty string.
 */
function readDeviceIdFromUser(user: User): string | null {
  // The Supabase User type narrows `app_metadata` to specific keys
  // in the public typings; we widen to read the custom `device_id`
  // field the pairing RPC writes. Cast to a permissive shape because
  // the public User interface does not include custom metadata keys.
  const meta = (user.app_metadata ?? {}) as Record<string, unknown>;
  const value = meta.device_id;
  if (typeof value !== "string" || value.length === 0) return null;
  return value;
}

function unauthorized(
  corsHeaders: Record<string, string>,
  message: string,
): { ok: false; response: Response } {
  return {
    ok: false,
    response: new Response(
      JSON.stringify({ error: message }),
      {
        status: 401,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json",
          // RFC 7235 §4.1: a 401 response MUST carry a WWW-Authenticate
          // challenge so the client knows which scheme to retry with.
          // The Bearer scheme is the only one this verifier accepts.
          "WWW-Authenticate": 'Bearer realm="parental-control"',
        },
      },
    ),
  };
}

function forbidden(
  corsHeaders: Record<string, string>,
  message: string,
): { ok: false; response: Response } {
  return {
    ok: false,
    response: new Response(
      JSON.stringify({ error: message }),
      {
        status: 403,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json",
          // RFC 7235 §4.1: 403 is an authenticated request that the
          // server refuses to authorize. We still surface a
          // WWW-Authenticate challenge so the client can debug the
          // missing-scope reason (here, an absent verified device_id).
          "WWW-Authenticate":
            'Bearer realm="parental-control", error="insufficient_scope", error_description="device_id required"',
        },
      },
    ),
  };
}
