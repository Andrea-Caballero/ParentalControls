package com.tudominio.parentalcontrol.overlay

import android.content.Context
import android.content.Intent
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface OverlayOutcome {
    val handle: OverlayHandle?
    data class Requested(override val handle: OverlayHandle) : OverlayOutcome
    data class Shown(override val handle: OverlayHandle, val actor: ActorId? = null, val owner: OwnerId? = null) : OverlayOutcome
    data class Hidden(override val handle: OverlayHandle, val actor: ActorId? = null, val owner: OwnerId? = null) : OverlayOutcome
    data class AlreadyAbsent(override val handle: OverlayHandle) : OverlayOutcome
    data class Failed(override val handle: OverlayHandle?, val reason: String) : OverlayOutcome
    data class Lost(override val handle: OverlayHandle, val actor: ActorId? = null, val owner: OwnerId? = null) : OverlayOutcome
}

typealias OverlayShowResult = OverlayOutcome
typealias OverlayHideResult = OverlayOutcome

interface OverlayGateway {
    suspend fun show(packageName: String, reason: String): OverlayShowResult
    suspend fun hide(handle: OverlayHandle): OverlayHideResult
}

sealed interface DesiredOverlayState {
    data object Hidden : DesiredOverlayState
    data class Shown(val handle: OverlayHandle, val packageName: String, val reason: String) : DesiredOverlayState
}

sealed interface ObservedOverlayState {
    data class Shown(val handle: OverlayHandle, val actor: ActorId, val owner: OwnerId, val request: RequestId? = null, val ownerIdentity: ExactOwnerIdentity? = null) : ObservedOverlayState
    data class Hidden(val handle: OverlayHandle, val actor: ActorId, val owner: OwnerId? = null, val reconciled: Boolean = false, val request: RequestId? = null, val ownerIdentity: ExactOwnerIdentity? = null) : ObservedOverlayState
    data class Failed(val handle: OverlayHandle, val reason: String, val actor: ActorId, val owner: OwnerId? = null, val request: RequestId? = null, val ownerIdentity: ExactOwnerIdentity? = null) : ObservedOverlayState
    data class Lost(val handle: OverlayHandle, val actor: ActorId, val owner: OwnerId? = null, val request: RequestId? = null, val ownerIdentity: ExactOwnerIdentity? = null) : ObservedOverlayState
}

class OverlayCoordinator internal constructor(private val context: Context) {
    private data class Operation(val actor: ActorId, val request: RequestId, val packageName: String, val reason: String)

    private val core = PhysicalOverlayOwnership()
    private val operations = mutableMapOf<OverlayHandle, Operation>()
    private val absenceReasons = mutableMapOf<OverlayHandle, String>()
    private val _desired = MutableStateFlow<DesiredOverlayState>(DesiredOverlayState.Hidden)
    private val _observed = MutableStateFlow<ObservedOverlayState?>(null)
    val desired: StateFlow<DesiredOverlayState> = _desired.asStateFlow()
    val observed: StateFlow<ObservedOverlayState?> = _observed.asStateFlow()

    @Synchronized
    fun requestShow(packageName: String, reason: String): OverlayHandle? {
        (_desired.value as? DesiredOverlayState.Shown)?.let { current ->
            if (current.packageName == packageName && current.reason == reason) return current.handle
        }
        val actor = (core.issueActor() as? AllocatorOutcome.Issued)?.id ?: return null
        val request = (core.issueRequest(actor) as? AllocatorOutcome.Issued)?.id ?: return null
        val handle = (core.issueHandle(actor, request) as? AllocatorOutcome.Issued)?.id ?: return null
        operations[handle] = Operation(actor, request, packageName, reason)
        _desired.value = DesiredOverlayState.Shown(handle, packageName, reason)
        return handle
    }

    @Synchronized
    fun requestHide(handle: OverlayHandle) {
        if ((_desired.value as? DesiredOverlayState.Shown)?.handle == handle) _desired.value = DesiredOverlayState.Hidden
    }

    @Synchronized
    fun registerService(): ActorId {
        val actor = (core.issueActor() as AllocatorOutcome.Issued).id
        val state = _desired.value as? DesiredOverlayState.Shown
        val operation = state?.let { operations[it.handle] }
            ?: core.owner()?.let { operations[it.handle] }
        if (operation != null && core.rebindActor(actor, operation.request) is CoreOutcome.Accepted) {
            operations.entries.firstOrNull { it.value.request == operation.request }?.let { (handle, _) ->
                operations[handle] = operation.copy(actor = actor)
            }
        }
        return actor
    }

    fun rebindService(handle: OverlayHandle): ActorId? = synchronized(this) {
        val operation = operations[handle] ?: return@synchronized null
        if ((_desired.value as? DesiredOverlayState.Shown)?.handle != handle) return@synchronized null
        val actor = core.currentActor() ?: return@synchronized null
        if (operation.actor != actor && core.rebindActor(actor, operation.request) !is CoreOutcome.Accepted) return@synchronized null
        operations[handle] = operation.copy(actor = actor)
        actor
    }
    @Synchronized
    fun owner(): ExactOwnerIdentity? = core.owner()
    @Synchronized
    fun ownerActor(owner: ExactOwnerIdentity): ActorId? = operations[owner.handle]?.actor
    @Synchronized
    fun request(handle: OverlayHandle): RequestId? = operations[handle]?.request

    /** Pre-WU2 boundary: an absent result is recorded but never clears ownership debt. */
    @Synchronized
    fun recordAuthorizedAbsence(handle: OverlayHandle, actor: ActorId, reason: String): CoreOutcome {
        val operation = operations[handle] ?: return CoreOutcome.Rejected(CoreRejection.STALE)
        if (operation.actor != actor || !core.isCurrentActor(actor) || core.owner() != null) {
            return CoreOutcome.Rejected(CoreRejection.STALE)
        }
        absenceReasons[handle] = reason
        return CoreOutcome.Accepted
    }
    @Synchronized
    fun claim(actor: ActorId, handle: OverlayHandle, view: View): ClaimOutcome =
        operations[handle]?.let { core.claim(actor, it.request, handle, view) }
            ?: ClaimOutcome.Rejected(ClaimRejection.STALE)

    @Synchronized
    fun markAttached(actor: ActorId, handle: OverlayHandle, expected: ExactOwnerIdentity): CoreOutcome =
        operations[handle]?.let { core.markAttached(actor, it.request, expected) }
            ?: CoreOutcome.Rejected(CoreRejection.STALE)

    @Synchronized
    fun markDetachUnresolved(actor: ActorId, expected: ExactOwnerIdentity): CoreOutcome =
        operations[expected.handle]?.let { core.markDetachUnresolved(actor, it.request, expected) }
            ?: CoreOutcome.Rejected(CoreRejection.STALE)

    @Synchronized
    fun markRemovalUncertain(actor: ActorId, expected: ExactOwnerIdentity): CoreOutcome {
        val operation = operations[expected.handle] ?: return CoreOutcome.Rejected(CoreRejection.STALE)
        if (expected.attachment == Attachment.DETACH_UNRESOLVED) {
            return core.confirmDetachUnresolved(actor, operation.request, expected)
        }
        return core.markDetachUnresolved(actor, operation.request, expected)
    }

    @Synchronized
    fun confirmRemoval(actor: ActorId, expected: ExactOwnerIdentity): CoreOutcome {
        val operation = operations[expected.handle] ?: return CoreOutcome.Rejected(CoreRejection.STALE)
        val result = core.confirmRemoval(actor, operation.request, expected)
        if (result is CoreOutcome.Accepted) operations.remove(expected.handle)
        return result
    }

    @Synchronized
    fun publish(state: ObservedOverlayState) {
        val handle = when (state) {
            is ObservedOverlayState.Shown -> state.handle
            is ObservedOverlayState.Hidden -> state.handle
            is ObservedOverlayState.Failed -> state.handle
            is ObservedOverlayState.Lost -> state.handle
        }
        val operation = operations[handle] ?: return
        val actor = when (state) {
            is ObservedOverlayState.Shown -> state.actor
            is ObservedOverlayState.Hidden -> state.actor
            is ObservedOverlayState.Failed -> state.actor
            is ObservedOverlayState.Lost -> state.actor
        }
        val request = when (state) {
            is ObservedOverlayState.Shown -> state.request
            is ObservedOverlayState.Hidden -> state.request
            is ObservedOverlayState.Failed -> state.request
            is ObservedOverlayState.Lost -> state.request
        }
        if (operation.actor != actor || (request != null && operation.request != request) || !core.isCurrentActor(actor)) return
        val currentOwner = core.owner()
        val publishedOwner = when (state) {
            is ObservedOverlayState.Shown -> state.ownerIdentity
            is ObservedOverlayState.Hidden -> state.ownerIdentity
            is ObservedOverlayState.Failed -> state.ownerIdentity
            is ObservedOverlayState.Lost -> state.ownerIdentity
        } ?: when (state) {
            is ObservedOverlayState.Shown -> state.owner
            is ObservedOverlayState.Hidden -> state.owner
            is ObservedOverlayState.Failed -> state.owner
            is ObservedOverlayState.Lost -> state.owner
        }?.let { ownerId -> currentOwner?.takeIf { it.owner == ownerId } }
        val publishedOwnerId = when (state) {
            is ObservedOverlayState.Shown -> state.owner
            is ObservedOverlayState.Hidden -> state.owner
            is ObservedOverlayState.Failed -> state.owner
            is ObservedOverlayState.Lost -> state.owner
        }
        if ((currentOwner != null) != (publishedOwner != null) ||
            (currentOwner != null && publishedOwner != currentOwner) ||
            (publishedOwner != null && publishedOwner.owner != publishedOwnerId)
        ) return
        val carried = when (state) {
            is ObservedOverlayState.Shown -> state.copy(request = operation.request, ownerIdentity = publishedOwner!!)
            is ObservedOverlayState.Hidden -> state.copy(request = operation.request, ownerIdentity = publishedOwner)
            is ObservedOverlayState.Failed -> state.copy(request = operation.request, ownerIdentity = publishedOwner)
            is ObservedOverlayState.Lost -> state.copy(request = operation.request, ownerIdentity = publishedOwner)
        }
        when (state) {
            is ObservedOverlayState.Shown -> {
                val desired = _desired.value as? DesiredOverlayState.Shown
                if (desired == null || desired.handle != state.handle) return
            }
            is ObservedOverlayState.Hidden -> {
                if (_desired.value is DesiredOverlayState.Shown) return
            }
            is ObservedOverlayState.Failed,
            is ObservedOverlayState.Lost -> Unit
        }
        _observed.value = carried
    }

    companion object {
        @Volatile private var instance: OverlayCoordinator? = null
        fun get(context: Context): OverlayCoordinator = instance ?: synchronized(this) {
            instance ?: OverlayCoordinator(context.applicationContext).also { instance = it }
        }
    }
}

class BlockOverlayServiceGateway(private val context: Context) : OverlayGateway {
    private val coordinator = OverlayCoordinator.get(context)

    override suspend fun show(packageName: String, reason: String): OverlayShowResult {
        val handle = coordinator.requestShow(packageName, reason)
            ?: return OverlayOutcome.Failed(null, "Overlay request allocator exhausted")
        try {
            startReconciliationService()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return OverlayOutcome.Failed(handle, "Overlay reconciliation service could not start")
        }
        return OverlayOutcome.Requested(handle)
    }

    override suspend fun hide(handle: OverlayHandle): OverlayHideResult {
        if (coordinator.owner() == null) {
            coordinator.requestHide(handle)
            context.stopService(Intent(context, BlockOverlayService::class.java))
            return OverlayOutcome.AlreadyAbsent(handle)
        }
        coordinator.requestHide(handle)
        try {
            val intent = Intent(context, BlockOverlayService::class.java).setAction(BlockOverlayService.ACTION_RECONCILE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return OverlayOutcome.Failed(handle, "Overlay reconciliation service could not start")
        }
        return OverlayOutcome.Requested(handle)
    }

    private fun startReconciliationService() {
        val intent = Intent(context, BlockOverlayService::class.java)
            .setAction(BlockOverlayService.ACTION_RECONCILE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

}
