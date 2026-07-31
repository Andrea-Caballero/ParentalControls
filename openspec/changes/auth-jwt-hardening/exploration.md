# Exploration: auth-jwt-hardening

### Current State
- Android starts with a synthetic anonymous Supabase session (`POST /auth/v1/signup`), stores `access_token`/`refresh_token` in an AES/GCM + Android Keystore blob, and refreshes via `POST /auth/v1/token?grant_type=refresh_token` on startup and after pairing.
- Parent auth uses Supabase magic-link OTP: `POST /auth/v1/magiclink` to send, then `POST /auth/v1/verify?type=magiclink` from the deep-link callback, persisting an encrypted parent session plus cleartext `role`/`parent_id` routing hints.
- Privileged Edge Functions mostly validate Bearer JWTs through a shared `verifyAuth()` helper that calls `supabase.auth.getUser(token)`; it returns `auth.uid()` as `parentId` and reads `device_id` from verified `app_metadata` (not raw JWT claims).
- `custom-access-token-hook` sanitizes claims and re-derives top-level `device_id` from `app_metadata.device_id`, rejecting malformed/forged events.

### Affected Areas
- `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` — session creation, storage, refresh, pairing, magic-link verify.
- `app/src/main/java/com/tudominio/parentalcontrol/auth/MagicLinkDeepLinkHandler.kt` — deep-link handoff into OTP verification.
- `app/src/main/java/com/tudominio/parentalcontrol/network/SupabaseClientProvider.kt` / `DeviceAuthService.kt` — boot/refresh path and token-backed backend calls.
- `supabase/functions/_shared/jwt.ts` — shared JWT verification boundary.
- `supabase/functions/{approve-request,reward,get-policy,heartbeat,register-token,set-device-state,get-devices-for-parent,create-pairing-code,pairing,verify-integrity}.ts` — auth-gated endpoints and pairing path.
- `supabase/functions/custom-access-token-hook/index.ts` — claim hardening.

### Approaches
1. **Backend-first hardening** — normalize all privileged functions onto the shared verification helper and tighten any remaining direct `auth.getUser` / claim-trust paths.
   - Pros: hardens the trust boundary first; protects every client, not just Android.
   - Cons: larger blast radius across Edge Functions.
   - Effort: Medium

2. **Android-client hardening first** — reduce token exposure and tighten session restore/refresh/storage behavior.
   - Pros: smaller initial slice; directly improves local token handling.
   - Cons: leaves backend trust surface untouched.
   - Effort: Medium

3. **Both, in a thin vertical slice** — one backend verification slice plus one Android token-handling slice.
   - Pros: closes both ends of the trust chain; best match for end-to-end auth hardening.
   - Cons: broader than a single-file fix.
   - Effort: High

### Recommendation
Do **both**, but sequence **backend verification first**. The backend is the actual security boundary, and the current code already has a shared verifier plus claim-sanitizing hook; the next slice should standardize that boundary, then align Android session handling to it.

### Risks
- Mixed auth patterns still exist (`verifyAuth` vs inline `auth.getUser`), so a partial slice can leave an inconsistent trust model.
- Cleartext preference keys (`role`, `parent_id`, `device_id`, `is_paired`) are routing hints, not security boundaries; future code must not treat them as authoritative.
- Deep-link OTP handling is syntactic only on-device; all real trust is deferred to Supabase verification.

### Ready for Proposal
Yes — propose a thin vertical slice, starting with backend verification hardening, then Android session/token handling if scope allows.
