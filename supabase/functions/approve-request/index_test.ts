// Deno tests for `approve-request/index.ts` after the migration to the
// `approve_request_atomic` RPC (supabase/migrations/012_approve_request_atomic.sql).
//
// The production handler now commits verdict + grant via a single
// RPC POST to `/rest/v1/rpc/approve_request_atomic`. The fetch mock
// in this file simulates the migration contract:
//   - SELECT /rest/v1/time_requests → ownership precheck
//   - PATCH /rest/v1/time_requests → auto-DENY sweep on stale PENDING rows
//   - POST /rest/v1/rpc/approve_request_atomic → atomic verdict + grant
//   - GET  /rest/v1/device_push_tokens → optional FCM lookup
//   - GET  /auth/v1/user              → BLK-01 JWT verification
//
// The auto-DENY 1h sweep and the auth-hardening 401 contract are
// pinned by the existing tests below. The RPC mock was added so the
// handler's `supabaseAdmin.rpc("approve_request_atomic", ...)` call
// resolves to the migration's success/error shape.

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_ID = "device-aaaa";
const STALE_REQUEST_ID = "req-stale-old";
const FRESH_REQUEST_ID = "req-fresh-new";

// Headers that the OLD handler treated as "auth failure" (401). Under
// the BLK-01 helper, the error message is more granular:
//   - headers without a Bearer prefix → 401 "Token requerido"
//   - headers with a Bearer prefix but a rejected JWT → 401
//     "Token inválido o expirado"
// Either way the HTTP status is 401 and no privileged work happens,
// which is the contract the test still pins.
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

function authHeader(jwt: string): Record<string, string> {
  return { Authorization: `Bearer ${jwt}` };
}

/**
 * Build a fetch mock that mirrors the post-migration handler call shape:
 *  - GET  /auth/v1/user → BLK-01 JWT verification (returns the
 *    server-side parent identity)
 *  - time_requests SELECT (ownership precheck)
 *  - time_requests PATCH (auto-DENY sweep only — the RPC owns the verdict)
 *  - POST /rest/v1/rpc/approve_request_atomic (atomic verdict + grant)
 *  - device_push_tokens SELECT (FCM lookup; null token = no push)
 */
function buildFetchMock(opts: {
  staleRequest: Record<string, unknown>;
  freshRequest: Record<string, unknown>;
  /** When set, /auth/v1/user returns an error envelope (forged/expired JWT). */
  rejectAuth?: boolean;
}) {
  const calls: Array<{ url: string; method: string; body: unknown }> = [];
  const timeRequests = new Map<string, Record<string, unknown>>();
  timeRequests.set(STALE_REQUEST_ID, opts.staleRequest);
  timeRequests.set(FRESH_REQUEST_ID, opts.freshRequest);

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
    init?: RequestInit
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = await parseBody(init);
    calls.push({ url, method, body });

    // BLK-01: auth.getUser(token) hits /auth/v1/user. The mock returns
    // the server-side parent identity (NOT a decoded sub claim). When
    // opts.rejectAuth is set we return the standard "invalid_grant"
    // envelope so the helper returns 401.
    if (url.includes("/auth/v1/user")) {
      if (opts.rejectAuth) {
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      return jsonResponse({ id: PARENT_UUID, email: "parent@local.test" });
    }

    // time_requests SELECT — ownership precheck
    if (url.includes("/rest/v1/time_requests") && method === "GET") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? timeRequests.get(id) : undefined;
      if (!row) return jsonResponse({ error: "not found" }, 404);
      return jsonResponse({ ...row, devices: { parent_id: PARENT_UUID } });
    }

    // time_requests PATCH — auto-DENY sweep only.
    if (url.includes("/rest/v1/time_requests") && method === "PATCH") {
      if (
        body &&
        typeof body === "object" &&
        (body as Record<string, unknown>).status === "DENIED" &&
        (body as Record<string, unknown>).response_text ===
          "Auto-denied: no parent response within 1h"
      ) {
        const urlLtMatch = url.match(/created_at=lt\.([^&]+)/);
        const ltIso = urlLtMatch ? decodeURIComponent(urlLtMatch[1]) : null;
        for (const [_id, row] of timeRequests.entries()) {
          if (
            row.status === "PENDING" &&
            row.device_id === DEVICE_ID &&
            ltIso != null &&
            typeof row.created_at === "string" &&
            (row.created_at as string) < ltIso
          ) {
            Object.assign(row, body as Record<string, unknown>);
          }
        }
        return jsonResponse([]);
      }
      return jsonResponse([]);
    }

    // approve_request_atomic RPC — migration 012.
    if (
      url.includes("/rest/v1/rpc/approve_request_atomic") &&
      method === "POST"
    ) {
      const params = (body ?? {}) as Record<string, unknown>;
      if (params.p_minutes == null) {
        return jsonResponse({ success: true, decision: "DENIED" });
      }
      return jsonResponse({
        success: true,
        decision: "APPROVED",
        grant_id: "grant-mock",
        minutes: Number(params.p_minutes),
        expires_at: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
        policy_version: 1,
      });
    }

    if (url.includes("/rest/v1/device_push_tokens")) {
      return jsonResponse(null);
    }

    return jsonResponse({ error: `unhandled url: ${url}` }, 500);
  };
  return { fetchMock, calls, timeRequests };
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

function makeApproveRequest(body: object): Request {
  return new Request("https://example.test/functions/v1/approve-request", {
    method: "POST",
    headers: {
      Authorization: "Bearer header.payload.signature",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

// ============ Tests ============

Deno.test({
  name: "A.1.9 — autoDenyAfterOneHour: stale PENDING request is auto-denied via atomic RPC path",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();

    const now = Date.now();
    const twoHoursAgo = new Date(now - 2 * 60 * 60 * 1000).toISOString();
    const staleRequest = {
      id: STALE_REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
      created_at: twoHoursAgo,
    };
    const freshRequest = {
      id: FRESH_REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 30,
      status: "PENDING",
      created_at: new Date(now - 5 * 60 * 1000).toISOString(),
    };

    const { fetchMock, calls, timeRequests } = buildFetchMock({
      staleRequest,
      freshRequest,
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(
        makeApproveRequest({
          request_id: FRESH_REQUEST_ID,
          minutes: 30,
          action: "APPROVE",
        })
      );

      assertEquals(response.status, 200);

      const rpcCall = calls.find((c) =>
        c.method === "POST" &&
        c.url.includes("/rest/v1/rpc/approve_request_atomic")
      );
      assertEquals(
        typeof rpcCall,
        "object",
        "approve-request must call the atomic RPC " +
          "(/rest/v1/rpc/approve_request_atomic) for the verdict. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );
      const rpcParams = rpcCall!.body as Record<string, unknown>;
      assertEquals(rpcParams.p_request_id, FRESH_REQUEST_ID);
      assertEquals(rpcParams.p_parent_id, PARENT_UUID);
      assertEquals(rpcParams.p_minutes, 30);
      const body = await response.json();
      assertEquals(body.success, true);
      assertEquals(body.decision, "APPROVED");
      assertEquals(body.grant_id, "grant-mock");
      assertEquals(body.minutes, 30);

      const autoDenyPatch = calls.find((c) =>
        c.method === "PATCH" &&
        c.url.includes("/rest/v1/time_requests") &&
        typeof c.body === "object" &&
        (c.body as Record<string, unknown>).response_text ===
          "Auto-denied: no parent response within 1h"
      );
      assertEquals(
        typeof autoDenyPatch,
        "object",
        "approve-request must issue a PATCH with status=DENIED + " +
          "response_text='Auto-denied: no parent response within 1h' for " +
          "any PENDING row older than 1h before processing the main request. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );

      const stale = timeRequests.get(STALE_REQUEST_ID);
      assertEquals(stale?.status, "DENIED");
      assertEquals(
        stale?.response_text,
        "Auto-denied: no parent response within 1h",
      );
      assertEquals(typeof stale?.denied_at, "string");
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "A.1.9b — autoDeny does NOT touch fresh PENDING requests (< 1h old)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();

    const now = Date.now();
    const freshRequest = {
      id: FRESH_REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
      created_at: new Date(now - 30 * 60 * 1000).toISOString(),
    };

    const { fetchMock, calls, timeRequests } = buildFetchMock({
      staleRequest: { ...freshRequest, id: "stale-id" },
      freshRequest,
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(
        makeApproveRequest({
          request_id: FRESH_REQUEST_ID,
          minutes: 30,
          action: "APPROVE",
        })
      );

      assertEquals(response.status, 200);

      const fresh = timeRequests.get(FRESH_REQUEST_ID);
      assertEquals(
        fresh?.response_text,
        undefined,
        "Auto-DENY sweep must NOT write response_text to fresh (< 1h) " +
          "PENDING rows. The PATCH call is a no-op when no rows match " +
          "the URL filter, so the row keeps its pre-call state. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "auth — malformed Authorization headers and JWT payloads return 401",
  fn: async () => {
    for (const authHeader of MALFORMED_AUTH_HEADERS) {
      const response = await handleRequest(new Request("https://example.test/functions/v1/approve-request", {
        method: "POST",
        headers: { Authorization: authHeader, "Content-Type": "application/json" },
        body: JSON.stringify({ request_id: FRESH_REQUEST_ID, minutes: 30 }),
      }));
      assertEquals(response.status, 401, authHeader);
      const body = await response.json();
      // Under the BLK-01 helper:
      //   - "Basic credentials"        → 401 "Token requerido"
      //   - "Bearer <anything>"         → 401 "Token inválido o expirado"
      // Both short-circuit before any privileged DB/RPC call.
      const allowedErrors = ["Token requerido", "Token inválido o expirado"];
      assertEquals(
        allowedErrors.includes(body.error),
        true,
        `unexpected error for ${authHeader}: ${body.error}`,
      );
    }
  },
});

/**
 * BLK-01 negative path — verify that the handler short-circuits to
 * 401 when Supabase Auth reports a forged/expired token, AND that
 * the privileged approve_request_atomic RPC is never reached. The
 * fetch mock rejects /auth/v1/user (no successful auth response) and
 * the test asserts the RPC was never called.
 */
Deno.test({
  name:
    "BLK-01 — forged/expired JWT is rejected; approve_request_atomic RPC is NEVER reached",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();

    // Forged payload: {sub: PARENT_UUID} — would have been accepted by
    // the OLD handler that just decoded the JWT.
    const forgedJwt = `header.${btoa(JSON.stringify({ sub: PARENT_UUID }))}.signature`;

    const now = Date.now();
    const freshRequest = {
      id: FRESH_REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
      created_at: new Date(now - 5 * 60 * 1000).toISOString(),
    };

    const { fetchMock, calls } = buildFetchMock({
      staleRequest: { ...freshRequest, id: "stale-id" },
      freshRequest,
      rejectAuth: true,
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(new Request(
        "https://example.test/functions/v1/approve-request",
        {
          method: "POST",
          headers: { Authorization: `Bearer ${forgedJwt}`, "Content-Type": "application/json" },
          body: JSON.stringify({ request_id: FRESH_REQUEST_ID, minutes: 30, action: "APPROVE" }),
        },
      ));

      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token inválido o expirado");

      // The service-role / privileged RPC must NOT have been called.
      const rpcCalls = calls.filter((c) =>
        c.url.includes("/rest/v1/rpc/approve_request_atomic")
      );
      assertEquals(
        rpcCalls.length,
        0,
        "forged/expired JWT must not reach approve_request_atomic. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );
      // The time_requests ownership precheck (a service-role read)
      // must also NOT have been reached.
      const timeRequestReads = calls.filter((c) =>
        c.url.includes("/rest/v1/time_requests") && c.method === "GET"
      );
      assertEquals(
        timeRequestReads.length,
        0,
        "forged/expired JWT must not reach the time_requests read. " +
          `Calls: ${calls.map((c) => `${c.method} ${c.url}`).join(", ")}`,
      );
    } finally {
      restoreFetch();
    }
  },
});

/**
 * BLK-01 positive path — a verified parent reaches the RPC and the
 * `p_parent_id` is the verified `user.id`, NOT the decoded `sub`
 * claim. The forged-token test above proves the old code accepted
 * any `sub`; this one pins the new contract.
 */
Deno.test({
  name:
    "BLK-01 — verified parent: RPC p_parent_id comes from verified user.id (not JWT sub)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();

    // The JWT carries a `sub` claim for a DIFFERENT user. The mock
    // returns the real parent (PARENT_UUID) from /auth/v1/user, so
    // the RPC must use the verified identity, not the JWT claim.
    const forgedSubJwt =
      `header.${btoa(JSON.stringify({ sub: "00000000-0000-0000-0000-000000000000" }))}.signature`;

    const now = Date.now();
    const freshRequest = {
      id: FRESH_REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
      created_at: new Date(now - 5 * 60 * 1000).toISOString(),
    };

    const { fetchMock, calls } = buildFetchMock({
      staleRequest: { ...freshRequest, id: "stale-id" },
      freshRequest,
    });
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(new Request(
        "https://example.test/functions/v1/approve-request",
        {
          method: "POST",
          headers: { Authorization: `Bearer ${forgedSubJwt}`, "Content-Type": "application/json" },
          body: JSON.stringify({ request_id: FRESH_REQUEST_ID, minutes: 30, action: "APPROVE" }),
        },
      ));

      assertEquals(response.status, 200);
      const rpcCall = calls.find((c) =>
        c.url.includes("/rest/v1/rpc/approve_request_atomic") &&
        c.method === "POST"
      );
      assertEquals(typeof rpcCall, "object");
      const params = rpcCall!.body as Record<string, unknown>;
      // The verified user.id (PARENT_UUID) is the one that reaches
      // the RPC, NOT the JWT `sub` ("00000000-...").
      assertEquals(
        params.p_parent_id,
        PARENT_UUID,
        "p_parent_id must come from the verified user, not the JWT sub",
      );
    } finally {
      restoreFetch();
    }
  },
});
