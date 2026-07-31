// BLK-02 tests for `custom-access-token-hook`.
//
// Pinned trust boundaries (the security contract this hook MUST hold):
//
//   1. Verify the Standard Webhooks signature before trusting any
//      claim. Missing / malformed / unverifiable signatures fail
//      closed.
//   2. Parse the official Supabase event shape
//      `{ user_id, claims, authentication_method }`; reject anything
//      else.
//   3. Preserve every required claim unless it is the top-level
//      `device_id` claim, which is derived exclusively from
//      `claims.app_metadata.device_id`.
//   4. Ignore anything outside `app_metadata` that tries to influence
//      top-level `device_id` (request body, query params,
//      `user_metadata.device_id`, stale top-level `device_id`).
//   5. Enforce `user_id === claims.sub`; mismatch refuses to mint
//      claims for a different subject.
//   6. Fail closed when `app_metadata.device_id` violates the backend
//      UUID contract.
//   7. Every error response is a stable, non-sensitive code — no
//      secret, no payload bytes, no stack frames.
//
// HTTP tests exercise REAL signature verification end-to-end via
// `standardwebhooks@1.0.0`: we sign payloads with the same library
// Supabase uses, so a regression in the verification algorithm breaks
// the test. Verification is never mocked.
//
// Run with:
//   cd supabase/functions/custom-access-token-hook && deno task test

import {
  assertEquals,
  assertExists,
  assertNotStrictEquals,
  assertStrictEquals,
} from "jsr:@std/assert@1";
import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";
import {
  customAccessTokenHook,
  handleRequest,
  HOOK_ERROR,
  isValidUuid,
  type HookEvent,
} from "./index.ts";

const DEVICE_UUID = "ab123456-7890-1234-89ab-cdef01234567";
const PARENT_UUID = "11111111-2222-3333-4444-555555555555";
const USER_UUID = "user-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

// Supabase env `CUSTOM_ACCESS_TOKEN_SECRET` is `v1,whsec_<base64>`. We
// build one with `Webhook` so the same secret string round-trips
// between the test signer and the production-style verifier.
const TEST_SECRET_BASE64 = "VmFlWlo4d1l5VGVwT2dOZlpMbE9Lc1VaY3hxdw==";
const TEST_SECRET = `v1,whsec_${TEST_SECRET_BASE64}`;

// Claims the SDK MUST see on every issued token. `device_id` is
// deliberately excluded — it's the hook's derivation target, not a
// pre-existing field.
const REQUIRED_CLAIM_KEYS = [
  "iss",
  "aud",
  "exp",
  "iat",
  "sub",
  "role",
  "aal",
  "session_id",
  "email",
  "phone",
  "is_anonymous",
  "app_metadata",
  "user_metadata",
] as const;

function baseClaims(
  overrides: {
    app_metadata?: Record<string, unknown>;
    user_metadata?: Record<string, unknown>;
    device_id?: string;
    sub?: string;
  } = {},
): Record<string, unknown> {
  const sub = overrides.sub ?? USER_UUID;
  return {
    iss: "supabase",
    aud: "authenticated",
    exp: 1_900_000_000,
    iat: 1_899_999_000,
    sub,
    role: "authenticated",
    aal: "aal1",
    session_id: "session-helper",
    email: "device@example.com",
    phone: "",
    is_anonymous: true,
    app_metadata: overrides.app_metadata ?? {
      provider: "anonymous",
      providers: ["anonymous"],
    },
    user_metadata: overrides.user_metadata ?? {},
    ...(overrides.device_id !== undefined ? { device_id: overrides.device_id } : {}),
  };
}

function buildEvent(
  overrides: {
    user_id?: string;
    claims?: Record<string, unknown>;
    authentication_method?: string;
  } = {},
): HookEvent {
  const userId = overrides.user_id ?? USER_UUID;
  return {
    user_id: userId,
    claims: overrides.claims ?? baseClaims(),
    authentication_method: overrides.authentication_method ?? "anonymous",
  };
}

/**
 * Sign a JSON payload with the test secret and return the signed
 * `Request` together with the raw payload string (the standardwebhooks
 * library requires the EXACT bytes that were signed, so we keep both
 * in lockstep).
 */
function signRequest(
  event: HookEvent,
  opts: { secret?: string; timestampOffsetSec?: number } = {},
): { request: Request; payload: string } {
  const payload = JSON.stringify(event);
  const wh = new Webhook(opts.secret ?? TEST_SECRET_BASE64);
  const msgId = `msg_${crypto.randomUUID()}`;
  // The `Date` constructor takes MILLISECONDS. Build the timestamp in
  // seconds first, then convert to milliseconds — otherwise the
  // signature is computed in 1970 and the verifier rejects it.
  const tsSeconds = Math.floor(Date.now() / 1000) + (opts.timestampOffsetSec ?? 0);
  const ts = new Date(tsSeconds * 1000);
  const signature = wh.sign(msgId, ts, payload);
  const request = new Request(
    "https://example.test/functions/v1/custom-access-token-hook",
    {
      method: "POST",
      headers: {
        "webhook-id": msgId,
        "webhook-timestamp": String(tsSeconds),
        "webhook-signature": signature,
        "Content-Type": "application/json",
      },
      body: payload,
    },
  );
  return { request, payload };
}

/** Run an async body with the env set, then restore regardless of failure. */
async function withHookSecret<T>(
  value: string | undefined,
  body: () => Promise<T>,
): Promise<T> {
  if (value === undefined) Deno.env.delete("CUSTOM_ACCESS_TOKEN_SECRET");
  else Deno.env.set("CUSTOM_ACCESS_TOKEN_SECRET", value);
  try {
    return await body();
  } finally {
    Deno.env.delete("CUSTOM_ACCESS_TOKEN_SECRET");
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Pure-function tests: claim derivation
// ────────────────────────────────────────────────────────────────────────────

Deno.test({
  name: "paired: device_id injected from app_metadata; stale top-level removed",
  fn: () => {
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: DEVICE_UUID,
        },
      }),
    });

    const out = customAccessTokenHook(event);
    assertEquals(out.claims.device_id, DEVICE_UUID);
    assertEquals(out.claims.role, "authenticated");
    assertEquals(out.claims.sub, USER_UUID);
    assertEquals(out.claims.app_metadata, {
      provider: "anonymous",
      providers: ["anonymous"],
      device_id: DEVICE_UUID,
    });
  },
});

Deno.test({
  name: "unpaired: no top-level device_id; stale top-level removed",
  fn: () => {
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: { provider: "email", providers: ["email"] },
        device_id: "stale-device-id-from-old-issuance",
      }),
    });

    const out = customAccessTokenHook(event);
    assertStrictEquals(
      "device_id" in out.claims,
      false,
      "stale top-level device_id must be removed",
    );
    assertEquals(out.claims.role, "authenticated");
    assertEquals(out.claims.sub, USER_UUID);
  },
});

Deno.test({
  name: "all required claims preserved generically",
  fn: () => {
    const requiredClaims = baseClaims({
      app_metadata: {
        provider: "anonymous",
        providers: ["anonymous"],
        device_id: DEVICE_UUID,
      },
      user_metadata: { name: "Agent" },
    });
    // Extras beyond the standard 13 must also survive (e.g. `amr`).
    (requiredClaims as Record<string, unknown>).amr = [
      { method: "anonymous", timestamp: 1_899_999_000 },
    ];
    const event = buildEvent({ claims: requiredClaims });

    const out = customAccessTokenHook(event);
    for (const key of Object.keys(requiredClaims)) {
      assertEquals(
        out.claims[key],
        requiredClaims[key as keyof typeof requiredClaims],
        `required claim ${key} must be preserved`,
      );
    }
    assertEquals(out.claims.device_id, DEVICE_UUID);
  },
});

Deno.test({
  name: "user_metadata.device_id cannot inject top-level device_id",
  fn: () => {
    const attackerDevice = "deadbeef-dead-beef-dead-beefdeadbeef";
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: { provider: "anonymous", providers: ["anonymous"] },
        user_metadata: { device_id: attackerDevice },
        device_id: "another-attacker-supplied-id",
      }),
    });

    const out = customAccessTokenHook(event);
    assertStrictEquals(
      "device_id" in out.claims,
      false,
      "no claim from app_metadata → no top-level device_id",
    );
    assertNotStrictEquals(out.claims.device_id, attackerDevice);
  },
});

Deno.test({
  name: "user_id/sub mismatch → INVALID_EVENT",
  fn: () => {
    const cases: Array<{ user_id: string; sub: unknown; label: string }> = [
      { user_id: USER_UUID, sub: "a-different-sub-uuid", label: "different sub" },
      { user_id: USER_UUID, sub: undefined, label: "missing sub" },
      { user_id: USER_UUID, sub: "", label: "empty sub" },
      { user_id: "", sub: USER_UUID, label: "empty user_id" },
    ];
    for (const c of cases) {
      const claims = baseClaims();
      if (c.sub !== undefined) (claims as Record<string, unknown>).sub = c.sub;
      else delete claims.sub;
      const event = buildEvent({ user_id: c.user_id, claims });

      let caught: unknown = null;
      try {
        customAccessTokenHook(event);
      } catch (err) {
        caught = err;
      }
      assertExists(caught, `${c.label} should be rejected`);
      assertEquals(
        (caught as { code: string }).code,
        HOOK_ERROR.INVALID_EVENT,
        `${c.label} must surface as INVALID_EVENT`,
      );
    }
  },
});

Deno.test({
  name: "invalid device_id → INVALID_DEVICE_ID",
  fn: () => {
    // String values that are not RFC 4122 UUIDs.
    const badStrings = [
      "not-a-uuid",
      "../../etc/passwd",
      "'; DROP TABLE devices; --",
      "123",
      "ab123456-7890-1234-89ab-cdef0123456", // 35 chars
      "ab123456-7890-1234-89ab-cdef012345678", // 37 chars
      "ZZ123456-7890-1234-89ab-cdef01234567", // non-hex
    ];
    // Non-string values are a server-side integrity bug; must fail closed.
    const badNonStrings: unknown[] = [42, true, false, { id: "x" }, ["x"], null];

    for (const bad of [...badStrings, ...badNonStrings]) {
      const event = buildEvent({
        claims: baseClaims({
          app_metadata: {
            provider: "anonymous",
            providers: ["anonymous"],
            device_id: bad,
          },
        }),
      });
      let caught: unknown = null;
      try {
        customAccessTokenHook(event);
      } catch (err) {
        caught = err;
      }
      assertExists(caught, `value ${JSON.stringify(bad)} should be rejected`);
      assertEquals(
        (caught as { code: string }).code,
        HOOK_ERROR.INVALID_DEVICE_ID,
        `value ${JSON.stringify(bad)} should surface as INVALID_DEVICE_ID`,
      );
    }

    // Empty string is "not set"; omit the top-level claim, do NOT throw.
    const emptyEvent = buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: "",
        },
      }),
    });
    // Empty string is "not set"; omit the top-level claim, do NOT throw.
    const out = customAccessTokenHook(emptyEvent);
    assertStrictEquals("device_id" in out.claims, false);
  },
});

Deno.test({
  name: "isValidUuid accepts valid; rejects malformed",
  fn: () => {
    const valid = [
      DEVICE_UUID,
      PARENT_UUID,
      "ABCDEF12-3456-7890-ABCD-EF1234567890",
      "00000000-0000-0000-0000-000000000000",
    ];
    for (const v of valid) assertEquals(isValidUuid(v), true, `expected UUID: ${v}`);

    const malformed = [
      "",
      "not-a-uuid",
      "ab123456-7890-1234-89ab-cdef0123456",
      "ab123456-7890-1234-89ab-cdef012345678",
      "ab1234567890123456789abcdef0123456",
      "ab123456-7890-1234-89ab-cdef0123456g",
      "ab123456-7890-1234-89ab-cdef01234567 ",
    ];
    for (const m of malformed) assertEquals(isValidUuid(m), false, `expected reject: ${m}`);
  },
});

// ────────────────────────────────────────────────────────────────────────────
// HTTP entry tests: real signature verification
// ────────────────────────────────────────────────────────────────────────────

Deno.test({
  name: "valid signed paired event → 200 with { claims }",
  fn: async () => {
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: DEVICE_UUID,
        },
      }),
    });
    const { request } = signRequest(event);

    const res = await withHookSecret(TEST_SECRET, () => handleRequest(request));
    assertEquals(res.status, 200);
    assertEquals(res.headers.get("Content-Type"), "application/json");
    const body = await res.json();
    assertEquals(body.claims.device_id, DEVICE_UUID);
    assertEquals(body.claims.sub, USER_UUID);
    assertEquals(body.claims.role, "authenticated");
  },
});

Deno.test({
  name: "valid signed unpaired event → 200 with required claims preserved",
  fn: async () => {
    const claims = baseClaims({
      app_metadata: { provider: "email", providers: ["email"] },
      device_id: "stale-attacker-supplied-id",
    });
    const event = buildEvent({ claims });
    const { request } = signRequest(event);

    const res = await withHookSecret(TEST_SECRET, () => handleRequest(request));
    assertEquals(res.status, 200);
    const body = await res.json();
    assertStrictEquals("device_id" in body.claims, false);
    // Every required claim present in the signed payload must survive.
    for (const k of REQUIRED_CLAIM_KEYS) {
      assertEquals(body.claims[k], claims[k], `claim ${k} preserved`);
    }
  },
});

Deno.test({
  name: "signature failures → 401 INVALID_SIGNATURE",
  fn: async () => {
    // Pre-build a payload we can sign with one secret, mutate the
    // headers/body, then assert the verifier rejects each variant.
    const event = buildEvent();
    const { request: signed, payload } = signRequest(event);
    const url = signed.url;

    // A request signed with a different secret; reuse the headers.
    const wrongSecretReq = signRequest(event, {
      secret: "OTAwMTIzNDU2Nzg5MGFiY2RlZg==",
    }).request;

    const cases: Array<{ name: string; build: () => Request }> = [
      {
        name: "tampered signature",
        build: () => {
          // Flip a byte in the signature. We replace a hex digit with a
          // known different hex digit so the mutation always lands
          // somewhere the verifier hashes.
          const sig = signed.headers.get("webhook-signature") ?? "";
          const tampered = sig.replace(/[0-9a-f]/, "f") === sig
            ? sig.replace(/[A-Z]/, "X")
            : sig.replace(/[0-9a-f]/, "f");
          const h = new Headers(signed.headers);
          h.set("webhook-signature", tampered);
          return new Request(url, { method: "POST", headers: h, body: payload });
        },
      },
      {
        name: "missing webhook-id header",
        build: () =>
          new Request(url, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "webhook-timestamp": String(Math.floor(Date.now() / 1000)),
              "webhook-signature": "v1,AAAA",
            },
            body: payload,
          }),
      },
      {
        name: "old timestamp",
        build: () => signRequest(event, { timestampOffsetSec: -10 * 60 }).request,
      },
      {
        name: "payload signed with different secret",
        build: () => wrongSecretReq,
      },
      {
        name: "unsigned payload",
        build: () =>
          new Request(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: payload,
          }),
      },
      {
        name: "non-JSON body (signature is valid for body bytes)",
        build: () => {
          const wh = new Webhook(TEST_SECRET_BASE64);
          const ts = new Date();
          const sig = wh.sign("msg_unsigned_body", ts, "not-json-at-all");
          return new Request(url, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "webhook-id": "msg_unsigned_body",
              "webhook-timestamp": String(Math.floor(ts.getTime() / 1000)),
              "webhook-signature": sig,
            },
            body: "not-json-at-all",
          });
        },
      },
    ];

    for (const c of cases) {
      const res = await withHookSecret(TEST_SECRET, () => handleRequest(c.build()));
      assertEquals(res.status, 401, `${c.name}: status`);
      const body = await res.json();
      assertEquals(
        body.error,
        HOOK_ERROR.INVALID_SIGNATURE,
        `${c.name}: returned ${JSON.stringify(body)}`,
      );
    }
  },
});

Deno.test({
  name: "fail-closed: missing secret, malformed secret, non-POST, invalid device_id, user_id/sub mismatch",
  fn: async () => {
    // Build all signed requests up-front so we don't race env state
    // against request construction.
    const missingSecretReq = signRequest(buildEvent()).request;
    const invalidDeviceIdReq = signRequest(buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: "not-a-uuid",
        },
      }),
    })).request;
    const mismatchClaims = baseClaims();
    mismatchClaims.sub = "00000000-0000-0000-0000-000000000000";
    const mismatchReq = signRequest(buildEvent({ claims: mismatchClaims })).request;

    const cases: Array<{
      name: string;
      env: string | undefined;
      request: Request;
      expectedStatus: number;
      expectedCode: string;
    }> = [
      {
        name: "missing secret",
        env: undefined,
        request: missingSecretReq,
        expectedStatus: 500,
        expectedCode: HOOK_ERROR.HOOK_SECRET_MISSING,
      },
      {
        name: "malformed secret (bad base64)",
        // The hook MUST surface the same closed envelope as a forged
        // payload, never a 500 or a leaked detail, when the env secret
        // is itself unparseable.
        env: "v1,whsec_$$$$not-valid-base64$$$$",
        request: signRequest(buildEvent()).request,
        expectedStatus: 401,
        expectedCode: HOOK_ERROR.INVALID_SIGNATURE,
      },
      {
        name: "non-POST method",
        env: TEST_SECRET,
        request: new Request(
          "https://example.test/functions/v1/custom-access-token-hook",
          { method: "GET" },
        ),
        expectedStatus: 405,
        expectedCode: HOOK_ERROR.INVALID_EVENT,
      },
      {
        name: "invalid device_id (UUID contract)",
        env: TEST_SECRET,
        request: invalidDeviceIdReq,
        expectedStatus: 400,
        expectedCode: HOOK_ERROR.INVALID_DEVICE_ID,
      },
      {
        name: "user_id/sub mismatch",
        env: TEST_SECRET,
        request: mismatchReq,
        expectedStatus: 400,
        expectedCode: HOOK_ERROR.INVALID_EVENT,
      },
    ];

    for (const c of cases) {
      const res = await withHookSecret(c.env, () => handleRequest(c.request));
      assertEquals(res.status, c.expectedStatus, `${c.name}: status`);
      const body = await res.json();
      assertEquals(
        body.error,
        c.expectedCode,
        `${c.name}: returned ${JSON.stringify(body)}`,
      );
    }
  },
});

Deno.test({
  name: "error envelopes are stable and non-sensitive",
  fn: async () => {
    // Pre-build both requests so we don't race env state.
    const missingSecretReq = signRequest(buildEvent()).request;
    const forgedReq = signRequest(buildEvent(), {
      secret: "OTAwMTIzNDU2Nzg5MGFiY2RlZg==",
    }).request;

    const cases: Array<{
      name: string;
      env: string | undefined;
      request: Request;
      expectedCode: string;
    }> = [
      {
        name: "missing secret",
        env: undefined,
        request: missingSecretReq,
        expectedCode: HOOK_ERROR.HOOK_SECRET_MISSING,
      },
      {
        name: "wrong signature",
        env: TEST_SECRET,
        request: forgedReq,
        expectedCode: HOOK_ERROR.INVALID_SIGNATURE,
      },
    ];

    for (const c of cases) {
      const res = await withHookSecret(c.env, () => handleRequest(c.request));
      assertEquals(
        res.headers.get("Content-Type"),
        "application/json",
        `${c.name}: must be JSON`,
      );
      const body = await res.json();
      assertEquals(body.error, c.expectedCode, `${c.name}: returned ${JSON.stringify(body)}`);
      const text = JSON.stringify(body);
      // Body MUST NOT carry sensitive material: no payload bytes, no
      // stack frames, no secret prefix.
      assertEquals(text.includes("payload"), false, `${c.name}: must not leak payload`);
      assertEquals(text.includes("stack"), false, `${c.name}: must not leak stack`);
      assertEquals(text.includes("whsec_"), false, `${c.name}: must not leak secret prefix`);
    }
  },
});

// ─────────────────────────────────────────────────────────────────────────────
// Phase 1 hardening — extend the trust boundary with edge cases the spec
// asks for but the existing suite covers only in spirit. None of these
// add NEW behavior; they pin the existing fail-closed contract so a
// future refactor cannot silently re-introduce the BLK-01 forgery class.
// ─────────────────────────────────────────────────────────────────────────────

Deno.test({
  name: "hook — app_metadata: null omits top-level device_id (no crash, no claim)",
  fn: () => {
    // The Supabase HKDF endpoint may omit app_metadata entirely for
    // brand-new users. The hook MUST treat missing app_metadata as
    // "no device registered" and emit no top-level device_id claim.
    const claims = baseClaims();
    delete (claims as Record<string, unknown>).app_metadata;
    const event = buildEvent({ claims });

    const out = customAccessTokenHook(event);
    assertStrictEquals(
      "device_id" in out.claims,
      false,
      "absent app_metadata must surface as no top-level device_id",
    );
    assertEquals(out.claims.sub, USER_UUID);
    assertEquals(out.claims.role, "authenticated");
  },
});

Deno.test({
  name: "hook — app_metadata: {} omits top-level device_id",
  fn: () => {
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: {},
      }),
    });

    const out = customAccessTokenHook(event);
    assertStrictEquals(
      "device_id" in out.claims,
      false,
      "empty app_metadata must surface as no top-level device_id",
    );
    assertEquals(out.claims.app_metadata, {});
  },
});

Deno.test({
  name: "hook — app_metadata with a non-object value throws INVALID_EVENT",
  fn: () => {
    // Auth ALWAYS sends app_metadata as an object. A string or number
    // is a server-side integrity bug — the hook MUST fail closed,
    // since silently omitting device_id would downgrade a paired
    // device to an unpaired one.
    const badValues: unknown[] = ["a string", 42, true];
    for (const bad of badValues) {
      const claims = baseClaims({
        app_metadata: bad as Record<string, unknown>,
      });
      const event = buildEvent({ claims });

      let caught: unknown = null;
      try {
        customAccessTokenHook(event);
      } catch (err) {
        caught = err;
      }
      assertExists(caught, `app_metadata=${JSON.stringify(bad)} must throw`);
      assertEquals(
        (caught as { code: string }).code,
        HOOK_ERROR.INVALID_EVENT,
        `app_metadata=${JSON.stringify(bad)} must surface as INVALID_EVENT`,
      );
    }
  },
});

Deno.test({
  name: "hook — app_metadata as an array throws INVALID_EVENT",
  fn: () => {
    // Arrays are objects, but the contract is "object (not array)".
    // An array reaching the hook is a server-side integrity bug.
    const claims = baseClaims();
    (claims as Record<string, unknown>).app_metadata = [
      "never",
      "an",
      "array",
    ];
    const event = buildEvent({ claims });

    let caught: unknown = null;
    try {
      customAccessTokenHook(event);
    } catch (err) {
      caught = err;
    }
    assertExists(caught, "app_metadata=[...] must throw");
    assertEquals(
      (caught as { code: string }).code,
      HOOK_ERROR.INVALID_EVENT,
    );
  },
});

Deno.test({
  name: "hook — strips top-level device_id even when user_metadata.device_id is also present",
  fn: () => {
    // An attacker who can influence user_metadata.user_metadata (or
    // anything other than app_metadata) MUST NOT be able to inject a
    // top-level device_id. The hook strips whatever draft device_id is
    // present, then re-derives from app_metadata alone.
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: DEVICE_UUID,
        },
        user_metadata: { device_id: "attacker-supplied-id" },
        device_id: "another-attacker-supplied-id",
      }),
    });

    const out = customAccessTokenHook(event);
    assertEquals(
      out.claims.device_id,
      DEVICE_UUID,
      "top-level device_id must equal app_metadata.device_id, never user_metadata or the draft claim",
    );
    // The user_metadata field is preserved verbatim — only the top-level
    // claim is sanitized.
    assertEquals(
      (out.claims.user_metadata as Record<string, unknown>).device_id,
      "attacker-supplied-id",
    );
  },
});

Deno.test({
  name: "hook — paired event: device_id is re-derived from app_metadata even when paired user_metadata also has device_id",
  fn: () => {
    // Companion to the strip test: even when user_metadata legitimately
    // carries a device_id, the hook re-derives the top-level claim
    // solely from app_metadata. The trusted surface is app_metadata;
    // user_metadata is an attacker-controlled surface.
    const event = buildEvent({
      claims: baseClaims({
        app_metadata: {
          provider: "anonymous",
          providers: ["anonymous"],
          device_id: DEVICE_UUID,
        },
        user_metadata: { device_id: "user-metadata-id" },
      }),
    });

    const out = customAccessTokenHook(event);
    assertEquals(out.claims.device_id, DEVICE_UUID);
    assertNotStrictEquals(out.claims.device_id, "user-metadata-id");
  },
});

Deno.test({
  name: "hook — all non-device_id claims are preserved verbatim across the sanitization pass",
  fn: () => {
    // Pin the "preserve everything else" contract: the hook only
    // touches the top-level device_id claim. Every other claim round-
    // trips through unchanged, including those the SDK may add over
    // time (e.g. amr, session_id, custom keys).
    const exoticClaims = baseClaims({
      app_metadata: {
        provider: "anonymous",
        providers: ["anonymous"],
        device_id: DEVICE_UUID,
      },
      user_metadata: { name: "Kid", favorite_color: "blue" },
    });
    (exoticClaims as Record<string, unknown>).amr = [
      { method: "anonymous", timestamp: 1_899_999_000 },
    ];
    (exoticClaims as Record<string, unknown>).custom_claim = "preserve-me";
    const event = buildEvent({ claims: exoticClaims });

    const out = customAccessTokenHook(event);
    for (const key of Object.keys(exoticClaims)) {
      assertEquals(
        out.claims[key],
        exoticClaims[key as keyof typeof exoticClaims],
        `claim ${key} must round-trip unchanged`,
      );
    }
    assertEquals(out.claims.device_id, DEVICE_UUID);
  },
});
