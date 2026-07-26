package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * RED coverage for the deferred `renameChild` from
 * `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md` §2.
 *
 * Per Q2=h + Q4=p (engram `sdd/fix-rename-child-dialog/decisions`):
 *  - ParentRepository gains a `renameChild(childId: String, newName: String): Result<Unit>`
 *    that PATCHes `${SUPABASE_URL}/rest/v1/children?id=eq.{childId}` with body
 *    `{"first_name":"..."}` (Supabase PostgREST shape).
 *  - Success on a 2xx; failure otherwise (typed as `DeviceListError.Transient`).
 *  - Mirrors the existing `approveRequest` / `denyRequest` HTTP pattern.
 *
 * RED today (without this feature): `renameChild` does not exist on
 * `ParentRepository` — test fails to compile (`Unresolved reference`).
 * The apply phase lands `renameChild` and the tests turn GREEN.
 *
 * Implementation note: this test deliberately avoids `mockk<DeviceAuthManager>`
 * and `mockk<SupabaseClientProvider>` because mockk 1.13.7's
 * SubclassMockMaker cannot mock Kotlin final-class private constructors on
 * this codebase (project-wide infrastructure issue also affecting
 * `ParentViewModelTest`, `DeviceAuthManagerColdStartTest`, etc. on
 * master). Instead, the test uses Robolectric for a real
 * `android.content.Context`, a real `DeviceAuthManager.getInstance(...)`
 * seeded with a synthetic token via reflection (the only mutable seam
 * in `DeviceAuthManager.getAccessToken()`'s backing field), and a real
 * `SupabaseClientProvider` constructed with an injected
 * `MockEngine`-backed `HttpClient`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentRepositoryRenameTest {

    private lateinit var captured: MutableList<HttpRequestData>
    private lateinit var mockClient: HttpClient
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var context: Context
    private lateinit var repository: ParentRepository

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        captured = mutableListOf()
        mockClient = HttpClient(
            MockEngine { request ->
                captured.add(request)
                respond(
                    content = ByteReadChannel(EMPTY_CHILDREN_RESPONSE),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        // SupabaseClientProvider exposes an `internal` constructor that
        // accepts an injected HttpClient — use it to swap in our mock
        // engine client without going through `getInstance(context)`.
        clientProvider = SupabaseClientProvider(context, injectedClient = mockClient)
        // DeviceAuthManager.getInstance is a singleton factory; we ask
        // for it with a Robolectric-baked context and inject a token
        // via reflection. The `currentAccessToken` backing field is
        // the source of truth that `getAccessToken()` reads.
        authManager = DeviceAuthManager.getInstance(context)
        injectAccessToken(authManager, "test-jwt-token")

        repository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = clientProvider
        )
    }

    @After
    fun tearDown() {
        // Drop the injected state so a test ordering inversion doesn't
        // leak tokens into later tests.
        injectAccessToken(authManager, null)
        mockClient.close()
    }

    private fun requestBodyText(req: HttpRequestData): String {
        return when (val body = req.body) {
            is TextContent -> body.text
            EmptyContent -> ""
            else -> body.toString()
        }
    }

    /**
     * Reflectively sets `DeviceAuthManager.currentAccessToken` so the
     * test can drive `getAccessToken()` to return a synthetic JWT.
     * The field is `private var currentAccessToken: String?` (final),
     * declared at `DeviceAuthManager.kt:144`. We bypass Kotlin null
     * safety via raw field access; this seam exists ONLY for tests
     * that need a deterministic auth state without driving the full
     * `authenticateOrCreate(Role.PARENT)` flow (which itself has
     * project-wide MockK infrastructure issues).
     */
    private fun injectAccessToken(target: DeviceAuthManager, token: String?) {
        val field: Field = DeviceAuthManager::class.java
            .getDeclaredField("currentAccessToken")
        field.isAccessible = true
        field.set(target, token)
    }

    /**
     * Happy path: a PATCH 200 returns Result.success(Unit). The repo
     * uses the parent's bearer token + apikey + ContentType headers,
     * builds the URL with the `id=eq.{childId}` query filter, and
     * serializes `{"first_name":"<newName>"}` as the body.
     */
    @Test
    fun renameChild_patch_200_returns_success() = runTest {
        val result = repository.renameChild(
            childId = "child-lucas",
            newName = "Mateo"
        )

        assertTrue(
            "Expected Result.success(Unit), got $result",
            result.isSuccess
        )

        // Verify the wire request: PATCH /rest/v1/children?id=eq.child-lucas
        assertEquals(
            "renameChild must issue exactly one HTTP call, got ${captured.size}",
            1,
            captured.size
        )
        val req = captured.first()
        assertEquals(HttpMethod.Patch, req.method)
        val url = req.url.toString()
        assertTrue(
            "URL should target /rest/v1/children, got $url",
            url.contains("/rest/v1/children")
        )
        assertTrue(
            "URL should include id=eq.child-lucas filter, got $url",
            url.contains("id=eq.child-lucas")
        )
        assertEquals("Bearer test-jwt-token", req.headers[HttpHeaders.Authorization])
        assertEquals(SupabaseClientProvider.SUPABASE_ANON_KEY, req.headers["apikey"])

        val body = requestBodyText(req)
        assertTrue(
            "Body should contain first_name with the new value, got: $body",
            body.contains("\"first_name\":\"Mateo\"")
        )
    }

    /**
     * Failure path: a non-2xx response (the 409 UNIQUE conflict
     * scenario for an already-taken `(parent_id, first_name)` pair)
     * must surface as `DeviceListError.Transient`, never as a silent
     * success.
     */
    @Test
    fun renameChild_non_2xx_returns_Transient_failure() = runTest {
        // Close the happy-path mockClient, then provide a failing one.
        mockClient.close()
        val failingClient = HttpClient(
            MockEngine { _ ->
                respondError(HttpStatusCode.Conflict)
            }
        )
        val failingProvider = SupabaseClientProvider(context, injectedClient = failingClient)

        try {
            val failingRepo = ParentRepository(
                context = context,
                authManager = authManager,
                clientProvider = failingProvider
            )

            val result = failingRepo.renameChild(
                childId = "child-lucas",
                newName = "Mateo"
            )

            assertTrue(
                "Expected Result.failure on 409, got $result",
                result.isFailure
            )
            val ex = result.exceptionOrNull()
            assertNotNull("Exception must not be null", ex)
            assertTrue(
                "renameChild must surface 409 as DeviceListError.Transient, got $ex",
                ex is DeviceListError.Transient
            )
            assertTrue(
                "Transient reason must mention HTTP 409, got ${(ex as DeviceListError.Transient).reason}",
                ex.reason.contains("HTTP 409")
            )
        } finally {
            failingClient.close()
            mockClient = failingClient // keep the field consistent for tearDown
        }
    }

    /**
     * Auth seam: with no token the repo must short-circuit to
     * `DeviceListError.AuthMissing` without issuing any HTTP. Mirrors
     * the contract every other `ParentRepository` method honours.
     */
    @Test
    fun renameChild_without_token_returns_AuthMissing() = runTest {
        injectAccessToken(authManager, null)

        val result = repository.renameChild(
            childId = "child-lucas",
            newName = "Mateo"
        )

        assertTrue(
            "Expected Result.failure, got $result",
            result.isFailure
        )
        assertEquals(
            "Expected typed AuthMissing, got ${result.exceptionOrNull()}",
            DeviceListError.AuthMissing,
            result.exceptionOrNull()
        )
        assertTrue(
            "renameChild must NOT issue an HTTP call when unauthenticated",
            captured.isEmpty()
        )
    }

    companion object {
        private const val EMPTY_CHILDREN_RESPONSE = """[]"""
    }
}
