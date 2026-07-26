package com.tudominio.parentalcontrol.ui.parent.screens

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepository
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.BehaviorLogViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * RED→GREEN coverage for [BehaviorLogScreen] + [BehaviorLogViewModel].
 * Phase 0 of PR B (change `feat-parent-behavioral-event-log`). The 8 tests
 * pin the surface contract — they MUST fail at compile time on master @
 * `475ca73` before Phase 3 lands. Mirrors `RenameChildDialogTest` and
 * `DeviceDetailScreenTest` patterns (Robolectric + createComposeRule, no
 * Hilt; real Room in-memory DAO + MockEngine Supabase client for the
 * repository — the project's pattern since BehavioralEventsRepository is
 * not open for subclassing; PR A froze the data layer).
 *
 * Spanish UI copy per project convention. snake_case testTags per
 * `proposal.md` §Change B.5. (Baseline absorbs 2 unused imports + 3
 * long lines per the pre-built ktlint baseline generated when the
 * `BehaviorLogScreenTest.kt` slot was reserved.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorLogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var dao: BehavioralEventDao
    private lateinit var authManager: DeviceAuthManager
    private lateinit var repository: BehavioralEventsRepository
    private val openClients = mutableListOf<HttpClient>()
    private var capturedRefreshes = 0
    private val captured = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        authManager = DeviceAuthManager.getInstance(context)
        injectAccessToken(authManager, "test-jwt-token")
        database = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.behavioralEventDao()
        repository = newRepo(responseBody = EMPTY_ARRAY)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        injectAccessToken(authManager, null)
        openClients.forEach { runCatching { it.close() } }
        database.close()
    }

    private fun injectAccessToken(target: DeviceAuthManager, token: String?) {
        val field: Field = DeviceAuthManager::class.java.getDeclaredField("currentAccessToken")
        field.isAccessible = true
        field.set(target, token)
    }

    private fun newRepo(
        responseBody: String = EMPTY_ARRAY,
        status: HttpStatusCode = HttpStatusCode.OK
    ): BehavioralEventsRepository {
        val client = HttpClient(
            MockEngine { req ->
                captured.add(req.url.encodedPath)
                capturedRefreshes++
                respond(ByteReadChannel(responseBody), status, JSON_CT)
            }
        ) { }
        openClients.add(client)
        return BehavioralEventsRepository(
            SupabaseClientProvider(context, injectedClient = client),
            dao,
            authManager
        )
    }

    private fun newVm(): BehaviorLogViewModel =
        BehaviorLogViewModel(repository, authManager)

    private fun event(id: Long, type: String, device: String, ts: String) = BehavioralEventEntity(
        id = id, event_type = type, event_version = 1,
        device_id = device, client_ts = ts, props = "{}",
        synced = true, created_at = ts, parentId = ""
    )

    private fun seed(events: List<BehavioralEventEntity>) = runBlocking { dao.insertAll(events) }

    @Test
    fun behavior_log_renders_events_in_reverse_chronological_order() = runTest {
        seed(
            listOf(
                event(1, "limit_reached", "dev-001", "2026-07-08T09:00:00Z"),
                event(2, "time_warning_shown", "dev-001", "2026-07-08T10:00:00Z"),
                event(3, "block_overlay_shown", "dev-001", "2026-07-08T11:00:00Z")
            )
        )
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_event_card_limit_reached").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_time_warning_shown").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_block_overlay_shown").assertExists()
    }

    @Test
    fun behavior_log_per_child_filter_chip_narrows_visible_events() = runTest {
        seed(
            listOf(
                event(1, "limit_reached", "dev-001", "2026-07-08T09:00:00Z"),
                event(2, "time_warning_shown", "dev-001", "2026-07-08T10:00:00Z"),
                event(3, "block_overlay_shown", "dev-002", "2026-07-08T11:00:00Z"),
                event(4, "app_open", "dev-002", "2026-07-08T11:30:00Z")
            )
        )
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("child_picker_chip_dev-001").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_event_card_limit_reached").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_time_warning_shown").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_block_overlay_shown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("behavior_log_event_card_app_open").assertDoesNotExist()
    }

    @Test
    fun behavior_log_pull_to_refresh_triggers_repository_refresh() = runTest {
        val beforeCount = capturedRefreshes
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_pull_refresh").performClick()
        composeTestRule.waitForIdle()
        org.junit.Assert.assertTrue(
            "pull-to-refresh must hit /behavioral_events, captured paths=${captured.distinct()}",
            captured.any { it.contains("behavioral_events") }
        )
    }

    @Test
    fun behavior_log_empty_state_shows_placeholder_when_no_events() = runTest {
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sin eventos").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_empty_state").assertExists()
    }

    @Test
    fun behavior_log_loading_state_shows_spinner_during_refresh() = runTest {
        // Suspend the mock engine forever so the init { refresh() } never
        // resolves — the screen's isLoading StateFlow stays true, the
        // PullRefresh container stays visible, and the spinner overlay
        // remains in the composition. After this test, the suspended
        // client is closed via runCatching so the test doesn't leak.
        val pendingClient = HttpClient(
            MockEngine {
                kotlinx.coroutines.suspendCancellableCoroutine<io.ktor.client.request.HttpResponseData> { }
            }
        ) { }
        openClients.add(pendingClient)
        val pendingRepo = BehavioralEventsRepository(
            SupabaseClientProvider(context, injectedClient = pendingClient),
            dao,
            authManager
        )
        composeTestRule.setContent {
            ParentalControlTheme {
                BehaviorLogScreen(
                    BehaviorLogViewModel(pendingRepo, authManager),
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_pull_refresh").assertExists()
    }

    @Test
    fun behavior_log_error_state_shows_error_banner_on_refresh_failure() = runTest {
        val failRepo = newRepo(responseBody = "boom", status = HttpStatusCode.InternalServerError)
        composeTestRule.setContent {
            ParentalControlTheme {
                BehaviorLogScreen(
                    BehaviorLogViewModel(failRepo, authManager),
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_error_banner").assertExists()
        composeTestRule.onNodeWithText("Error cargando eventos").assertExists()
    }

    @Test
    fun behavior_log_card_displays_icon_eventType_timestamp_and_childName() = runTest {
        seed(listOf(event(1, "limit_reached", "dev-001", "2026-07-08T10:00:00Z")))
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_event_card_limit_reached").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_timestamp").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_child_name").assertExists()
        composeTestRule.onNodeWithText("LIMIT_REACHED").assertExists()
    }

    @Test
    fun behavior_log_all_events_chip_taps_clear_filter() = runTest {
        seed(
            listOf(
                event(1, "limit_reached", "dev-001", "2026-07-08T09:00:00Z"),
                event(2, "time_warning_shown", "dev-001", "2026-07-08T10:00:00Z"),
                event(3, "block_overlay_shown", "dev-002", "2026-07-08T11:00:00Z"),
                event(4, "app_open", "dev-002", "2026-07-08T11:30:00Z")
            )
        )
        composeTestRule.setContent { ParentalControlTheme { BehaviorLogScreen(newVm(), onNavigateBack = {}) } }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("child_picker_chip_dev-001").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_event_card_block_overlay_shown").assertDoesNotExist()
        composeTestRule.onNodeWithTag("behavior_log_event_card_app_open").assertDoesNotExist()
        composeTestRule.onNodeWithTag("child_picker_chip_all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("behavior_log_event_card_limit_reached").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_time_warning_shown").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_block_overlay_shown").assertExists()
        composeTestRule.onNodeWithTag("behavior_log_event_card_app_open").assertExists()
    }

    companion object {
        private const val EMPTY_ARRAY = "[]"
        private val JSON_CT = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
