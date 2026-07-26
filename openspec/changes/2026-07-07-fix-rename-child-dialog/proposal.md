# Proposal: fix-rename-child-dialog

> GREENFIELD feature proposal (NOT bug-fix, NOT chained). Mirrors `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` format: same single-PR shape, same ~280 LoC budget, same explicit "RED tests are the acceptance contract" framing. The 8 RED tests at `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialogTest.kt` are pre-existing compile-time failures (`Unresolved reference RenameChildDialog × 8`); the apply phase flips them GREEN without deleting them. Decisions cited: Q1=m, Q2=h, Q3=m, Q4=p, Q5=d, Q6=defaults — full text in engram #294.

## Why

The Q2 chain (`feat-multi-child-picker`, archived 2026-07-07 at `7f20f05`) shipped the data layer (children table + RLS, `Child` model, `child_id`/`child_first_name` wire fields), the picker UI (`ChildPickerChips` + `selectedChildId` filter), the backfill migration, and the mock fixtures — but explicitly **deferred the rename UI** itself. The deferral is documented in the Q2 chain's design at `archive/2026-07-06-feat-multi-child-picker/design.md:69-70` ("`RenameChildDialog` (NEW composable, Material 3 Dialog `usePlatformDefaultWidth` = false — see §B.6 form-factor recommendation)") and §B.6 (`design.md:448-460` — full Material 3 modal specification, form-factor decided as "modal" recommended option).

The follow-up was understood as "upgrade the existing `NameChildDialog` placeholder", but the explore confirmed there is **NO placeholder** on master @ `9b57669`:

- `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/NameChildDialog.kt` — does NOT exist.
- `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialog.kt` — does NOT exist.
- `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` — has NO `renameChild(...)` method (last full read confirms only `getDevices`, `getPendingRequests`, `approveRequest`, `denyRequest`, `createPairingCode`, `lockDevice`, `unlockDevice`, `getUsageStats`, `getDeviceHealth`, `getTemplates`, `applyTemplate`, `grantReward`).
- `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` — has NO `renameChildState` (only `selectedChildId` and `_devices` / `_pendingRequests` / `_approvalResult` / `_pairingCode` / `_isLoading` / `_error`).
- `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt` — has NO rename trigger anywhere (no `ChildPickerChips` long-press handler, no `DeviceCard` overflow menu — the `DeviceCard` composable at `DeviceComponents.kt:28-77` renders device state without any menu affordance today).
- Only stale **KDoc references** survive: `Models.kt:38`, `MockSupabaseEngine.kt:63/80/247` all mention `RenameChildDialog` as if it exists, but the file is absent.

This change delivers the **full Material 3 modal** that design §B.6 originally specified: a `Dialog(usePlatformDefaultWidth = false)` containing one `OutlinedTextField` ("Nombre del niño") + Cancel + Guardar buttons, with the Q4=pessimistic rename flow (await server, surface success or inline error). GREENFIELD — no existing placeholder to migrate, no existing trigger to wire around, no existing state to reconcile.

## What changes

Single PR, ~280 LoC total (estimate from engram #294's implications breakdown):

1. **NEW `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialog.kt`** (~120 LoC). Stateless Material 3 full-screen `Dialog` (`DialogProperties(usePlatformDefaultWidth = false)`) with the exact signature the 8 RED tests at `RenameChildDialogTest.kt:109-303` pin:

   ```kotlin
   @Composable
   fun RenameChildDialog(
       initialName: String,
       isLoading: Boolean,
       errorMessage: String?,
       onConfirm: (String) -> Unit,
       onDismiss: () -> Unit
   )
   ```

   Layout per design §B.6 (modal recommendation):
   - Single `OutlinedTextField` labelled "Nombre del niño" (Spanish UI copy per Q6=defaults). testTag: **`rename_child_text_field`** (locked by `RenameChildDialogTest.kt:297` — the apply phase MUST use this exact selector, not `rename_dialog_input` as initially drafted elsewhere). `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)` so IME Done triggers Guardar.
   - Client-side validation (per `RenameChildDialogTest.kt:131-196` — three cases pin this): empty-after-trim → inline error "El nombre no puede estar vacío"; whitespace-only → same error; length > 32 → inline error "Máximo 32 caracteres" (matches Supabase `CHECK length 1-32` on `children.first_name` at `005_children_table.sql` per Q2 chain's archive).
   - "Guardar" `Button` — testTag **`rename_child_save_button`** (`RenameChildDialogTest.kt:146, 195, 217, 298, 299`). Disabled when validation fails or `isLoading = true`. Inside the button: a `CircularProgressIndicator` with testTag **`rename_child_loading_indicator`** (`RenameChildDialogTest.kt:219`) shown only while `isLoading`.
   - "Cancelar" `TextButton` — testTag **`rename_child_cancel_button`** (`RenameChildDialogTest.kt:218, 269`). Disabled when `isLoading`.
   - Inline error label — testTag **`rename_child_error_text`** (`RenameChildDialogTest.kt:168, 194`). Displays validation errors above the buttons, OR a server error passed via `errorMessage` (per `RenameChildDialogTest.kt:228-244` — Spanish example "Ya existe un niño con ese nombre").
   - Dialog root — testTag **`rename_child_dialog`** (`RenameChildDialogTest.kt:123, 170, 243`).

   Stateless: the dialog holds only local `mutableStateOf(initialName)` for the text field. All state transitions live in `ParentViewModel.renameChildState`. This matches Q2=h.

2. **MODIFIED `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt`** (~20 LoC). Add:

   ```kotlin
   suspend fun renameChild(childId: String, newName: String): Result<Unit> =
       withContext(Dispatchers.IO) {
           try {
               val token = authManager.getAccessToken()
                   ?: return@withContext Result.failure(DeviceListError.AuthMissing)
               val httpResponse = clientProvider.httpClient.request(
                   "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/children?id=eq.$childId"
               ) {
                   method = HttpMethod.Patch
                   header("Authorization", "Bearer $token")
                   header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                   contentType(ContentType.Application.Json)
                   setBody("""{"first_name":"${escape(newName)}"}""")
               }
               if (!httpResponse.status.isSuccess()) {
                   return@withContext Result.failure(
                       DeviceListError.Transient("HTTP ${httpResponse.status}")
                   )
               }
               Result.success(Unit)
           } catch (e: Exception) {
               Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
           }
       }
   ```

   Mirrors the existing `getDevices()` / `denyRequest()` HTTP pattern. PATCH to `/rest/v1/children?id=eq.{childId}` per design §A.8 + Q3=m. RLS-guarded by `children_parent_update` (`parent_id = auth.uid()`). On 409 (UNIQUE conflict on `(parent_id, first_name)` per Q2 chain's `005_children_table.sql`), the error is surfaced via `Result.failure(Transient(...))` and the dialog shows the errorMessage passed by the VM.

3. **MODIFIED `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt`** (~15 LoC). Per Q3=m, the mock engine mutates an in-memory `MutableStateFlow<List<ChildFixture>>` on PATCH and echoes 200. Adds:
   - `private val childrenState = MutableStateFlow(children())` seeded from the existing `children.json` fixture.
   - `request.url.encodedPath.endsWith("/rest/v1/children") && request.method == HttpMethod.Patch` branch in the `MockEngine` lambda: parses the `first_name` from the body, updates the matching `ChildFixture` in `childrenState`, responds with 200 + the updated row JSON. PATCH with `id=eq.{childId}` filter is dispatched at `request.url.encodedQuery` parse.
   - A `fun currentChildren(): List<ChildFixture> = childrenState.value` accessor so tests can verify the in-memory mutate end-to-end (per Q3=m — "tests can verify the rename persisted, not just that the PATCH was called").

   Does NOT touch `path.endsWith("/rest/v1/children")` GET behavior (already served at `MockSupabaseEngine.kt:106-107`).

4. **MODIFIED `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt`** (~50 LoC). Per Q2=h (hoisted StateFlow):

   ```kotlin
   // Sealed UI state for the rename dialog. The dashboard composable
   // pattern-matches on this; the dialog composes itself from the
   // appropriate fields.
   sealed interface RenameChildState {
       data object Hidden : RenameChildState
       data class Editing(val childId: String, val currentName: String) : RenameChildState
       data class Saving(val childId: String) : RenameChildState
       data class Saved(val childId: String) : RenameChildState   // Auto-dismiss after 1.5s
       data class Failed(val childId: String, val error: String) : RenameChildState
   }

   private val _renameChildState = MutableStateFlow<RenameChildState>(RenameChildState.Hidden)
   val renameChildState: StateFlow<RenameChildState> = _renameChildState.asStateFlow()

   fun requestRename(childId: String, currentName: String) {
       _renameChildState.value = RenameChildState.Editing(childId, currentName)
   }

   fun confirmRename(newName: String) {
       val editing = _renameChildState.value as? RenameChildState.Editing ?: return
       _renameChildState.value = RenameChildState.Saving(editing.childId)
       viewModelScope.launch {
           val result = repository.renameChild(editing.childId, newName.trim())
           _renameChildState.value = if (result.isSuccess) {
               RenameChildState.Saved(editing.childId).also {
                   loadDevices()  // Refresh dashboard per V1 contract.
                   viewModelScope.launch {
                       delay(1500)  // Brief confirmation window.
                       if (_renameChildState.value == it) _renameChildState.value = RenameChildState.Hidden
                   }
               }
           } else {
               RenameChildState.Failed(
                   childId = editing.childId,
                   error = result.exceptionOrNull()?.message ?: "Error al renombrar"
               )
           }
       }
   }

   fun dismissRename() { _renameChildState.value = RenameChildState.Hidden }
   ```

   The `Saved` state's 1.5s confirmation window auto-dismisses via the inner `delay` — the dashboard shows the chip/picker with the new name immediately (because `loadDevices()` fires inside the success branch), then the dialog fades 1.5s later. This is a refinement over the GREENFIELD default and keeps the parent from needing a second tap to acknowledge.

5. **MODIFIED `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt`** (~30 LoC). Per Q1=m (manual trigger only):
   - **`ChildPickerChips` long-press**: pass `onLongPress = { childId -> viewModel.requestRename(childId, currentName = it) }` to the chip row. Material 3 `FilterChip` supports `combinedClickable` with `onLongClick` via `Modifier.combinedClickable`. testTag: **`rename_dialog_chip_long_press_${childId}`** (locked by future DashboardScreenTest cases the apply phase adds).
   - **`DeviceCard` overflow menu** (`Más opciones`): add a Material 3 `DropdownMenu` with one item "Renombrar niño" → `viewModel.requestRename(device.child!!.id, device.child!!.firstName)`. The menu trigger is an `IconButton(Icons.Default.MoreVert)` inside the card. testTag: **`rename_dialog_device_overflow_${deviceId}`**.
   - **Dialog render**: at the bottom of `DashboardScaffold` (alongside `if (showPairingSheet) PairingBottomSheet(...)`):

     ```kotlin
     val renameState by viewModel.renameChildState.collectAsState()
     when (val rs = renameState) {
         is RenameChildState.Editing -> RenameChildDialog(
             initialName = rs.currentName,
             isLoading = false,
             errorMessage = null,
             onConfirm = { newName -> viewModel.confirmRename(newName) },
             onDismiss = { viewModel.dismissRename() }
         )
         is RenameChildState.Saving -> RenameChildDialog(
             initialName = /* re-derive from devices cache */,
             isLoading = true,
             errorMessage = null,
             onConfirm = {},
             onDismiss = {}  // Cancel disabled while loading.
         )
         is RenameChildState.Failed -> RenameChildDialog(
             initialName = /* re-derive */,
             isLoading = false,
             errorMessage = rs.error,
             onConfirm = { newName -> viewModel.confirmRename(newName) },
             onDismiss = { viewModel.dismissRename() }
         )
         RenameChildState.Hidden, is RenameChildState.Saved -> Unit  // No dialog.
     }
     ```

   `Editing` / `Saving` / `Failed` carry the `childId` so the dashboard can re-derive `initialName` from `_devices.value.firstOrNull { it.child?.id == rs.childId }?.child?.firstName ?: ""` if the dialog needs to re-show with the current name after a failed save.

## Capabilities

- **New**: none at the spec level. The `parent-device-list` spec at `openspec/specs/parent-device-list/spec.md` is silent on child rename — the GREENFIELD feature is the surface, and the 8 RED tests are the contract.
- **Modified**: none. Per Q5=d (defer spec delta) and the precedent set by `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md:26` ("spec delta deferred unless the user wants it formalized"). The 8 RED tests are the acceptance contract; formalizing the spec is a follow-up if the user wants it.

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `ui/parent/components/RenameChildDialog.kt` | New | Material 3 full-screen `Dialog` per §B.6 (~120 LoC). |
| `data/repository/ParentRepository.kt` | Modified | Add `renameChild(childId, newName): Result<Unit>` (~20 LoC). |
| `data/remote/MockSupabaseEngine.kt` | Modified | PATCH handler for `/rest/v1/children` with in-memory `MutableStateFlow<List<ChildFixture>>` mutate (~15 LoC). |
| `viewmodel/ParentViewModel.kt` | Modified | Add `RenameChildState` sealed UI state + `renameChildState: StateFlow` + `requestRename` / `confirmRename` / `dismissRename` intents (~50 LoC). |
| `ui/parent/screens/DashboardScreen.kt` | Modified | Wire `ChildPickerChips` long-press + `DeviceCard` overflow menu → `vm.requestRename(...)`; render `RenameChildDialog` from `renameChildState` (~30 LoC). |
| `ui/parent/components/DeviceComponents.kt` | Modified | Add `onLongPress` parameter to `ChildPickerChips`; add `MoreVert` overflow menu to `DeviceCard` with "Renombrar niño" item (~30 LoC). |
| `test/.../RenameChildDialogTest.kt` | RED → GREEN | 8 existing cases flip. |
| `test/.../ParentRepositoryRenameTest.kt` (new) | New | 2-3 cases: happy path PATCH 200, 409 conflict → `Transient`, network exception → `Transient` (~50 LoC). |
| `test/.../MockSupabaseEngineRenameTest.kt` (new) | New | 2 cases: PATCH mutates `childrenState`, PATCH with unknown id is a 404 (~30 LoC). |
| `test/.../ParentViewModelRenameTest.kt` (new) | New | 4 cases: `Editing` initial state, `confirmRename` happy path `Saved → Hidden`, `confirmRename` failed path `Failed`, `dismissRename` always works (~60 LoC). |
| `openspec/specs/parent-device-list/spec.md` | Unchanged (deferred) | Per Q5=d. |

## Impact

- **User-facing**: parent can rename a child via either (a) long-pressing a chip in the `ChildPickerChips` row, or (b) tapping the new `MoreVert` overflow menu on a `DeviceCard` → "Renombrar niño". Both flows open the same Material 3 modal. The modal pre-populates the child's current name, validates input (non-empty after trim, ≤ 32 chars), shows a spinner during the PATCH, surfaces a Spanish error inline on failure, and auto-dismisses 1.5s after success. The `Solicitudes` and `Devices` tabs refresh on success because `confirmRename` calls `loadDevices()`.
- **Pessimistic rename** (per Q4=p): the parent sees a loading spinner inside the Guardar button until the server round-trip completes. The dialog stays open during the in-flight window. No optimistic UI updates — server is the single source of truth, consistent with the project's Supabase pattern (`approve-request`, `deny-request`, `create-pairing-code` all use the same pessimistic-Result-shape).
- **Performance**: one extra PATCH per rename (`POST → /rest/v1/children?id=eq.{childId}` body `{"first_name":"..."}`). No new GET — the post-success `loadDevices()` re-uses the existing path. No new cached state.
- **Migration**: none. The `children` table already exists from the Q2 chain. No new fixtures required (the mock engine mutates the existing `children.json` in-memory per Q3=m).
- **DI/Hilt/DB/Compose/nav**: zero change to module structure. `ParentViewModel`'s constructor stays the same. No new Hilt module. No nav graph change (the rename lives inside `DashboardScaffold`'s `when (renameState)` branch, alongside `PairingBottomSheet`).
- **Locale**: all UI copy in Spanish per Q6=defaults ("Guardar", "Cancelar", "Nombre del niño", "Renombrar niño", "Error al renombrar", "El nombre no puede estar vacío", "Máximo 32 caracteres").

## Spec Changes

Per Q5=d (defer spec delta):

- **Likely defer**: `openspec/specs/parent-device-list/spec.md` does NOT currently document a rename affordance. Adding one would be a paper-thin spec delta (single `## Rename` section under "Parent Capabilities"). The 8 RED tests at `RenameChildDialogTest.kt` pin the same contract more precisely than a spec could. Per the precedent at `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md:80` (open question #1 deferred the `time-request-approval/spec.md` cold-start hydration delta), this change follows the same pattern.
- **Spec delta is a follow-up** if the user wants the affordance formalized. Trigger: sdd-spec phase writes the delta; otherwise sdd-tasks moves forward with the 8 RED tests as the only contract.

## Test Acceptance

The 8 RED tests at `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialogTest.kt:108-303` are the acceptance contract. All 8 must turn GREEN with the apply-phase implementation. Test catalog (verbatim from the file):

| # | Line | Test | Pins |
|---|---|---|---|
| 1 | 109 | `renders_dialog_with_initial_name_prepopulated` | testTag `rename_child_dialog` exists + initial name rendered. |
| 2 | 132 | `empty_name_disables_save_button` | Save button disabled when name empty post-trim. |
| 3 | 154 | `whitespace_only_name_shows_inline_error` | testTag `rename_child_error_text` shown + dialog stays open. |
| 4 | 179 | `name_exceeding_max_length_is_rejected` | > 32 chars → inline error + Save disabled. |
| 5 | 203 | `loading_state_disables_buttons_and_shows_spinner` | testTag `rename_child_loading_indicator` + Save/Cancel disabled. |
| 6 | 228 | `server_error_surfaces_inline_and_keeps_dialog_open` | `errorMessage = "Ya existe un niño con ese nombre"` rendered inline + dialog open. |
| 7 | 253 | `cancel_dismisses_without_invoking_confirm` | Cancel → `onDismiss` exactly once, `onConfirm` zero times. |
| 8 | 282 | `save_with_valid_name_invokes_confirm` | Text input + Save click → `onConfirm` exactly once. |

Plus **new apply-phase tests** (per "What changes" §1 + §3 + §4):

- `ParentRepositoryRenameTest.kt` — 2-3 cases: happy path, 409 conflict → `Transient`, network exception → `Transient`.
- `MockSupabaseEngineRenameTest.kt` — 2 cases: PATCH mutates `childrenState`, PATCH with unknown id returns 404.
- `ParentViewModelRenameTest.kt` — 4 cases: `Editing` initial state, `confirmRename` happy path `Saved → Hidden`, `confirmRename` failed path `Failed`, `dismissRename` always works.

**Locked testTags** (the RED tests assert these exact strings — apply phase MUST match):

- `rename_child_dialog` — Dialog root (Material 3 `Dialog` itself).
- `rename_child_text_field` — `OutlinedTextField` for the name input.
- `rename_child_save_button` — "Guardar" `Button`.
- `rename_child_cancel_button` — "Cancelar" `TextButton`.
- `rename_child_error_text` — inline validation / server error `Text`.
- `rename_child_loading_indicator` — `CircularProgressIndicator` inside the Save button.

(Note: the initial proposal draft used `rename_dialog_input` / `rename_dialog_save` / `rename_dialog_cancel` — the apply phase MUST use the `rename_child_*` prefix to match the locked RED-test selectors at `RenameChildDialogTest.kt:72-77`. This is a pre-implementation correction, not a spec change.)

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Dialog re-shows stale name after `Editing → Failed` transition | Med | `DashboardScaffold` re-derives `initialName` from `_devices.value` on every state change (see "What changes" §5 `when (val rs = renameState)` block). RED test #6 (`server_error_surfaces_inline_and_keeps_dialog_open`) pins the failure-mode behavior. |
| `Saved` state's 1.5s `delay` races with a fresh `requestRename` | Low | The inner `delay`'s dismissal guards on `_renameChildState.value == it` (the Saved snapshot) — a fresh `Editing` request replaces the snapshot, so the old dismissal no-ops. |
| `DeviceCard` overflow menu competes with the existing card click target | Low | Material 3 `DropdownMenu` swallows taps in its scrim. The card's primary `onClick` is unaffected because the menu's anchor is a separate `IconButton`. Manual UI smoke test in apply. |
| Mock engine PATCH parse breaks on real Supabase response shape | Low | The mock engine returns the updated row JSON; the production path ignores the response body (only checks `status.isSuccess()`). The `currentChildren()` accessor is test-only. |
| `Result<Unit>` from `renameChild` makes the VM bridge awkward | Low | Pattern matches `denyRequest`'s `Result<Boolean>` return — same `result.isSuccess` check, just no payload to forward. |
| `RenameChildState.Saved` + auto-dismiss feels "magic" to users | Low | Visual confirmation: the chip name updates on `loadDevices()` BEFORE the dialog dismisses, so the parent sees the change land. The 1.5s window is short enough to feel responsive. If users complain, replace with explicit "Listo" button (follow-up). |

## Rollback

Single PR. `git revert` of the 5 modified files (1 new + 4 modified) restores the pre-change state. No schema migration (Q2 chain already shipped the `children` table), no feature flag, no data loss. The `RenameChildDialog.kt` file is the only production-side net-new artifact; removing it removes all UX surface.

## Out of scope

- **Data-driven auto-trigger** (per Q1=m): the dialog does NOT auto-open when a device with `child_id = NULL` is paired (design §B.6 originally hinted at this; rejected as "accidental UX"). A future polish change can add a banner + auto-open if parents request it.
- **Top-level rename screen** (design §B.6 option "top"): adding a full-screen route requires a navigation graph change. The modal suffices for V1.
- **Deleting a child** from the parent UI (Q2 chain's archive §"Out of scope"). RLS permits it; UI affordance deferred.
- **Renaming from the `PairingBottomSheet`** (the original "post-dismiss name this child" flow at design §A.7 — note that Q2's apply phase did NOT implement that capture either, so today the parent names the child at the server-side `pairing` edge function via the `child_first_name` field). This change covers renames only; initial naming stays server-side.
- **`ChildRepository`** (the unrelated child-side class at `child-side` package): no rename hooks there; rename is parent-only.

## Success criteria

- [ ] RED baseline: `RenameChildDialogTest.kt` 8 cases fail to compile on master @ `9b57669` (`Unresolved reference RenameChildDialog` × 8).
- [ ] GREEN: same 8 cases pass after the apply phase.
- [ ] New `ParentRepositoryRenameTest.kt` 2-3 cases pass.
- [ ] New `MockSupabaseEngineRenameTest.kt` 2 cases pass.
- [ ] New `ParentViewModelRenameTest.kt` 4 cases pass.
- [ ] `./gradlew :app:assembleDebug` green.
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the 5 touched files.
- [ ] Manual smoke test on real device: long-press chip → dialog opens with name pre-populated → save → chip name updates + dialog auto-dismisses in 1.5s.
- [ ] Manual smoke test on real device: `DeviceCard` overflow → "Renombrar niño" → dialog opens → save with too-long name → inline error appears.

## Open questions

1. **`Saved` state's auto-dismiss delay (currently 1.5s)** — is 1.5s the right window? Shorter feels abrupt, longer feels sluggish. Defer to manual smoke test; tweakable in 1 line at `ParentViewModel.confirmRename`.
2. **`Renombrar niño` overflow menu item placement** — should it live under `Más opciones` or as a separate `IconButton(Icons.Default.Edit)` on the card? Current proposal uses an overflow menu to keep the card visually clean; alternative is a dedicated edit button. Defer to manual UI review.
3. **Confirmation copy after rename** — should `Saved` show a brief snackbar ("Renombrado a Lucas") OR rely on the chip-name update alone? Current proposal relies on the chip update; snackbar is a 5-line follow-up if needed.

## References

- **Decisions**: engram **#294** `sdd/fix-rename-child-dialog/decisions` — (m) manual trigger, (h) hoisted StateFlow, (m) MockSupabaseEngine mutate, (p) pessimistic, (d) defer spec, defaults.
- **Explore**: engram `sdd/fix-rename-child-dialog/explore` — confirms GREENFIELD (no `NameChildDialog.kt` / `RenameChildDialog.kt` exists; only stale KDoc references in `Models.kt:38`, `MockSupabaseEngine.kt:63/80/247`).
- **RED tests (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialogTest.kt:108-303` — 8 cases at lines 109, 132, 154, 179, 203, 228, 253, 282.
- **Design source (§B.6 — the original spec)**: `openspec/changes/archive/2026-07-06-feat-multi-child-picker/design.md:448-460` — Material 3 full-screen `Dialog` recommendation, validation rules, cancel/guardar contract.
- **Q2 chain archive (deferral context)**: `openspec/changes/archive/2026-07-06-feat-multi-child-picker/archive-report.md:67-70` — explicit "Deferred from this PR" note.
- **Wire-shape precedents**: `ParentRepository.kt:555-594` (`approveRequest` HTTP pattern), `ParentRepository.kt:608-637` (`denyRequest` HTTP pattern) — the new `renameChild` mirrors both.
- **Mock engine precedent**: `MockSupabaseEngine.kt:84-143` (the `MockEngine` routing block) — the new PATCH branch slots in at the same level.
- **VM StateFlow precedent**: `ParentViewModel.kt:103-104` (`_approvalResult` + `approvalResult`) — the new `renameChildState` mirrors this one-shot sealed-Result pattern.
- **Compose Dialog precedent**: `PairingBottomSheet` at `ui/parent/components/DeviceComponents.kt:363` — same modal-surface pattern, same `usePlatformDefaultWidth = false` form-factor.
- **Format precedent**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (same single-PR ~280 LoC budget, same "RED tests are the acceptance contract" framing, same deferral-open-question style).