// RED tests for the custom-access-token-hook parent_id injection (Slice A
// of `openspec/changes/feat-cross-device-pairing-and-approval`).
//
// Covers the ADDED Requirements from
// `openspec/changes/feat-cross-device-pairing-and-approval/specs/supabase-backend-integration/spec.md`:
//
//   - **A.1.5** `injectParentIdClaim` — parent JWT carries a top-level
//     `parent_id` claim equal to `app_metadata.parent_id`.
//   - **A.1.6** `noParentIdForChild` — child JWT (no `app_metadata.parent_id`)
//     does NOT have a top-level `parent_id` claim.
//
// The hook source today only copies `device_id` (see `index.ts:42-56`).
// GREEN injects `parent_id` from `app_metadata` when present; otherwise
// the claim is omitted (never `null`).
//
// Run with: cd supabase/functions/custom-access-token-hook && deno test --allow-all
//
// RED today (master = 3f3a81d): the existing `customAccessTokenHook` does
// not look at `app_metadata.parent_id` at all; only `device_id` is copied.
// The assertion `result.parent_id === "uuid-p"` fails because the returned
// claims object has no `parent_id` key.

import { assertEquals } from "jsr:@std/assert@1";
import { customAccessTokenHook } from "./index.ts";

const PARENT_UUID = "parent-uuid-aaaa";
const DEVICE_UUID = "device-uuid-bbbb";

function buildHookEvent(
  appMetadata: Record<string, unknown>,
  deviceId?: string
): Parameters<typeof customAccessTokenHook>[0] {
  return {
    type: "verify-jwt",
    event: {
      jwt: {
        sub: "user-sub-uuid",
        role: "authenticated",
        aud: "authenticated",
        exp: Math.floor(Date.now() / 1000) + 3600,
        iat: Math.floor(Date.now() / 1000),
        email: "parent@example.com",
        app_metadata: {
          provider: "email",
          providers: ["email"],
          ...(deviceId !== undefined ? { device_id: deviceId } : {}),
          ...appMetadata,
        },
      },
      claims: {
        role: "authenticated",
        sub: "user-sub-uuid",
        email: "parent@example.com",
      },
    },
  };
}

// ============ Tests ============

Deno.test({
  name:
    "A.1.5 — injectParentIdClaim: parent_id from app_metadata appears at top level",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const event = buildHookEvent({ parent_id: PARENT_UUID }, DEVICE_UUID);

    const result = await customAccessTokenHook(event);

    // The hook MUST copy `app_metadata.parent_id` to a first-level claim
    // named `parent_id`. Before GREEN this key is absent (the existing
    // `device_id`-only branch at index.ts:42-56 returns claims without
    // any parent_id field).
    assertEquals(
      result.parent_id,
      PARENT_UUID,
      "Hook must inject app_metadata.parent_id as a first-level JWT claim",
    );
    // Regression: device_id injection must still work.
    assertEquals(
      result.device_id,
      DEVICE_UUID,
      "Hook must preserve the device_id injection (regression-safe)",
    );
  },
});

Deno.test({
  name:
    "A.1.6 — noParentIdForChild: child JWT (no app_metadata.parent_id) omits the claim",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const event = buildHookEvent({}, DEVICE_UUID); // no parent_id in app_metadata

    const result = await customAccessTokenHook(event);

    // The hook MUST NOT add a parent_id claim when app_metadata.parent_id
    // is absent (per scenario "Child JWT has no parent_id claim").
    assertEquals(
      result.parent_id,
      undefined,
      "Hook must omit parent_id claim when app_metadata.parent_id is missing",
    );
    // device_id injection still works.
    assertEquals(
      result.device_id,
      DEVICE_UUID,
      "Hook must still inject device_id when app_metadata.parent_id is missing",
    );
  },
});

Deno.test({
  name:
    "A.1.5b — emptyAppMetadata returns original claims without parent_id claim",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    // app_metadata is present but empty (no parent_id, no device_id).
    const event = {
      type: "verify-jwt" as const,
      event: {
        jwt: {
          sub: "user-sub-uuid",
          role: "authenticated",
          aud: "authenticated",
          exp: Math.floor(Date.now() / 1000) + 3600,
          iat: Math.floor(Date.now() / 1000),
          app_metadata: {
            provider: "email",
            providers: ["email"],
          },
        },
        claims: {
          role: "authenticated",
          sub: "user-sub-uuid",
        },
      },
    };

    const result = await customAccessTokenHook(event);

    assertEquals(
      result.parent_id,
      undefined,
      "Empty app_metadata must NOT produce a parent_id claim",
    );
    assertEquals(
      result.device_id,
      undefined,
      "Empty app_metadata must NOT produce a device_id claim",
    );
    // Original claims are preserved.
    assertEquals(
      result.role,
      "authenticated",
      "Original claims must be preserved",
    );
  },
});