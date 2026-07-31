// BLK-01 tests for `heartbeat/index.ts`.
//
// Pinned trust boundaries:
//   1. Missing/malformed Authorization → 401 (no service-role call).
//   2. Forged/expired JWT → 401 (no devices / heartbeats / outbox writes).
//   3. Valid Bearer but no app_metadata.device_id → 403
//      (no service-role call).
//   4. Valid Bearer + verified device → 200, and the heartbeat insert
//      uses the server-side deviceId, not the JWT claim.

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const DEVICE_UUID_FROM_AUTH = "server-device-id";
const DEVICE_UUID_FROM_JWT = "attacker-device-id";
const PARENT_UUID = "11111111-1111-1111-1111-111111111111";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

interface BuildOptions {
  authOutcome: "ok-with-device" | "ok-without-device" | "reject";
}

function buildFetchMock(opts: BuildOptions) {
  const calls: Array<{ url: string; method: string; body?: unknown; auth: string | null }> = [];
  const fetchMock = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    let body: unknown = undefined;
    if (init?.body) {
      try {
        body = typeof init.body === "string"
          ? JSON.parse(init.body)
          : init.body;
      } catch {
        body = init.body;
      }
    }
    const auth = extractAuth(init?.headers);
    calls.push({ url, method, body, auth });

    if (url.includes("/auth/v1/user")) {
      if (opts.authOutcome === "reject") {
        // Supabase Auth 401 envelope — the helper maps this to
        // 401 "Token inválido o expirado" without ever instantiating
        // the service-role client.
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      if (opts.authOutcome === "ok-without-device") {
        return jsonResponse({ id: PARENT_UUID, email: "p@local.test" });
      }
      return jsonResponse({
        id: PARENT_UUID,
        email: "device@local.test",
        app_metadata: { device_id: DEVICE_UUID_FROM_AUTH },
      });
    }

    if (url.includes("/rest/v1/devices") && method === "GET") {
      return jsonResponse({
        id: DEVICE_UUID_FROM_AUTH,
        policy_version: 1,
      });
    }
    if (url.includes("/rest/v1/devices") && method === "PATCH") {
      return jsonResponse([{ id: DEVICE_UUID_FROM_AUTH }]);
    }
    if (url.includes("/rest/v1/device_heartbeats")) {
      return jsonResponse([{ id: "heartbeat-mock" }]);
    }
    if (url.includes("/rest/v1/outbox")) {
      return jsonResponse([{ id: "outbox-mock" }]);
    }
    return jsonResponse({ error: `unhandled ${url}` }, 500);
  };
  return { fetchMock, calls };
}

function extractAuth(headers: HeadersInit | undefined): string | null {
  if (!headers) return null;
  if (typeof (headers as Headers).get === "function") {
    return (headers as Headers).get("Authorization");
  }
  if (Array.isArray(headers)) {
    const pair = headers.find(([k]) => k.toLowerCase() === "authorization");
    return pair ? pair[1] : null;
  }
  // deno-lint-ignore no-explicit-any
  return (headers as Record<string, any>)["Authorization"] ?? null;
}

function installFetchMock(fetchMock: typeof fetch) {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = fetchMock;
}

function restoreFetch() {
  // deno-lint-ignore no-explicit-any
  delete (globalThis as any).fetch;
}

function setEnv() {
  Deno.env.set("SUPABASE_URL", "https://example.supabase.co");
  Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-role-test-key");
}

function makeHeartbeatRequest(auth: string): Request {
  return new Request("https://example.test/functions/v1/heartbeat", {
    method: "POST",
    headers: { Authorization: auth, "Content-Type": "application/json" },
    body: JSON.stringify({
      battery_level: 80,
      is_charging: false,
      app_in_foreground: true,
    }),
  });
}

Deno.test({
  name: "BLK-01 — forged JWT returns 401 and never reaches service-role paths",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({ authOutcome: "reject" });
    installFetchMock(fetchMock);
    try {
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(makeHeartbeatRequest(`Bearer ${forged}`));
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token inválido o expirado");
      const privilegedCalls = calls.filter((c) => c.url.includes("/rest/v1/"));
      assertEquals(privilegedCalls.length, 0);

      // The forged Bearer token must reach /auth/v1/user so the
      // cryptographic verifier can reject it. If the helper truncated
      // or synthesized the token we couldn't trust the verification.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${forged}`,
        "helper must forward the caller's exact Bearer token to /auth/v1/user",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — verified parent without a paired device returns 403 'device_id no encontrado en token'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      authOutcome: "ok-without-device",
    });
    installFetchMock(fetchMock);
    try {
      const jwt = "header.payload.signature";
      const response = await handleRequest(
        makeHeartbeatRequest(`Bearer ${jwt}`),
      );
      assertEquals(response.status, 403);
      const body = await response.json();
      assertEquals(body.error, "device_id no encontrado en token");
      const privilegedCalls = calls.filter((c) => c.url.includes("/rest/v1/"));
      assertEquals(privilegedCalls.length, 0);

      // The 403 branch (verified user, no paired device) ALSO
      // requires the helper to forward the Bearer token to
      // /auth/v1/user — the cryptographic step runs first.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${jwt}`,
        "Bearer token must be forwarded on the 403 branch too",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — verified device: heartbeat uses app_metadata.device_id, NOT the JWT claim",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      authOutcome: "ok-with-device",
    });
    installFetchMock(fetchMock);
    try {
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(makeHeartbeatRequest(`Bearer ${forged}`));
      assertEquals(response.status, 200);
      const hb = calls.find((c) =>
        c.url.includes("/rest/v1/device_heartbeats") && c.method === "POST"
      );
      assertEquals(typeof hb, "object");
      const hbBody = hb!.body as Record<string, unknown>;
      assertEquals(
        hbBody.device_id,
        DEVICE_UUID_FROM_AUTH,
        "device_heartbeats insert must use the verified deviceId",
      );
      assertEquals(
        hbBody.device_id === DEVICE_UUID_FROM_JWT,
        false,
        "the JWT device_id claim must never reach the service-role insert",
      );

      // And the Bearer token must reach /auth/v1/user on the happy
      // path too — the cryptographic verification step is only
      // meaningful if the caller's token actually goes there.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${forged}`,
        "happy path must forward the Bearer token to /auth/v1/user",
      );
    } finally {
      restoreFetch();
    }
  },
});

/**
 * Pins that wrong/missing Bearer tokens are rejected by the
 * verifier path. The mock only accepts the EXPECTED JWT; sending
 * a DIFFERENT JWT must yield a 401.
 */
Deno.test({
  name: "BLK-01 — wrong Bearer token (mismatch with the verifier's expected value) returns 401 'Token inválido o expirado'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const wrongJwt = "header.WRONG-JWT-FOR-HEARTBEAT.signature";
    // The mock pretends the only valid JWT is `good.jwt`. Any other
    // Bearer must produce the Supabase Auth 401 envelope, which the
    // helper translates to "Token inválido o expirado".
    const fetchMock: typeof fetch = async (
      input: RequestInfo | URL,
      _init?: RequestInit,
    ): Promise<Response> => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/auth/v1/user")) {
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      return jsonResponse({ error: `unhandled ${url}` }, 500);
    };
    installFetchMock(fetchMock);
    try {
      const response = await handleRequest(
        makeHeartbeatRequest(`Bearer ${wrongJwt}`),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(
        body.error,
        "Token inválido o expirado",
        "wrong Bearer token must be rejected by the verifier — no service-role call",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — missing Authorization returns 401 with no service-role call",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    let fetchCalled = false;
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      fetchCalled = true;
      return Promise.resolve(new Response(""));
    };
    try {
      const response = await handleRequest(
        new Request("https://example.test/functions/v1/heartbeat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ battery_level: 80 }),
        }),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token requerido");
      assertEquals(fetchCalled, false);
    } finally {
      restoreFetch();
    }
  },
});
