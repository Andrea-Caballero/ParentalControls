# Spec: boot-worker-lifecycle

## Purpose
Defines the contract for WorkManager jobs scheduled in response to `BOOT_COMPLETED`. Boot-time enqueues are conditional on a restored auth session; when no session exists, previously-persisted unique works are cancelled to prevent wasted retries while logged out.

## Requirements

### Requirement: BootReceiver gates post-boot work on a restored session
`BootReceiver.onReceive` MUST call `DeviceAuthManager.restoreSession()` before scheduling any WorkManager work. When the result is non-null, the receiver MUST enqueue the post-boot sync chain via `scheduleSyncAfterBoot(context)`. When the result is null, the receiver MUST NOT enqueue `scheduleSyncAfterBoot` and MUST cancel the three persisted unique works.

#### Scenario: Session present schedules the sync chain
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns a non-null session
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL be invoked
- AND no `cancelWork` calls SHALL be issued.

#### Scenario: No session cancels persisted works and skips scheduling
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns `null`
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL NOT be invoked
- AND `WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")` SHALL be called
- AND `WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME)` SHALL NOT be called
- AND `WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)` SHALL NOT be called.

### Requirement: After restoreSession, access token is rehydrated before scheduling
When `BootReceiver.onReceive` runs the `BOOT_COMPLETED` branch and `DeviceAuthManager.restoreSession()` returns a non-null session, the receiver MUST invoke `DeviceAuthManager.authenticateOrCreate()` between `restoreSession()` and any WorkManager enqueue so the in-memory access token is rehydrated from the persisted session before any authenticated request fires. When `restoreSession()` returns null, `authenticateOrCreate()` MUST NOT be invoked.

This rehydration step is required because `loadPersistedState` restores only `deviceId` + `isPaired` from SharedPreferences; the in-memory `currentAccessToken` is left null until the boot path explicitly rehydrates it via `authenticateOrCreate()`. Without this step, any authenticated request (sync, pull, drain) returns `Offline` after a reboot until the user re-pairs.

#### Scenario: Session present rehydrates the token before the sync chain runs
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns a non-null session
- WHEN `BootReceiver.onReceive` runs
- THEN `authManager.authenticateOrCreate()` SHALL be invoked after `restoreSession()` and before any `WorkScheduler.scheduleSyncAfterBoot` call
- AND the call order SHALL be `restoreSession()` → `authenticateOrCreate()` → `WorkScheduler.scheduleOutboxDrainer(context)` → `WorkerInitializer.initialize(context, isAfterBoot = true)`.

#### Scenario: No session skips rehydration
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns `null`
- WHEN `BootReceiver.onReceive` runs
- THEN `authManager.authenticateOrCreate()` SHALL NOT be invoked
- AND `WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")` SHALL be called
- AND the `else` branch SHALL proceed straight to the cancel call without any rehydration attempt.

### Requirement: scheduleSyncAfterBoot enqueues the post-boot chain
`scheduleSyncAfterBoot(context)` MUST enqueue `${SyncWorker.WORK_NAME}_after_boot` as unique work and MUST chain `HeartbeatWorker.WORK_NAME` and `OutboxDrainer.WORK_NAME` to run after the initial sync. It MUST NOT be invoked when no session is present at boot.

The chain ordering (sync → heartbeat → outbox) is a composed pipeline: `SyncWorker` pulls the server state, `HeartbeatWorker` reports the post-sync liveness signal back to the server, and `OutboxDrainer` flushes any local writes queued during the offline period. The chain runs as a `beginUniqueWork(...).then(...).then(...)` flow, so a failure in any step terminates the rest of the chain. The chain is one-shot (`OneTimeWorkRequestBuilder`), not periodic; periodic schedules for `HeartbeatWorker` and `OutboxDrainer` are set up separately by `scheduleAllPeriodicWork` and are NOT touched by the boot path.

#### Scenario: Chain ordering at boot with a session
- GIVEN a session is restored at boot
- WHEN `scheduleSyncAfterBoot` is invoked
- THEN `${SyncWorker.WORK_NAME}_after_boot` SHALL be enqueued first
- AND `HeartbeatWorker.WORK_NAME` SHALL be enqueued to run after sync success
- AND `OutboxDrainer.WORK_NAME` SHALL be enqueued to run after sync success.

#### Scenario: scheduleSyncAfterBoot is not called without a session
- GIVEN `DeviceAuthManager.restoreSession()` returns `null` at boot
- WHEN `BootReceiver.onReceive` completes
- THEN `scheduleSyncAfterBoot` SHALL NOT have been invoked from the boot path.

### Requirement: Cancel does not disturb after-pairing or periodic schedules
The cancel call from `BootReceiver` MUST target the three boot-specific unique work names. It MUST NOT cancel `${WORK_NAME}_after_pairing` schedules (distinct unique-work name) or periodic workers (different `enqueueUniquePeriodic*` API), and MUST NOT touch any in-flight worker run.

#### Scenario: After-pairing schedule survives a reboot
- GIVEN the user paired the device before the last reboot
- AND `scheduleSyncAfterPairing` enqueued `${SyncWorker.WORK_NAME}_after_pairing`
- WHEN the device reboots and `BootReceiver` runs without a session
- THEN `${SyncWorker.WORK_NAME}_after_boot` SHALL be cancelled
- AND `${SyncWorker.WORK_NAME}_after_pairing` SHALL remain scheduled.

#### Scenario: Periodic workers stay scheduled across reboots
- GIVEN `OutboxDrainer` periodic work is scheduled with `ExistingPeriodicWorkPolicy.KEEP`
- WHEN the device reboots and `BootReceiver` runs without a session
- THEN the periodic `OutboxDrainer` entry SHALL remain in the WorkManager database
- AND only the boot-specific unique work name SHALL be cancelled.

### Requirement: In-flight worker runs are not affected by the gate
Workers that started before the gate check MUST read `restoreSession()` at run time and behave accordingly. The gate in `BootReceiver` MUST NOT cancel or interrupt an already-running worker.

#### Scenario: Worker started before reboot completes its run
- GIVEN a worker run was started before the device rebooted
- WHEN the reboot completes and `BootReceiver` runs
- THEN no attempt SHALL be made to interrupt or cancel the prior run
- AND WorkManager SHALL treat the prior run as terminated by the OS.

#### Scenario: Session restored mid-worker-run
- GIVEN a worker started when no session was present
- AND the user signs in mid-run via the app UI
- WHEN the worker reads `restoreSession()` at its next checkpoint
- THEN it SHALL observe the newly-restored session
- AND MAY proceed to call `SyncManager.sendOutboxItem` for pending rows.