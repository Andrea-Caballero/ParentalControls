// Deno tests for `supabase/functions/_shared/jwt.ts`.
//
// Run from the repo root:
//   deno test --allow-net --allow-env --no-lock supabase/functions/_shared/jwt_test.ts
//
// Or from this directory:
//   cd supabase/functions/_shared && deno test --allow-net --allow-env
//
// These tests pin the trust boundaries for BLK-01:
//   - Forged / unsigned / expired / revoked tokens never yield a
//     verified identity (every malformed/expired case is a 401).
//   - A JWT signed by an attacker CANNOT supply a fake device_id — the
//     helper reads device_id from `user.app_metadata` (server-controlled),
//     not from the JWT claim. With the mock returning a user without
//     `app_metadata.device_id`, even a "valid" auth.getUser response
//     must not yield a device_id.
//   - requireDevice:true fails 403 when the verified user has no
//     device in app_metadata.
//   - requireDevice:false yields a verified parent identity from the
//     server-side `user.id`, not from a forged `sub` claim.
//   - When auth.getUser reports failure, no privileged code path can
//     be reached (the helper returns a 401 Response, not an exception).

import { assertEquals, assertExists } from "jsr:@std/assert@1";
import {
  type VerifiedAuth,
  verifyAuth,
} from "./jwt.ts";

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";
const DEVICE_UUID = "22222222-2222-2222-2222-222222222222";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Content-Type": "application/json",
};

const ENV = {
  url: "https://example.supabase.co",
  anonKey: "anon-test-key",
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function buildFetchMock(opts: {
  /** A user object returned by `/auth/v1/user`. `null` simulates an
   *  unverifiable token (the server rejected the JWT). */
  user?: Record<string, unknown> | null;
  /** When set, the mock throws (simulates a network error). */
  throwOnUser?: boolean;
  /** When set, `/auth/v1/user` returns 401 unless the Bearer token in
   *  the Authorization header exactly matches this raw JWT string.
   *  Use it to assert the helper forwards the expected token and to
   *  prove wrong/missing tokens are rejected by the verifier. */
  expectedToken?: string;
}) {
  const calls: Array<{ url: string; method: string; auth: string | null }> = [];
  // deno-lint-ignore no-explicit-any
  const fetchMock: typeof fetch = (async (input: any, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const auth = extractAuth(init?.headers);
    calls.push({ url, method, auth });

    if (url.includes("/auth/v1/user")) {
      if (opts.throwOnUser) {
        throw new Error("simulated network failure");
      }
      if (opts.expectedToken && auth !== `Bearer ${opts.expectedToken}`) {
        // Wrong Bearer token: the verifier MUST reject it. The
        // returned 401 envelope mimics the Supabase Auth "Bad JWT"
        // path so the helper surfaces the standard
        // "Token inválido o expirado" error to the caller.
        return jsonResponse(
          { error: "invalid_grant", message: "Bad JWT" },
          401,
        );
      }
      if (opts.user === null) {
        // Supabase auth returns 401 with an error envelope when the
        // JWT is invalid/expired/revoked. The client surfaces this as
        // a falsy `data.user`.
        return jsonResponse({ error: "invalid_grant", message: "Bad JWT" }, 401);
      }
      return jsonResponse(opts.user);
    }
    return jsonResponse({ error: `unhandled ${url}` }, 500);
  }) as typeof fetch;
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

// Discriminated-union helpers. Without these the TS compiler will not
// narrow `VerifiedAuth` to its failure / success variant inside an
// async test, so we extract the relevant fields explicitly.
function isFailure(
  r: VerifiedAuth,
): r is Extract<VerifiedAuth, { ok: false }> {
  return r.ok === false;
}
function isSuccess(
  r: VerifiedAuth,
): r is Extract<VerifiedAuth, { ok: true }> {
  return r.ok === true;
}

// ============ Tests ============

Deno.test({
  name: "verifyAuth — missing Authorization returns 401 'Token requerido' (no fetch call)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    let fetchCalled = false;
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      fetchCalled = true;
      return Promise.resolve(new Response(""));
    };
    try {
      const result = await verifyAuth({
        authHeader: null,
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        assertEquals(body.error, "Token requerido");
      }
      assertEquals(fetchCalled, false, "must short-circuit on missing header");
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — non-Bearer Authorization returns 401 'Token requerido'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    for (
      const header of [
        "",
        "Basic credentials",
        "Bearer",
        "Token foo",
        "Bearer    ",
      ]
    ) {
      const result = await verifyAuth({
        authHeader: header,
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false, `header: ${JSON.stringify(header)}`);
      if (isFailure(result)) {
        assertEquals(
          result.response.status,
          401,
          `header: ${JSON.stringify(header)}`,
        );
        const body = await result.response.json();
        assertEquals(body.error, "Token requerido");
      }
    }
  },
});

Deno.test({
  name:
    "verifyAuth — auth.getUser error envelope (forged/expired token) returns 401 'Token inválido o expirado'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock, calls } = buildFetchMock({ user: null });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        assertEquals(body.error, "Token inválido o expirado");
      }
      // The verifier must call /auth/v1/user — the only way to
      // know whether the JWT is valid. We do NOT trust the payload
      // alone (the historical bug).
      assertEquals(
        calls.some((c) => c.url.includes("/auth/v1/user")),
        true,
        "verifyAuth must call /auth/v1/user to perform cryptographic verification",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — auth.getUser transport error does not throw, returns 401",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock } = buildFetchMock({ throwOnUser: true });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        // We deliberately do NOT leak the transport error message.
        assertEquals(body.error, "Token inválido o expirado");
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — forged JWT with a fake sub claim is ignored; parentId comes from the verified user.id",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The mock returns the server-authoritative user — the `id` field
    // is what the Supabase project database says the user id is. Any
    // `sub` claim in the JWT itself is irrelevant: we never read it.
    const { fetchMock } = buildFetchMock({
      user: { id: PARENT_UUID, email: "parent@local.test" },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader:
          "Bearer header." +
          btoa(JSON.stringify({ sub: "fake-uuid", role: "attacker" })) +
          ".signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        // The fake `sub` ("fake-uuid") must NOT be the parentId. The
        // helper derives parentId from the verified user, not the JWT.
        assertEquals(result.parentId, PARENT_UUID);
        assertEquals(result.deviceId, null);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — requireDevice:true with no app_metadata.device_id returns 403 'device_id no encontrado en token'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // Verified user exists but the server-side app_metadata has no
    // device_id — exactly what would happen for a parent who has not
    // paired a device yet, or for an attacker whose forged token
    // bypassed the gateway.
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "parent@local.test",
        app_metadata: { provider: "email" },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 403);
        const body = await result.response.json();
        assertEquals(body.error, "device_id no encontrado en token");
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — requireDevice:true with app_metadata.device_id returns ok with deviceId from app_metadata",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "device@local.test",
        app_metadata: { device_id: DEVICE_UUID },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.deviceId, DEVICE_UUID);
        assertEquals(result.parentId, PARENT_UUID);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — empty string app_metadata.device_id is treated as missing (not a real id)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "x",
        app_metadata: { device_id: "" },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 403);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — non-string app_metadata.device_id is treated as missing",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    for (const value of [42, true, false, { id: "x" }, ["x"]]) {
      const { fetchMock } = buildFetchMock({
        user: {
          id: PARENT_UUID,
          app_metadata: { device_id: value },
        },
      });
      installFetchMock(fetchMock);
      try {
        const result = await verifyAuth({
          authHeader: "Bearer header.payload.signature",
          corsHeaders: CORS,
          env: ENV,
          requireDevice: true,
        });
        assertEquals(result.ok, false, `value: ${JSON.stringify(value)}`);
        if (isFailure(result)) {
          assertEquals(result.response.status, 403);
        }
      } finally {
        restoreFetch();
      }
    }
  },
});

Deno.test({
  name:
    "verifyAuth — requireDevice:false allows parents without a paired device (verified parent happy path)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@x.test" },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: false,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.parentId, PARENT_UUID);
        assertEquals(result.deviceId, null);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — error responses are JSON with Content-Type and the CORS headers",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const result = await verifyAuth({
      authHeader: null,
      corsHeaders: CORS,
      env: ENV,
    });
    assertEquals(result.ok, false);
    if (isFailure(result)) {
      const res = result.response;
      assertEquals(res.headers.get("Content-Type"), "application/json");
      assertEquals(
        res.headers.get("Access-Control-Allow-Origin"),
        "*",
      );
      const body = await res.json();
      assertExists(body.error);
    }
  },
});

Deno.test({
  name:
    "verifyAuth — forged JWT with a fake top-level device_id claim still yields deviceId from app_metadata (not the claim)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The forged JWT carries `device_id: "attacker-device"` in its
    // payload. The verified user returned by /auth/v1/user has
    // `app_metadata.device_id = "real-device"` because that is the
    // server-side state. The helper MUST return the server value, not
    // anything derived from the JWT. This test pins the BLK-01 trust
    // boundary: app_metadata > JWT claim.
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "device@x.test",
        app_metadata: { device_id: "real-device" },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        // base64 segment decodes to {"device_id":"attacker-device", "sub":"x"}.
        authHeader:
          "Bearer header." +
          btoa(JSON.stringify({ device_id: "attacker-device", sub: "x" })) +
          ".signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(
          result.deviceId,
          "real-device",
          "device_id must come from app_metadata, never the JWT claim",
        );
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — Bearer 'header.eyJzdWIi.signature' (the historical 'sub-only' forged payload) is rejected",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The exact forged payload from the BLK-01 threat model: a
    // attacker-controlled JWT with `sub` set to the target parent.
    // The Supabase server must reject it (signature mismatch). We
    // simulate that rejection with a 401 from /auth/v1/user and
    // assert the helper returns 401 with the right message — no
    // service-role path is reachable.
    const { fetchMock } = buildFetchMock({ user: null });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.eyJzdWIi.signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        // Must NOT contain "Token requerido" — this is the
        // post-parse branch.
        assertEquals(body.error !== "Token requerido", true);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — Bearer token is forwarded to /auth/v1/user as 'Authorization: Bearer <jwt>'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // Track exactly what the helper sends to /auth/v1/user. The mock
    // only accepts the EXPECTED JWT; any other Bearer value must
    // produce a 401 envelope. This proves the helper preserves the
    // caller's token end-to-end and does NOT synthesize a token.
    const expectedJwt = "header.expected.signature";
    const { fetchMock, calls } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@local.test" },
      expectedToken: expectedJwt,
    });
    installFetchMock(fetchMock);
    try {
      const ok = await verifyAuth({
        authHeader: `Bearer ${expectedJwt}`,
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(ok.ok, true);
      if (isSuccess(ok)) assertEquals(ok.parentId, PARENT_UUID);

      const userCall = calls.find((c) => c.url.includes("/auth/v1/user"));
      assertEquals(typeof userCall, "object");
      assertEquals(
        userCall!.auth,
        `Bearer ${expectedJwt}`,
        "the helper must forward the caller's Bearer token unchanged",
      );
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name:
    "verifyAuth — wrong Bearer token (mismatch with the verifier's expected value) returns 401 'Token inválido o expirado'",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The mock advertises ONLY a specific expected JWT. The helper
    // must forward a DIFFERENT JWT and the mock returns 401. The
    // helper in turn must surface that 401 as "Token inválido o
    // expirado" — i.e. wrong tokens are rejected by the verifier
    // path, not silently accepted.
    const expectedJwt = "header.expected.signature";
    const wrongJwt = "header.WRONG.signature";
    const { fetchMock, calls } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@local.test" },
      expectedToken: expectedJwt,
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: `Bearer ${wrongJwt}`,
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        assertEquals(body.error, "Token inválido o expirado");

        const userCall = calls.find((c) => c.url.includes("/auth/v1/user"));
        assertEquals(typeof userCall, "object");
        assertEquals(
          userCall!.auth,
          `Bearer ${wrongJwt}`,
          "wrong token must be forwarded so the verifier can reject it",
        );
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — Bearer token with NO 'Bearer' prefix is rejected, never reaches /auth/v1/user",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    let fetchCalled = false;
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      fetchCalled = true;
      return Promise.resolve(new Response(""));
    };
    try {
      const result = await verifyAuth({
        authHeader: "Token header.payload.signature", // no "Bearer" prefix
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        assertEquals(body.error, "Token requerido");
      }
      assertEquals(
        fetchCalled,
        false,
        "headers without a Bearer prefix must short-circuit before any network call",
      );
    } finally {
      restoreFetch();
    }
  },
});

// ─────────────────────────────────────────────────────────────────────────────
// Phase 1 hardening — RFC 7235 hardening + edge-case triangulation.
//
// These tests pin the verifier's contract once more, both with new
// behavior (WWW-Authenticate challenge per RFC 7235) and with edge cases
// the existing suite covers in spirit but not by literal assertion.
// ─────────────────────────────────────────────────────────────────────────────

Deno.test({
  name: "verifyAuth — 401 responses include WWW-Authenticate: Bearer challenge (RFC 7235)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // RFC 7235 §4.1: a 401 response MUST carry a WWW-Authenticate header
    // so the client knows which scheme to retry with. The verifier's
    // 401 path is the "Token requerido" / "Token inválido o expirado"
    // branch — both MUST challenge the bearer scheme.
    const { fetchMock } = buildFetchMock({ user: null });
    installFetchMock(fetchMock);
    try {
      for (
        const header of [
          null,
          "Basic credentials",
          "Bearer header.payload.bad",
        ]
      ) {
        const result = await verifyAuth({
          authHeader: header,
          corsHeaders: CORS,
          env: ENV,
        });
        assertEquals(result.ok, false, `header: ${JSON.stringify(header)}`);
        if (isFailure(result)) {
          assertEquals(result.response.status, 401);
          const challenge = result.response.headers.get("WWW-Authenticate");
          assertEquals(
            typeof challenge,
            "string",
            `header ${JSON.stringify(header)}: must include WWW-Authenticate`,
          );
          assertEquals(
            challenge!.toLowerCase().startsWith("bearer"),
            true,
            `header ${JSON.stringify(header)}: challenge must be Bearer scheme`,
          );
        }
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — 403 (requireDevice with no device_id) includes WWW-Authenticate with insufficient_scope",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const { fetchMock } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@local.test" },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 403);
        const challenge = result.response.headers.get("WWW-Authenticate");
        assertEquals(
          typeof challenge,
          "string",
          "403 must include a WWW-Authenticate challenge so clients can debug",
        );
        assertEquals(
          challenge!.toLowerCase().includes("insufficient_scope"),
          true,
          "403 challenge must signal insufficient_scope",
        );
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — app_metadata: null returns ok:true with deviceId=null",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The Supabase server may return `app_metadata: null` for legacy or
    // unusual user records. The verifier MUST treat this as "no device
    // registration present" and propagate a verified identity with
    // deviceId=null, not crash and not invent a device_id.
    const { fetchMock } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@local.test", app_metadata: null },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.parentId, PARENT_UUID);
        assertEquals(result.deviceId, null);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — app_metadata as an array returns deviceId=null (no device)",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The cast widens to `Record<string, unknown>`; if the server ever
    // returned an array, the lookup would just be undefined. The
    // contract MUST hold: no device_id is reported, no exception
    // escapes, no privilege is leaked.
    const { fetchMock } = buildFetchMock({
      user: { id: PARENT_UUID, email: "p@local.test", app_metadata: ["x"] },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.parentId, PARENT_UUID);
        assertEquals(result.deviceId, null);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — multi-key app_metadata extracts device_id correctly without losing other keys",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The server-side record may carry many metadata keys (provider,
    // providers, custom claims, etc.). The verifier must surface
    // device_id without being confused by sibling keys.
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "p@local.test",
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: DEVICE_UUID,
          custom_claim: "ignored-by-helper",
          pairing_rev: 7,
        },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.deviceId, DEVICE_UUID);
        assertEquals(result.parentId, PARENT_UUID);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — requireDevice:true happy path with verified app_metadata.device_id returns ok with both parentId and deviceId",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The "verified session is accepted" scenario from the spec —
    // pin it explicitly so a future regression to the post-parse
    // branch cannot break the device-scoped happy path.
    const { fetchMock } = buildFetchMock({
      user: {
        id: PARENT_UUID,
        email: "p@local.test",
        app_metadata: { device_id: DEVICE_UUID },
      },
    });
    installFetchMock(fetchMock);
    try {
      const result = await verifyAuth({
        authHeader: "Bearer header.payload.signature",
        corsHeaders: CORS,
        env: ENV,
        requireDevice: true,
      });
      assertEquals(result.ok, true);
      if (isSuccess(result)) {
        assertEquals(result.parentId, PARENT_UUID);
        assertEquals(result.deviceId, DEVICE_UUID);
        // auth.uid() MUST be the verified user.id, never the JWT `sub`.
        assertEquals(result.parentId, PARENT_UUID);
      }
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "verifyAuth — Bearer with only whitespace token ('Bearer   ') is rejected as 401, never reaches fetch",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // The regex `^Bearer\s+(\S+)$` requires at least one non-whitespace
    // token. An attacker could send `Bearer   ` (whitespace only) to
    // probe the regex. The verifier MUST reject without hitting the
    // auth server.
    let fetchCalled = false;
    // deno-lint-ignore no-explicit-any
    (globalThis as any).fetch = () => {
      fetchCalled = true;
      return Promise.resolve(new Response(""));
    };
    try {
      const result = await verifyAuth({
        authHeader: "Bearer   ",
        corsHeaders: CORS,
        env: ENV,
      });
      assertEquals(result.ok, false);
      if (isFailure(result)) {
        assertEquals(result.response.status, 401);
        const body = await result.response.json();
        assertEquals(body.error, "Token requerido");
      }
      assertEquals(
        fetchCalled,
        false,
        "whitespace-only Bearer token must short-circuit before any network call",
      );
    } finally {
      restoreFetch();
    }
  },
});
