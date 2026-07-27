// Regression tests for get-policy's safe JWT header parser.
// Mirrors the pattern used in supabase/functions/approve-request/index_test.ts
// to keep the suite uniform across edge functions.
import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { parseDeviceId } from "./index.ts";

// Base64url helper (matches what Supabase Auth emits for JWT payloads).
function b64url(input: string): string {
  return btoa(input).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function makeJwt(payload: Record<string, unknown>): string {
  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = b64url(JSON.stringify(payload));
  // Signature is opaque for parsing — any non-empty string works.
  return `${header}.${body}.sig`;
}

Deno.test("parseDeviceId: missing Authorization header → 401 'Token requerido'", () => {
  const result = parseDeviceId(null);
  assertEquals(result.status, 401);
  if (result.status !== 200) {
    assertStringIncludes(result.error, "Token requerido");
  }
});

Deno.test("parseDeviceId: header without 'Bearer ' prefix → 401 'Token inválido'", () => {
  const jwt = makeJwt({ device_id: "d-1" });
  const result = parseDeviceId(jwt);
  assertEquals(result.status, 401);
  if (result.status !== 200) {
    assertStringIncludes(result.error, "inv");
  }
});

Deno.test("parseDeviceId: only two segments → 401 'Token inválido'", () => {
  const header = b64url(JSON.stringify({ alg: "HS256" }));
  const result = parseDeviceId(`Bearer ${header}.sig`);
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: garbage payload segment → 401, no throw", () => {
  const result = parseDeviceId("Bearer hdr.!!!not-base64!!!.sig");
  assertEquals(result.status, 401);
});

Deno.test("parseDeviceId: payload without device_id claim → 403", () => {
  const jwt = makeJwt({ sub: "user-1", role: "authenticated" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
  if (result.status !== 200) {
    assertStringIncludes(result.error, "device_id");
  }
});

Deno.test("parseDeviceId: payload with non-string device_id → 403", () => {
  const jwt = makeJwt({ device_id: 42 });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
});

Deno.test("parseDeviceId: valid Bearer with device_id claim → 200 + deviceId", () => {
  const jwt = makeJwt({ device_id: "device-abc", sub: "user-1" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 200);
  if (result.status === 200) {
    assertEquals(result.deviceId, "device-abc");
  }
});

Deno.test("parseDeviceId: base64url payload (missing padding) is accepted", () => {
  // Build a payload whose base64 form has padding stripped — Supabase
  // emits JWTs in base64url. We must not require manual padding.
  const jwt = makeJwt({ device_id: "device-xyz" });
  // Sanity: ensure the body is unpadded (matches real Supabase format).
  const body = jwt.split(".")[1];
  if (body.includes("=")) {
    throw new Error("test setup expected unpadded base64url");
  }
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 200);
  if (result.status === 200) {
    assertEquals(result.deviceId, "device-xyz");
  }
});
