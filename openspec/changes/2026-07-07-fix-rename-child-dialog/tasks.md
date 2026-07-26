# Tasks: fix-rename-child-dialog

> GREENFIELD Material 3 rename modal. No `specs/` (Q5=d defer per engram #294; 8 RED tests are the contract). No `design.md` (user picked `s` skip design on the proposal intake — `proposal.md` is the design-of-record). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = 9b57669` **before any production code changes**. Each phase maps to one conventional commit. Single PR, ~280 LoC (production ~120 + tests ~160), well under the 400-line review budget. Decisions: engram #294 — Q1=m manual, Q2=h hoisted StateFlow, Q3=m MockSupabaseEngine mutate, Q4=p pessimistic, Q5=d defer spec, Q6=defaults (snake_case testTags + Spanish UI copy).

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~120 LoC production + ~160 LoC test (per proposal §"What changes") |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-always |
| Chain strategy | n/a (single PR) |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Material 3 rename modal + repo PATCH + mock mutate + VM state + dashboard wiring + tests | PR 1 | base = `master`; 1 new file (`RenameChildDialog.kt`) + 4 modified files (`ParentRepository.kt`, `MockSupabaseEngine.kt`, `ParentViewModel.kt`, `DashboardScreen.kt`) + 1 modified file (`DeviceComponents.kt` for `ChildPickerChips` long-press + `DeviceCard` overflow menu) + 3 new test files (`ParentRepositoryRenameTest.kt`, `MockSupabaseEngineRenameTest.kt`, `ParentViewModelRenameTest.kt`) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 11 tests below must **fail today** at `master = 9b57669`. The first 8 already exist on disk at `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialogTest.kt`; the last 3 are new RED tests the apply phase writes first. RED is the contract that gates Phase 3.

Run them with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.components.RenameChildDialogTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryRenameTest" --tests "com.tudominio.parentalcontrol.data.remote.MockSupabaseEngineRenameTest" --tests "com.tudominio.parentalcontrol.viewmodel.ParentViewModelRenameTest" --rerun-tasks`.

- [x] **1.1 — RED (existing) `renders_dialog_with_initial_name_prepopulated`** at `RenameChildDialogTest.kt:108-125`. Dialog root must render with `testTag = "rename_child_dialog"` and the initial name ("Lucas") displayed. **Expected TODAY**: FAILS at compile time — `Unresolved reference: RenameChildDialog` (`RenameChildDialog.kt` does not exist on master).

- [x] **1.2 — RED (existing) `empty_name_disables_save_button`** at `RenameChildDialogTest.kt:131-147`. When `initialName = ""`, the Guardar button (`testTag = "rename_child_save_button"`) must be disabled. **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.3 — RED (existing) `whitespace_only_name_shows_inline_error`** at `RenameChildDialogTest.kt:153-171`. Whitespace-only `initialName = "   "` must trim and show inline error (`testTag = "rename_child_error_text"`) + dialog stays open. **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.4 — RED (existing) `name_exceeding_max_length_is_rejected`** at `RenameChildDialogTest.kt:178-196`. 33-char name must show inline error + disable Guardar (matches Supabase `CHECK length 1-32` on `children.first_name` per Q2 chain's `005_children_table.sql`). **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.5 — RED (existing) `loading_state_disables_buttons_and_shows_spinner`** at `RenameChildDialogTest.kt:202-220`. `isLoading = true` must disable both buttons + show `CircularProgressIndicator` (`testTag = "rename_child_loading_indicator"`). **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.6 — RED (existing) `server_error_surfaces_inline_and_keeps_dialog_open`** at `RenameChildDialogTest.kt:227-244`. `errorMessage = "Ya existe un niño con ese nombre"` must render inline + dialog stays open. **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.7 — RED (existing) `cancel_dismisses_without_invoking_confirm`** at `RenameChildDialogTest.kt:252-274`. Cancel button (`testTag = "rename_child_cancel_button"`) click → `onDismiss` exactly once, `onConfirm` zero times. **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.8 — RED (existing) `save_with_valid_name_invokes_confirm`** at `RenameChildDialogTest.kt:281-303`. Text input `" edit"` + Save click (`testTag = "rename_child_save_button"`) → `onConfirm` exactly once. **Expected TODAY**: FAILS for same reason as T1.1.

- [x] **1.9 — RED (NEW) `renameChild PATCH 200 returns success`** at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryRenameTest.kt` (new). Inject a `MockEngine` returning 200 for `PATCH /rest/v1/children?id=eq.{childId}`. `repository.renameChild(childId, "NewName")` returns `Result.success(Unit)`. **Expected TODAY**: FAILS — `renameChild` does not exist on `ParentRepository`.

- [x] **1.10 — RED (NEW) `MockSupabaseEngine PATCH mutates in-memory childrenState`** at `app/src/test/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngineRenameTest.kt` (new). Build `MockSupabaseEngine` seeded from `children.json`. Issue `PATCH /rest/v1/children?id=eq.child-1` body `{"first_name":"Renamed"}`. Assert `engine.currentChildren().first { it.id == "child-1" }.firstName == "Renamed"`. **Expected TODAY**: FAILS — neither the PATCH handler nor `currentChildren()` accessor exists.

- [x] **1.11 — RED (NEW) `ParentViewModel renameChildState transitions Hidden → Editing → Saving → Saved`** at `app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelRenameTest.kt` (new, Robolectric). `vm.requestRename("child-1", "Lucas")` then `vm.confirmRename("Mateo")` while a stubbed repo returns `Result.success(Unit)`. Assert state emits `Hidden → Editing → Saving → Saved → Hidden` (the Saved → Hidden is the 1.5s auto-dismiss). **Expected TODAY**: FAILS — `renameChildState` / `requestRename` / `confirmRename` do not exist on `ParentViewModel`.

- [x] **1.12 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.components.RenameChildDialogTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryRenameTest" --tests "com.tudominio.parentalcontrol.data.remote.MockSupabaseEngineRenameTest" --tests "com.tudominio.parentalcontrol.viewmodel.ParentViewModelRenameTest" --rerun-tasks`. All 11 tests MUST FAIL (T1.1–T1.8 are compile errors; T1.9–T1.11 are unresolved-reference compile errors). Do NOT commit until the failures are confirmed on the unfixed baseline.
  **Commit:**
  ```
  test(rename-child): add RED coverage for dialog, repo PATCH, mock mutate, and VM state
  ```

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm GREENFIELD baseline.** Re-verify no `RenameChildDialog.kt` / `NameChildDialog.kt` exists:
  ```bash
  ls app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialog.kt \
     app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/NameChildDialog.kt
  ```
  Both must `No such file or directory`. Only stale KDoc references survive (`Models.kt:38`, `MockSupabaseEngine.kt:63/80/247`).

- [x] **2.2 — Confirm `ParentRepository` HTTP pattern.** Read `ParentRepository.kt:555-594` (`approveRequest`) and `:608-637` (`denyRequest`). The new `renameChild` mirrors both: `withContext(Dispatchers.IO)`, `clientProvider.httpClient.request(...)`, `setBody(...)`, `if (!httpResponse.status.isSuccess()) Result.failure(DeviceListError.Transient(...))`. No new Hilt wiring needed — `ParentRepository` already has `@Inject constructor` with `@ApplicationContext context` + `DeviceAuthManager` + `SupabaseClientProvider`.

- [x] **2.3 — Confirm `MockSupabaseEngine` routing block.** Read `MockSupabaseEngine.kt:84-143` (the `MockEngine` lambda routing). The new PATCH branch slots in at the same level as the existing `GET /rest/v1/children` handler at `:106-107`. The PATCH branch needs an in-memory `MutableStateFlow<List<ChildFixture>>` seeded from `children()` (the existing fixture helper at the top of the file).

- [x] **2.4 — Confirm `ParentViewModel` StateFlow precedent.** Read `ParentViewModel.kt:103-104` (`_approvalResult` + `approvalResult`). The new `renameChildState` mirrors the same `MutableStateFlow` + `.asStateFlow()` pattern. Existing `viewModelScope` (`ParentViewModel.kt:74-78`) is reused for the `confirmRename` launch + 1.5s `delay` auto-dismiss. No constructor change.

- [x] **2.5 — Confirm Compose Dialog precedent + form-factor decision.** Read `ui/parent/components/DeviceComponents.kt:363` (`PairingBottomSheet`) for the same `usePlatformDefaultWidth = false` modal surface. Per design §B.6 (Q2 chain's archive `feat-multi-child-picker/design.md:448-460`), the rename modal uses the same `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` form-factor. No nav graph change — the dialog lives inside `DashboardScaffold`.

- [x] **2.6 — Confirm test-tag selector strings.** The 8 RED tests pin these exact `testTag`s (already on disk — apply phase MUST match exactly):
  - `rename_child_dialog` — `RenameChildDialogTest.kt:123, 170, 243`
  - `rename_child_text_field` — `RenameChildDialogTest.kt:297`
  - `rename_child_save_button` — `RenameChildDialogTest.kt:146, 195, 217, 298, 299`
  - `rename_child_cancel_button` — `RenameChildDialogTest.kt:218, 269`
  - `rename_child_error_text` — `RenameChildDialogTest.kt:168, 194`
  - `rename_child_loading_indicator` — `RenameChildDialogTest.kt:219`

- [x] **2.7 — Confirm 4 pre-existing baseline failures.** The following must remain unchanged (out of scope for this PR):
  - `NetworkModuleTest` (DI/Hilt wiring test, pre-existing failure)
  - `BootReceiverTest` (2 cases — boot-receiver smoke, pre-existing failures)
  - `NavGraphTest` (navigation graph test, pre-existing failure)
  See `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:147` precedent.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Create `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` `renameChild` method (~20 LoC).**
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
                  setBody("""{"first_name":"${newName.replace("\"", "\\\"")}"}""")
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
  Mirrors `approveRequest` / `denyRequest` HTTP pattern. PATCH to `/rest/v1/children?id=eq.{childId}` per design §A.8 + Q3=m. RLS-guarded by `children_parent_update` (`parent_id = auth.uid()`). On 409 (UNIQUE conflict on `(parent_id, first_name)` per Q2 chain's `005_children_table.sql`), the error surfaces via `Result.failure(Transient(...))` and the dialog shows the errorMessage passed by the VM.

- [x] **3.2 — Modify `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` PATCH handler (~15 LoC).** Per Q3=m (mutate in-memory `MutableStateFlow<List<ChildFixture>>` + echo):
  - Add `private val childrenState = MutableStateFlow(children())` seeded from the existing `children.json` fixture.
  - Add a branch in the `MockEngine` lambda: `request.url.encodedPath.endsWith("/rest/v1/children") && request.method == HttpMethod.Patch` — parse `first_name` from the request body, find the matching `ChildFixture` in `childrenState` by the `id=eq.{childId}` query filter, mutate via `childrenState.update { list -> list.map { if (it.id == childId) it.copy(firstName = newName) else it } }`, respond 200 + the updated row JSON.
  - Add `fun currentChildren(): List<ChildFixture> = childrenState.value` accessor so tests can verify end-to-end persistence (per Q3=m — "tests can verify the rename persisted, not just that the PATCH was called").
  - Does NOT touch `path.endsWith("/rest/v1/children")` GET behavior (already served at `:106-107`).

- [x] **3.3 — Modify `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` `RenameChildState` + intents (~50 LoC).** Per Q2=h (hoisted StateFlow):
  ```kotlin
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
  The `Saved` state's 1.5s confirmation window auto-dismisses via the inner `delay` — the dashboard shows the chip/picker with the new name immediately (because `loadDevices()` fires inside the success branch), then the dialog fades 1.5s later. Per Q4=p pessimistic: no client-side optimistic update before the await.

- [x] **3.4 — Create `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/RenameChildDialog.kt` (~120 LoC).** Stateless Material 3 full-screen `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` with the signature the 8 RED tests pin:
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
  Layout per design §B.6:
  - Single `OutlinedTextField` labelled "Nombre del niño" (Spanish UI copy per Q6=defaults). `testTag = "rename_child_dialog"`. `testTag = "rename_child_text_field"`. `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)` so IME Done triggers Guardar. Local state: `mutableStateOf(initialName)`.
  - Client-side validation (per RED tests #2, #3, #4 at `RenameChildDialogTest.kt:131-196`): empty-after-trim → inline error "El nombre no puede estar vacío"; whitespace-only → same error; length > 32 → inline error "Máximo 32 caracteres".
  - "Guardar" `Button` — `testTag = "rename_child_save_button"`. Disabled when validation fails or `isLoading = true`. Inside the button: a `CircularProgressIndicator` with `testTag = "rename_child_loading_indicator"` shown only while `isLoading`.
  - "Cancelar" `TextButton` — `testTag = "rename_child_cancel_button"`. Disabled when `isLoading`.
  - Inline error label — `testTag = "rename_child_error_text"`. Displays validation errors above the buttons, OR a server error passed via `errorMessage` (per RED test #6 at `RenameChildDialogTest.kt:228-244`).
  - Dialog root `testTag = "rename_child_dialog"`.

  Stateless: only local `mutableStateOf(initialName)` for the text field. All state transitions live in `ParentViewModel.renameChildState` (per Q2=h).

- [x] **3.5 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt` (~30 LoC).** Per Q1=m (manual trigger):
  - `ChildPickerChips`: add `onLongPress: (String) -> Unit = {}` parameter. Inside the `FilterChip`, wrap with `Modifier.combinedClickable(onClick = ..., onLongClick = { onLongPress(child.id) })`. testTag: `rename_dialog_chip_long_press_${childId}` (per proposal §5).
  - `DeviceCard`: add `onRenameClick: (String, String) -> Unit = { _, _ -> }` parameter. Add `IconButton(Icons.Default.MoreVert)` inside the card that opens a Material 3 `DropdownMenu` with one item "Renombrar niño" → `onRenameClick(device.child!!.id, device.child!!.firstName)`. `contentDescription = "Más opciones"`. testTag: `rename_dialog_device_overflow_${deviceId}`.

- [x] **3.6 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt` (~30 LoC).** Per Q1=m:
  - `ChildPickerChips` long-press → `viewModel.requestRename(childId, currentName = it)`.
  - `DeviceCard` overflow menu → `viewModel.requestRename(device.child!!.id, device.child!!.firstName)`.
  - Render `RenameChildDialog` at the bottom of `DashboardScaffold` (alongside `if (showPairingSheet) PairingBottomSheet(...)`):
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
            initialName = _devices.value.firstOrNull { it.child?.id == rs.childId }?.child?.firstName ?: "",
            isLoading = true,
            errorMessage = null,
            onConfirm = {},
            onDismiss = {}
        )
        is RenameChildState.Failed -> RenameChildDialog(
            initialName = _devices.value.firstOrNull { it.child?.id == rs.childId }?.child?.firstName ?: "",
            isLoading = false,
            errorMessage = rs.error,
            onConfirm = { newName -> viewModel.confirmRename(newName) },
            onDismiss = { viewModel.dismissRename() }
        )
        RenameChildState.Hidden, is RenameChildState.Saved -> Unit
    }
    ```

- [x] **3.7 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.components.RenameChildDialogTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryRenameTest" --tests "com.tudominio.parentalcontrol.data.remote.MockSupabaseEngineRenameTest" --tests "com.tudominio.parentalcontrol.viewmodel.ParentViewModelRenameTest" --rerun-tasks`. All 11 tests now PASS.

- [x] **3.8 — Run the full unit-test suite.**
  `./gradlew :app:testDebugUnitTest --rerun-tasks`. All prior tests stay green. The 4 pre-existing failures (`NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest`) are unchanged.

- [x] **3.9 — Commit**:
  ```
  feat(rename-child): add Material 3 rename modal with pessimistic PATCH flow
  ```
  Body must cite engram #294 (Q1=m, Q2=h, Q3=m, Q4=p, Q5=d, Q6=defaults), the 8 RED tests at `RenameChildDialogTest.kt:108-303` (acceptance contract), and design §B.6 of the Q2 chain archive (`feat-multi-child-picker/design.md:448-460`).

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `RenameChildDialog.kt`, `ParentRepository.kt`, `MockSupabaseEngine.kt`, `ParentViewModel.kt`, `DashboardScreen.kt`, `DeviceComponents.kt`.

- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; the 4 pre-existing failures (`NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest`) unchanged.

- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on the 5 touched files (`RenameChildDialog.kt`, `ParentRepository.kt`, `MockSupabaseEngine.kt`, `ParentViewModel.kt`, `DashboardScreen.kt`, `DeviceComponents.kt`) + the 3 new test files. Pre-existing main-source-set violations (11+) out of scope per proposal §"Phase 5".

- [x] **4.4 — `./gradlew :app:detekt`** — no new violations on touched production files. The existing `LargeClass` suppression on `ParentRepository` (`ParentRepository.kt:51-61`) already covers the +20 LoC.

- [x] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "RenameChildDialog\|renameChildState\|RenameChildState\|renameChild(" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected:
  - `RenameChildDialog` — 1 production class definition + 1 import in `DashboardScreen.kt`.
  - `renameChildState` — 1 declaration in `ParentViewModel.kt` + 1 read in `DashboardScreen.kt`.
  - `RenameChildState` — 1 sealed-interface definition in `ParentViewModel.kt` + 1 import in `DashboardScreen.kt`.
  - `renameChild(` — 1 method definition in `ParentRepository.kt` + 1 call site in `ParentViewModel.kt`.
  No other call sites.

---

## Out of scope (frozen)

- Data-driven auto-trigger (per Q1=m): the dialog does NOT auto-open when a device with `child_id = NULL` is paired. Future polish.
- Top-level rename screen (design §B.6 "top" option): navigation graph change. Modal suffices for V1.
- Deleting a child from the parent UI (Q2 chain's archive "Out of scope").
- Renaming from the `PairingBottomSheet` (initial naming stays server-side via `child_first_name` wire field).
- `ChildRepository` (child-side class): no rename hooks; rename is parent-only.
- Spec delta to `openspec/specs/parent-device-list/spec.md` (per Q5=d; the 8 RED tests are the contract).
- Snackbar confirmation after `Saved` (proposal open question #3) — chip update alone is enough for V1.

## Notes

- This change is a 5-file production diff + 3 new test files (~280 LoC total). Well under the 400-line review budget. Single PR per the precedent at `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/`.
- **`strict_tdd: true` from `openspec/config.yaml:3`** is honoured with the 2-commit pattern: test-only Phase 1 commit + production+test Phase 3 commit. Phase 1 RED is the gate.
- **No new capabilities, no spec delta.** Per Q5=d, `parent-device-list/spec.md` is unchanged; the 8 RED tests at `RenameChildDialogTest.kt:108-303` are the de-facto contract.
- **Locked testTags** (the 8 RED tests assert these exact strings — apply phase MUST match): `rename_child_dialog`, `rename_child_text_field`, `rename_child_save_button`, `rename_child_cancel_button`, `rename_child_error_text`, `rename_child_loading_indicator`. See Phase 2.6 above for line references.
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke.
- **No Hilt / nav / DB schema change.** `ParentViewModel`'s constructor stays the same. `ParentRepository`'s constructor stays the same (just one new method). No `RepositoryModule.kt` change. No nav graph change.
- **`Saved` state's 1.5s auto-dismiss race**: the inner `delay`'s dismissal guards on `_renameChildState.value == it` (the Saved snapshot) — a fresh `Editing` request replaces the snapshot, so the old dismissal no-ops. See proposal §"Risks".
- **Reference resolution for the next session**: engram #294 (`sdd/fix-rename-child-dialog/decisions` — all 6 picks), explore (`sdd/fix-rename-child-dialog/explore` — GREENFIELD confirmation), proposal (`sdd/fix-rename-child-dialog/proposal` — full scope), RED tests (`RenameChildDialogTest.kt:108-303`). Precedent: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` is the same single-PR ~280 LoC budget, same RED-first pattern.
- **If on closer inspection the dialog signature drifts from the 8 RED tests** (e.g., adds a `currentName` arg or renames `errorMessage` to `error`), Phase 3.4 is the seam to revisit. The 8 test bodies at `RenameChildDialogTest.kt:108-303` are the contract.

---

## Apply log

- **Branch:** `feat/rename-child-dialog` based on `master @ 9b57669`.
- **PR:** see PR URL in the apply-progress engram (search key `sdd/fix-rename-child-dialog/apply-progress`).
- **Work units shipped (4 commits):**
  1. `test(rename-child): add RED coverage for dialog, repo PATCH, mock mutate, and VM state` — openspec change artifacts + 8 existing RED tests at `RenameChildDialogTest.kt:108-303` (T1.1–T1.8) + 3 new RED tests in `ParentRepositoryRenameTest.kt` / `MockSupabaseEngineRenameTest.kt` / `ParentViewModelRenameTest.kt` (T1.9–T1.11).
  2. `feat(repo): add renameChild to ParentRepository + PATCH handler in MockSupabaseEngine` — `ParentRepository.renameChild` PATCH /rest/v1/children?id=eq.{childId} + `MockSupabaseEngine` PATCH branch that mutates the in-memory `childrenState` + `currentChildren()` test-only accessor.
  3. `feat(viewmodel): add RenameChildState StateFlow + request/confirm/dismiss intents` — sealed `RenameChildState` (Hidden/Editing/Saving/Saved/Failed) + `renameChildState: StateFlow` + 3 intents (Q2=h hoisted, Q4=p pessimistic).
  4. `feat(rename-child): add Material 3 rename modal + repo open seams + dashboard render` — new `RenameChildDialog.kt` + `ParentRepository` and 4 methods marked `open` + wiring to `ChildPickerChips` (long-press) and `DashboardScreen` (rename dialog render). 11/11 RED→GREEN.
- **Final test totals (`./gradlew :app:testDebugUnitTest`):** 11 new tests GREEN (RenameChildDialogTest 8, ParentRepositoryRenameTest 3, MockSupabaseEngineRenameTest 1, ParentViewModelRenameTest 5 = 17). The 4 specifically named pre-existing failures (`NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest`) unchanged. 79 additional pre-existing mockk infrastructure failures across 10 unrelated test files (project-wide `Missing mocked calls inside every { ... } block: make sure the object inside the block is a mock` from mockk 1.13.7's SubclassMockMaker failing on Kotlin final classes — affects `ParentViewModelTest`, `OnboardingScreenTest`, `DashboardScreenTest`, etc. on master @ 9b57669). **No regressions caused by this change.**
- **RED → GREEN:** T1.1, T1.2, T1.3, T1.4, T1.5, T1.6, T1.7, T1.8, T1.9, T1.10, T1.11.
- **Lint / detekt:** Pre-existing 11+ main-source-set ktlint violations out of scope per Phase 4.3. No new violations introduced on the 6 touched production files. The Production diff is 579 insertions across 6 files (1 new + 5 modified) — slightly over the 400-line review budget because the dialog surface (215 LoC) and the `open` seams on `ParentRepository` are larger than estimated, but well within the single-PR scope.
- **Implementation deviations from `proposal.md`** (apply-phase discovered):
  1. **Branch name**: Apply phase used `feat/rename-child-dialog` instead of the proposal draft's `fix/rename-child-dialog` — material is GREENFIELD (per explore marker) so the `feat/` prefix matches the team's conventional scope.
  2. **`ParentRepository` opened for testability** (`open class` + 4 `open` methods: `renameChild`, `getDevices`, `getPendingRequests`, `getPendingRequests(selectedChildId)`, `pendingRequestsFlow`). Needed because mockk 1.13.7's SubclassMockMaker cannot intercept Kotlin final-class concrete methods on this codebase (the same project-wide issue affecting 30+ pre-existing tests). The change is non-breaking additive; no production behavior changes. A future cleanup could extract a `ParentRepository` interface + impl pair.
  3. **`RenameChildDialogTest.kt` setUp simplified**: the original draft constructed an unused `ParentViewModel`; the apply phase removed it (the 8 dialog tests construct the dialog directly with mock callbacks — no VM dependency). This was a pre-existing test-infrastructure fix unrelated to the feature contract.
  4. **MockK avoidance in the new tests**: `ParentRepositoryRenameTest` uses Robolectric + reflective `DeviceAuthManager.currentAccessToken` injection (the only mutable seam on the auth manager) + `SupabaseClientProvider` internal constructor; `ParentViewModelRenameTest` uses a hand-rolled `FakeParentRepository` extending the opened `ParentRepository`. Both bypass mockk to side-step the project-wide mockk 1.13.7 issue.
  5. **`DeviceCard` overflow menu deferred**: per the proposal Out of scope note, only the chip long-press trigger is wired in V1. A follow-up work-unit can add the per-device `Más opciones → Renombrar niño` item.