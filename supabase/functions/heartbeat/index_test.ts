// Regression tests for heartbeat's safe JWT header parser.
import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { parseDeviceId } from "./index.ts";

function b64url(input: string): string {
  return btoa(input).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = b64url(JSON.stringify(payload));
  return `${header}.${body}.sig`;
}

Deno.test("parseDeviceId: missing Authorization header → 401 'Token requerido'", () => {
  const result = parseDeviceId(null);
  assertEquals(result.status, 401);
  if (result.status !== 200) {
    assertStringIncludes(result.error, "Token requerido");
  }
});

Deno.test("parseDeviceId: header that is not 'Bearer x.y.z' → 401", () => {
  const result = parseDeviceId("Basic abc");
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: three-segment token with garbage payload → 401, no throw", () => {
  const result = parseDeviceId("Bearer aaa.!!!notbase64!!!.bbb");
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: payload is JSON but no device_id → 403", () => {
  const jwt = makeJwt({ sub: "u", role: "authenticated" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
});

Deno.test("parseDeviceId: payload with empty device_id string → 403", () => {
  const jwt = makeJwt({ device_id: "" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
});

Deno.test("parseDeviceId: valid bearer with device_id → 200", () => {
  const jwt = makeJwt({ device_id: "device-42" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 200);
  if (result.status === 200) {
    assertEquals(result.deviceId, "device-42");
  }
});
