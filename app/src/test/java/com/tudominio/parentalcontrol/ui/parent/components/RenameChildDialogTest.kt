package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for the deferred `RenameChildDialog` from
 * `openspec/changes/archive/2026-07-06-feat-multi-child-picker/design.md` §B.6.
 *
 * Background (verified during explore):
 *  - The Q2 chain shipped the data layer (children table + RLS), the
 *    picker UI ([ChildPickerChips]), and the filter wiring
 *    ([ParentViewModel.selectedChildId]).
 *  - The rename UI itself was DEFERRED — no
 *    `app/src/main/java/.../ui/parent/components/NameChildDialog.kt`
 *    exists on master @ `9b57669`. Only stale KDoc comments reference
 *    `RenameChildDialog` (Models.kt:38, MockSupabaseEngine.kt:63/80/247).
 *  - There is NO `ChildrenRepository`/`ChildRepository.renameChild` method.
 *    `ChildRepository` (the child-side class) is unrelated to the
 *    parent-side rename flow.
 *  - There is NO rename trigger anywhere in [DashboardScreen] /
 *    [com.tudominio.parentalcontrol.ui.parent.screens.DeviceDetailScreen].
 *
 * These tests pin the design §B.6 acceptance contract. RED today because
 * the [RenameChildDialog] composable does not exist. The tests will turn
 * GREEN once the apply phase lands the dialog + the
 * `ParentViewModel.renameChild` mutation + the `loadDevices()` refetch.
 *
 * Scope pinned (per design §B.6):
 *  - Material 3 full-screen Dialog (`usePlatformDefaultWidth = false`).
 *  - Single OutlinedTextField labelled "Nombre del niño" — autofocus,
 *    IME `Done` action triggers Guardar.
 *  - Client-side validation:
 *      * empty after `.trim()` rejected with inline error
 *      * length > 32 rejected (matches Supabase CHECK on `children.first_name`)
 *      * whitespace-only rejected (trim test)
 *  - Server-side error surfacing: if the rename mutation returns
 *    [Result.failure], the dialog stays open and shows the error inline.
 *  - Loading state: `Guardar` button is disabled + shows a spinner while
 *    the mutation is in-flight.
 *  - Success: dialog calls `onDismiss` and the dashboard refetches via
 *    [ParentViewModel.loadDevices] (V1 contract — exact refetch flow
 *    is a proposal-phase decision; these tests assert the dialog's
 *    surface contract only).
 *
 * Test tags (locked here so the apply phase lands matching selectors):
 *   - `rename_child_dialog`         — Dialog root
 *   - `rename_child_text_field`     — OutlinedTextField
 *   - `rename_child_save_button`    — Guardar button
 *   - `rename_child_cancel_button`  — Cancelar button
 *   - `rename_child_error_text`     — inline error label
 *   - `rename_child_loading_indicator` — progress inside Guardar
 *
 * setUp note (added during the `fix-rename-child-dialog` apply phase):
 * the original draft constructed a `ParentViewModel` as a sanity check
 * but never used it in any of the 8 tests below. Dropping it removes
 * a dependency on a project-wide MockK infrastructure bug (mockk 1.13.7
 * cannot mock `ParentRepository`/`DeviceAuthManager` on this codebase).
 * The 8 dialog tests do NOT depend on the VM at all — they construct
 * `RenameChildDialog` directly with mock callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RenameChildDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * RED: the dialog root must exist with the documented `testTag` and
     * show the initial child name pre-populated in the text field.
     */
    @Test
    fun renders_dialog_with_initial_name_prepopulated() {
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "Lucas",
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lucas").assertIsDisplayed()
    }

    /**
     * RED: Guardar must be disabled when the text field is empty (post-trim)
     * so the parent can't submit a no-op rename.
     */
    @Test
    fun empty_name_disables_save_button() {
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "",
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_save_button").assertIsNotEnabled()
    }

    /**
     * RED: whitespace-only input must trim and reject. The dialog
     * surfaces an inline error and stays open.
     */
    @Test
    fun whitespace_only_name_shows_inline_error() {
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "   ",
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_error_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lucas", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("rename_child_dialog").assertIsDisplayed()
    }

    /**
     * RED: the Supabase CHECK constraint caps `first_name` at 32 chars.
     * The dialog must reject >32 with an inline error and a disabled
     * Guardar.
     */
    @Test
    fun name_exceeding_max_length_is_rejected() {
        val tooLong = "A".repeat(33)
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = tooLong,
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_error_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rename_child_save_button").assertIsNotEnabled()
    }

    /**
     * RED: while the rename mutation is in-flight, Guardar must be
     * disabled AND show a spinner so the parent can't double-submit.
     */
    @Test
    fun loading_state_disables_buttons_and_shows_spinner() {
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "Lucas",
                    isLoading = true,
                    errorMessage = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_save_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rename_child_cancel_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rename_child_loading_indicator").assertIsDisplayed()
    }

    /**
     * RED: a server-side error (e.g. duplicate name per parent — UNIQUE
     * (parent_id, first_name)) must surface inline. The dialog stays
     * open so the parent can correct the name.
     */
    @Test
    fun server_error_surfaces_inline_and_keeps_dialog_open() {
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "Lucas",
                    isLoading = false,
                    errorMessage = "Ya existe un niño con ese nombre",
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Ya existe un niño con ese nombre").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rename_child_dialog").assertIsDisplayed()
    }

    /**
     * RED: tapping Cancel must call `onDismiss` without invoking
     * `onConfirm`. Critical — design §B.6 explicitly calls out "Cancel
     * must dismiss without throwing an exception" because the
     * data-driven trigger re-opens the dialog on next load.
     */
    @Test
    fun cancel_dismisses_without_invoking_confirm() {
        var confirmCalls = 0
        var dismissCalls = 0
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "Lucas",
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = { confirmCalls++ },
                    onDismiss = { dismissCalls++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_cancel_button").performClick()
        composeTestRule.waitForIdle()

        assert(confirmCalls == 0) { "Cancel must NOT invoke onConfirm" }
        assert(dismissCalls == 1) { "Cancel must invoke onDismiss exactly once" }
    }

    /**
     * RED: tapping Guardar with a valid name must call `onConfirm`
     * exactly once. The dialog itself stays open while the mutation is
     * in-flight (covered by [loading_state_disables_buttons_and_shows_spinner]).
     */
    @Test
    fun save_with_valid_name_invokes_confirm() {
        var confirmCalls = 0
        composeTestRule.setContent {
            ParentalControlTheme {
                RenameChildDialog(
                    initialName = "Lucas",
                    isLoading = false,
                    errorMessage = null,
                    onConfirm = { confirmCalls++ },
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rename_child_text_field").performTextInput(" edit")
        composeTestRule.onNodeWithTag("rename_child_save_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("rename_child_save_button").performClick()
        composeTestRule.waitForIdle()

        assert(confirmCalls == 1) { "Guardar must invoke onConfirm exactly once" }
    }
}

/**
 * Compose-test helper import kept at the bottom so the imports above
 * stay focused on the dialog-under-test. (No-op in production builds.)
 */
private val _unused = Unit
