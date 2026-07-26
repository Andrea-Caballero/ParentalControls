-- Slice A — Add `parent_id` to `device_push_tokens` + RLS policies (per
-- design §3.2). Used by Slice D for the `child.paired` FCM fanout; the
-- column + policies ship in Slice A so the parent-side RLS is in place
-- before any push tokens are written.
--
-- Pre-Slice-A:  `device_push_tokens` has only `device_id`; the only
--               policy is `device_push_tokens_agent_all` at
--               `002_rls_policies.sql:200-204` (scoped to device JWT).
-- Post-Slice-A: `parent_id` is a nullable FK to `auth.users(id)` with
--               `ON DELETE SET NULL` so a parent delete clears the
--               column without cascading to child push tokens.
--               New sibling-parent-denial policies `parent_select` and
--               `parent_insert` keyed on `parent_id = auth.uid()`.
--
-- The column is `IF NOT EXISTS` and the policies are `CREATE POLICY`
-- (not `DROP POLICY IF EXISTS` first), so the migration is safe to
-- re-run on a partially-applied state.
--
-- Rollback (symmetric): DROP POLICY device_push_tokens_parent_select
--                        ON device_push_tokens;
--                        DROP POLICY device_push_tokens_parent_insert
--                        ON device_push_tokens;
--                        ALTER TABLE device_push_tokens DROP COLUMN parent_id;

ALTER TABLE device_push_tokens
    ADD COLUMN IF NOT EXISTS parent_id UUID
    REFERENCES auth.users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_push_tokens_parent
    ON device_push_tokens(parent_id) WHERE parent_id IS NOT NULL;

-- Sibling-parent denial: a parent can only see/manage their own push
-- tokens. Mirrors the `parent_id = auth.uid()` predicate used in
-- `time_requests_parent_select` (`002_rls_policies.sql:153-161`).
CREATE POLICY device_push_tokens_parent_select ON device_push_tokens
    FOR SELECT
    USING (parent_id = auth.uid());

CREATE POLICY device_push_tokens_parent_insert ON device_push_tokens
    FOR INSERT
    WITH CHECK (parent_id = auth.uid());

CREATE POLICY device_push_tokens_parent_update ON device_push_tokens
    FOR UPDATE
    USING (parent_id = auth.uid());