// Focused tests for the shared JWT helper.
//
// The previous implementation copy-pasted the same parser into six
// edge functions. This suite pins the contract on the new single
// import target so the next time someone "tweaks" one site, they
// are forced to update a single, centrally-tested source instead
// of fighting six divergent copies.
//
// Coverage focuses on:
//   1. the import path itself (./jwt.ts) so any accidental rename
//      of the helper is caught by the test suite, not by a runtime
//      regression in a single edge function.
//   2. the 401/403 contract that the consumer edge functions rely
//      on to translate the helper's return into HTTP status codes.

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { decodeJwtPayload, extractBearerToken, parseDeviceId } from "./jwt.ts";

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

Deno.test("extractBearerToken: returns the credential from a valid header", () => {
  assertEquals(extractBearerToken("Bearer signed.jwt.token"), "signed.jwt.token");
  assertEquals(extractBearerToken("bearer signed.jwt.token"), "signed.jwt.token");
});

Deno.test("extractBearerToken: rejects missing and malformed headers", () => {
  assertEquals(extractBearerToken(null), null);
  assertEquals(extractBearerToken(""), null);
  assertEquals(extractBearerToken("Basic signed.jwt.token"), null);
  assertEquals(extractBearerToken("Bearer"), null);
  assertEquals(extractBearerToken("Bearer token with-spaces"), null);
});

Deno.test("decodeJwtPayload: returns null for non-Bearer header", () => {
  const result = decodeJwtPayload("Basic abc");
  assertEquals(result, null);
});

Deno.test("decodeJwtPayload: returns null for two-segment token", () => {
  const header = b64url(JSON.stringify({ alg: "HS256" }));
  const result = decodeJwtPayload(`Bearer ${header}.sig`);
  assertEquals(result, null);
});

Deno.test("decodeJwtPayload: returns the parsed object for a valid JWT", () => {
  const jwt = makeJwt({ device_id: "device-1", sub: "parent-1" });
  const result = decodeJwtPayload(`Bearer ${jwt}`);
  assertEquals(result, { device_id: "device-1", sub: "parent-1" });
});

Deno.test("decodeJwtPayload: returns null for non-JSON payload without throwing", () => {
  const result = decodeJwtPayload("Bearer hdr.!!!not-base64!!!.sig");
  assertEquals(result, null);
});

Deno.test("parseDeviceId: null header → 401 'Token requerido'", () => {
  const result = parseDeviceId(null);
  assertEquals(result, { status: 401, error: "Token requerido" });
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

Deno.test("parseDeviceId: payload without device_id claim → 403 'device_id no encontrado en token'", () => {
  const jwt = makeJwt({ sub: "user-1", role: "authenticated" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result, {
    status: 403,
    error: "device_id no encontrado en token",
  });
});

Deno.test("parseDeviceId: payload with empty-string device_id → 403", () => {
  const jwt = makeJwt({ device_id: "" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
});

Deno.test("parseDeviceId: payload with non-string device_id → 403", () => {
  const jwt = makeJwt({ device_id: 42 });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result.status, 403);
});

Deno.test("parseDeviceId: valid Bearer with device_id claim → 200 + deviceId", () => {
  const jwt = makeJwt({ device_id: "device-abc", sub: "user-1" });
  const result = parseDeviceId(`Bearer ${jwt}`);
  assertEquals(result, { status: 200, deviceId: "device-abc" });
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
  assertEquals(result, { status: 200, deviceId: "device-xyz" });
});
