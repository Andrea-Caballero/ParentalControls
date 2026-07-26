// PR 2 of openspec/changes/wire-pairing-and-approval-end-to-end.
//
// Deno tests for the get-devices-for-parent edge function. These tests mock
// the underlying fetch call (the Supabase JS client uses fetch under the
// hood) so we can exercise the handler without a live Supabase project.
//
// Run with: cd supabase/functions/get-devices-for-parent && deno test
//
// The tests cover the four scenarios from
// openspec/changes/wire-pairing-and-approval-end-to-end/specs/parent-device-list/spec.md:
//   1. Authenticated parent gets their own devices (200 + JSON array).
//   2. Unauthenticated caller is rejected (401).
//   3. Parent with no devices gets an empty array (200 + []).
//   4. RLS scopes rows to the authenticated parent (defense-in-depth).

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

// ============ Test fixtures ============

const PARENT_JWT_1 = "header.parent1.payload.signature";
const PARENT_JWT_2 = "header.parent2.payload.signature";

const PARENT_UUID_1 = "11111111-1111-1111-1111-111111111111";
const PARENT_UUID_2 = "22222222-2222-2222-2222-222222222222";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function authHeader(jwt: string): Record<string, string> {
  return { Authorization: `Bearer ${jwt}` };
}

/**
 * Build a fetch mock that responds to Supabase's auth and REST calls.
 * The `deviceRows` argument is the array returned by the devices table
 * query — simulating what RLS would return for the authenticated parent.
 *
 * We capture the URLs the function calls so Test 4 can assert that the
 * REST request was made (without leaking which parent_id was filtered,
 * because we don't add a client-side eq("parent_id") filter — RLS does
 * the work).
 */
function buildFetchMock(opts: {
  parentUuid: string;
  deviceRows: unknown[];
}) {
  const calls: Array<{ url: string; method: string }> = [];
  const fetchMock = async (
    input: RequestInfo | URL,
    _init?: RequestInit
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = _init?.method ?? "GET";
    calls.push({ url, method });

    // auth.getUser(token) hits /auth/v1/user
    if (url.includes("/auth/v1/user")) {
      return jsonResponse({ id: opts.parentUuid, email: "parent@local.test" });
    }

    // from("devices").select(...).order(...) hits /rest/v1/devices?...
    if (url.includes("/rest/v1/devices")) {
      return jsonResponse(opts.deviceRows);
    }

    return jsonResponse({ error: `unhandled url: ${url}` }, 500);
  };
  return { fetchMock, calls };
}

function makeRequest(method = "POST"): Request {
  return new Request("https://example.test/functions/v1/get-devices-for-parent", {
    method,
    headers: authHeader(PARENT_JWT_1),
    body: method === "POST" ? "{}" : undefined,
  });
}

function installFetchMock(fetchMock: typeof fetch) {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = fetchMock;
}

function restoreFetch() {
  // deno-lint-ignore no-explicit-any
  delete (globalThis as any).fetch;
}

// Required environment for the edge function handler.
function setEnv() {
  Deno.env.set("SUPABASE_URL", "https://example.supabase.co");
  Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key");
}

// ============ Tests ============

Deno.test({
  name: "returns 200 with empty array when no devices paired",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      parentUuid: PARENT_UUID_1,
      deviceRows: [],
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(makeRequest());

      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body, []);
      // Verify the function actually called auth.getUser + the devices REST query.
      assertEquals(calls.length >= 2, true);
      assertEquals(calls.some((c) => c.url.includes("/auth/v1/user")), true);
      assertEquals(calls.some((c) => c.url.includes("/rest/v1/devices")), true);
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "returns 200 with device list when parent has 2 devices",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const deviceRows = [
      {
        id: "device-aaaa",
        device_name: "Galaxy S21",
        device_model: "SM-G991B",
        os_version: "34",
        app_version: "1.0.0",
        device_state: "ACTIVE",
        policy_version: 5,
        last_seen_at: "2026-06-04T12:00:00Z",
      },
      {
        id: "device-bbbb",
        device_name: "Pixel 7",
        device_model: "Pixel 7",
        os_version: "35",
        app_version: "1.0.0",
        device_state: "LOCKED",
        policy_version: 3,
        last_seen_at: "2026-06-04T11:30:00Z",
      },
    ];
    const { fetchMock } = buildFetchMock({
      parentUuid: PARENT_UUID_1,
      deviceRows,
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(makeRequest());

      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body.length, 2);
      assertEquals(body[0].device_name, "Galaxy S21");
      assertEquals(body[0].device_state, "ACTIVE");
      assertEquals(body[1].device_name, "Pixel 7");
      assertEquals(body[1].device_state, "LOCKED");
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "returns 401 when no auth header",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    // No fetch call should happen — short-circuit on missing header.
    let fetchCalled = false;
    const fetchMock: typeof fetch = async () => {
      fetchCalled = true;
      return jsonResponse({});
    };
    installFetchMock(fetchMock);

    try {
      const request = new Request(
        "https://example.test/functions/v1/get-devices-for-parent",
        { method: "POST", body: "{}" }
      );
      const response = await handleRequest(request);

      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token requerido");
      assertEquals(fetchCalled, false);
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "returns only devices for the authenticated parent (RLS scopes per parent)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    // First call uses PARENT_JWT_1; the mocked devices REST response only
    // includes rows for parent 1 (RLS would scope them). Then a second call
    // with PARENT_JWT_2 gets parent 2's rows. The test asserts the handler
    // hits the devices endpoint with each caller's Authorization header
    // — the JWT is forwarded so RLS scopes the response correctly.
    const parent1Rows = [
      {
        id: "device-aaaa",
        device_name: "Parent 1 Device",
        device_model: "X",
        os_version: "34",
        app_version: "1.0.0",
        device_state: "ACTIVE",
        policy_version: 1,
        last_seen_at: "2026-06-04T12:00:00Z",
      },
    ];
    const parent2Rows = [
      {
        id: "device-cccc",
        device_name: "Parent 2 Device",
        device_model: "Y",
        os_version: "35",
        app_version: "1.0.0",
        device_state: "ACTIVE",
        policy_version: 1,
        last_seen_at: "2026-06-04T11:00:00Z",
      },
    ];

    const calls: Array<{ url: string; auth: string | null }> = [];
    function extractAuth(headers: HeadersInit | undefined): string | null {
      if (!headers) return null;
      if (typeof (headers as Headers).get === "function") {
        return (headers as Headers).get("Authorization");
      }
      if (Array.isArray(headers)) {
        const pair = headers.find(([k]) => k.toLowerCase() === "authorization");
        return pair ? pair[1] : null;
      }
      return (headers as Record<string, string>)["Authorization"] ?? null;
    }
    const fetchMock: typeof fetch = async (
      input: RequestInfo | URL,
      init?: RequestInit
    ): Promise<Response> => {
      const url = typeof input === "string" ? input : input.toString();
      const auth = extractAuth(init?.headers);
      calls.push({ url, auth });

      if (url.includes("/auth/v1/user")) {
        const uuid = auth?.includes(PARENT_JWT_1) ? PARENT_UUID_1 : PARENT_UUID_2;
        return jsonResponse({ id: uuid });
      }
      if (url.includes("/rest/v1/devices")) {
        return jsonResponse(
          auth?.includes(PARENT_JWT_1) ? parent1Rows : parent2Rows
        );
      }
      return jsonResponse({ error: "unhandled" }, 500);
    };
    installFetchMock(fetchMock);

    try {
      // Parent 1 request.
      const request1 = new Request(
        "https://example.test/functions/v1/get-devices-for-parent",
        {
          method: "POST",
          headers: authHeader(PARENT_JWT_1),
          body: "{}",
        }
      );
      const response1 = await handleRequest(request1);
      const body1 = await response1.json();

      // Parent 2 request.
      const request2 = new Request(
        "https://example.test/functions/v1/get-devices-for-parent",
        {
          method: "POST",
          headers: authHeader(PARENT_JWT_2),
          body: "{}",
        }
      );
      const response2 = await handleRequest(request2);
      const body2 = await response2.json();

      assertEquals(response1.status, 200);
      assertEquals(body1.length, 1);
      assertEquals(body1[0].device_name, "Parent 1 Device");

      assertEquals(response2.status, 200);
      assertEquals(body2.length, 1);
      assertEquals(body2[0].device_name, "Parent 2 Device");

      // The REST calls must each carry the caller's JWT so RLS can scope.
      const restCalls = calls.filter((c) => c.url.includes("/rest/v1/devices"));
      assertEquals(restCalls.length, 2);
      assertStringIncludes(restCalls[0].auth ?? "", PARENT_JWT_1);
      assertStringIncludes(restCalls[1].auth ?? "", PARENT_JWT_2);
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "OPTIONS preflight returns 200 with CORS headers",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const request = new Request(
      "https://example.test/functions/v1/get-devices-for-parent",
      { method: "OPTIONS" }
    );
    const response = await handleRequest(request);
    assertEquals(response.status, 200);
    assertEquals(
      response.headers.get("Access-Control-Allow-Origin"),
      "*"
    );
  },
});