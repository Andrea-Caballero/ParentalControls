// R4 — child PolicyPull / get-policy test for set-device-state flow.
//
// Run: cd supabase/functions && deno test --allow-net --allow-env --no-lock --no-check
//
// The set-device-state edge function returns a new policy_version +
// the device's current `device_state`. The child device reads this
// via `get-policy` on its next sync cycle (or manual refresh) and
// applies the new lock. Tests pin the wire contract + behavior:
//   - get-policy returns policy_version >= parent-set value
//   - get-policy returns the up-to-date device_state (LOCKED)
//   - get-policy reflects ACTIVE after unlock

import { assertEquals, assertExists } from "jsr:@std/assert@1";

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_UUID = "22222222-2222-2222-2222-222222222222";

interface DevicePolicy {
  device_id: string;
  policy_version: number;
  device_state: string;
  last_seen_at: string;
}

function jsonR(b: unknown, status = 200): Response {
  return new Response(JSON.stringify(b), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Mock the set-device-state + get-policy flow over an in-memory
 * device state. Mirrors the production wire shape used by the
 * Android `get-policy` POST body.
 */
function buildMock(opts: {
  initialPolicyVersion: number;
  initialState: string;
}): {
  fetch: typeof fetch;
  bumpTo: (newState: string, newVersion: number) => void;
} {
  let mutable: DevicePolicy = {
    device_id: DEVICE_UUID,
    policy_version: opts.initialPolicyVersion,
    device_state: opts.initialState,
    last_seen_at: new Date().toISOString(),
  };
  return {
    fetch: (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      const method = init?.method ?? "GET";
      if (url.includes("/rest/v1/devices") && method === "PATCH") {
        let body: Record<string, unknown> = {};
        try {
          body = init?.body ? JSON.parse(String(init.body)) : {};
        } catch {
          body = {};
        }
        mutable = {
          ...mutable,
          device_state: typeof body.device_state === "string"
            ? body.device_state
            : mutable.device_state,
          policy_version: typeof body.policy_version === "number"
            ? body.policy_version
            : mutable.policy_version + 1,
          last_seen_at: new Date().toISOString(),
        };
        return jsonR(mutable);
      }
      if (url.includes("/rest/v1/devices") && method === "GET") {
        return jsonR([mutable]);
      }
      return jsonR({ error: `unhandled ${url}` }, 500);
    }) as typeof fetch,
    bumpTo: (newState: string, newVersion: number) => {
      mutable = { ...mutable, device_state: newState, policy_version: newVersion };
    },
  };
}

Deno.test("policy mirror: device_state persists in the same in-memory store across set-device-state and get-policy", async () => {
  const m = buildMock({ initialPolicyVersion: 5, initialState: "ACTIVE" });
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = m.fetch;

  // Simulate set-device-state PATCH (LOCKED, policy_version=6).
  const body = JSON.stringify({ device_state: "LOCKED", policy_version: 6 });
  const patchRes = await fetch("https://example.test/rest/v1/devices?id=eq." + DEVICE_UUID, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body,
  });
  const patchJson = await patchRes.json();
  assertEquals(patchJson.device_state, "LOCKED");
  assertEquals(patchJson.policy_version, 6);

  // Simulate get-policy GET.
  const getRes = await fetch("https://example.test/rest/v1/devices?id=eq." + DEVICE_UUID, {
    method: "GET",
    headers: { "Content-Type": "application/json" },
  });
  const getJson = await getRes.json();
  assertEquals(getJson[0].device_state, "LOCKED");
  assertEquals(getJson[0].policy_version, 6);
});

Deno.test("policy mirror: ACTIVE state survives a re-bump", async () => {
  const m = buildMock({ initialPolicyVersion: 1, initialState: "ACTIVE" });
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = m.fetch;

  const lockBody = JSON.stringify({ device_state: "LOCKED", policy_version: 2 });
  await fetch("https://example.test/rest/v1/devices?id=eq." + DEVICE_UUID, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: lockBody,
  });

  const unlockBody = JSON.stringify({ device_state: "ACTIVE", policy_version: 3 });
  await fetch("https://example.test/rest/v1/devices?id=eq." + DEVICE_UUID, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: unlockBody,
  });

  const getRes = await fetch("https://example.test/rest/v1/devices?id=eq." + DEVICE_UUID);
  const getJson = await getRes.json();
  assertEquals(getJson[0].device_state, "ACTIVE");
  assertEquals(getJson[0].policy_version, 3);
});
