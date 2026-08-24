package com.tudominio.parentalcontrol.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One row in the local outbox.
 *
 * Lifecycle:
 *  - `processed = false` AND `in_flight = false` → ready to be claimed by
 *    a drainer.
 *  - `in_flight = true` → a drainer has selected this row and is sending
 *    it. The drainer MUST clear `in_flight` (via [markProcessedFromClaim]
 *    or [incrementRetriesFromClaim]) or the row will be re-claimable only
 *    after a stale-claim sweep. The `in_flight_at` stamp is the
 *    observation used by the stale-claim sweeper.
 *  - `processed = true` → terminal. A periodic sweeper deletes rows older
 *    than N days via `OutboxDao.deleteProcessedOlderThan`.
 *
 * The `in_flight` flag is the in-DB lock that prevents two drainers
 * (the `OutboxDrainer` worker AND the legacy `SyncManager.drainOutbox`
 * path called from `SyncWorker` / `enqueue`) from picking the same row
 * and double-sending it. See the `outbox-drain` spec scenario
 * "Crash between send and mark-processed re-sends safely" — without
 * `in_flight`, a concurrent drainer between SELECT and UPDATE could
 * see the same row and POST it twice.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val tipo: String,
    val payload_json: String,
    val dedup_key: String?,
    val retries: Int = 0,
    val created_at: String,
    val server_date: String,
    val processed: Boolean = false,
    val processed_at: String? = null,
    /**
     * True while a drainer is sending this row. Set by
     * [com.tudominio.parentalcontrol.data.db.OutboxDao.claimPendingItems]
     * inside the same transaction that selects it. Cleared by
     * `markProcessedFromClaim`, `incrementRetriesFromClaim`, or
     * `releaseClaim` / `releaseStaleClaims`. New column in v9.
     */
    @ColumnInfo(defaultValue = "0")
    val in_flight: Boolean = false,
    /**
     * ISO-8601 timestamp set when `in_flight` flips to true. Used by
     * the stale-claim sweeper to recover rows that were claimed by a
     * drainer that crashed before it could clear the flag. Nullable
     * so existing rows pre-v9 stay at NULL → not eligible for stale
     * recovery (they were claimed in the pre-claim world, so we leave
     * them alone). New column in v9.
     */
    val in_flight_at: String? = null,
    /** Opaque UUID identifying the exact drainer ownership batch. */
    val claim_token: String? = null
)
