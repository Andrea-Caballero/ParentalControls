# Spec: child-entity

## Purpose

Introduces a first-class `Child` entity the parent app can attach to paired devices, so a single parent account can group devices by child and the dashboard can scope views per child. Owns the new `children` table, its RLS policies, the parent-side "name this child" prompt flow, and the backfill story for pre-existing devices.

## ADDED Requirements

### Requirement: children table models a child under a parent account

The system SHALL provide a `children` table keyed by `id` (UUID) with columns `id`, `parent_id` (UUID, FK → `auth.users.id`), `first_name` (TEXT, NOT NULL), `created_at` (TIMESTAMPTZ, default `now()`), and `updated_at` (TIMESTAMPTZ, default `now()`). The migration SHALL add `idx_children_parent_id` on `parent_id`. The system SHALL enforce a UNIQUE constraint on `(parent_id, first_name)` so a parent cannot create two children with the same first name.

#### Scenario: A parent creates a child row
- **WHEN** the parent-side "name this child" prompt flow inserts a child for an existing device,
- **THEN** a row SHALL be created in `children` with the parent's `auth.uid()` as `parent_id`, the supplied `first_name`, and server-default timestamps.

#### Scenario: Duplicate first name under the same parent is rejected
- **WHEN** the parent tries to create a second child with the same `first_name` under the same `parent_id`,
- **THEN** the insert SHALL fail with a UNIQUE-violation error and the UI SHALL surface a non-blocking "ya existe un hijo con ese nombre" message.

### Requirement: devices references children with a nullable FK

The migration SHALL add a nullable `child_id` (UUID) column to the `devices` table with a FK to `children.id` and `ON DELETE SET NULL`. Devices paired before the migration SHALL keep `child_id = NULL`.

#### Scenario: New pair creates the child row first, then links the device
- **WHEN** a parent pairs a new device via the parent-side "name this child" prompt,
- **THEN** a `children` row SHALL be inserted before the device row, the new `devices.child_id` SHALL reference it, and the dashboard SHALL render the new child name on the device card.

#### Scenario: Pre-migration device keeps a NULL child_id
- **WHEN** the migration runs against a database that already has devices,
- **THEN** every pre-existing `devices.child_id` SHALL be `NULL` until the backfill script assigns one.

#### Scenario: Deleting a child nulls the FK on its devices
- **WHEN** a `children` row is deleted,
- **THEN** every `devices.child_id` that referenced it SHALL be set to `NULL` (cascade behavior) and the affected devices SHALL remain visible on the dashboard as unassigned.

### Requirement: Row Level Security scopes children to their parent

The migration SHALL add RLS policies `children_parent_select`, `children_parent_insert`, `children_parent_update`, and `children_parent_delete` mirroring the pattern in `002_rls_policies.sql:37-41`. Every policy SHALL be `USING (parent_id = auth.uid())` and the mutation policies SHALL set `WITH CHECK (parent_id = auth.uid())`.

#### Scenario: A parent can read and mutate only their own children
- **WHEN** the parent's session queries `children` via Supabase REST,
- **THEN** the response SHALL contain only rows where `parent_id = auth.uid()` and any insert/update/delete SHALL be accepted only when the same condition holds.

#### Scenario: A non-parent or unauthenticated caller is denied
- **WHEN** the caller is missing a valid bearer token or is not the parent in the row,
- **THEN** every children-table operation SHALL return 401/403 or an empty result set, matching the policy in `002_rls_policies.sql`.

### Requirement: Pairing captures the child name on the parent side

The parent-side "name this child" prompt SHALL capture `child_first_name` for every device that arrives with `child_id = NULL`. The pairing edge function SHALL accept a `child_first_name` parameter and SHALL insert the `children` row + link the device in the same transaction. A device paired without a `child_first_name` SHALL be rejected by the edge function with HTTP 400.

#### Scenario: Parent pairs a new device and names the child
- **WHEN** the parent taps "Emparejar" in `PairingBottomSheet` and enters `child_first_name = "Lucas"`,
- **THEN** the edge function SHALL create a `children` row with `first_name = "Lucas"` for `parent_id = auth.uid()` and SHALL set `devices.child_id` to the new child's id before returning.

#### Scenario: Pairing without a child name is rejected
- **WHEN** the parent submits the pairing request with an empty `child_first_name`,
- **THEN** the edge function SHALL return HTTP 400, no `children` row SHALL be created, and the UI SHALL re-show the prompt with a validation error.

### Requirement: Backfill script is mandatory before the dashboard relies on the picker

The release SHALL include a one-shot backfill script that runs against the production database BEFORE the new dashboard ships, so every pre-existing `devices.child_id` is non-NULL. The script's owner-strategy is **synthetic "Anónimo" child created during migration** — a single synthetic child row is inserted per parent via `INSERT ... ON CONFLICT (parent_id, first_name) DO NOTHING`, then all pre-existing `devices.child_id` rows are `UPDATE`d to reference that synthetic child. The parent can later rename the synthetic child via the post-dismiss "name this child" prompt. The script SHALL be idempotent so it can be re-run safely.

#### Scenario: Backfill assigns every NULL child_id before release
- **WHEN** the operator runs the backfill script in a maintenance window,
- **THEN** every `devices.child_id` that was NULL SHALL be set to the id of the synthetic "Anónimo" child for that parent, and the script SHALL exit 0.

#### Scenario: A re-run is a safe no-op
- **WHEN** the operator runs the backfill script a second time,
- **THEN** no `devices.child_id` SHALL be overwritten (the `WHERE child_id IS NULL` clause makes the UPDATE a no-op once the first run has populated every row) and the script SHALL exit 0.

## Out of scope
- Per-child screen-time policies, app-block policies, or analytics rollups (covered by `app-block-policy`).
- Renaming a child after creation beyond what `children_parent_update` permits (UI for rename is a future change).
- Deleting a child from the parent UI (RLS permits it; UI affordance is a future change).
- Real-time pairing notifications on the parent side (deferred to a later change).