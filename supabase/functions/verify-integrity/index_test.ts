// BLK-01 tests for `verify-integrity/index.ts`.
//
// Pinned trust boundaries:
//   1. Missing/malformed Authorization → 401.
//   2. Forged/expired JWT → 401 (no service-role call).
//   3. Valid Bearer but no app_metadata.device_id → 403.
//   4. Valid Bearer + verified device → 200, and the outbox
//      integrity_verification entry uses the server-side deviceId,
//      not the JWT claim.

import { assertEquals } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

const DEVICE_UUID_FROM_AUTH = "server-device-id";
const DEVICE_UUID_FROM_JWT = "attacker-device-id";
const PARENT_UUID = "11111111-1111-1111-1111-111111111111";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

interface BuildOptions {
  authOutcome: "ok-with-device" | "ok-without-device" | "reject";
}

function buildFetchMock(opts: BuildOptions) {
  const calls: Array<{ url: string; method: string; body?: unknown; auth: string | null }> = [];
  const fetchMock = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    let body: unknown = undefined;
    if (init?.body) {
      try {
        body = typeof init.body === "string"
          ? JSON.parse(init.body)
          : init.body;
      } catch {
        body = init.body;
      }
    }
    const auth = extractAuth(init?.headers);
    calls.push({ url, method, body, auth });

    if (url.includes("/auth/v1/user")) {
      if (opts.authOutcome === "reject") {
        // Mock the Supabase Auth "401 invalid_grant" envelope. The
        // helper must surface this as 401 "Token inválido o expirado"
        // without ever constructing the service-role client.
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      if (opts.authOutcome === "ok-without-device") {
        return jsonResponse({ id: PARENT_UUID, email: "p@local.test" });
      }
      return jsonResponse({
        id: PARENT_UUID,
        email: "device@local.test",
        app_metadata: { device_id: DEVICE_UUID_FROM_AUTH },
      });
    }
    if (url.includes("playintegritymanager.googleapis.com")) {
      return jsonResponse({ tokenPayloadExternal: {} });
    }
    if (url.includes("/rest/v1/outbox")) {
      return jsonResponse([{ id: "outbox-mock" }]);
    }
    return jsonResponse({ error: `unhandled ${url}` }, 500);
  };
  return { fetchMock, calls };
}

function extractAuth(headers: HeadersInit | undefined): string | null {
  if (!headers) return null;
  if (typeof (headers as Headers).get === "function") {
    return (headers as Headers).get("Authorization");
  }
  if (Array.isArray(headers)) {
    const pair = headers.find(([k]) => k.toLowerCase() === "authorization");
    return pair ? pair[1] : null;
  }
  // deno-lint-ignore no-explicit-any
  return (headers as Record<string, any>)["Authorization"] ?? null;
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
  // No PLAY_PACKAGE_NAME / GOOGLE_SERVICE_ACCOUNT → handler uses
  // the mock verdict from verifyWithGoogle (is_valid: true).
}

function makeIntegrityRequest(auth: string): Request {
  return new Request("https://example.test/functions/v1/verify-integrity", {
    method: "POST",
    headers: { Authorization: auth, "Content-Type": "application/json" },
    body: JSON.stringify({ integrity_token: "test.token.value" }),
  });
}

Deno.test({
  name: "BLK-01 — forged JWT returns 401 and never reaches service-role paths",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({ authOutcome: "reject" });
    installFetchMock(fetchMock);
    try {
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(makeIntegrityRequest(`Bearer ${forged}`));
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token inválido o expirado");
      const privilegedCalls = calls.filter((c) => c.url.includes("/rest/v1/"));
      assertEquals(privilegedCalls.length, 0);

      // The forged Bearer token must reach /auth/v1/user — that's the
      // whole point of the helper. If the helper swallowed, truncated,
      // or swapped the token we couldn't trust the cryptographic
      // verification it claims to do.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${forged}`,
        "the helper must forward the caller's exact Bearer token to /auth/v1/user",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — verified parent without a paired device returns 403 'device_id no encontrado en token'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      authOutcome: "ok-without-device",
    });
    installFetchMock(fetchMock);
    try {
      const jwt = "header.payload.signature";
      const response = await handleRequest(
        makeIntegrityRequest(`Bearer ${jwt}`),
      );
      assertEquals(response.status, 403);
      const body = await response.json();
      assertEquals(body.error, "device_id no encontrado en token");
      const privilegedCalls = calls.filter((c) => c.url.includes("/rest/v1/"));
      assertEquals(privilegedCalls.length, 0);

      // Even on the 403 branch the Bearer token must reach the
      // Supabase verifier — the cryptographic step happens BEFORE the
      // requireDevice check.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${jwt}`,
        "Bearer token must be forwarded on the 403 branch too",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — verified device: outbox entry uses app_metadata.device_id, NOT the JWT claim",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const { fetchMock, calls } = buildFetchMock({
      authOutcome: "ok-with-device",
    });
    installFetchMock(fetchMock);
    try {
      const forged = `header.${btoa(JSON.stringify({ sub: PARENT_UUID, device_id: DEVICE_UUID_FROM_JWT }))}.signature`;
      const response = await handleRequest(makeIntegrityRequest(`Bearer ${forged}`));
      assertEquals(response.status, 200);
      const outbox = calls.find((c) =>
        c.url.includes("/rest/v1/outbox") && c.method === "POST"
      );
      assertEquals(typeof outbox, "object");
      const outboxBody = outbox!.body as Record<string, unknown>;
      assertEquals(
        outboxBody.device_id,
        DEVICE_UUID_FROM_AUTH,
        "outbox insert must use the verified deviceId",
      );
      assertEquals(
        outboxBody.device_id === DEVICE_UUID_FROM_JWT,
        false,
        "the JWT device_id claim must never reach the service-role outbox insert",
      );

      // And the Bearer token must be forwarded to /auth/v1/user on the
      // happy path too — without that, the cryptographic verification
      // step isn't reachable.
      const authCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof authCall, "object");
      assertEquals(
        authCall!.auth,
        `Bearer ${forged}`,
        "happy path must forward the Bearer token to /auth/v1/user",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "BLK-01 — missing Authorization returns 401 with no service-role call",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    let fetchCalled = false;
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      fetchCalled = true;
      return Promise.resolve(new Response(""));
    };
    try {
      const response = await handleRequest(
        new Request("https://example.test/functions/v1/verify-integrity", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ integrity_token: "x" }),
        }),
      );
      assertEquals(response.status, 401);
      const body = await response.json();
      assertEquals(body.error, "Token requerido");
      assertEquals(fetchCalled, false);
    } finally {
      restoreFetch();
    }
  },
});

/**
 * Pins BLK-01 #3 — `verify-integrity` must return a stable generic
 * `failure_reason` to the client, never the raw provider/parse
 * exception. Provider/parser error bodies are LOGGED server-side
 * (visible to operators) but the client only sees a stable code so
 * no internal exception text leaks into the verdict envelope.
 *
 * The verdict itself (device_integrity / app_integrity /
 * account_details labels) is NOT sensitive — those are non-secret
 * Google verdict strings that the UI may want to render. They stay
 * on the `details` object unchanged.
 */
Deno.test({
  name: "BLK-01 — Play Integrity provider error returns 'INTEGRITY_PROVIDER_ERROR', never the raw provider envelope",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    // Configure the production path so verifyWithGoogle actually
    // calls the Play Integrity API. The fetch mock returns 4xx for
    // the Play endpoint, simulating a provider-side rejection.
    Deno.env.set("PLAY_PACKAGE_NAME", "com.example.parentalcontrol");
    Deno.env.set(
      "GOOGLE_SERVICE_ACCOUNT",
      JSON.stringify({ client_email: "x@x", private_key: "not-a-pem" }),
    );
    try {
      // Build a combined fetch mock: buildFetchMock for /auth/v1/user
      // (auth outcome) and /rest/v1/outbox (no-op), plus a
      // Play Integrity endpoint that returns a 4xx error envelope.
      const { fetchMock: authMock } = buildFetchMock({ authOutcome: "ok-with-device" });
      const combinedFetch: typeof fetch = async (
        input: RequestInfo | URL,
        init?: RequestInit,
      ): Promise<Response> => {
        const url = typeof input === "string" ? input : input.toString();
        if (url.includes("playintegritymanager.googleapis.com")) {
          // Raw provider envelope — never want this leaked to clients.
          return jsonResponse(
            {
              error: {
                code: 400,
                message: "INVALID_ARGUMENT",
                status: "INVALID_ARGUMENT",
              },
            },
            400,
          );
        }
        return authMock(input, init);
      };
      installFetchMock(combinedFetch);
      try {
        const response = await handleRequest(
          makeIntegrityRequest("Bearer header.payload.signature"),
        );
        assertEquals(
          response.status,
          200,
          "handler returns 200; the failure is encoded in the verdict body",
        );
        const body = await response.json();
        assertEquals(body.valid, false);
        assertEquals(
          body.details.failure_reason,
          "INTEGRITY_PROVIDER_ERROR",
          "client must see the stable code, never the raw provider envelope",
        );
        const serialized = JSON.stringify(body);
        assertEquals(
          serialized.includes("INVALID_ARGUMENT"),
          false,
          `client envelope must NOT contain the raw provider error text. Got: ${serialized}`,
        );
      } finally {
        restoreFetch();
      }
    } finally {
      Deno.env.delete("PLAY_PACKAGE_NAME");
      Deno.env.delete("GOOGLE_SERVICE_ACCOUNT");
    }
  },
});

// Module-level keypair so the verdict-rejection test has a valid PKCS8
// PEM for getGoogleAccessToken. Mirrors the setup in
// `fcm-send/oauth-foundation.test.ts`. Generated once for the file.
const TEST_KEY_PAIR = await crypto.subtle.generateKey(
  {
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  },
  true,
  ["sign", "verify"],
);
const TEST_PRIVATE_KEY_PEM = `-----BEGIN PRIVATE KEY-----\n${
  btoa(String.fromCharCode(
    ...new Uint8Array(
      await crypto.subtle.exportKey("pkcs8", TEST_KEY_PAIR.privateKey),
    ),
  )).match(/.{1,64}/g)!.join("\n")
}\n-----END PRIVATE KEY-----\n`;

/**
 * The handler's `getGoogleAccessToken` does `strToArrayBuffer(atob(<pem body>))`
 * — TWO base64-decodes in a row against the same string. For a
 * normal PEM that would yield garbage; to make the verdict
 * rejection test reach `crypto.subtle.importKey` with valid PKCS8
 * we instead pre-`btoa` the DER bytes. The double-decode then
 * collapses back to the original DER and `importKey` succeeds.
 *
 * Pre-existing helper bugs (derToPem + double-atob) are out of
 * scope for this fix; we work around them here so the failure_reason
 * contract is still pinned.
 */
function pemToHandlerWireFormat(pem: string): string {
  const stripped = pem
    .replace(/-----BEGIN [^-]+-----/, "")
    .replace(/-----END [^-]+-----/, "")
    .replace(/\s+/g, "");
  const derBytes = Uint8Array.from(atob(stripped), (c) => c.charCodeAt(0));
  return btoa(String.fromCharCode(...derBytes));
}

/**
 * Pins that a Play verdict rejection (token validates fine, but the
 * verdict says the device/app/account is not OK) reports a stable
 * `INTEGRITY_VERDICT_REJECTED` code while still surfacing the
 * non-sensitive verdict labels in `details` so the UI can branch.
 */
Deno.test({
  name: "BLK-01 — Play verdict rejection returns 'INTEGRITY_VERDICT_REJECTED' with verdict details preserved",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    Deno.env.set("PLAY_PACKAGE_NAME", "com.example.parentalcontrol");
    Deno.env.set(
      "GOOGLE_SERVICE_ACCOUNT",
      JSON.stringify({
        client_email: "x@x",
        // Pre-btoa'd DER (see pemToHandlerWireFormat) so the
        // handler's double-atob collapses to valid PKCS8.
        private_key: pemToHandlerWireFormat(TEST_PRIVATE_KEY_PEM),
      }),
    );
    try {
      const { fetchMock: authMock } = buildFetchMock({ authOutcome: "ok-with-device" });
      const combinedFetch: typeof fetch = async (
        input: RequestInfo | URL,
        init?: RequestInit,
      ): Promise<Response> => {
        const url = typeof input === "string" ? input : input.toString();
        // The handler mints a Google OAuth token then calls Play
        // Integrity. We mock both: a fake access_token and the
        // verdict we want to assert against.
        if (url.includes("oauth2.googleapis.com")) {
          return jsonResponse({ access_token: "fake-access-token" });
        }
        if (url.includes("playintegritymanager.googleapis.com")) {
          return jsonResponse({
            tokenPayloadExternal: {
              deviceIntegrity: {
                deviceRecognitionVerdict: ["UNEVALUATED"],
              },
              appIntegrity: { appRecognitionVerdict: "UNEVALUATED" },
              accountDetails: { appLicensingVerdict: "UNLICENSED" },
              internalDebugInfo: {
                verboseMessage:
                  "sensitive-internal-debug-text-should-not-leak",
              },
            },
          });
        }
        return authMock(input, init);
      };
      installFetchMock(combinedFetch);
      try {
        // The handler decodes the middle base64 segment of the
        // integrity_token via `atob(...).split(".")[1]`. Send a
        // well-formed 3-segment token so it parses without throwing
        // before we get to the verdict branch.
        const validIntegrityToken = `test.${btoa('{"x":1}')}.signature`;
        const response = await handleRequest(
          new Request("https://example.test/functions/v1/verify-integrity", {
            method: "POST",
            headers: {
              Authorization: "Bearer header.payload.signature",
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ integrity_token: validIntegrityToken }),
          }),
        );
        assertEquals(response.status, 200);
        const body = await response.json();
        assertEquals(body.valid, false);
        assertEquals(
          body.details.failure_reason,
          "INTEGRITY_VERDICT_REJECTED",
          "client must see the stable verdict-rejection code",
        );
        // Non-sensitive verdict labels ARE preserved in details so
        // the UI can render them.
        assertEquals(body.details.device_integrity, "UNEVALUATED");
        assertEquals(body.details.app_integrity, "UNEVALUATED");
        assertEquals(body.details.account_details, "UNLICENSED");
        // Internal debug info MUST NOT leak.
        const serialized = JSON.stringify(body);
        assertEquals(
          serialized.includes("sensitive-internal-debug-text-should-not-leak"),
          false,
          `verdict-rejected envelope must NOT leak Google internal debug text. Got: ${serialized}`,
        );
      } finally {
        restoreFetch();
      }
    } finally {
      Deno.env.delete("PLAY_PACKAGE_NAME");
      Deno.env.delete("GOOGLE_SERVICE_ACCOUNT");
    }
  },
});

Deno.test({
  name: "BLK-01 — Play provider exception returns 'INTEGRITY_PROVIDER_ERROR', never 'EXCEPTION: <message>'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    Deno.env.set("PLAY_PACKAGE_NAME", "com.example.parentalcontrol");
    Deno.env.set(
      "GOOGLE_SERVICE_ACCOUNT",
      JSON.stringify({ client_email: "x@x", private_key: "not-a-pem" }),
    );
    try {
      const { fetchMock: authMock } = buildFetchMock({ authOutcome: "ok-with-device" });
      const combinedFetch: typeof fetch = async (
        input: RequestInfo | URL,
        init?: RequestInit,
      ): Promise<Response> => {
        const url = typeof input === "string" ? input : input.toString();
        if (url.includes("playintegritymanager.googleapis.com")) {
          // Simulate an exception path: Play API returns invalid
          // JSON. JSON.parse() throws with a message like
          // "Unexpected token x in JSON at position 17" — the
          // handler MUST NOT include that text in the envelope.
          return new Response("not-actually-json", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          });
        }
        return authMock(input, init);
      };
      installFetchMock(combinedFetch);
      try {
        const response = await handleRequest(
          makeIntegrityRequest("Bearer header.payload.signature"),
        );
        assertEquals(response.status, 200);
        const body = await response.json();
        assertEquals(body.valid, false);
        assertEquals(
          body.details.failure_reason,
          "INTEGRITY_PROVIDER_ERROR",
        );
        const serialized = JSON.stringify(body);
        // The exact pre-fix string format was `EXCEPTION: ${error.message}`.
        assertEquals(
          serialized.includes("EXCEPTION:"),
          false,
          `client envelope must NOT contain "EXCEPTION:". Got: ${serialized}`,
        );
        // Nor any JSON parse library message.
        assertEquals(
          serialized.includes("JSON") && serialized.includes("position"),
          false,
          `client envelope must NOT contain JSON parse position text. Got: ${serialized}`,
        );
      } finally {
        restoreFetch();
      }
    } finally {
      Deno.env.delete("PLAY_PACKAGE_NAME");
      Deno.env.delete("GOOGLE_SERVICE_ACCOUNT");
    }
  },
});
