package com.tudominio.parentalcontrol.overlay

import android.view.View
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@JvmInline value class ActorId private constructor(internal val raw: Long) {
    internal companion object { fun issue(v: Long) = ActorId(v) }
}
@JvmInline value class RequestId private constructor(internal val raw: Long) {
    internal companion object { fun issue(v: Long) = RequestId(v) }
}
@JvmInline value class OverlayHandle private constructor(internal val raw: Long) {
    internal companion object { fun issue(v: Long) = OverlayHandle(v) }
}
@JvmInline value class OwnerId private constructor(internal val raw: Long) {
    internal companion object { fun issue(v: Long) = OwnerId(v) }
}
@JvmInline value class ObservationId private constructor(internal val raw: Long) {
    internal companion object { fun issue(v: Long) = ObservationId(v) }
}

class PhysicalObserverCapability internal constructor(internal val token: Long, internal val actor: ActorId)

data class ExactOwnerIdentity(
    val handle: OverlayHandle, val owner: OwnerId, val view: View, val attachment: Attachment,
) {
    override fun equals(other: Any?) = other is ExactOwnerIdentity && handle == other.handle && owner == other.owner && view === other.view && attachment == other.attachment
    override fun hashCode() = (((31 * handle.hashCode() + owner.hashCode()) * 31 + System.identityHashCode(view)) * 31 + attachment.hashCode())
}
data class ObservationMetadata(
    val physicallyObserved: Boolean, val attached: Boolean?, val observedAtNanos: Long, val status: ObservationStatus,
)

enum class ObservationStatus { UNKNOWN, RECONCILIATION_REQUIRED }
class UnknownEvidence private constructor(
    val source: PhysicalObserverCapability, val actor: ActorId, val request: RequestId, val handle: OverlayHandle,
    val owner: OwnerId?, val observation: ObservationId, val metadata: ObservationMetadata,
) {
    override fun equals(other: Any?) = other is UnknownEvidence &&
        source === other.source && actor == other.actor && request == other.request &&
        handle == other.handle && owner == other.owner && observation == other.observation && metadata == other.metadata

    override fun hashCode() = listOf(source, actor, request, handle, owner, observation, metadata).hashCode()

    internal companion object {
        fun fromObserver(
            source: PhysicalObserverCapability,
            actor: ActorId,
            request: RequestId,
            handle: OverlayHandle,
            owner: OwnerId?,
            observation: ObservationId,
            metadata: ObservationMetadata,
        ) = UnknownEvidence(source, actor, request, handle, owner, observation, metadata)
    }
}
sealed interface DebtKey {
    data class ExactOwner(val identity: ExactOwnerIdentity) : DebtKey
    data class UnknownObservation(val observation: ObservationId) : DebtKey
}

enum class Attachment { ATTACHING, ATTACHED, DETACH_UNRESOLVED }
sealed interface AllocatorOutcome<out T> {
    data class Issued<T>(val id: T) : AllocatorOutcome<T>
    data object Exhausted : AllocatorOutcome<Nothing>
}
sealed interface EvidenceRejection {
    data object AUTHORITY : EvidenceRejection
    data object DUPLICATE : EvidenceRejection
    data object CONFLICT : EvidenceRejection
    data object OVERFLOW : EvidenceRejection
    data object METADATA : EvidenceRejection
}
sealed interface DebtRejection {
    data object INVALID : DebtRejection
    data object OVERFLOW : DebtRejection
    data object RETRY_EXHAUSTED : DebtRejection
}
sealed interface ClaimRejection {
    data object STALE : ClaimRejection
    data object BLOCKED : ClaimRejection
    data object EXHAUSTED : ClaimRejection
}
sealed interface CoreRejection {
    data object STALE : CoreRejection
    data object TERMINAL : CoreRejection
    data object UNSUPPORTED : CoreRejection
}
sealed interface CoreOutcome {
    data object Accepted : CoreOutcome
    data class Rejected(val reason: Any) : CoreOutcome
    data class Failed(val reason: String) : CoreOutcome
}
sealed interface ClaimOutcome {
    data class Claimed(val owner: ExactOwnerIdentity) : ClaimOutcome
    data class Rejected(val reason: ClaimRejection) : ClaimOutcome
}
sealed interface TerminalOutcome {
    data object CLOSED : TerminalOutcome
    data class Rejected(val reason: CoreRejection) : TerminalOutcome
}
class PreClaimCertificate internal constructor(internal val actor: ActorId, internal val request: RequestId, internal val nonce: Long)

internal object ObserverBoundary {
    private val token = AtomicLong(1)
    private val observations = AtomicLong(1)
    fun capability(actor: ActorId) = PhysicalObserverCapability(token.getAndIncrement(), actor)
    fun unknown(capability: PhysicalObserverCapability, request: RequestId, handle: OverlayHandle, owner: OwnerId?) =
        UnknownEvidence.fromObserver(capability, capability.actor, request, handle, owner, ObservationId.issue(observations.getAndIncrement()), ObservationMetadata(true, null, observations.get(), ObservationStatus.RECONCILIATION_REQUIRED))
}
private class CheckedAllocator(private var next: Long = 1) {
    private var exhausted = false
    fun issue(): Long? {
        if (exhausted) return null
        val issued = next
        if (issued == Long.MAX_VALUE) exhausted = true else next = issued + 1
        return issued
    }
}
internal class PhysicalOverlayOwnership {
    private val lock = ReentrantLock()
    private val actorIds = CheckedAllocator()
    private val requestIds = CheckedAllocator()
    private val handleIds = CheckedAllocator()
    private val ownerIds = CheckedAllocator()
    private val certificateNonces = CheckedAllocator()
    private val actors = mutableSetOf<ActorId>()
    private val requests = mutableMapOf<RequestId, ActorId>()
    private val handles = mutableMapOf<OverlayHandle, RequestId>()
    private val observerCapabilities = mutableSetOf<PhysicalObserverCapability>()
    private val evidence = mutableMapOf<ObservationId, UnknownEvidence>()
    private val debt = mutableMapOf<DebtKey, Int>()
    private val terminal = ArrayDeque<RequestId>()
    private val terminalSet = mutableSetOf<RequestId>()
    private val certificates = mutableMapOf<RequestId, PreClaimCertificate>()
    private val boundaryStarted = mutableSetOf<RequestId>()
    private var currentActor: ActorId? = null
    private var pending: RequestId? = null
    private var owner: ExactOwnerIdentity? = null
    private var evidenceOverflow = false
    private var debtOverflow = false
    fun issueActor(): AllocatorOutcome<ActorId> = lock.withLock {
        actorIds.issue()?.let { ActorId.issue(it).also { actor -> actors += actor; currentActor = actor } }
            ?.let { AllocatorOutcome.Issued(it) } ?: AllocatorOutcome.Exhausted
    }
    fun rebindActor(actor: ActorId, request: RequestId): CoreOutcome = lock.withLock {
        if (currentActor != actor || requests[request] == null || pending != request || request in terminalSet) {
            return@withLock CoreOutcome.Rejected(CoreRejection.STALE)
        }
        requests[request] = actor
        CoreOutcome.Accepted
    }
    fun issueRequest(actor: ActorId): AllocatorOutcome<RequestId> = lock.withLock {
        if (currentActor != actor || actor !in actors) return@withLock AllocatorOutcome.Exhausted
        requestIds.issue()?.let { RequestId.issue(it).also { request -> pending?.let(::retireRequest); requests[request] = actor; pending = request } }
            ?.let { AllocatorOutcome.Issued(it) } ?: AllocatorOutcome.Exhausted
    }
    fun issueHandle(actor: ActorId, request: RequestId): AllocatorOutcome<OverlayHandle> = lock.withLock {
        if (currentActor != actor || requests[request] != actor || pending != request || request in terminalSet) return@withLock AllocatorOutcome.Exhausted
        handleIds.issue()?.let { OverlayHandle.issue(it).also { handle -> handles[handle] = request } }
            ?.let { AllocatorOutcome.Issued(it) } ?: AllocatorOutcome.Exhausted
    }
    fun bindObserver(capability: PhysicalObserverCapability): CoreOutcome = lock.withLock {
        if (capability.actor !in actors) return@withLock CoreOutcome.Rejected(EvidenceRejection.AUTHORITY)
        if (capability in observerCapabilities) return@withLock CoreOutcome.Rejected(EvidenceRejection.DUPLICATE)
        observerCapabilities += capability
        CoreOutcome.Accepted
    }
    fun claim(actor: ActorId, request: RequestId, handle: OverlayHandle, view: View): ClaimOutcome = lock.withLock {
        if (currentActor != actor || requests[request] != actor || pending != request || handles[handle] != request || request in terminalSet) {
            return@withLock ClaimOutcome.Rejected(ClaimRejection.STALE)
        }
        if (
            owner != null ||
            evidence.isNotEmpty() ||
            debt.isNotEmpty() ||
            evidenceOverflow ||
            debtOverflow ||
            request in boundaryStarted
        ) {
            return@withLock ClaimOutcome.Rejected(ClaimRejection.BLOCKED)
        }
        val id = ownerIds.issue() ?: return@withLock ClaimOutcome.Rejected(ClaimRejection.EXHAUSTED)
        val exact = ExactOwnerIdentity(handle, OwnerId.issue(id), view, Attachment.ATTACHING)
        owner = exact
        boundaryStarted += request
        ClaimOutcome.Claimed(exact)
    }
    fun markAttached(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): CoreOutcome = lock.withLock {
        if (!reconciliationAuthorized(actor, request, expected) || expected.attachment != Attachment.ATTACHING) return@withLock CoreOutcome.Rejected(CoreRejection.STALE)
        owner = expected.copy(attachment = Attachment.ATTACHED)
        CoreOutcome.Accepted
    }
    fun markDetachUnresolved(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): CoreOutcome = lock.withLock {
        if (!reconciliationAuthorized(actor, request, expected) || expected.attachment == Attachment.DETACH_UNRESOLVED) return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        val unresolved = expected.copy(attachment = Attachment.DETACH_UNRESOLVED)
        val key = DebtKey.ExactOwner(unresolved)
        if (debt.size >= 32 && key !in debt) { debtOverflow = true; return@withLock CoreOutcome.Rejected(DebtRejection.OVERFLOW) }
        owner = unresolved
        debt[key] = debt[key] ?: 1
        CoreOutcome.Accepted
    }
    fun admitUnknown(value: UnknownEvidence): CoreOutcome = lock.withLock {
        if (
            value.source !in observerCapabilities ||
            value.source.actor != value.actor ||
            !authorized(value.actor, value.request) ||
            pending != value.request ||
            value.request in terminalSet ||
            handles[value.handle] != value.request
        ) return@withLock CoreOutcome.Rejected(EvidenceRejection.AUTHORITY)
        if (!value.metadata.physicallyObserved || value.metadata.observedAtNanos <= 0L) return@withLock CoreOutcome.Rejected(EvidenceRejection.METADATA)
        evidence[value.observation]?.let { return@withLock if (it == value) CoreOutcome.Rejected(EvidenceRejection.DUPLICATE) else CoreOutcome.Rejected(EvidenceRejection.CONFLICT) }
        if (evidence.size >= 32) { evidenceOverflow = true; return@withLock CoreOutcome.Rejected(EvidenceRejection.OVERFLOW) }
        evidence[value.observation] = value
        CoreOutcome.Accepted
    }
    fun admitDebt(actor: ActorId, request: RequestId, key: DebtKey): CoreOutcome = lock.withLock {
        if (!authorized(actor, request) || pending != request || !debtKeyBelongs(key, request)) return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        val valid = when (key) {
            is DebtKey.ExactOwner -> sameOwner(owner, key.identity) && owner?.attachment == Attachment.DETACH_UNRESOLVED
            is DebtKey.UnknownObservation -> key.observation in evidence
        }
        if (!valid || key in debt) return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        if (debt.size >= 32) { debtOverflow = true; return@withLock CoreOutcome.Rejected(DebtRejection.OVERFLOW) }
        debt[key] = 1
        CoreOutcome.Accepted
    }
    fun attemptDebt(actor: ActorId, request: RequestId, key: DebtKey): CoreOutcome = lock.withLock {
        if (!authorized(actor, request) || pending != request || !debtKeyBelongs(key, request)) return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        val attempts = debt[key] ?: return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        if (attempts >= 3) CoreOutcome.Rejected(DebtRejection.RETRY_EXHAUSTED) else { debt[key] = attempts + 1; CoreOutcome.Accepted }
    }
    fun reconcileExactDebt(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): CoreOutcome = lock.withLock {
        if (currentActor != actor || requests[request] != actor || pending != request || request in terminalSet ||
            handles.values.none { it == request } || !sameOwner(owner, expected) || expected.attachment != Attachment.DETACH_UNRESOLVED) {
            return@withLock CoreOutcome.Rejected(CoreRejection.STALE)
        }
        if (debt.remove(DebtKey.ExactOwner(expected)) == null) return@withLock CoreOutcome.Rejected(DebtRejection.INVALID)
        owner = null
        CoreOutcome.Accepted
    }
    fun preClaimAbsent(actor: ActorId, request: RequestId): PreClaimCertificate? = lock.withLock {
        if (!authorized(actor, request) || pending != request || request in boundaryStarted || owner != null || evidence.isNotEmpty() || debt.isNotEmpty() || evidenceOverflow || debtOverflow) null
        else certificates[request] ?: certificateNonces.issue()?.let { PreClaimCertificate(actor, request, it).also { certificate -> certificates[request] = certificate } }
    }

    fun closePreClaim(certificate: PreClaimCertificate): TerminalOutcome = lock.withLock {
        if (certificates[certificate.request] !== certificate || !authorized(certificate.actor, certificate.request) || pending != certificate.request || certificate.request in boundaryStarted || owner != null || evidence.isNotEmpty() || debt.isNotEmpty()) return@withLock TerminalOutcome.Rejected(CoreRejection.STALE)
        certificates.remove(certificate.request)
        handles.entries.removeIf { it.value == certificate.request }
        pending = null
        terminalSet += certificate.request
        terminal += certificate.request
        if (terminal.size > 64) terminalSet.remove(terminal.removeFirst())
        TerminalOutcome.CLOSED
    }

    fun owner() = lock.withLock { owner }
    fun isCurrentActor(actor: ActorId) = lock.withLock { currentActor == actor }
    fun currentActor() = lock.withLock { currentActor }
    fun attachment(expected: ExactOwnerIdentity) = lock.withLock { if (sameOwner(owner, expected)) owner?.attachment else null }
    fun confirmDetachUnresolved(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): CoreOutcome = lock.withLock {
        if (!reconciliationAuthorized(actor, request, expected) || expected.attachment != Attachment.DETACH_UNRESOLVED || !sameOwner(owner, expected)) {
            CoreOutcome.Rejected(CoreRejection.STALE)
        } else {
            CoreOutcome.Accepted
        }
    }
    fun confirmRemoval(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): CoreOutcome =
        reconcileExactDebt(actor, request, expected)
    fun evidenceCount() = lock.withLock { evidence.size }
    fun debtCount() = lock.withLock { debt.size }
    fun historyCount() = lock.withLock { terminal.size }
    fun handleRetired(handle: OverlayHandle) = lock.withLock { handle !in handles }

    private fun authorized(actor: ActorId, request: RequestId) = currentActor == actor && requests[request] == actor
    private fun reconciliationAuthorized(actor: ActorId, request: RequestId, expected: ExactOwnerIdentity): Boolean {
        if (currentActor != actor || handles[expected.handle] != request || request !in boundaryStarted || !sameOwner(owner, expected)) return false
        val ownerActor = requests[request] ?: return false
        return if (ownerActor == actor) pending == request else pending != null && requests[pending] == actor
    }
    private fun retireRequest(request: RequestId) {
        certificates.remove(request)
        val inactive = request !in boundaryStarted && owner == null && evidence.none { it.value.request == request } && debt.keys.none { debtKeyBelongs(it, request) }
        if (inactive) {
            handles.entries.removeIf { it.value == request }
            terminalSet += request; terminal += request
            if (terminal.size > 64) terminalSet.remove(terminal.removeFirst())
        }
    }
    private fun debtKeyBelongs(key: DebtKey, request: RequestId) = when (key) {
        is DebtKey.ExactOwner -> handles[key.identity.handle] == request
        is DebtKey.UnknownObservation -> evidence[key.observation]?.request == request
    }
    private fun sameOwner(left: ExactOwnerIdentity?, right: ExactOwnerIdentity) = left != null && left.handle == right.handle && left.owner == right.owner && left.attachment == right.attachment && left.view === right.view
}
