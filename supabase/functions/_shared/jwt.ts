// Shared JWT helpers for ParentalControl Supabase Edge Functions.
//
// These two functions used to be copy-pasted across six edge functions
// (get-policy, register-token, heartbeat, approve-request, reward,
// verify-integrity). Drift between the copies was the root cause of
// earlier regressions where one site accepted a payload the other
// rejected. Centralising here means a single source of truth for the
// Bearer header contract.
//
// Contract (pinned by _shared/jwt_test.ts):
//   - require "Authorization: Bearer <jwt>" exactly
//   - the JWT MUST have three base64url segments (header.payload.signature)
//   - the payload segment is base64url-normalised and padded before atob
//   - missing header              → 401 "Token requerido"
//   - malformed bearer/JWT/payload → 401 "Token inválido"
//   - payload present but no/empty device_id → 403 "device_id no encontrado en token"
//
// decodeJwtPayload exposes the raw payload for callers that need claims
// other than device_id (e.g. approve-request / reward read the parent's
// `sub` claim).

/**
 * Extract a non-empty bearer credential without attempting to validate it.
 * Signature, expiry, and revocation remain the responsibility of
 * `supabase.auth.getUser(token)` in the calling edge function.
 */
export function extractBearerToken(authHeader: string | null): string | null {
  const match = authHeader?.match(/^Bearer\s+(\S+)$/i);
  return match?.[1] ?? null;
}

export function decodeJwtPayload(authHeader: string): Record<string, unknown> | null {
  const match = authHeader.match(/^Bearer\s+(\S+)$/i);
  if (!match) return null;

  const segments = match[1].split(".");
  if (segments.length !== 3 || !segments[1]) return null;

  try {
    const payload = segments[1].replace(/-/g, "+").replace(/_/g, "/");
    const paddedPayload = payload.padEnd(Math.ceil(payload.length / 4) * 4, "=");
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(paddedPayload)) return null;
    const decoded = JSON.parse(atob(paddedPayload));
    return decoded && typeof decoded === "object" && !Array.isArray(decoded)
      ? decoded as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

export function parseDeviceId(authHeader: string | null):
  | { status: 200; deviceId: string }
  | { status: 401 | 403; error: string } {
  if (!authHeader) return { status: 401, error: "Token requerido" };

  const payload = decodeJwtPayload(authHeader);
  if (!payload) return { status: 401, error: "Token inválido" };

  const deviceId = payload.device_id;
  return typeof deviceId === "string" && deviceId.length > 0
    ? { status: 200, deviceId }
    : { status: 403, error: "device_id no encontrado en token" };
}
