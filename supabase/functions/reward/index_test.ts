import { assertEquals } from "jsr:@std/assert@1";

import { handleRequest } from "./index.ts";

const MALFORMED_AUTH_HEADERS = [
  "Basic credentials",
  "Bearer only-two-segments.parts",
  "Bearer header.%%%invalid%%%.signature",
  "Bearer header.eyJzdWIi.signature",
];

function requestWithAuth(authHeader: string): Request {
  return new Request("https://example.test/functions/v1/reward", {
    method: "POST",
    headers: {
      Authorization: authHeader,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ device_id: "dev-1", minutes: 5, reason: "test" }),
  });
}

Deno.test({
  name: "reward rejects malformed Authorization/JWT with 401",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const originalFetch = globalThis.fetch;
    // Any fetch call here would mean we got past auth parsing, which is a
    // regression. Use a hard failure so the test is noisy.
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      throw new Error("fetch must not be called for malformed auth");
    };

    try {
      for (const authHeader of MALFORMED_AUTH_HEADERS) {
        const response = await handleRequest(requestWithAuth(authHeader));
        assertEquals(response.status, 401);
      }
    } finally {
      // deno-lint-ignore no-explicit-any
      (globalThis as any).fetch = originalFetch;
    }
  },
});
