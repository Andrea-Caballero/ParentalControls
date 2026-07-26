# Spec: outbox-drain

## Purpose
Ensures that locally enqueued events (time requests, app policy changes, usage heartbeats) eventually reach Supabase, even when the device was offline, by periodically draining the Room `outbox` table from a Hilt-aware WorkManager worker.

## ADDED Requirements

### Requirement: OutboxDrainer is a Hilt-aware WorkManager worker
A new `OutboxDrainer` class in `app/src/main/java/.../workers/OutboxDrainer.kt` SHALL be a WorkManager `CoroutineWorker`, annotated with `@HiltWorker`, and SHALL be constructed through the `HiltWorkerFactory` exposed by `ParentalControlApp` (already implements `Configuration.Provider`).

#### Scenario: Periodic work is scheduled at app startup
- **WHEN** `ParentalControlApp.onCreate` runs,
- **THEN** the app SHALL enqueue a `PeriodicWorkRequest` for `OutboxDrainer` under a unique work name (e.g., `outbox-drain-periodic`) using `ExistingPeriodicWorkPolicy.KEEP` so the schedule survives process restarts.

#### Scenario: Worker receives injected dependencies
- **WHEN** WorkManager instantiates the worker,
- **THEN** the `HiltWorkerFactory` SHALL inject `OutboxDao`, `SyncManager`, and the `KtorClient` (or equivalent) without the worker holding a direct `Context` reference for them.

#### Scenario: Single-writer gate prevents overlap
- **WHEN** the worker is invoked while a previous run is still in progress,
- **THEN** WorkManager SHALL skip the new invocation; the next periodic interval SHALL retry.

### Requirement: Worker drains pending outbox rows via SyncManager
On each run, the worker SHALL select all `outbox` rows where `processed = FALSE` ordered by `created_at` ASC, and SHALL pass each row to `SyncManager.sendOutboxItem`.

#### Scenario: Empty outbox is a fast success
- **WHEN** the worker starts a drain cycle and there are no `processed = FALSE` rows,
- **THEN** the worker SHALL return `Result.success()` immediately without making any network call.

#### Scenario: Each row is forwarded to SyncManager
- **WHEN** there are pending rows,
- **THEN** the worker SHALL iterate them in `created_at` ASC order and SHALL call `SyncManager.sendOutboxItem(row)` for each.

#### Scenario: Successful send marks the row processed
- **WHEN** `sendOutboxItem` returns success for a given row,
- **THEN** the worker SHALL set `processed = TRUE` and `processed_at = NOW()` in the `outbox` table.

### Requirement: Retries on transient failure, gives up on permanent failure
The worker SHALL increment `outbox.retries` on each failed send and SHALL re-queue with WorkManager's exponential backoff up to a configured maximum.

#### Scenario: Network error triggers retry
- **WHEN** `sendOutboxItem` throws a `NetworkException`,
- **THEN** the worker SHALL increment `retries` and SHALL return `Result.retry()` so WorkManager applies its exponential backoff.

#### Scenario: Permanent error is not retried forever
- **WHEN** `sendOutboxItem` throws a `PermanentException` (e.g., a 4xx response other than 408/429),
- **THEN** the worker SHALL mark the row `processed = TRUE` with `processed_at = NOW()`, SHALL record a `failed` flag in `payload`, and SHALL log a warning.

#### Scenario: Retry budget is enforced
- **WHEN** `outbox.retries` exceeds the configured maximum (default 10),
- **THEN** the row SHALL be marked `processed = TRUE` with `failed = true` written into `payload` and the worker SHALL log a final error.

### Requirement: No duplicate sends on retry
The drainer SHALL guarantee that any outbox row is sent at most once successfully, even across worker restarts and process kills.

#### Scenario: Crash between send and mark-processed re-sends safely
- **WHEN** the worker is killed after a successful `sendOutboxItem` call but before the `processed = TRUE` update is committed,
- **THEN** the next drain cycle SHALL see the row still `processed = FALSE` and SHALL re-send; the receiving endpoint MUST short-circuit duplicates via the `outbox.dedup_key` column.

#### Scenario: dedup_key is forwarded to the server
- **WHEN** a row carries a non-null `dedup_key`,
- **THEN** `SyncManager.sendOutboxItem` SHALL include `dedup_key` in the request payload so the receiver can deduplicate.

### Requirement: OutboxDrainer boot re-arm is gated on a restored session
The boot-time re-arm of the post-boot chain (which includes the `OutboxDrainer` work) MUST only occur when `DeviceAuthManager.restoreSession()` returns a non-null session at the time `BootReceiver` handles `BOOT_COMPLETED`. When the result is null, the boot re-arm MUST be skipped and any previously-persisted `OutboxDrainer.WORK_NAME` unique work originating from a prior boot MUST be cancelled.

#### Scenario: Session restored at boot re-arms the chain
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns a non-null session
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL be invoked
- AND the chain SHALL include `OutboxDrainer.WORK_NAME`.

#### Scenario: No session at boot skips the OutboxDrainer re-arm
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns `null`
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL NOT be invoked from the boot path
- AND no `OutboxDrainer` work SHALL be enqueued from `BootReceiver`.

### Requirement: Persisted OutboxDrainer unique work is cancelled when no session exists
When `BootReceiver` runs without a restored session, it MUST cancel any previously-persisted `OutboxDrainer.WORK_NAME` unique work that originated from a prior boot, so the worker does not retry indefinitely while logged out.

#### Scenario: Cancellation at boot with no session
- GIVEN `OutboxDrainer.WORK_NAME` unique work persisted from a prior boot
- AND `DeviceAuthManager.restoreSession()` returns `null` at the current boot
- WHEN `BootReceiver.onReceive` runs
- THEN `WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)` SHALL be called
- AND the persisted `OutboxDrainer` unique work SHALL be removed from the WorkManager database.

#### Scenario: Periodic OutboxDrainer schedule is preserved across reboot
- GIVEN `OutboxDrainer` is scheduled as a periodic worker via `ExistingPeriodicWorkPolicy.KEEP`
- AND `DeviceAuthManager.restoreSession()` returns `null` at the current boot
- WHEN `BootReceiver.onReceive` runs
- THEN the periodic entry SHALL remain in the WorkManager database
- AND only the boot-specific unique work name (if previously enqueued from boot) SHALL be cancelled.

## Out of scope
- Server-side dedup logic (assumed to live in the receiving edge functions / REST endpoints).
- Backpressure policies when `outbox` grows past N rows.
- A foreground-service notification while the worker runs.
- Manual "drain now" UI action.
