# Spec: pairing-flow

## Purpose
Establishes a trust link between a parent and a child device: the parent generates a short pairing code (with a scannable QR), the child enters or scans the code, and a `devices` row is created linking the child to the parent's authenticated user.

## ADDED Requirements

### Requirement: Parent generates pairing code and QR
The parent app SHALL call the `create-pairing-code` Supabase edge function and SHALL present the returned 8-character code together with a QR code rendered from the `parentalcontrol://pair?code=...` deeplink.

#### Scenario: Code and QR render in PairingBottomSheet
- **WHEN** the parent taps "Generate code" in `ui/parent/components/DeviceComponents.kt` (around lines 445-508),
- **THEN** the app invokes `POST /functions/v1/create-pairing-code` and displays an 8-char `code` plus a scannable QR rendered via `io.github.alexzhirkevich:qrose`.

#### Scenario: QR encodes the same deeplink as the function response
- **WHEN** the edge function returns a deeplink payload,
- **THEN** the QR encodes exactly the string `parentalcontrol://pair?code=<code>` where `<code>` is the same 8-char value returned in the JSON body.

#### Scenario: Expired code shows regenerate
- **WHEN** the code is older than 15 minutes (or the server reports it as expired),
- **THEN** the parent UI SHALL show an "expired" state with a "Regenerate" action that re-invokes the edge function.

### Requirement: Child completes pairing via code or QR scan
The child app SHALL accept the 8-character code either by manual entry or by scanning the QR (CameraX + ML Kit), and SHALL submit it to the `pairing` Supabase edge function.

#### Scenario: Manual code entry posts to pairing edge function
- **WHEN** the child types an 8-char code in `PairingManager.pairWithCode` and taps "Pair",
- **THEN** the app SHALL `POST /functions/v1/pairing` with `{ "code": "..." }` and the child's authenticated bearer token.

#### Scenario: Deeplink pre-fills the code
- **WHEN** the child opens `parentalcontrol://pair?code=XYZ12345` from a scanned QR,
- **THEN** the deeplink intent filter in `AndroidManifest.xml` SHALL route the child to the pairing screen with the `code` query parameter pre-filled and the "Pair" button enabled.

#### Scenario: Success creates a devices row linked to the parent
- **WHEN** the `pairing` edge function responds with a `device_id`,
- **THEN** the child app SHALL persist it locally and the `devices` row in Supabase SHALL have `parent_id` set to the parent's `auth.uid()`.

### Requirement: Pairing code is single-use and time-bounded
Each pairing code SHALL be consumable at most once and SHALL expire 15 minutes after creation (per the default in `pairing_codes.expires_at`).

#### Scenario: Reused code is rejected
- **WHEN** the same code is submitted twice within its validity window,
- **THEN** the second submission SHALL be rejected with a "code already used" error and no second `devices` row SHALL be created (the existing `cleanup_expired_pairing_codes` job keeps `pairing_codes.status` consistent).

#### Scenario: Expired code is rejected
- **WHEN** a code is submitted more than 15 minutes after `created_at`,
- **THEN** the edge function SHALL return a `code expired` error and the child UI SHALL display a clear "Code expired, ask parent for a new one" message.

## Out of scope
- Sending FCM to the parent on successful pairing (existing TODO at `pairing/index.ts:237`, deferred to a follow-up).
- Parent-side FCM token registration against `device_push_tokens` (separate follow-up).
- Unpairing / re-pairing flows.
- Server-side rate limiting on `create-pairing-code` (assumed handled at the edge function).
