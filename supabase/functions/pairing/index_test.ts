// Deno tests for `pairing/index.ts` after the migration to the
// `redeem_pairing_code_atomic` RPC
// (supabase/migrations/013_pairing_redemption_atomic.sql).
//
// The production handler now commits child/device/app_metadata/policy
// writes AND the terminal CONSUMED transition in a single RPC call
// (POST /rest/v1/rpc/redeem_pairing_code_atomic). The pre-RPC fetch
// mock in this file simulated an in-REST UPDATE/POST split, which is
// no longer the production flow.
//
// Atomic semantics pinned here:
//
//   * First claim returns 200 with the success envelope.
//   * Sequential replay short-circuits at the read-only preflight as
//     ALREADY_USED (409) without re-entering the RPC.
//   * Two concurrent claims resolve to exactly one winner (200) and
//     one conflict (409) — exactly one FCM, one RPC commit.
//   * Expired / missing codes return 410 / 404 at the preflight and
//     never enter the RPC; no side effects.
//   * A forced downstream failure (RPC transport error) rolls the
//     transaction back: the pairing row stays ACTIVE, used_at stays
//     null, and a retry lands a clean 200.
//   * The commit boundary is exactly ONE redemption RPC POST per
//     attempt — no fallback PATCH against pairing_codes.
//   * Format validation rejects before any RPC traffic.
//   * Device identifier hashing is stable across calls.

import { assertEquals, assertExists, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest, hashDeviceIdentifier } from "./index.ts";

const PARENT = "11111111-1111-1111-1111-111111111111";
const DEVICE = "device-pairing-test";
const AGENT = "00000000-0000-0000-0000-000000000001";
// 8-char codes from `create-pairing-code`'s alphabet (A-H, J-N, P-Z, 2-9).
const VALID = "ABC23456";
const EXPIRED_CODE = "EXPRTD23";
const MISSING = "WKRXJM23";
const MALFORMED = "0BC23456";

function jsonR(b: unknown, status = 200): Response {
  return new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } });
}
function objR(b: unknown, status = 200): Response {
  return new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/vnd.pgrst.object+json" } });
}

interface Pair {
  id: string;
  code: string;
  status: "ACTIVE" | "CONSUMED" | "EXPIRED" | "REVOKED";
  expires_at: string;
  parent_id: string;
  child_first_name: string;
  device_name: string | null;
  used_at: string | null;
  created_at: string;
}

interface Store {
  pairing: Record<string, Pair>;
  fcmSends: number;
  rpcCalls: number;
}

interface Call {
  url: string;
  method: string;
  body: unknown;
}

function mkPair(
  code: string,
  status: Pair["status"] = "ACTIVE",
  expMs = 15 * 60 * 1000,
): Pair {
  return {
    id: "pairing-aaaa",
    code,
    parent_id: PARENT,
    child_first_name: "Lucia",
    device_name: "Test Device",
    expires_at: new Date(Date.now() + expMs).toISOString(),
    status,
    used_at: null,
    created_at: new Date().toISOString(),
  };
}

function mkStore(rows: Pair[] = [mkPair(VALID)]): Store {
  const pairing: Record<string, Pair> = {};
  for (const p of rows) pairing[p.code] = { ...p };
  return { pairing, fcmSends: 0, rpcCalls: 0 };
}

/**
 * Fetch mock that mirrors the post-migration handler call shape:
 *   - GET  /rest/v1/pairing_codes      (preflight row read)
 *   - GET  /auth/v1/admin/users        (auth admin listUsers for the
 *                                     existing agent-user lookup; the
 *                                     handler pages through results with
 *                                     perPage=1000 and filters by the
 *                                     deterministic device email)
 *   - POST /auth/v1/admin/users        (agent-user creation)
 *   - DELETE /auth/v1/admin/users/{id} (orphan cleanup on RPC failure)
 *   - POST /rest/v1/rpc/redeem_pairing_code_atomic
 *                                     (atomic commit; FOR UPDATE lock
 *                                      simulated by sync mutation ordering)
 *   - GET  /rest/v1/device_push_tokens (FCM token lookup)
 *   - POST fcm.googleapis.com          (best-effort push)
 */
function installMock(
  store: Store,
  opts: { rpcFail?: boolean } = {},
): { calls: Call[] } {
  const calls: Call[] = [];
  const mock = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = init?.body
      ? (() => {
        try {
          return JSON.parse(String(init.body));
        } catch {
          return init.body;
        }
      })()
      : undefined;
    calls.push({ url, method, body });

    if (url.includes("fcm.googleapis.com")) {
      store.fcmSends += 1;
      return jsonR({ success: 1 });
    }

    // Agent-user lifecycle.
    if (url.includes("/auth/v1/admin/users") && method === "POST") {
      return jsonR({ user: { id: AGENT } });
    }
    // Auth admin listUsers — the handler pages through this endpoint to
    // resolve an existing agent by email. We return the full
    // `listUsers` response shape with nextPage: null so a single page
    // covers the lookup.
    if (
      url.includes("/auth/v1/admin/users?") &&
      method === "GET" &&
      !url.includes("/auth/v1/admin/users/")
    ) {
      return jsonR({
        users: [
          { id: AGENT, email: `device_${await hashDeviceIdentifier("D", "P7")}@parentalcontrol.local` },
        ],
        aud: "authenticated",
        nextPage: null,
        lastPage: 1,
        total: 1,
      });
    }
    if (url.includes("/auth/v1/admin/users/") && method === "DELETE") {
      // Orphan cleanup if the RPC fails after we created an agent user.
      return jsonR({}, 200);
    }

    // pairing_codes read-only preflight.
    if (url.includes("/rest/v1/pairing_codes") && method === "GET") {
      const m = url.match(/code=eq\.([^&]+)/);
      const row = m ? store.pairing[decodeURIComponent(m[1])] : null;
      return objR(row ? { ...row } : null);
    }

    // Atomic redemption RPC. The Edge Function calls it via
    // supabaseAdmin.rpc("redeem_pairing_code_atomic", {...}).
    if (
      url.includes("/rest/v1/rpc/redeem_pairing_code_atomic") &&
      method === "POST"
    ) {
      store.rpcCalls += 1;
      if (opts.rpcFail) {
        // Transport-level failure: the handler throws and returns 500.
        // The RPC's transaction rolled back, so the pairing row is
        // NOT mutated.
        return jsonR({ message: "forced RPC failure" }, 500);
      }
      const params = (body ?? {}) as Record<string, unknown>;
      const code = String(params.p_code ?? "");
      const row = store.pairing[code];
      if (!row) return jsonR({ error: "INVALID_CODE" });
      if (row.status !== "ACTIVE") return jsonR({ error: "ALREADY_USED" });
      if (row.expires_at <= new Date().toISOString()) {
        return jsonR({ error: "EXPIRED_CODE" });
      }
      // Simulate the SQL transaction: child/device/metadata/policy
      // writes succeeded, terminal CONSUMED write applied.
      row.status = "CONSUMED";
      row.used_at = new Date().toISOString();
      return jsonR({
        success: true,
        device_id: DEVICE,
        parent_id: row.parent_id,
        policy_version: 1,
      });
    }

    // FCM token lookup by parent_id (best-effort post-commit).
    if (url.includes("/rest/v1/device_push_tokens") && method === "GET") {
      return jsonR([{ token: "tok", parent_id: PARENT, is_active: true }]);
    }

    return jsonR({ error: "unhandled", url, method }, 500);
  };
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = mock;
  return { calls };
}
function restoreMock() {
  // deno-lint-ignore no-explicit-any
  delete (globalThis as any).fetch;
}

function setEnv() {
  Deno.env.set("SUPABASE_URL", "https://example.supabase.co");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "svc");
  Deno.env.set("FCM_SERVER_KEY", "fcm");
}

function req(code: string, overrides: Record<string, unknown> = {}): Request {
  return new Request("https://example.test/functions/v1/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      code,
      device_name: "D",
      device_model: "P7",
      os_version: "34",
      app_version: "1.0.0",
      age_band: "7-12",
      child_first_name: "Lucia",
      ...overrides,
    }),
  });
}

// ============ Tests ============

Deno.test({
  name: "claim — first call wins; sequential replay returns 409 ALREADY_USED; no extra side effects",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore();
    installMock(store);
    try {
      const r1 = await handleRequest(req(VALID));
      const b1 = await r1.json();
      assertEquals(r1.status, 200);
      assertEquals(b1.success, true);
      assertEquals(b1.device_id, DEVICE);
      assertEquals(b1.parent_id, PARENT);
      assertEquals(store.rpcCalls, 1, "exactly one redemption RPC on the first call");
      assertEquals(store.fcmSends, 1, "FCM only fires after the RPC commits");

      const r2 = await handleRequest(req(VALID));
      const b2 = await r2.json();
      assertEquals(r2.status, 409);
      assertEquals(b2.code, "ALREADY_USED");
      assertEquals(store.rpcCalls, 1, "replay short-circuits at the preflight, no second RPC");
      assertEquals(store.fcmSends, 1, "no second FCM on replay");
    } finally {
      restoreMock();
    }
  },
});

Deno.test({
  name: "claim — two CONCURRENT claims resolve to exactly one winner + one conflict",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore();
    installMock(store);
    try {
      const [a, b] = await Promise.all([
        handleRequest(req(VALID)),
        handleRequest(req(VALID)),
      ]);
      const codes = [a.status, b.status].slice().sort();
      assertEquals(codes, [200, 409]);
      const winner = a.status === 200 ? a : b;
      const loser = a.status === 409 ? a : b;
      const lb = await loser.json();
      const wb = await winner.json();
      assertEquals(lb.code, "ALREADY_USED");
      assertEquals(wb.success, true);
      assertEquals(store.rpcCalls, 2, "both attempts hit the RPC; the row-locked loser sees CONSUMED");
      assertEquals(store.fcmSends, 1, "exactly one FCM (winner only)");
    } finally {
      restoreMock();
    }
  },
});

Deno.test({
  name: "claim — expired code returns 410 EXPIRED_CODE; row stays ACTIVE; no side effects",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore([mkPair(EXPIRED_CODE, "ACTIVE", -60_000)]);
    installMock(store);
    try {
      const r = await handleRequest(req(EXPIRED_CODE));
      assertEquals(r.status, 410);
      const b = await r.json();
      assertEquals(b.code, "EXPIRED_CODE");
      assertEquals(store.pairing[EXPIRED_CODE].status, "ACTIVE");
      assertEquals(store.fcmSends, 0);
      assertEquals(store.rpcCalls, 0, "no RPC for an expired code");
    } finally {
      restoreMock();
    }
  },
});

Deno.test({
  name: "claim — missing/invalid code returns 404 INVALID_CODE; no side effects",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore([]);
    installMock(store);
    try {
      const r = await handleRequest(req(MISSING));
      assertEquals(r.status, 404);
      const b = await r.json();
      assertEquals(b.code, "INVALID_CODE");
      assertEquals(store.fcmSends, 0);
      assertEquals(store.rpcCalls, 0);
    } finally {
      restoreMock();
    }
  },
});

// ATOMIC ROLLBACK contract (per windows-pairing/spec.md ADDED Requirement):
// a partial failure during redemption rolls the entire transaction
// back, including the terminal CONSUMED write. The code remains
// ACTIVE and the retry lands cleanly.
Deno.test({
  name: "claim — downstream failure rolls back atomic redemption; code stays ACTIVE; retry succeeds",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore();

    installMock(store, { rpcFail: true });
    try {
      const r = await handleRequest(req(VALID));
      assertEquals(r.status, 500);
      assertEquals(
        store.pairing[VALID].status,
        "ACTIVE",
        "atomic RPC rollback leaves the code ACTIVE",
      );
      assertEquals(
        store.pairing[VALID].used_at,
        null,
        "used_at is not set when the transaction rolls back",
      );
      assertEquals(store.rpcCalls, 1, "exactly one RPC attempt in the failing call");
      assertEquals(store.fcmSends, 0, "no FCM on the failing call");
    } finally {
      restoreMock();
    }

    installMock(store);
    try {
      const retry = await handleRequest(req(VALID));
      assertEquals(retry.status, 200);
      const b = await retry.json();
      assertEquals(b.success, true);
      assertEquals(
        store.pairing[VALID].status,
        "CONSUMED",
        "retry commits normally",
      );
      assertExists(store.pairing[VALID].used_at);
      assertEquals(store.fcmSends, 1, "FCM fires only on the successful retry");
    } finally {
      restoreMock();
    }
  },
});

// REPLACES the pre-RPC "query is PATCH with code=, status=ACTIVE"
// test. The handler now commits via the RPC and never PATCHes
// pairing_codes directly.
Deno.test({
  name: "claim — handler commits via POST /rest/v1/rpc/redeem_pairing_code_atomic; one RPC per attempt",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore();
    const { calls } = installMock(store);
    try {
      await handleRequest(req(VALID));
      const rpcCalls = calls.filter((c) =>
        c.method === "POST" &&
        c.url.includes("/rest/v1/rpc/redeem_pairing_code_atomic")
      );
      assertExists(rpcCalls[0], "the handler must call the redemption RPC");
      assertEquals(rpcCalls.length, 1, "exactly one redemption RPC per attempt");
      const body = rpcCalls[0].body as Record<string, unknown>;
      assertEquals(body.p_code, VALID, "RPC carries the pairing code");
      assertEquals(body.p_device_name, "D", "RPC carries the device name");
      assertEquals(body.p_device_model, "P7", "RPC carries the device model");
      assertEquals(body.p_app_version, "1.0.0", "RPC carries the app version");
      assertEquals(body.p_child_first_name, "Lucia", "RPC carries the child first name");
      assertEquals(body.p_age_band, "7-12", "RPC carries the age band");
      const patches = calls.filter((c) =>
        c.method === "PATCH" && c.url.includes("/rest/v1/pairing_codes")
      );
      assertEquals(
        patches.length,
        0,
        "no pre-RPC PATCH to pairing_codes (the RPC owns the commit boundary)",
      );
    } finally {
      restoreMock();
    }
  },
});

Deno.test({
  name: "validation — malformed code returns 400 INVALID_CODE_FORMAT with zero RPC/side effects",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const store = mkStore();
    installMock(store);
    try {
      const r = await handleRequest(req(MALFORMED));
      assertEquals(r.status, 400);
      const b = await r.json();
      assertEquals(b.code, "INVALID_CODE_FORMAT");
      assertEquals(b.error, "INVALID_CODE_FORMAT");
      assertEquals(store.rpcCalls, 0, "no redemption RPC when format is invalid");
      assertEquals(store.fcmSends, 0);
    } finally {
      restoreMock();
    }
  },
});

Deno.test({
  name: "auth user identifier — same device name and model produce a stable hash",
  fn: async () => {
    const first = await hashDeviceIdentifier("D", "P7");
    const second = await hashDeviceIdentifier("D", "P7");
    assertEquals(first, second);
  },
});
