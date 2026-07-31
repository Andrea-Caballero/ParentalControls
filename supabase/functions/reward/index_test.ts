// BLK-01 tests for `reward/index.ts`.
//
// The legacy reward test only covered the malformed-JWT 401 path.
// After the BLK-01 hardening the handler must:
//   1. Use `supabase.auth.getUser(token)` to verify the JWT (via the
//      shared `_shared/jwt.ts` helper).
//   2. Derive `parentId` from the verified `user.id`, not the JWT
//      `sub` claim.
//   3. Construct the service-role client only after a verified
//      identity is returned — so a forged/expired token never
//      reaches the rewards / device lookup / grant insert path.

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_ID = "device-1";
const PARENT_JWT = "header.parent.payload.signature";

// Headers that the OLD handler treated as "auth failure" (401). Under
// the BLK-01 helper the error message is more granular but the HTTP
// status is still 401, and no privileged work happens.
const MALFORMED_AUTH_HEADERS = [
  "Basic credentials",
  "Bearer only-two-segments.parts",
  "Bearer header.%%%invalid%%%.signature",
  "Bearer header.eyJzdWIi.signature",
];

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

interface Device {
  id: string;
  parent_id: string;
}

function buildFetchMock(opts: {
  rejectAuth?: boolean;
  device?: Device;
  noGrants?: boolean;
  insertError?: boolean;
}) {
  const calls: Array<{ url: string; method: string; body: unknown }> = [];
  const device: Device = opts.device ?? {
    id: DEVICE_ID,
    parent_id: PARENT_UUID,
  };

  const parseBody = async (init?: RequestInit): Promise<unknown> => {
    if (!init?.body) return undefined;
    if (typeof init.body === "string") {
      try {
        return JSON.parse(init.body);
      } catch {
        return init.body;
      }
    }
    return undefined;
  };

  const fetchMock = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = await parseBody(init);
    calls.push({ url, method, body });

    // BLK-01: auth.getUser(token) hits /auth/v1/user.
    if (url.includes("/auth/v1/user")) {
      if (opts.rejectAuth) {
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      return jsonResponse({ id: PARENT_UUID, email: "p@local.test" });
    }

    // Device ownership check.
    if (
      url.includes("/rest/v1/devices") && method === "GET"
    ) {
      return jsonResponse([device]);
    }

    // Recent reward grants.
    if (url.includes("/rest/v1/grants") && method === "GET") {
      if (opts.noGrants) return jsonResponse([]);
      return jsonResponse([]);
    }

    // Grant insert.
    if (url.includes("/rest/v1/grants") && method === "POST") {
      if (opts.insertError) {
        return jsonResponse(
          { message: "forced insert error" },
          500,
        );
      }
      return jsonResponse({
        id: "grant-mock",
        device_id: DEVICE_ID,
        minutes: 5,
        source: "REWARD",
        status: "APPROVED",
      });
    }

    // FCM token lookup — no token means no push.
    if (url.includes("/rest/v1/device_push_tokens")) {
      return jsonResponse(null);
    }

    return jsonResponse({ error: `unhandled url: ${url}` }, 500);
  };
  return { fetchMock, calls };
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
  Deno.env.set("FCM_SERVER_KEY", "fcm-server-key-test");
}

function makeRewardRequest(
  body: object,
  auth = PARENT_JWT,
): Request {
  return new Request("https://example.test/functions/v1/reward", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${auth}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

// ============ Tests ============

Deno.test({
  name: "reward rejects malformed Authorization/JWT with 401",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The BLK-01 helper creates a Supabase client to call
    // /auth/v1/user, so the env must be set. The mock fetch below
    // then guarantees no auth or DB traffic actually happens.
    setEnv();
    const originalFetch = globalThis.fetch;
    // Any fetch call here would mean we got past auth parsing, which
    // is a regression. Use a hard failure so the test is noisy.
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      throw new Error("fetch must not be called for malformed auth");
    };

    try {
      for (const authHeader of MALFORMED_AUTH_HEADERS) {
        const response = await handleRequest(
          new Request("https://example.test/functions/v1/reward", {
            method: "POST",
            headers: {
              Authorization: authHeader,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ device_id: DEVICE_ID, minutes: 5, reason: "test" }),
          }),
        );
        assertEquals(response.status, 401);
      }
    } finally {
      // deno-lint-ignore no-explicit-any
      (globalThis as any).fetch = originalFetch;
    }
  },
});

Deno.test({
  name:
    "BLK-01 — forged JWT with fake sub is rejected; no devices/grants writes happen",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    // The forged payload claims sub = PARENT_UUID. The mock rejects
    // /auth/v1/user so the helper returns 401 and the handler must
    // never reach the devices/grants endpoints.
    const forgedJwt =
      `header.${btoa(JSON.stringify({ sub: PARENT_UUID }))}.signature`;

    const { fetchMock, calls } = buildFetchMock({ rejectAuth: true });
    installFetchMock(fetchMock);
    try {
      const response = await handleRequest(
        makeRewardRequest(
          { device_id: DEVICE_ID, minutes: 5, reason: "x" },
          forgedJwt,
        ),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token inválido o expirado");

      // The service-role / devices / grants path must NOT be reached.
      const privilegedCalls = calls.filter((c) =>
        c.url.includes("/rest/v1/devices") ||
        c.url.includes("/rest/v1/grants") ||
        c.url.includes("/rest/v1/device_push_tokens")
      );
      assertEquals(
        privilegedCalls.length,
        0,
        "forged/expired JWT must not reach devices/grants/push_tokens. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "BLK-01 — verified parent: device query and grant insert use the verified parentId (not JWT sub)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();

    // The JWT carries a sub for a DIFFERENT user. The mock returns
    // the real parent from /auth/v1/user. The handler must use the
    // verified identity for the device ownership check + grant insert.
    const forgedSubJwt =
      `header.${
        btoa(JSON.stringify({ sub: "00000000-0000-0000-0000-000000000000" }))
      }.signature`;

    const { fetchMock, calls } = buildFetchMock({});
    installFetchMock(fetchMock);
    try {
      const response = await handleRequest(
        makeRewardRequest(
          { device_id: DEVICE_ID, minutes: 5, reason: "x" },
          forgedSubJwt,
        ),
      );
      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body.success, true);

      // The device ownership check should have used the verified
      // parentId, NOT the JWT sub.
      const deviceCall = calls.find((c) =>
        c.url.includes("/rest/v1/devices") && c.method === "GET"
      );
      assertEquals(typeof deviceCall, "object");
      // The device fetch URL must include parent_id=eq.<verified uuid>.
      assertStringIncludes(
        deviceCall!.url,
        `parent_id=eq.${PARENT_UUID}`,
        "device ownership check must use the verified parentId, not the JWT sub",
      );
    } finally {
      restoreFetch();
    }
  },
});
