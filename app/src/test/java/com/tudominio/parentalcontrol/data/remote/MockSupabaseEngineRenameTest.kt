package com.tudominio.parentalcontrol.data.remote

import androidx.test.core.app.ApplicationProvider
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for the `MockSupabaseEngine` PATCH handler from
 * `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md` §3.
 *
 * Per Q3=m (engram `sdd/fix-rename-child-dialog/decisions`):
 *  - Mock engine mutates an in-memory `MutableStateFlow<List<ChildFixture>>`
 *    on `PATCH /rest/v1/children?id=eq.{childId}` so Robolectric tests
 *    can verify the rename persisted end-to-end (not just that PATCH was called).
 *  - A `currentChildren(): List<ChildFixture>` accessor is added so the
 *    integration assertion can read the in-memory state.
 *
 * RED today: the mock engine's `when` block has no `PATCH` branch for
 * `/rest/v1/children` AND `currentChildren()` does not exist. Both
 * references fail to compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MockSupabaseEngineRenameTest {

    /**
     * Happy path: PATCH /rest/v1/children?id=eq.child-lucas with
     * body `{"first_name":"Renamed"}` returns 200 AND the in-memory
     * `currentChildren()` reflects the rename.
     *
     * This pins the Q3=m "mutate + echo" contract: tests can verify
     * the rename persisted end-to-end, not just that the PATCH was
     * called.
     */
    @Test
    fun patch_children_mutates_in_memory_childrenState() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val localEngine = MockSupabaseEngine(context)

        // Sanity: the fixture is seeded with "Lucas" before mutation.
        val before = localEngine.children()
        val target = before.firstOrNull { it.id == "child-lucas" }
        assertNotNull(
            "children.json fixture must contain child-lucas for this test, got ids=" +
                before.map { it.id },
            target
        )
        assertEquals("Lucas", target!!.first_name)

        // 1) Issue the PATCH through the engine's httpClient so the
        //    production `when` block is exercised end-to-end.
        val client = localEngine.httpClient
        val response = client.patch(
            "/rest/v1/children?id=eq.child-lucas"
        ) {
            contentType(ContentType.Application.Json)
            setBody("""{"first_name":"Renamed"}""")
        }

        // Wire shape: PATCH returns 2xx. The URL/body assertion is loose
        // here because we don't have direct visibility into the
        // MockEngine's captured request — the strict body assertion is
        // covered by `ParentRepositoryRenameTest`.
        assertEquals(
            "PATCH /rest/v1/children must return 200, got ${response.status}",
            200,
            response.status.value
        )

        // 2) In-memory mutate: the Q3=m contract — currentChildren()
        //    reflects the new value, not the seeded "Lucas".
        val after = localEngine.currentChildren()
        val renamed = after.firstOrNull { it.id == "child-lucas" }
        assertNotNull(
            "currentChildren() must surface the renamed row, got ids=" +
                after.map { it.id },
            renamed
        )
        assertEquals(
            "child-lucas must be renamed to 'Renamed' in the in-memory state",
            "Renamed",
            renamed!!.first_name
        )
    }
}
