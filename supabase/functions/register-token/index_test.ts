// Regression tests for register-token's safe JWT header parser.
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

Deno.test("parseDeviceId: missing Authorization header → 401", () => {
  const result = parseDeviceId(null);
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: malformed token segment → 401 without throwing", () => {
  const result = parseDeviceId("Bearer hdr.!!!.sig");
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: bearer-less header rejected", () => {
  const jwt = makeJwt({ device_id: "d-1" });
  const result = parseDeviceId(jwt);
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: missing device_id claim → 403", () => {
  const jwt = makeJwt({ sub: "u" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
  if (result.status !== 200) {
    assertStringIncludes(result.error, "device_id");
  }
});

Deno.test("parseDeviceId: valid bearer with device_id → 200", () => {
  const jwt = makeJwt({ device_id: "d-1" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 200);
  if (result.status === 200) {
    assertEquals(result.deviceId, "d-1");
  }
});
