// RED→GREEN tests for the B1a1 OAuth credential / JWT / cache foundation.
// Run from this directory: deno test --allow-all --no-check oauth-foundation.test.ts
// deno-lint-ignore-file no-import-prefix

import { assertEquals } from "jsr:@std/assert@1";
import {
  clearCachedOAuthToken,
  clearOAuthCacheForTests,
  decodeServiceAccount,
  FcmConfigError,
  getCachedOAuthToken,
  mintFcmAssertionJwt,
  setCachedOAuthToken,
} from "./oauth-foundation.ts";

const NOT_CONFIGURED = "fcm_service_account_not_configured";

// Module-load RSA-2048 keypair (WebCrypto). Synthetic keys fail at
// crypto.subtle.importKey (Engram #371) so the keypair is minted here
// and exported in PEM form. Public key re-imported with explicit
// algorithm metadata (djwt v2.8 needs it for verify()).
const KEY_PAIR = await crypto.subtle.generateKey(
  {
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  },
  true,
  ["sign", "verify"],
);
const PRIVATE_KEY_PEM = `-----BEGIN PRIVATE KEY-----\n${
  btoa(String.fromCharCode(
    ...new Uint8Array(
      await crypto.subtle.exportKey("pkcs8", KEY_PAIR.privateKey),
    ),
  )).match(/.{1,64}/g)!.join("\n")
}\n-----END PRIVATE KEY-----\n`;
const PUBLIC_VERIFY_KEY = await crypto.subtle.importKey(
  "spki",
  await crypto.subtle.exportKey("spki", KEY_PAIR.publicKey),
  { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
  false,
  ["verify"],
);

const VALID_SERVICE_ACCOUNT = {
  type: "service_account",
  project_id: "parentalcontrol-b1a1",
  private_key: PRIVATE_KEY_PEM,
  client_email: "fcm@parentalcontrol-b1a1.iam.gserviceaccount.com",
  client_id: "1",
};
const VALID_B64 = btoa(JSON.stringify(VALID_SERVICE_ACCOUNT));

const decodeB64Url = (s: string) =>
  JSON.parse(new TextDecoder().decode(
    Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) =>
      c.charCodeAt(0)),
  ));

async function expectFcm(
  code: string,
  fn: () => unknown | Promise<unknown>,
): Promise<void> {
  let err: unknown;
  try {
    await fn();
  } catch (e) {
    err = e;
  }
  if (!err) throw new Error(`expected throw with code=${code}, got none`);
  if (!(err instanceof FcmConfigError)) {
    throw new Error(
      `expected FcmConfigError, got ${(err as Error)?.constructor?.name}`,
    );
  }
  assertEquals((err as FcmConfigError).code, code);
}

// ===== B1a1.1 — malformed config normalization =====
// All decode/mint-stage failure paths normalize to FcmConfigError (review
// CRITICAL/WARNING B1A-OAUTH-002). No raw DataError / InvalidCharacterError
// leak. Both empty secret and per-field-missing path within the decode
// stage; garbage PEM and valid-PEM-but-non-PKCS#8 paths within mint.
Deno.test("B1a1.1 — decode rejects empty / per-field / invalid-base64 → FcmConfigError", async () => {
  clearOAuthCacheForTests();
  try {
    // decode stage: empty / non-base64
    await expectFcm(NOT_CONFIGURED, () => decodeServiceAccount(""));
    await expectFcm(
      NOT_CONFIGURED,
      () => decodeServiceAccount("%%%%%not-base64"),
    );
    // missing-field per-field
    for (const f of ["project_id", "private_key", "client_email"]) {
      const sa = { ...VALID_SERVICE_ACCOUNT };
      delete (sa as Record<string, unknown>)[f];
      await expectFcm(
        NOT_CONFIGURED,
        () => decodeServiceAccount(btoa(JSON.stringify(sa))),
      );
    }
    // mint stage: atob failure on garbage PEM + importKey failure on
    // non-PKCS#8 bytes — both normalize to FcmConfigError.
    const mintCases: Array<{ privateKey: string }> = [
      { privateKey: "not-a-pem" },
      {
        privateKey:
          "-----BEGIN PRIVATE KEY-----\nZ3VydQ==\n-----END PRIVATE KEY-----\n",
      },
    ];
    for (const c of mintCases) {
      const sa = decodeServiceAccount(
        btoa(
          JSON.stringify({
            ...VALID_SERVICE_ACCOUNT,
            private_key: c.privateKey,
          }),
        ),
      );
      await expectFcm(
        NOT_CONFIGURED,
        () => mintFcmAssertionJwt(sa, Date.now()),
      );
    }
  } finally {
    clearOAuthCacheForTests();
  }
});

// ===== B1a1.2 — absolute iat/exp + signature verifies =====
// Reviewer CRITICAL B1A-OAUTH-001: `getNumericDate(Date)` produces absolute
// Unix epoch; `getNumericDate(iatSec)` (number) produces a JWT ≈ 30 years
// in the future (Engram #380). This test asserts the timestamp mirrors
// `Math.round(nowMs/1000)` exactly and verifies the signature against
// the re-imported public key (NOT just claims-only decoding — the
// pre-remediation test relied solely on `decodeB64Url` of the payload,
// which proves structure but not authenticity).
Deno.test("B1a1.2 — mint: ABSOLUTE iat/exp + signature verifies against pubkey", async () => {
  clearOAuthCacheForTests();
  try {
    const sa = decodeServiceAccount(VALID_B64);
    const nowMs = Date.now();
    const jwt = await mintFcmAssertionJwt(sa, nowMs);
    const [headerSeg, payloadSeg] = jwt.split(".");
    assertEquals(jwt.split(".").length, 3);
    const header = decodeB64Url(headerSeg);
    const payload = decodeB64Url(payloadSeg);
    assertEquals(header.alg, "RS256");
    assertEquals(payload.iss, sa.client_email);
    assertEquals(
      payload.scope,
      "https://www.googleapis.com/auth/firebase.messaging",
    );
    assertEquals(payload.aud, "https://oauth2.googleapis.com/token");
    // djwt 2.8's getNumericDate(Date) uses Math.round(ms/1000). Mirror exactly.
    assertEquals(payload.iat, Math.round(nowMs / 1000));
    assertEquals(payload.exp, Math.round(nowMs / 1000) + 3600);
    // Real cryptographic signature verification against the public key.
    const { verify } = await import("https://deno.land/x/djwt@v2.8/mod.ts");
    const verified = await verify(jwt, PUBLIC_VERIFY_KEY);
    assertEquals(verified.iss, sa.client_email);
    assertEquals(
      verified.aud,
      "https://oauth2.googleapis.com/token",
    );
  } finally {
    clearOAuthCacheForTests();
  }
});

// ===== B1a1.3 — cache: set populates, clear roundtrip, safety window =====
// Reviewer WARNING B1A-TEST-001: cache state is module-global. This test
// uses a UNIQUE cache key and a `try { … } finally { clear… }` cleanup so
// the assertion remains independent under `deno test --parallel`. Asserts
// BOTH the populated AND the post-clear state explicitly (not just
// "the cache contains something").
Deno.test("B1a1.3 — cache: set populates, clear roundtrip, safety window evicts", () => {
  clearOAuthCacheForTests();
  try {
    const key = "b1a1-cache";
    assertEquals(getCachedOAuthToken(key), null);
    const setAt = 1_700_000_000_000;
    setCachedOAuthToken(key, "tok", 3600, setAt);
    const e = getCachedOAuthToken(key, setAt + 100);
    if (!e) throw new Error("cache miss after set");
    assertEquals(e.token, "tok");
    assertEquals(clearCachedOAuthToken(key), true, "first clear: true");
    assertEquals(getCachedOAuthToken(key), null);
    assertEquals(clearCachedOAuthToken(key), false, "double clear: false");
    // Safety window: within last 60s of expiry → treated as stale.
    setCachedOAuthToken(key, "tok2", 3600, setAt);
    assertEquals(
      getCachedOAuthToken(key, setAt + 3_541_000),
      null,
      "59s before expiry MUST be stale",
    );
  } finally {
    clearOAuthCacheForTests();
  }
});
