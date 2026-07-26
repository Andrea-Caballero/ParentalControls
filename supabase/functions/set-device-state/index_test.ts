// WU-2 — set-device-state Edge Function tests.
// Run: cd supabase/functions/set-device-state && deno test --allow-net --allow-env --no-lock

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

Deno.env.set("SUPABASE_URL", "https://example.test");
Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-role-test-key");

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_UUID = "22222222-2222-2222-2222-222222222222";

function jsonResp(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

interface Device {
  id: string;
  parent_id: string;
  policy_version: number;
  device_state: string;
}

function buildFetch(opts: {
  devices?: Device[];
  authError?: boolean;
}): { fetch: typeof fetch } {
  const mutable: Device[] = (opts.devices ?? [{
    id: DEVICE_UUID,
    parent_id: PARENT_UUID,
    policy_version: 5,
    device_state: "ACTIVE",
  }]).map((d) => ({ ...d }));
  return {
    fetch: (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      const method = init?.method ?? "GET";
      if (url.includes("/auth/v1/user")) {
        return opts.authError
          ? jsonResp({ error: "invalid_grant" }, 400)
          : jsonResp({ id: PARENT_UUID, email: "p@local.test" });
      }
      if (url.includes("/rest/v1/devices") && method === "GET") {
        return jsonResp(mutable);
      }
      if (url.includes("/rest/v1/devices") && method === "PATCH") {
        const body = init?.body ? JSON.parse(String(init.body)) : {};
        const cur = mutable[0];
        const next = {
          id: cur.id,
          parent_id: cur.parent_id,
          device_state: typeof body.device_state === "string"
            ? body.device_state
            : cur.device_state,
          policy_version: typeof body.policy_version === "number"
            ? body.policy_version
            : cur.policy_version + 1,
          updated_at: new Date().toISOString(),
        };
        mutable[0] = next as Device;
        return jsonResp(next);
      }
      return jsonResp({ error: `unhandled ${url}` }, 500);
    }) as typeof fetch,
  };
}

function req(
  body: object,
  headers: Record<string, string> = { Authorization: "Bearer test-jwt" },
): Request {
  return new Request(
    "https://example.test/functions/v1/set-device-state",
    { method: "POST", headers, body: JSON.stringify(body) },
  );
}

Deno.test("lock happy path: 200 + LOCKED + policy_version incremented by 1", async () => {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = buildFetch({}).fetch;
  const res = await handleRequest(req({ device_id: DEVICE_UUID, state: "LOCKED" }));
  assertEquals(200, res.status);
  const body = await res.json();
  assertEquals(true, body.success);
  assertEquals("LOCKED", body.state);
  assertEquals(6, body.policy_version);
});

Deno.test("unlock happy path: 200 + ACTIVE + policy_version incremented by 1", async () => {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = buildFetch({
    devices: [{
      id: DEVICE_UUID, parent_id: PARENT_UUID,
      policy_version: 8, device_state: "LOCKED",
    }],
  }).fetch;
  const body = await handleRequest(
    req({ device_id: DEVICE_UUID, state: "ACTIVE" }),
  ).then((r) => r.json());
  assertEquals("ACTIVE", body.state);
  assertEquals(9, body.policy_version);
});

Deno.test("missing Authorization → 401 Token requerido", async () => {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = () => Promise.resolve(new Response(""));
  const res = await handleRequest(
    new Request("https://example.test/functions/v1/set-device-state", {
      method: "POST", body: JSON.stringify({}),
    }),
  );
  assertEquals(401, res.status);
  assertStringIncludes((await res.json()).error, "Token requerido");
});

Deno.test("non-owner parent → 403 (ownership RLS guard)", async () => {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = buildFetch({
    devices: [{
      id: DEVICE_UUID, parent_id: "OTHER-PARENT",
      policy_version: 1, device_state: "ACTIVE",
    }],
  }).fetch;
  const res = await handleRequest(
    req({ device_id: DEVICE_UUID, state: "LOCKED" }),
  );
  assertEquals(403, res.status);
});

/**
 * P2 — unknown device_id returns 404 with no update side effects.
 * Production RLS rejects unknown device_ids; the mock must mirror
 * that contract (no auto-create row, no policy_version bump).
 */
Deno.test("P2 unknown device_id returns 404 with no update side effects", async () => {
  // Empty devices table — no row matches the requested device_id.
  (globalThis as any).fetch = buildFetch({ devices: [] }).fetch;
  const res = await handleRequest(
    req({ device_id: "99999999-9999-9999-9999-999999999999", state: "LOCKED" }),
  );
  assertEquals(404, res.status);
  const body = await res.json();
  assertStringIncludes(body.error, "no encontrado");
});
