// B1a1 — OAuth credential / JWT / cache foundation. Purely additive on
// master: legacy `key=` send in `index.ts` is untouched. The OAuth
// TOKEN-EXCHANGE happens in `oauth.ts` (B1a2), which imports the
// foundation helpers exported here.
//
// Audit fixes (#374) and review-remediation fixes (#378, #379) live
// here:
//   - `FcmConfigError`: typed error class with `.code` contract
//   - `decodeServiceAccount`: accepts JSON or base64-encoded JSON
//   - `pemToBinary` / `mintFcmAssertionJwt`: PEM/PKCS#8/importKey
//     failures normalize to `FcmConfigError` (no raw DataError leak)
//   - RS256 JWT assertion minting uses ABSOLUTE Unix-epoch
//     `iat`/`exp` (passing `Date` objects — see Engram #380)
//   - bounded in-process token cache with safety window
// deno-lint-ignore-file no-import-prefix
import {
  create as jwtCreate,
  getNumericDate,
} from "https://deno.land/x/djwt@v2.8/mod.ts";

export interface FcmServiceAccount {
  readonly type: string;
  readonly project_id: string;
  readonly private_key_id?: string;
  readonly private_key: string;
  readonly client_email: string;
  readonly client_id?: string;
  readonly [k: string]: unknown;
}

export class FcmConfigError extends Error {
  readonly code: string;
  constructor(code: string, msg?: string) {
    super(msg ?? code);
    this.name = "FcmConfigError";
    this.code = code;
  }
}

export const MAX_EXPIRES_IN_SEC = 3600;
export const SAFETY_WINDOW_MS = 60_000;
export const GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
export const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
export const ASSERTION_LIFETIME_SEC = 3600;
export const NOT_CONFIGURED = "fcm_service_account_not_configured";

// === Bounded in-process token cache ===

interface CachedToken {
  readonly token: string;
  readonly expiresAtMs: number;
}
const tokenCache: Map<string, CachedToken> = new Map();

export function getCachedOAuthToken(
  projectId: string,
  nowMs: number = Date.now(),
): CachedToken | null {
  const e = tokenCache.get(projectId);
  if (!e) return null;
  if (nowMs >= e.expiresAtMs - SAFETY_WINDOW_MS) {
    tokenCache.delete(projectId);
    return null;
  }
  return e;
}

export function setCachedOAuthToken(
  projectId: string,
  token: string,
  expiresInSec: number,
  nowMs: number = Date.now(),
): CachedToken {
  const e: CachedToken = { token, expiresAtMs: nowMs + expiresInSec * 1000 };
  tokenCache.set(projectId, e);
  return e;
}

export function clearCachedOAuthToken(projectId: string): boolean {
  return tokenCache.delete(projectId);
}

export function clearOAuthCacheForTests(): void {
  tokenCache.clear();
}

// === PEM → DER (BASE-02: malformed input → FcmConfigError) ===

export function pemToBinary(pem: string): Uint8Array<ArrayBuffer> {
  const stripped = pem.replace(/-----BEGIN [^-]+-----/, "")
    .replace(/-----END [^-]+-----/, "").replace(/\s+/g, "");
  if (stripped.length === 0) {
    throw new FcmConfigError(NOT_CONFIGURED, "PEM body is empty");
  }
  let bin: string;
  try {
    bin = atob(stripped);
  } catch {
    throw new FcmConfigError(
      NOT_CONFIGURED,
      "PEM body is not valid base64",
    );
  }
  // `Uint8Array.from` over an `ArrayBuffer`-backed iterable so the returned
  // view is typed as `Uint8Array<ArrayBuffer>` — required by WebCrypto's
  // `BufferSource` under TypeScript 6+. The looser `new Uint8Array(len)` form
  // infers `ArrayBufferLike`, which permits `SharedArrayBuffer` and trips
  // deno check (TS2769) at importKey.
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

// === Service-account decode (raw JSON or base64-wrapped JSON) ===

export function decodeServiceAccount(raw: string): FcmServiceAccount {
  if (!raw) {
    throw new FcmConfigError(NOT_CONFIGURED, "empty secret");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw.trim());
  } catch {
    try {
      parsed = JSON.parse(atob(raw.trim()));
    } catch {
      throw new FcmConfigError(
        NOT_CONFIGURED,
        "not JSON or base64 JSON",
      );
    }
  }
  if (!parsed || typeof parsed !== "object") {
    throw new FcmConfigError(
      NOT_CONFIGURED,
      "did not decode to object",
    );
  }
  const obj = parsed as Record<string, unknown>;
  const missing: string[] = [];
  for (const f of ["project_id", "private_key", "client_email"]) {
    const v = obj[f];
    if (typeof v !== "string" || v.length === 0) missing.push(f);
  }
  if (missing.length > 0) {
    throw new FcmConfigError(
      NOT_CONFIGURED,
      `missing fields: ${missing.join(", ")}`,
    );
  }
  return obj as FcmServiceAccount;
}

/**
 * Mint an RS256 JWT with ABSOLUTE Unix-epoch iat/exp. Passing a `Date`
 * to djwt 2.8's `getNumericDate` encodes the absolute timestamp;
 * passing a `number` is interpreted as a relative offset (≈ now + n×
 * 1000 ms), producing a JWT ≈ 30 years in the future that Google
 * OAuth rejects. (See Engram #380.)
 */
export async function mintFcmAssertionJwt(
  sa: FcmServiceAccount,
  nowMs: number = Date.now(),
): Promise<string> {
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey(
      "pkcs8",
      pemToBinary(sa.private_key),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
  } catch {
    throw new FcmConfigError(
      NOT_CONFIGURED,
      "private_key is not valid PKCS#8",
    );
  }
  return await jwtCreate(
    { alg: "RS256", typ: "JWT" },
    {
      iss: sa.client_email,
      scope: FCM_SCOPE,
      aud: GOOGLE_OAUTH_TOKEN_URL,
      iat: getNumericDate(new Date(nowMs)),
      exp: getNumericDate(
        new Date(nowMs + ASSERTION_LIFETIME_SEC * 1000),
      ),
    },
    key,
  );
}
