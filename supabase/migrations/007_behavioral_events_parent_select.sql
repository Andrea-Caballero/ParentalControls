-- Slice A — Rename `behavioral_events` SELECT policy to match the project's
-- `*_parent_select` naming convention (per design §3.1).
--
-- Pre-Slice-A:  `parent_events` policy at `004_parent_outcome_checkins.sql:89-99`
--               predicate: `parent_id = auth.uid() OR EXISTS (devices join)`.
-- Post-Slice-A: `behavioral_events_parent_select` — same predicate, new name.
--
-- The OLD `parent_events` policy is DROPped so it does not stack with the
-- new one (Postgres allows multiple SELECT policies but the OR semantics
-- would be redundant and could confuse future RLS lint tooling).
--
-- Rollback: DROP POLICY behavioral_events_parent_select ON behavioral_events;
--           CREATE POLICY parent_events ON behavioral_events FOR SELECT USING (...);

ALTER TABLE behavioral_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS parent_events ON behavioral_events;

CREATE POLICY behavioral_events_parent_select ON behavioral_events
    FOR SELECT
    USING (
        parent_id = auth.uid()
        OR
        EXISTS (
            SELECT 1 FROM devices
            WHERE devices.id = behavioral_events.device_id
              AND devices.parent_id = auth.uid()
        )
    );