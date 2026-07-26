-- T-Migration: Children entity + device→child FK
-- Change: feat-multi-child-picker (Change A — schema + domain + pairing)
-- Source: openspec/changes/2026-07-06-feat-multi-child-picker/specs/child-entity/spec.md
-- Design: openspec/changes/2026-07-06-feat-multi-child-picker/design.md §A.1 + §A.2 + §A.7
--
-- Summary:
--   1. CREATE TABLE children (id UUID PK, parent_id UUID FK → auth.users, first_name, timestamps).
--   2. UNIQUE (parent_id, first_name) — prevents "two Lucas" confusion per parent.
--   3. CREATE INDEX idx_children_parent_id — supports the parent_id = auth.uid() RLS hot path.
--   4. ALTER TABLE devices ADD COLUMN child_id UUID NULL + FK with ON DELETE SET NULL
--      so deleting a child un-assigns its devices (preserves history).
--   5. ALTER TABLE pairing_codes ADD COLUMN child_first_name TEXT — captured by the
--      parent-side "name this child" prompt (design §A.7) before the child scans,
--      propagated to the children table at pairing time.
--   6. updated_at trigger on children (mirrors devices/app_policies etc).
--
-- NOT bundled with 001_initial_schema.sql on purpose: keep the change scoped to
-- one forward-only migration file so reviewers can diff cleanly and the
-- production rollout is a single Supabase CLI `db push`.

-- ============================================
-- 1. CHILDREN
-- ============================================
CREATE TABLE children (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    parent_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    first_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT children_first_name_length CHECK (char_length(first_name) BETWEEN 1 AND 32),
    CONSTRAINT children_unique_per_parent UNIQUE (parent_id, first_name)
);

CREATE INDEX idx_children_parent_id ON children(parent_id);

CREATE TRIGGER children_updated_at
    BEFORE UPDATE ON children
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================
-- 2. DEVICES.CHILD_ID FK (nullable — back-compat with pre-migration rows)
-- ============================================
ALTER TABLE devices
    ADD COLUMN child_id UUID NULL;

ALTER TABLE devices
    ADD CONSTRAINT fk_devices_child
    FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE SET NULL;

CREATE INDEX idx_devices_child_id ON devices(child_id);

-- ============================================
-- 3. PAIRING_CODES.CHILD_FIRST_NAME (parent-side "name this child" prompt)
-- ============================================
ALTER TABLE pairing_codes
    ADD COLUMN child_first_name TEXT;

-- ============================================
-- 4. RLS FOR CHILDREN + UPDATE FOR DEVICE.CHILD_ID ASSIGNMENT
-- ============================================
-- (See also supabase/migrations/002_rls_policies.sql — this section is repeated
-- here for runtime visibility. The 002 file appends the same policies so existing
-- RLS rolls out as a single diff. Run order matters: 002 is shipped ahead of
-- this migration, but Supabase applies both atomically per `db push`.)
ALTER TABLE children ENABLE ROW LEVEL SECURITY;

CREATE POLICY children_parent_select ON children
    FOR SELECT
    USING (parent_id = auth.uid());

CREATE POLICY children_parent_insert ON children
    FOR INSERT
    WITH CHECK (parent_id = auth.uid());

CREATE POLICY children_parent_update ON children
    FOR UPDATE
    USING (parent_id = auth.uid())
    WITH CHECK (parent_id = auth.uid());

CREATE POLICY children_parent_delete ON children
    FOR DELETE
    USING (parent_id = auth.uid());

-- Allow parents to assign / re-assign a device to one of their own children
-- WITHOUT removing the existing devices_parent_select / devices_parent_delete
-- policies. The existing policies stay untouched — this is additive.
CREATE POLICY devices_parent_update_child_assignment ON devices
    FOR UPDATE
    USING (parent_id = auth.uid())
    WITH CHECK (parent_id = auth.uid());
