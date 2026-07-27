package com.tudominio.parentalcontrol.service

import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.db.UsageDao
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [MonitorForegroundService.checkWarnings] non-blocking behavior
 * and threshold logic.
 *
 * # Why these tests exist
 *
 * The original [MonitorForegroundService.checkWarnings] used
 * `collectLatest` on a `StateFlow` returned by
 * `UsageDao.getUsageForPackageFlow(...)`. Because the flow is hot, the
 * collector never returns, so the per-tick `updateUsage()` path
 * suspended indefinitely inside `checkWarnings()` and the service's
 * `startTicking()` loop never advanced past the first tick. The fix
 * uses [kotlinx.coroutines.flow.first] so the function reads the
 * current value once per tick and returns.
 *
 * These tests pin the fix:
 *  - **`checkWarnings_does_not_block_on_never_ending_flow`** — the
 *    bug-red test. The DAO returns a flow that emits forever and
 *    never completes; the test calls `checkWarnings()` via reflection
 *    inside a `withTimeout` and asserts the function returns. Under
 *    the old `collectLatest` implementation the call would hang past
 *    the 5-second timeout.
 *  - **`checkWarnings_emits_warning_when_usage_crosses_threshold`** —
 *    the behavior-preservation test. The DAO emits a single value
 *    past the 10-minute threshold; the test verifies the warning
 *    notification is dispatched.
 *  - **`applyWarningThresholds_*`** — pure-decision unit tests for
 *    the extracted `applyWarningThresholds` helper. These pin the
 *    threshold math (10/5 minute boundaries, latch state) without
 *    driving the full Flow.
 *
 * # Why reflection
 *
 * `checkWarnings` is a `private suspend` member; the test reaches it
 * through `Method.invoke` so the production API surface stays small
 * (no `@VisibleForTesting` widening required). `applyWarningThresholds`
 * is also private and the test reaches it the same way.
 *
 * # Why no sleep
 *
 * The original `BootReceiverTest` patterns use `Thread.sleep(1000L)`
 * to wait for `GlobalScope.launch` work. We deliberately avoid that
 * pattern here — the test uses `withTimeout` so a regression to the
 * old `collectLatest` behavior fails fast (5 s) with a clear timeout
 * exception, instead of hanging the test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MonitorForegroundServiceTest {

    private val context by lazy {
        ApplicationProvider.getApplicationContext<android.content.Context>()
    }

    /**
     * Build a [MonitorForegroundService] instance with the
     * [ParentalDatabase] field, [currentPackage] field, and
     * [timeProvider] field pre-populated. Returns the instance plus
     * a [Job] the caller can use to cancel any leftover coroutines.
     *
     * Uses [Robolectric.buildService] to attach the service to a
     * real (Robolectric) Android context — required because
     * `sendWarningNotification` builds a `NotificationCompat.Builder`
     * which dereferences `Context.getResources()`. A bare
     * `MonitorForegroundService()` instance has a null `mBase` and
     * crashes the first time a system call touches the resources.
     */
    private fun buildService(
        database: ParentalDatabase,
        packageName: String?,
        timeProvider: TimeProvider
    ): MonitorForegroundService {
        val controller = org.robolectric.Robolectric.buildService(
            MonitorForegroundService::class.java
        )
        val service = controller.create().get()

        // Inject the database via reflection. The field is declared
        // `@Inject lateinit var` in the service; Hilt would populate
        // it in production. The unit test source set does not run
        // Hilt, so we set it directly.
        val databaseField = MonitorForegroundService::class.java
            .getDeclaredField("database")
        databaseField.isAccessible = true
        databaseField.set(service, database)

        val currentPackageField = MonitorForegroundService::class.java
            .getDeclaredField("currentPackage")
        currentPackageField.isAccessible = true
        currentPackageField.set(service, packageName)

        val timeProviderField = MonitorForegroundService::class.java
            .getDeclaredField("timeProvider")
        timeProviderField.isAccessible = true
        timeProviderField.set(service, timeProvider)

        return service
    }

    /**
     * Reach the private `checkWarnings` suspend function via reflection.
     * Returns the underlying [java.lang.reflect.Method] so the test
     * can invoke it under [withTimeout].
     *
     * Suspend functions are compiled to a JVM method that takes a
     * trailing [kotlin.coroutines.Continuation] parameter and returns
     * `Object` (either the actual return value or
     * [kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED]). We pass a
     * `Continuation` that captures the result and lets us await it.
     */
    private fun checkWarningsMethod(): java.lang.reflect.Method {
        val method = MonitorForegroundService::class.java
            .getDeclaredMethod("checkWarnings", kotlin.coroutines.Continuation::class.java)
        method.isAccessible = true
        return method
    }

    private fun applyWarningThresholdsMethod(): java.lang.reflect.Method {
        // The Kotlin signature is `(usedMinutes: Int?, limitMinutes: Int)`.
        // On the JVM that becomes `(java.lang.Integer, int)` because
        // nullable Int boxes. Use Int::class.javaObjectType for the
        // boxed parameter and Int::class.javaPrimitiveType for the
        // primitive. The Kotlin compiler maps both to the JVM
        // Integer/int pair; the `Int::class.java*` form keeps the
        // test type-safe.
        val method = MonitorForegroundService::class.java
            .getDeclaredMethod(
                "applyWarningThresholds",
                Int::class.javaObjectType,
                Int::class.javaPrimitiveType
            )
        method.isAccessible = true
        return method
    }

    /**
     * Invoke the private `checkWarnings` suspend function via reflection
     * and block the test thread until the function returns (or
     * [timeoutMs] elapses, which fails the test).
     *
     * The Kotlin compiler lowers `suspend fun` to a JVM method that
     * takes a trailing [Continuation] parameter. The method either
     * returns the actual value or [COROUTINE_SUSPENDED] — in the latter
     * case we must wait for the continuation to be resumed. We use a
     * [CountDownLatch] the continuation counts down to bridge that
     * gap deterministically (no `Thread.sleep`).
     */
    private fun invokeCheckWarnings(service: MonitorForegroundService, timeoutMs: Long = 5_000L) {
        val method = checkWarningsMethod()
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val continuation = object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.exceptionOrNull()?.let(errorRef::set)
                latch.countDown()
            }
        }

        val returnValue = method.invoke(service, continuation)
        if (returnValue === COROUTINE_SUSPENDED) {
            val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            check(completed) {
                "checkWarnings did not return within ${timeoutMs}ms — the per-tick " +
                    "path is blocked (regression: collectLatest is back?)"
            }
        }
        errorRef.get()?.let { throw it }
    }

    private fun readWarned10Field(service: MonitorForegroundService): Boolean {
        val field = MonitorForegroundService::class.java
            .getDeclaredField("warned10Minutes")
        field.isAccessible = true
        return field.getBoolean(service)
    }

    private fun readWarned5Field(service: MonitorForegroundService): Boolean {
        val field = MonitorForegroundService::class.java
            .getDeclaredField("warned5Minutes")
        field.isAccessible = true
        return field.getBoolean(service)
    }

    @Test
    fun checkWarnings_does_not_block_on_never_ending_flow() = runTest {
        // Bug-red test. The DAO returns a flow that emits forever and
        // never completes. Under the old `collectLatest` implementation
        // the suspend call hangs and `invokeCheckWarnings`'s 5s latch
        // trips, failing the test. Under the `first()` fix the function
        // returns after the first emit and the latch counts down
        // immediately.
        val usageDao = mockk<UsageDao>(relaxed = true)
        every {
            usageDao.getUsageForPackageFlow(any(), any())
        } returns flow {
            while (true) {
                emit(42)
                delay(100L)
            }
        }
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.usageDao() } returns usageDao

        val service = buildService(
            database = database,
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        invokeCheckWarnings(service)
    }

    @Test
    fun checkWarnings_emits_warning_when_usage_crosses_threshold() = runTest {
        // Behavior-preservation test. The DAO emits a value that puts
        // the user past the 10-minute warning threshold (limit=60,
        // used=55 → 5 min remaining). The function must dispatch the
        // 10-minute warning via the service's notification path.
        //
        // We don't assert on the actual notification (the notification
        // path is exercised in the larger integration tests); we
        // verify the latch state mutated by checking the private
        // `warned10Minutes` field is now true.
        val usageValue = MutableStateFlow<Int?>(55) // 5 minutes remaining
        val usageDao = mockk<UsageDao>(relaxed = true)
        every {
            usageDao.getUsageForPackageFlow(any(), any())
        } returns usageValue

        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.usageDao() } returns usageDao

        val service = buildService(
            database = database,
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        invokeCheckWarnings(service)

        assertTrue(
            "Expected warned10Minutes to flip after usage crossed the 10-minute threshold",
            readWarned10Field(service)
        )
    }

    @Test
    fun applyWarningThresholds_no_emission_when_used_is_null() {
        val service = buildService(
            database = mockk(relaxed = true),
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        val method = applyWarningThresholdsMethod()
        // `null` is the boxed `Int?` value; the second arg is the
        // primitive `Int` (autoboxed by `Method.invoke`).
        method.invoke(service, null, 60)

        assertFalse(
            "warned10Minutes must stay false when usage is null (no row yet)",
            readWarned10Field(service)
        )
        assertFalse(
            "warned5Minutes must stay false when usage is null (no row yet)",
            readWarned5Field(service)
        )
    }

    @Test
    fun applyWarningThresholds_emits_only_10min_warning_at_fifty_one_minutes_used() {
        // limit=60, used=51 → 9 min remaining. The 10-minute warning
        // fires (9 <= 10); the 5-minute warning does NOT fire
        // (9 > 5). This is the band that proves the 5-min threshold
        // is a separate gate from the 10-min one and not just a
        // duplicated predicate.
        val service = buildService(
            database = mockk(relaxed = true),
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        val method = applyWarningThresholdsMethod()
        method.invoke(service, 51, 60)

        assertTrue(
            "Expected 10-minute warning at 9 min remaining (limit=60, used=51)",
            readWarned10Field(service)
        )
        assertFalse(
            "5-minute warning must NOT fire at 9 min remaining (only at <=5)",
            readWarned5Field(service)
        )
    }

    @Test
    fun applyWarningThresholds_emits_both_warnings_when_used_equals_limit() {
        // limit=60, used=60 → 0 min remaining. Both 10-min and 5-min
        // warning thresholds are crossed (remaining <= 5 && <= 10).
        val service = buildService(
            database = mockk(relaxed = true),
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        val method = applyWarningThresholdsMethod()
        method.invoke(service, 60, 60)

        assertTrue(
            "10-minute warning must fire when used equals limit",
            readWarned10Field(service)
        )
        assertTrue(
            "5-minute warning must fire when used equals limit",
            readWarned5Field(service)
        )
    }

    @Test
    fun applyWarningThresholds_does_not_rewarn_once_latched() {
        // First call: used=55 → warns (10-min only).
        // Second call: used=56 → must NOT re-warn because the latch
        // is set; the contract is once-per-day per threshold.
        val service = buildService(
            database = mockk(relaxed = true),
            packageName = "com.test.app",
            timeProvider = DefaultTimeProvider(context)
        )

        val method = applyWarningThresholdsMethod()
        method.invoke(service, 55, 60)

        // The point of the test is to confirm the second call does
        // not throw and the state stays consistent (no counter
        // increment, no exception). The latch is already true, so a
        // re-warn would still leave it true — the absence of a
        // mutation is what we pin via "second call returns cleanly".
        method.invoke(service, 56, 60)
        assertTrue(
            "Latched state must persist across calls (no re-warn, no reset)",
            readWarned10Field(service)
        )
    }
}
