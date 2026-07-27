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
//
// The auto-DENY 1h sweep and the auth-hardening 401 contract are
// pinned by the existing tests below. The RPC mock was added so the
// handler's `supabaseAdmin.rpc("approve_request_atomic", ...)` call
// resolves to the migration's success/error shape.

import { assertEquals } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const MALFORMED_AUTH_HEADERS = [
  "Basic credentials",
  "Bearer only-two-segments.parts",
  "Bearer header.%%%invalid%%%.signature",
  "Bearer header.eyJzdWIi.signature",
];

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_ID = "device-aaaa";
const STALE_REQUEST_ID = "req-stale-old";
const FRESH_REQUEST_ID = "req-fresh-new";

const PARENT_JWT = `header.${btoa(JSON.stringify({ sub: PARENT_UUID }))}.signature`;

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
 *  - time_requests SELECT (ownership precheck)
 *  - time_requests PATCH (auto-DENY sweep only — the RPC owns the verdict)
 *  - POST /rest/v1/rpc/approve_request_atomic (atomic verdict + grant)
 *  - device_push_tokens SELECT (FCM lookup; null token = no push)
 */
function buildFetchMock(opts: {
  staleRequest: Record<string, unknown>;
  freshRequest: Record<string, unknown>;
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

    // time_requests SELECT — ownership precheck
    if (url.includes("/rest/v1/time_requests") && method === "GET") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? timeRequests.get(id) : undefined;
      if (!row) return jsonResponse({ error: "not found" }, 404);
      return jsonResponse({ ...row, devices: { parent_id: PARENT_UUID } });
    }

    // time_requests PATCH — auto-DENY sweep only.
    if (url.includes("/rest/v1/time_requests") && method === "PATCH") {
      // The auto-DENY patch body has `status: "DENIED"` +
      // `response_text: "Auto-denied: no parent response within 1h"`.
      if (
        body &&
        typeof body === "object" &&
        (body as Record<string, unknown>).status === "DENIED" &&
        (body as Record<string, unknown>).response_text ===
          "Auto-denied: no parent response within 1h"
      ) {
        // Mirror the production WHERE clause so fresh rows are not
        // mutated by the sweep.
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
    // Returns the jsonb success envelope (or error shape) the Edge
    // Function expects from the SECURITY DEFINER function.
    if (
      url.includes("/rest/v1/rpc/approve_request_atomic") &&
      method === "POST"
    ) {
      const params = (body ?? {}) as Record<string, unknown>;
      // p_minutes IS NULL → DENY branch.
      if (params.p_minutes == null) {
        return jsonResponse({ success: true, decision: "DENIED" });
      }
      // p_minutes IS NOT NULL → APPROVE branch.
      return jsonResponse({
        success: true,
        decision: "APPROVED",
        grant_id: "grant-mock",
        minutes: Number(params.p_minutes),
        expires_at: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
        policy_version: 1,
      });
    }

    // device_push_tokens SELECT — no FCM in this test
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
      ...authHeader(PARENT_JWT),
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

    // 2-hour-old PENDING request on the same device as the fresh one.
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
      created_at: new Date(now - 5 * 60 * 1000).toISOString(), // 5 min ago
    };

    const { fetchMock, calls, timeRequests } = buildFetchMock({
      staleRequest,
      freshRequest,
    });
    installFetchMock(fetchMock);

    try {
      // Trigger an APPROVE on the FRESH request. The handler must:
      //  1. Run the auto-DENY sweep BEFORE the RPC,
      //  2. Then POST the atomic RPC (`approve_request_atomic`)
      //     which returns the APPROVED envelope.
      const response = await handleRequest(
        makeApproveRequest({
          request_id: FRESH_REQUEST_ID,
          minutes: 30,
          action: "APPROVE",
        })
      );

      assertEquals(response.status, 200);

      // The RPC was called with the expected params and returned the
      // migration-contract shape, which the Edge Function translates
      // into the response envelope.
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

      // The auto-DENY PATCH fires before the RPC and updates the
      // stale row only (the WHERE clause gates it).
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

      // The stale row's status is now DENIED in the in-memory table.
      const stale = timeRequests.get(STALE_REQUEST_ID);
      assertEquals(
        stale?.status,
        "DENIED",
        "Stale PENDING row must be auto-updated to DENIED",
      );
      assertEquals(
        stale?.response_text,
        "Auto-denied: no parent response within 1h",
        "Auto-DENY must set response_text to the spec-pinned message",
      );
      assertEquals(
        typeof stale?.denied_at,
        "string",
        "Auto-DENY must set denied_at to a timestamp",
      );
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

    // Only a fresh 30-minute-old PENDING request — no stale row.
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
      staleRequest: { ...freshRequest, id: "stale-id" }, // not present in table
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

      // Assert the FRESH row's response_text is NOT the auto-deny
      // message (which would indicate the auto-deny sweep wrongly
      // touched it). The status can legitimately be "APPROVED"
      // because the atomic RPC ran and flipped it.
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
      assertEquals(await response.json(), { error: "Usuario no autenticado" });
    }
  },
});
