// BLK-01 tests for `get-policy/index.ts`.
//
// Pinned trust boundaries:
//   1. Missing/malformed Authorization → 401 (no service-role call).
//   2. Forged/expired JWT (any Bearer that the Supabase server rejects)
//      → 401 (no get_device_policy RPC, no devices SELECT).
//   3. Valid Bearer but the verified user has no `app_metadata.device_id`
//      → 403 "device_id no encontrado en token" (no service-role call).
//   4. Valid Bearer + verified user with app_metadata.device_id → 200
//      and the policy fetch uses the verified deviceId (server-side
//      value, not the JWT claim).

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_UUID_FROM_AUTH = "server-device-id"; // what the server says
const DEVICE_UUID_FROM_JWT = "attacker-device-id"; // what the JWT claim says

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

interface BuildOptions {
  /** "ok" returns a verified user (with or without app_metadata.device_id).
   *  "reject" returns the 401 envelope for /auth/v1/user. */
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

    if (url.includes("/rest/v1/rpc/get_device_policy")) {
      return jsonResponse({
        policy: "default",
        policy_version: 1,
        grants_active: [],
      });
    }
    if (url.includes("/rest/v1/devices") && method === "GET") {
      return jsonResponse({
        id: DEVICE_UUID_FROM_AUTH,
        device_name: "Test",
        app_version: "1.0.0",
        last_seen_at: new Date().toISOString(),
      });
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

Deno.test({
  name: "BLK-01 — forged JWT (rejected by Supabase Auth) returns 401 and never reaches service-role paths",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({ authOutcome: "reject" });
    installFetchMock(fetchMock);
    try {
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(
        new Request("https://example.test/functions/v1/get-policy", {
          method: "GET",
          headers: { Authorization: `Bearer ${forged}` },
        }),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token inválido o expirado");

      // No service-role call must have happened.
      const privilegedCalls = calls.filter((c) =>
        c.url.includes("/rest/v1/") ||
        c.url.includes("/rest/v1/rpc/")
      );
      assertEquals(
        privilegedCalls.length,
        0,
        `forged JWT must not reach the service-role client. Calls: ${
          calls.map((c) => `${c.method} ${c.url}`).join(", ")
        }`,
      );

      // The Bearer token must reach /auth/v1/user so the verifier
      // can actually reject it.
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
        new Request("https://example.test/functions/v1/get-policy", {
          method: "GET",
          headers: { Authorization: `Bearer ${jwt}` },
        }),
      );
      assertEquals(response.status, 403);
      const body = await response.json();
      assertEquals(body.error, "device_id no encontrado en token");
      // No service-role call must have happened.
      const privilegedCalls = calls.filter((c) =>
        c.url.includes("/rest/v1/") || c.url.includes("/rest/v1/rpc/")
      );
      assertEquals(privilegedCalls.length, 0);

      // Even on the 403 branch the Bearer token must reach the
      // verifier — the cryptographic step runs before the
      // requireDevice check.
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
  name:
    "BLK-01 — verified device: get_device_policy is called with app_metadata.device_id, NOT the JWT claim",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      authOutcome: "ok-with-device",
    });
    installFetchMock(fetchMock);
    try {
      // The forged JWT carries a top-level `device_id` claim. The
      // helper MUST use the server-side value (DEVICE_UUID_FROM_AUTH)
      // for the get_device_policy RPC, not the JWT claim.
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(
        new Request("https://example.test/functions/v1/get-policy", {
          method: "GET",
          headers: { Authorization: `Bearer ${forged}` },
        }),
      );
      assertEquals(response.status, 200);

      const rpc = calls.find((c) =>
        c.url.includes("/rest/v1/rpc/get_device_policy")
      );
      assertEquals(typeof rpc, "object");
      // supabase-js sends the RPC args in the POST body.
      const rpcBody = rpc!.body as Record<string, unknown>;
      assertEquals(
        rpcBody.target_device_id,
        DEVICE_UUID_FROM_AUTH,
        "get_device_policy must be called with the verified deviceId",
      );
      // The JWT claim must NOT appear in the RPC body.
      assertEquals(
        rpcBody.target_device_id === DEVICE_UUID_FROM_JWT,
        false,
        "the JWT device_id claim must never reach the service-role RPC",
      );

      // And the Bearer token must reach /auth/v1/user on the happy
      // path too — without it, the cryptographic step can't run.
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

Deno.test({
  name: "BLK-01 — wrong Bearer token is rejected; no service-role call happens",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const wrongJwt = "header.WRONG-JWT-FOR-GET-POLICY.signature";
    const fetchMock: typeof fetch = async (
      input: RequestInfo | URL,
      _init?: RequestInit,
    ): Promise<Response> => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/auth/v1/user")) {
        // Wrong Bearer → Supabase Auth 401 envelope.
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
        new Request("https://example.test/functions/v1/get-policy", {
          method: "GET",
          headers: { Authorization: `Bearer ${wrongJwt}` },
        }),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(
        body.error,
        "Token inválido o expirado",
        "wrong Bearer token must be rejected by the verifier",
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
        new Request("https://example.test/functions/v1/get-policy", {
          method: "GET",
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
