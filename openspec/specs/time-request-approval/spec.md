# Spec: time-request-approval

## Purpose

Closes the loop on a child asking for more screen time: child outbox → parent verdict → `grants` row + FCM → child's `EnforcementController` reads the new grant from Room. The Solicitudes tab that surfaces pending requests to the parent respects the multi-child picker (`parent-device-list`) and scopes its rendering to the parent's currently-selected child when the picker is visible (V1 client-side filter; server-side refactor deferred to a later change).
## ADDED Requirements

### Requirement: Child enqueues a time request via the outbox
The child app SHALL write a `time_request` payload into local `outbox`; `OutboxDrainer` SHALL deliver it to the `time_requests` table.

#### Scenario: Local insert enqueues the request
- **WHEN** the child taps "Ask for more time" with `minutes = 30`, `reason = "homework"`,
- **THEN** a row SHALL be inserted into `outbox` with `tipo = "time_request"` and payload `{ device_id, minutes_requested: 30, reason: "homework" }`; the UI shows "queued".

#### Scenario: Drained row creates a PENDING time_request
- **WHEN** the `OutboxDrainer` picks the row up,
- **THEN** a row SHALL be created in `time_requests` with `status = "PENDING"` and the local row SHALL be marked `processed = TRUE`.

#### Scenario: Offline request is sent later
- **WHEN** the device is offline at request time,
- **THEN** the local insert still succeeds and the request SHALL be sent automatically once the drainer runs.

### Requirement: Parent lists pending requests
The parent app SHALL fetch `time_requests` rows with `status = "PENDING"` for the parent's devices and render them in `RequestCard` components on `DeviceDetailScreen`.

#### Scenario: RLS-aware query scopes to the parent
- **WHEN** `ParentRepository.getPendingRequests()` is called,
- **THEN** the app SHALL query `time_requests` via Supabase REST filtered by `status = "PENDING"`, with the device join enforcing `parent_id = auth.uid()` via RLS.

#### Scenario: Non-empty list renders RequestCards
- **WHEN** the list is non-empty,
- **THEN** `DeviceDetailScreen` SHALL render one `RequestCard` per request showing device name, minutes, reason, and "Approve" / "Deny" buttons.

#### Scenario: Refresh removes stale PENDING items
- **WHEN** the parent pulls to refresh,
- **THEN** the list SHALL be re-fetched and any request no longer `PENDING` SHALL be removed.

### Requirement: Parent issues approve or deny verdict
Tapping "Approve" / "Deny" on a `RequestCard` SHALL call the `approve-request` edge function with the right `decision`.

#### Scenario: Approve creates a grants row and FCMs the child
- **WHEN** the parent taps "Approve",
- **THEN** `ParentRepository.approveRequest(requestId, minutes)` SHALL `POST /functions/v1/approve-request` with `{ request_id, decision: "APPROVED", minutes }`; the edge function SHALL insert a `grants` row (`status = "APPROVED"`, `source = "MANUAL"`, `expires_at = NOW() + minutes`) and FCM the child's active tokens in `device_push_tokens`.

#### Scenario: Deny closes without a grant
- **WHEN** the parent taps "Deny",
- **THEN** the edge function SHALL set `time_requests.status = "DENIED"`, `responded_at = NOW()`, SHALL NOT insert a `grants` row, and MAY FCM the denial reason.

#### Scenario: UI updates optimistically and rolls back on failure
- **WHEN** the verdict call succeeds,
- **THEN** the parent UI SHALL optimistically remove the `RequestCard`; **WHEN** it fails, the UI SHALL roll back and show a "Retry" snackbar.

### Requirement: Child receives the verdict and enforcement applies it
On FCM receipt the child SHALL sync grants; `EnforcementController` SHALL pick up the new `grants` row from Room without an app restart.

#### Scenario: FCM triggers a grants sync
- **WHEN** the child receives an FCM of type `grant.approved`,
- **THEN** `FirebaseMessagingService` SHALL trigger `SyncManager.syncGrants()` which upserts the new `grants` row into Room.

#### Scenario: New grant is reflected in remaining time
- **WHEN** the next `EnforcementController` poll runs after the sync,
- **THEN** the new `grants` row SHALL be reflected in remaining screen time and the UI SHALL show the new allowance.

#### Scenario: Expired grant reverts
- **WHEN** a grant's `expires_at` passes,
- **THEN** `EnforcementController` SHALL revert to the pre-grant state by reading `expires_at` from Room (server-side `cleanup_expired_grants` is a background concern).

### Requirement: Solicitudes tab auto-refreshes when visible
The parent dashboard SHALL re-fetch pending time requests whenever the user switches to the Solicitudes tab, so the list is never stale relative to the last tap.

#### Scenario: First tap on the Solicitudes tab triggers a fetch
- **WHEN** the parent dashboard's `selectedTab` becomes the Solicitudes tab for the first time in the session,
- **THEN** a `LaunchedEffect(selectedTab)` keyed on tab 1 SHALL invoke `loadPendingRequests()` on `ParentViewModel`, which SHALL call `ParentRepository.getPendingRequests()` against `time_requests`.

#### Scenario: Re-tapping the Solicitudes tab triggers a fresh fetch
- **WHEN** the parent switches away from the Solicitudes tab and back to it,
- **THEN** the `LaunchedEffect(selectedTab)` SHALL re-invoke `loadPendingRequests()` and SHALL NOT rely on cached `StateFlow` data alone.

#### Scenario: In-flight fetch is deduped
- **WHEN** `loadPendingRequests()` is already running,
- **THEN** a second tab tap SHALL NOT issue a parallel fetch (gated by the view model's `isLoading` flag) and SHALL keep the spinner visible.

#### Scenario: Other tabs do not trigger a Solicitudes fetch
- **WHEN** `selectedTab` is any tab other than the Solicitudes tab,
- **THEN** `loadPendingRequests()` SHALL NOT be invoked by the `LaunchedEffect`.

### Requirement: SolicitudesPollingWorker runs on a 5-minute cadence
The system SHALL run a periodic `SolicitudesPollingWorker` that mirrors `HeartbeatWorker`'s lifecycle and fetches pending time requests for the currently-authenticated parent, so the UI is fresh even when the parent never opens the tab.

#### Scenario: Worker is scheduled with a 5-minute repeat interval
- **WHEN** `WorkScheduler.scheduleAllPeriodicWork(context)` runs,
- **THEN** `SolicitudesPollingWorker` SHALL be enqueued via `PeriodicWorkRequestBuilder<SolicitudesPollingWorker>(5.minutes)` with the same constraints and unique work name pattern used by `HeartbeatWorker`.

#### Scenario: Worker fetches pending requests for the signed-in parent
- **WHEN** the worker executes,
- **THEN** it SHALL resolve the current parent session and SHALL call `ParentRepository.getPendingRequests()`, which queries `time_requests` filtered by `status = "PENDING"` with RLS enforcing `parent_id = auth.uid()`.

#### Scenario: Worker skips when offline
- **WHEN** `SyncManager.connectionState` is anything other than `CONNECTED` at worker start,
- **THEN** the worker SHALL return `Result.success()` without performing the network call (mirrors `SyncManager.sync()`'s connectivity gate).

#### Scenario: Worker retries on transient failure
- **WHEN** `ParentRepository.getPendingRequests()` throws a transient network or server error,
- **THEN** the worker SHALL return `Result.retry()` so WorkManager reschedules with backoff, and SHALL NOT swallow the error silently.

#### Scenario: Worker is idempotent across overlapping runs
- **WHEN** a worker run overlaps with the previous run that has not yet completed,
- **THEN** WorkManager's unique-work enqueue SHALL keep only one active instance and SHALL NOT enqueue duplicate fetches.

### Requirement: Successful worker fetch updates the pending-requests StateFlow
The system SHALL propagate a successful worker fetch into `ParentViewModel.pendingRequests` so the next time the parent opens the Solicitudes tab, the UI reflects newly-arrived requests without a manual reload.

#### Scenario: Worker success pushes fresh data to the StateFlow
- **WHEN** `SolicitudesPollingWorker` completes its fetch and the parent is currently signed in,
- **THEN** it SHALL post the resulting list to `ParentViewModel.pendingRequests` via `StateFlow.update {}` (or equivalent) so any collector on the Solicitudes tab sees the new list.

#### Scenario: Worker with no signed-in parent is a no-op
- **WHEN** the worker runs and no parent session exists,
- **THEN** the worker SHALL return `Result.success()` and SHALL NOT mutate `ParentViewModel.pendingRequests`.

#### Scenario: UI reflects new requests without manual reload
- **WHEN** the parent opens the Solicitudes tab after the worker has posted new data,
- **THEN** `RequestCard` components SHALL render the freshly-polled rows on first composition, with no pull-to-refresh required.

### Requirement: SolicitudesPollingWorker is wired into scheduleAllPeriodicWork
The system SHALL enqueue `SolicitudesPollingWorker` alongside `HeartbeatWorker`, `OutboxDrainer`, and `Reconciliation` so it is registered exactly once on app start.

#### Scenario: scheduleAllPeriodicWork enqueues all four periodic workers
- **WHEN** `WorkScheduler.scheduleAllPeriodicWork(context)` is invoked at app start or login,
- **THEN** it SHALL enqueue `SolicitudesPollingWorker` in addition to the existing `HeartbeatWorker`, `OutboxDrainer`, and `Reconciliation` jobs, each with its own unique work name and `KEEP` existing-work policy.

#### Scenario: Worker survives process restart
- **WHEN** the parent app process is killed and later relaunched,
- **THEN** WorkManager SHALL resume `SolicitudesPollingWorker` on its 5-minute cadence without the user opening the dashboard.

#### Scenario: Existing periodic jobs are not duplicated
- **WHEN** `scheduleAllPeriodicWork` is invoked a second time,
- **THEN** the existing `solicitudes_polling` unique work SHALL be kept (not replaced) and SHALL NOT produce a second concurrent fetcher.

### Requirement: Solicitudes tab filters pending requests by selectedChildId

The Solicitudes tab on `DashboardScreen` SHALL filter its rendered `RequestCard` list by `ParentViewModel._selectedChildId.value`. When the value is `null` (chip = "Todos", or picker hidden because N ≤ 1), the tab SHALL render every pending request returned by `ParentRepository.getPendingRequests()`. When the value is a specific `childId`, the tab SHALL render only requests whose `deviceId` belongs to a `ChildDevice` whose `child?.id` matches. The filter SHALL be applied at the UI layer in `DashboardScaffold` against the already-fetched list — no new HTTP call SHALL be issued for a chip tap.

#### Scenario: "Todos" shows every pending request
- **WHEN** `_selectedChildId.value == null` and the parent opens the Solicitudes tab,
- **THEN** the tab SHALL render every entry in `_pendingRequests.value` with no filtering, and the badge SHALL match the rendered count.

#### Scenario: A child-specific chip narrows the Solicitudes tab
- **WHEN** the parent taps a per-child chip for childId = "c-123",
- **THEN** the Solicitudes tab SHALL render only requests whose `deviceId` is in the set of devices belonging to that child, and a tap on "Aprobar" / "Denegar" SHALL still hit `ParentRepository.approveRequest` with the original `requestId` (no client-side mutation of the request id).

#### Scenario: Filter is purely client-side
- **WHEN** the parent switches chips,
- **THEN** the repository SHALL NOT receive a new call to `getPendingRequests()` and the existing `_pendingRequests` `StateFlow` SHALL be reused as the source of truth.

### Requirement: V2 server-side Solicitudes filter

`ParentRepository.getPendingRequests(selectedChildId: String?)` SHALL append `device_id=in.(<comma-separated device ids>)` to the Postgrest query when `selectedChildId != null`, so the server returns only that child's pending requests. The static clauses (`status=eq.PENDING`, `order=created_at.desc`, `select=*,devices(device_name)`) MUST stay intact and the existing `time_requests_parent_select` RLS policy remains the security boundary — the `device_id` clause is a transport optimization. When `selectedChildId` is `null` (Todos chip) the URL stays parameter-less and RLS alone scopes the rows. If the selected child has zero associated device ids, the call SHALL return `Result.success(emptyList())` rather than emitting a malformed `device_id=in.()` query. The no-arg `getPendingRequests()` overload stays callable by `SolicitudesPollingWorker` (Todos-only polling semantics) and delegates to the parameterized form with `selectedChildId = null`.

#### Scenario: Per-child fetch appends the device_id in-list filter
- **WHEN** `ParentViewModel.loadPendingRequests()` calls `getPendingRequests(selectedChildId = "child-lucas-id")`,
- **THEN** the Postgrest request SHALL include `device_id=in.(<device ids belonging to child-lucas-id>)` alongside the static clauses, and the response SHALL contain only that child's pending requests.

#### Scenario: Todos fetch omits the device_id clause
- **WHEN** the parent has the Todos chip active and either `SolicitudesPollingWorker` or `ParentViewModel` calls the no-arg `getPendingRequests()`,
- **THEN** the Postgrest request SHALL NOT include a `device_id=` parameter, and the existing RLS-scoped fetch returns every pending request for the parent.

#### Scenario: Empty device-id set returns success with empty list
- **WHEN** the resolved device-id set for the selected child is empty (e.g., the child just unpaired),
- **THEN** the call SHALL return `Result.success(emptyList())` and `DashboardScreen` SHALL render its existing "Sin solicitudes" empty state.

## Out of scope
- Parent-side FCM token registration (not needed for the verdict).
- Modifying `EnforcementController` (already grant-aware from hotfix #2).
- Batch approval, scheduling, or auto-approval rules.
- Editing `time_requests.reason` from the parent UI.
- Per-child notifications (covered by `app-block-policy`).
- Realtime push of new pending requests into the Solicitudes tab without a tab switch (acceptable V1 window; covered by `SolicitudesPollingWorker`).
