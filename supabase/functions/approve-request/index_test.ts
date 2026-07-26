// RED test for the 1h auto-DENY contract in `approve-request/index.ts`
// (Slice A of `openspec/changes/feat-cross-device-pairing-and-approval`).
//
// Covers the ADDED Requirement from
// `openspec/changes/feat-cross-device-pairing-and-approval/specs/time-request-approval/spec.md`:
//
//   - **A.1.9** `autoDenyAfterOneHour` — a `time_request` with
//     `status = "PENDING"` and `created_at < NOW() - 1 hour` MUST be
//     auto-updated to `status = "DENIED"`, `denied_at = NOW()`,
//     `response_text = "Auto-denied: no parent response within 1h"`
//     when the parent hits `approve-request` (even for a different
//     `request_id`). No `grants` row is created.
//
// Server-side enforcement (per `tasks.md` Slice A A.2.7): at the top of
// `handleRequest`, before parsing the body, run a single PATCH-style
// UPDATE on `time_requests` with the predicate
// `status = "PENDING" AND created_at < NOW() - INTERVAL '1 hour'` on
// the same `device_id` the request is for. The patch is idempotent
// (`WHERE status = "PENDING"` is the gate).
//
// RED today (master = 3f3a81d): `approve-request` does not run any
// auto-DENY sweep. A 2-hour-old PENDING request stays PENDING after
// `approve-request` fires.
//
// Run with: cd supabase/functions/approve-request && deno test --allow-all

import { assertEquals } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

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
 * Build a fetch mock that:
 *  - Auto-denies on a PATCH `time_requests?or=(...)` (or any PATCH with
 *    `status=eq.PENDING` in the URL filter). Records the PATCH body so
 *    the test can verify the WHERE clause + response_text.
 *  - Handles the SELECT/UPDATE paths the production handler issues.
 *
 * The PATCH body for the auto-deny looks like:
 *   { status: "DENIED", denied_at: <iso>, response_text: "Auto-denied..." }
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

    // auth.getUser
    if (url.includes("/auth/v1/user")) {
      return jsonResponse({ id: PARENT_UUID, email: "parent@local.test" });
    }

    // time_requests SELECT — `/rest/v1/time_requests?id=eq.X` for either
    // the stale or fresh request
    if (url.includes("/rest/v1/time_requests") && method === "GET") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? timeRequests.get(id) : undefined;
      if (!row) return jsonResponse({ error: "not found" }, 404);
      return jsonResponse({ ...row, devices: { parent_id: PARENT_UUID } });
    }

    // time_requests PATCH — covers BOTH the auto-DENY sweep AND the
    // handler's own APPROVE/DENY update
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
        // Apply ONLY to rows whose created_at < oneHourAgo AND on
        // DEVICE_ID AND status='PENDING'. The production Supabase
        // server enforces the URL filter (`created_at=lt.<iso>`); the
        // mock mirrors that so A.1.9b's "fresh rows untouched" assertion
        // is real (not vacuous).
        const urlLtMatch = url.match(/created_at=lt\.([^&]+)/);
        const ltIso = urlLtMatch ? decodeURIComponent(urlLtMatch[1]) : null;
        for (const [id, row] of timeRequests.entries()) {
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
      // Normal handler update — apply only to the id in the URL
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? timeRequests.get(id) : undefined;
      if (row && body && typeof body === "object") {
        Object.assign(row, body as Record<string, unknown>);
      }
      return jsonResponse([]);
    }

    // grants INSERT — the AUTO-DENY sweep does NOT create grants
    // (per spec "Auto-denial does not create a grants row"), but the
    // main APPROVE flow does. We accept the INSERT and return 200.
    // The A.1.9 assertion does not check the grants table, but the
    // handler must complete APPROVE for the FRESH row.
    if (url.includes("/rest/v1/grants") && method === "POST") {
      return jsonResponse({ id: "grant-stub", ...(body as Record<string, unknown>) });
    }

    // device_push_tokens SELECT — returns empty (no FCM in this test)
    if (url.includes("/rest/v1/device_push_tokens")) {
      return jsonResponse(null);
    }

    // devices SELECT policy_version
    if (url.includes("/rest/v1/devices") && method === "GET") {
      return jsonResponse({ id: DEVICE_ID, policy_version: 1 });
    }

    // FCM POST — never hit (no push token)
    if (url.includes("fcm.googleapis.com")) {
      return jsonResponse({ error: "FCM should not be called" }, 500);
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
  name: "A.1.9 — autoDenyAfterOneHour: stale PENDING request is auto-denied",
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
      //  1. Run the auto-DENY sweep BEFORE the main handler logic,
      //     updating the STALE row to status=DENIED.
      //  2. Then process the APPROVE on the FRESH row normally.
      const response = await handleRequest(
        makeApproveRequest({
          request_id: FRESH_REQUEST_ID,
          minutes: 30,
          action: "APPROVE",
        })
      );

      assertEquals(response.status, 200);

      // The auto-DENY PATCH must have fired before the main handler's
      // own PATCH on the fresh row.
      const autoDenyPatch = calls.find((c) =>
        c.method === "PATCH" &&
        typeof c.body === "object" &&
        (c.body as Record<string, unknown>).response_text ===
          "Auto-denied: no parent response within 1h"
      );
      // The auto-DENY PATCH body MUST carry status=DENIED + denied_at.
      assertEquals(
        typeof autoDenyPatch,
        "object",
        "approve-request must issue a PATCH with status=DENIED + " +
          "response_text='Auto-denied: no parent response within 1h' for " +
          "any PENDING row older than 1h before processing the main request. " +
          "Today the handler does not run any auto-DENY sweep. " +
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

      // The auto-DENY PATCH always fires (it's an idempotent sweep
      // with the `WHERE status='PENDING' AND created_at < now-1h`
      // predicate as the gate), but it must NOT update the fresh
      // row because that row's created_at is within the last hour.
      //
      // Assert the FRESH row's response_text is NOT the auto-deny
      // message (which would indicate the auto-deny sweep wrongly
      // touched it). The status can legitimately be "APPROVED"
      // because the main APPROVE flow ran on this row.
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