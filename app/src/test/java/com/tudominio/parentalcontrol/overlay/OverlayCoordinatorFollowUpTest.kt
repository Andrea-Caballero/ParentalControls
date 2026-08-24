package com.tudominio.parentalcontrol.overlay

import android.view.View
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCoordinatorFollowUpTest {
    @Test
    fun `outcomes carry typed identity and proof state`() {
        val handle = OverlayHandle.issue(1)
        val outcome: OverlayOutcome = OverlayOutcome.Lost(handle)
        assertEquals(handle, outcome.handle)
        assertTrue(outcome is OverlayOutcome.Lost)
    }
    @Test
    fun `exact owner identity keeps view identity`() {
        val handle = OverlayHandle.issue(1)
        val owner = OwnerId.issue(1)
        val view = mockk<View>()
        val same = ExactOwnerIdentity(handle, owner, view, Attachment.ATTACHED)
        val different = ExactOwnerIdentity(handle, owner, mockk(), Attachment.ATTACHED)
        assertEquals(same, same)
        assertTrue(same != different)
    }

    @Test
    fun `late hide cannot replace the latest desired operation`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val first = coordinator.requestShow("one", "first")
        val second = coordinator.requestShow("two", "second")
        coordinator.requestHide(first!!)
        assertEquals(second, (coordinator.desired.value as DesiredOverlayState.Shown).handle)
    }

    @Test
    fun `physical removal retains unresolved authority before WU2 proof`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val handle = requireNotNull(coordinator.requestShow("one", "first"))
        val actor = coordinator.registerService()
        val attached = (coordinator.claim(actor, handle, mockk<View>()) as ClaimOutcome.Claimed).owner
            .copy(attachment = Attachment.ATTACHED)
        assertEquals(CoreOutcome.Accepted, coordinator.markAttached(actor, handle, attached))
        assertEquals(CoreOutcome.Accepted, coordinator.markRemovalUncertain(actor, attached))
        assertEquals(Attachment.DETACH_UNRESOLVED, coordinator.owner()?.attachment)
    }

    @Test
    fun `legacy outcomes remain distinct from uncertainty`() {
        val handle = OverlayHandle.issue(2)
        assertTrue(OverlayOutcome.Hidden(handle) is OverlayOutcome.Hidden)
        assertFalse(OverlayOutcome.Lost(handle) is OverlayOutcome.Hidden)
    }

    @Test
    fun `replacement has an explicit current-service rebind seam`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val first = requireNotNull(coordinator.requestShow("one", "first"))
        val firstActor = coordinator.registerService()
        val second = requireNotNull(coordinator.requestShow("two", "second"))

        assertEquals(second, (coordinator.desired.value as DesiredOverlayState.Shown).handle)
        val secondActor = coordinator.registerService()
        assertTrue(secondActor != firstActor)
        assertTrue(coordinator.rebindService(second) != null)
        assertTrue(first != second)
    }

    @Test
    fun `repeating the same show request is idempotent`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val first = coordinator.requestShow("one", "same")
        assertEquals(first, coordinator.requestShow("one", "same"))
    }

    @Test
    fun `stale publication cannot overwrite current observation`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val first = requireNotNull(coordinator.requestShow("one", "first"))
        val stale = coordinator.registerService()
        val current = requireNotNull(coordinator.requestShow("two", "second"))
        coordinator.publish(ObservedOverlayState.Lost(first, stale))
        coordinator.publish(ObservedOverlayState.Failed(current, "current failure", stale))
        assertEquals(null, coordinator.observed.value)
    }

    @Test
    fun `publication rejects fabricated owner without mutation`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val handle = requireNotNull(coordinator.requestShow("one", "first"))
        val actor = coordinator.registerService()
        val view = mockk<View>()
        val owner = (coordinator.claim(actor, handle, view) as ClaimOutcome.Claimed).owner
        val attached = owner.copy(attachment = Attachment.ATTACHED)
        assertEquals(CoreOutcome.Accepted, coordinator.markAttached(actor, handle, attached))
        coordinator.publish(ObservedOverlayState.Shown(handle, actor, OwnerId.issue(99)))
        assertEquals(null, coordinator.observed.value)
        coordinator.publish(ObservedOverlayState.Shown(handle, actor, owner.owner, coordinator.request(handle), attached))
        assertTrue(coordinator.observed.value is ObservedOverlayState.Shown)
    }

    @Test
    fun `service recreation rebinds hidden pending cleanup to the fresh actor`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val handle = requireNotNull(coordinator.requestShow("one", "first"))
        val original = coordinator.registerService()
        val owner = (coordinator.claim(original, handle, mockk<View>()) as ClaimOutcome.Claimed).owner
        val attached = owner.copy(attachment = Attachment.ATTACHED)
        assertEquals(CoreOutcome.Accepted, coordinator.markAttached(original, handle, attached))
        assertEquals(CoreOutcome.Accepted, coordinator.markRemovalUncertain(original, attached))
        coordinator.requestHide(handle)
        val fresh = coordinator.registerService()
        assertTrue(fresh != original)
        assertEquals(fresh, coordinator.ownerActor(coordinator.owner()!!))
        assertEquals(CoreOutcome.Accepted, coordinator.markRemovalUncertain(fresh, coordinator.owner()!!))
    }

    @Test
    fun `already absent records pre-WU2 reason without clearing fail-closed boundary`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val handle = requireNotNull(coordinator.requestShow("one", "first"))
        val actor = coordinator.registerService()
        assertEquals(
            CoreOutcome.Accepted,
            coordinator.recordAuthorizedAbsence(handle, actor, "physical absence proof unavailable before WU2"),
        )
        assertEquals(handle, (coordinator.desired.value as DesiredOverlayState.Shown).handle)
    }

    @Test
    fun `unresolved removal idempotence still requires current actor and exact owner`() {
        val coordinator = OverlayCoordinator(mockk(relaxed = true))
        val handle = requireNotNull(coordinator.requestShow("one", "first"))
        val actor = coordinator.registerService()
        val attached = (coordinator.claim(actor, handle, mockk<View>()) as ClaimOutcome.Claimed).owner
            .copy(attachment = Attachment.ATTACHED)
        assertEquals(CoreOutcome.Accepted, coordinator.markAttached(actor, handle, attached))
        assertEquals(CoreOutcome.Accepted, coordinator.markRemovalUncertain(actor, attached))
        val currentActor = coordinator.registerService()
        assertEquals(
            CoreOutcome.Rejected(CoreRejection.STALE),
            coordinator.markRemovalUncertain(actor, attached),
        )
        assertEquals(CoreOutcome.Accepted, coordinator.markRemovalUncertain(currentActor, attached))
        assertEquals(
            CoreOutcome.Rejected(CoreRejection.STALE),
            coordinator.markRemovalUncertain(currentActor, attached.copy(owner = OwnerId.issue(99))),
        )
        assertEquals(Attachment.DETACH_UNRESOLVED, coordinator.owner()?.attachment)
    }

    @Test
    fun `ordinary desired reconciliation does not authorize foreign cleanup`() {
        val source = java.io.File(
            "src/main/java/com/tudominio/parentalcontrol/overlay/BlockOverlayService.kt",
        ).readText()
        assertTrue(source.contains("if (explicitCleanup && foreignOwner != null)"))
        assertFalse(source.contains("if ((explicitCleanup || desired is DesiredOverlayState.Shown) && foreignOwner != null)"))
        assertTrue(source.contains("coordinator.markRemovalUncertain(serviceActor, removalOwner)"))
    }

    @Test
    fun `gateway dispatches the service reconciliation action`() {
        val coordinatorSource = java.io.File(
            "src/main/java/com/tudominio/parentalcontrol/overlay/OverlayCoordinator.kt",
        ).readText()
        assertTrue(coordinatorSource.contains("setAction(BlockOverlayService.ACTION_RECONCILE)"))
        assertTrue(coordinatorSource.contains("startForegroundService(intent)"))
        assertTrue(coordinatorSource.contains("Overlay reconciliation service could not start"))
        assertTrue(coordinatorSource.contains("catch (cancellation: kotlinx.coroutines.CancellationException)"))
    }

    @Test
    fun `confirmed removal clears only the exact local view state`() {
        val source = java.io.File(
            "src/main/java/com/tudominio/parentalcontrol/overlay/BlockOverlayService.kt",
        ).readText()
        assertTrue(source.contains("composeView === view"))
        assertTrue(source.contains("rendered?.handle == owner.handle"))
        assertTrue(source.contains("owner == null && rendered == null && composeView == null"))
    }
}
