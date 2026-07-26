// Deno tests for `pairing/index.ts` atomic-claim security contract.
// The fetch mock simulates Postgres' row-lock semantics so that two
// concurrent UPDATE attempts against `pairing_codes` resolve to
// exactly one winner and one conflict.

import { assertEquals, assertExists, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const PARENT = "11111111-1111-1111-1111-111111111111";
const DEVICE = "device-pairing-test";
const AGENT = "00000000-0000-0000-0000-000000000001";
// 8-char codes from `create-pairing-code`'s alphabet (A-H, J-N, P-Z, 2-9).
const VALID = "ABC23456", EXPIRED_CODE = "EXPRTD23", MISSING = "WKRXJM23", MALFORMED = "0BC23456";

function jsonR(b: unknown, status = 200) { return new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/json" } }); }
function objR(b: unknown, status = 200) { return new Response(JSON.stringify(b), { status, headers: { "Content-Type": "application/vnd.pgrst.object+json" } }); }
function setEnv() { Deno.env.set("SUPABASE_URL", "https://example.supabase.co"); Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "svc"); Deno.env.set("FCM_SERVER_KEY", "fcm"); }

interface Pair { id: string; code: string; status: "ACTIVE" | "CONSUMED" | "EXPIRED" | "REVOKED"; expires_at: string; parent_id: string; child_first_name: string; device_name: string | null; used_at: string | null; created_at: string; }
interface Store { pairing: Record<string, Pair>; deviceCreates: number; childCreates: number; fcmSends: number; }

function mkPair(code: string, status: Pair["status"] = "ACTIVE", expMs = 15 * 60 * 1000): Pair {
  return { id: "pairing-aaaa", code, parent_id: PARENT, child_first_name: "Lucia", device_name: "Test Device", expires_at: new Date(Date.now() + expMs).toISOString(), status, used_at: null, created_at: new Date().toISOString() };
}
function mkStore(rows: Pair[] = [mkPair(VALID)]): Store {
  const pairing: Record<string, Pair> = {}; for (const p of rows) pairing[p.code] = { ...p };
  return { pairing, deviceCreates: 0, childCreates: 0, fcmSends: 0 };
}

/** Atomic-claim-simulating fetch mock. The first PATCH matching
 *  code=eq.X&status=eq.ACTIVE&expires_at=gt.<iso> flips the row to
 *  CONSUMED; any subsequent PATCH sees CONSUMED and returns null
 *  via `.maybeSingle()`'s Accept header. */
function installMock(store: Store, opts: { devicesFail?: boolean } = {}): { calls: Array<{ url: string; method: string; body: unknown }> } {
  const calls: Array<{ url: string; method: string; body: unknown }> = [];
  const mock = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = init?.body ? (() => { try { return JSON.parse(String(init.body)); } catch { return init.body; } })() : undefined;
    calls.push({ url, method, body });
    if (url.includes("fcm.googleapis.com")) { store.fcmSends += 1; return jsonR({ success: 1 }); }
    if (url.includes("/auth/v1/admin/users") && method === "POST") return jsonR({ user: { id: AGENT } });
    if (url.includes("/auth/v1/admin/users/") && method === "PUT") return jsonR({ id: AGENT });
    if (url.includes("/rest/v1/pairing_codes") && method === "PATCH") {
      const codeM = url.match(/code=eq\.([^&]+)/); if (!codeM) return objR(null);
      const code = decodeURIComponent(codeM[1]);
      const row = store.pairing[code];
      const gtIso = (() => { const m = url.match(/expires_at=gt\.([^&]+)/); return m ? decodeURIComponent(m[1]) : null; })();
      const fresh = (body && typeof body === "object") ? body as Record<string, unknown> : {};
      if (row && row.status === "ACTIVE" && gtIso && row.expires_at > gtIso) {
        row.status = (fresh.status as Pair["status"]) ?? "CONSUMED";
        row.used_at = (fresh.used_at as string) ?? new Date().toISOString();
        return objR({ ...row });
      }
      return objR(null);
    }
    if (url.includes("/rest/v1/pairing_codes") && method === "GET") {
      const m = url.match(/code=eq\.([^&]+)/);
      const row = m ? store.pairing[decodeURIComponent(m[1])] : null;
      return objR(row ? { ...row } : null);
    }
    if (url.includes("/rest/v1/children") && method === "POST") { const child = { id: "child-1", ...(body as Record<string, unknown>) }; store.childCreates += 1; return objR(child, 201); }
    if (url.includes("/rest/v1/children") && method === "GET") return objR(null);
    if (url.includes("/rest/v1/devices") && method === "POST") {
      if (opts.devicesFail) return jsonR({ message: "forced" }, 500);
      store.deviceCreates += 1; return objR({ id: DEVICE, ...(body as Record<string, unknown>) }, 201);
    }
    if (url.includes("/rest/v1/devices") && method === "GET") return objR({ id: DEVICE, policy_version: 1 });
    if (url.includes("/rest/v1/auth.users") && method === "GET") return jsonR([{ id: AGENT }]);
    if (url.includes("/rest/v1/schedules") && method === "POST") return objR({ id: "s" }, 201);
    if (url.includes("/rest/v1/app_policies") && method === "POST") return objR({ id: "a" }, 201);
    if (url.includes("/rest/v1/policy_templates") && method === "GET") return objR({ age_band: "7-12", config: { schedules: [], app_policies: [] } });
    if (url.includes("/rest/v1/device_push_tokens") && method === "GET") return jsonR([{ token: "tok", parent_id: PARENT, is_active: true }]);
    return jsonR({ error: "unhandled", url, method }, 500);
  };
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = mock;
  return { calls };
}
function restoreMock() { /* deno-lint-ignore no-explicit-any */ delete (globalThis as any).fetch; }

function req(code: string, overrides: Record<string, unknown> = {}): Request {
  return new Request("https://example.test/functions/v1/pairing", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      code, device_name: "D", device_model: "P7", os_version: "34",
      app_version: "1.0.0", age_band: "7-12", child_first_name: "Lucia", ...overrides,
    }),
  });
}

Deno.test({
  name: "claim — first call wins; sequential replay returns 409 ALREADY_USED with no extra side effects",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore(); installMock(store);
    try {
      const r1 = await handleRequest(req(VALID)); const b1 = await r1.json();
      assertEquals(r1.status, 200);
      assertEquals(b1.success, true);
      assertEquals(b1.device_id, DEVICE);
      assertEquals(b1.parent_id, PARENT);
      assertEquals(store.deviceCreates, 1);
      assertEquals(store.fcmSends, 1);
      assertEquals(store.childCreates, 1);
      assertEquals(store.pairing[VALID].status, "CONSUMED");
      assertExists(store.pairing[VALID].used_at);
      const baseDev = store.deviceCreates, baseFcm = store.fcmSends;
      const r2 = await handleRequest(req(VALID)); const b2 = await r2.json();
      assertEquals(r2.status, 409);
      assertEquals(b2.code, "ALREADY_USED");
      assertEquals(store.deviceCreates, baseDev, "no extra device");
      assertEquals(store.fcmSends, baseFcm, "no extra FCM");
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "claim — two CONCURRENT claims resolve to exactly one winner + one conflict",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore(); installMock(store);
    try {
      const [a, b] = await Promise.all([handleRequest(req(VALID)), handleRequest(req(VALID))]);
      assertEquals([a.status, b.status].sort(), [200, 409]);
      const w = a.status === 200 ? a : b, l = a.status === 409 ? a : b;
      const lb = await l.json(), wb = await w.json();
      assertEquals(lb.code, "ALREADY_USED");
      assertEquals(wb.success, true);
      assertEquals(store.deviceCreates, 1, "exactly one device created");
      assertEquals(store.fcmSends, 1, "exactly one FCM sent");
      assertEquals(store.childCreates, 1, "exactly one child upsert");
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "claim — expired code returns 410 EXPIRED_CODE; row stays ACTIVE; no side effects",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore([mkPair(EXPIRED_CODE, "ACTIVE", -60_000)]); installMock(store);
    try {
      const r = await handleRequest(req(EXPIRED_CODE));
      assertEquals(r.status, 410);
      const b = await r.json();
      assertEquals(b.code, "EXPIRED_CODE");
      assertEquals(store.pairing[EXPIRED_CODE].status, "ACTIVE");
      assertEquals(store.deviceCreates, 0);
      assertEquals(store.fcmSends, 0);
      assertEquals(store.childCreates, 0);
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "claim — missing/invalid code returns 404 INVALID_CODE; no side effects",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore([]); installMock(store);
    try {
      const r = await handleRequest(req(MISSING));
      assertEquals(r.status, 404);
      const b = await r.json();
      assertEquals(b.code, "INVALID_CODE");
      assertEquals(store.deviceCreates, 0);
      assertEquals(store.fcmSends, 0);
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "claim — downstream failure AFTER successful claim leaves code CONSUMED; retry conflicts",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore(); installMock(store, { devicesFail: true });
    try {
      const r = await handleRequest(req(VALID));
      assertEquals(r.status, 500);
      assertEquals(store.pairing[VALID].status, "CONSUMED");
      assertExists(store.pairing[VALID].used_at);
    } finally { restoreMock(); }
    installMock(store); // retry with the normal mock
    try {
      const retry = await handleRequest(req(VALID));
      assertEquals(retry.status, 409);
      assertEquals((await retry.json()).code, "ALREADY_USED");
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "claim — query is PATCH with code=, status=ACTIVE, expires_at=gt.; ONE PATCH only",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore(); const { calls } = installMock(store);
    try {
      await handleRequest(req(VALID));
      const claim = calls.find((c) => c.method === "PATCH" && c.url.includes("/rest/v1/pairing_codes"));
      assertExists(claim);
      assertStringIncludes(claim.url, `code=eq.${VALID}`);
      assertStringIncludes(claim.url, "status=eq.ACTIVE");
      assertStringIncludes(claim.url, "expires_at=gt.");
      const patches = calls.filter((c) => c.method === "PATCH" && c.url.includes("/rest/v1/pairing_codes"));
      assertEquals(patches.length, 1, "exactly one claim PATCH — no late UPDATE");
      const body = claim.body as Record<string, unknown>;
      assertEquals(body.status, "CONSUMED");
      assertExists(body.used_at);
    } finally { restoreMock(); }
  },
});

Deno.test({
  name: "validation — malformed code returns 400 INVALID_CODE_FORMAT with zero claim/side effects",
  sanitizeOps: false, sanitizeResources: false,
  fn: async () => {
    setEnv(); const store = mkStore(); const { calls } = installMock(store);
    try {
      const r = await handleRequest(req(MALFORMED));
      assertEquals(r.status, 400);
      const b = await r.json();
      assertEquals(b.code, "INVALID_CODE_FORMAT");
      assertEquals(b.error, "INVALID_CODE_FORMAT");
      const claimCalls = calls.filter((c) => c.url.includes("/rest/v1/pairing_codes") && c.method === "PATCH");
      assertEquals(claimCalls.length, 0, "no atomic claim when format is invalid");
      assertEquals(store.deviceCreates, 0);
      assertEquals(store.fcmSends, 0);
    } finally { restoreMock(); }
  },
});
