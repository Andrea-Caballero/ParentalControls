-- T-Migration: Idempotent "Anónimo" backfill for pre-migration devices
-- Change: feat-multi-child-picker (Change A — schema + domain + pairing)
-- Source: openspec/changes/2026-07-06-feat-multi-child-picker/specs/child-entity/spec.md
--         (Requirement: "Backfill script is mandatory before the dashboard
--          relies on the picker")
-- Design: design.md §A.4
--
-- Decision: backfill strategy = synthetic "Anónimo" child per parent
-- (per the spec-round #226 engram observation). Pre-migration devices
-- (those with `child_id IS NULL`) get linked to a synthetic child row
-- named "Anónimo" owned by the same parent. The parent renames it from
-- the dashboard's "name this child" prompt (Change B's RenameChildDialog,
-- design §B.6).
--
-- Idempotency contract:
--   - INSERT ... ON CONFLICT (parent_id, first_name) DO NOTHING protects
--     against the synthetic row already existing (e.g., from a previous run
--     or because the parent already paired a child named "Anónimo" — unlikely,
--     but the contract is generous).
--   - The subsequent UPDATE only touches rows with `child_id IS NULL`, so
--     re-running the script against an already-backfilled database is a
--     no-op (no rows match the WHERE).
--   - Safe to run in a maintenance window and re-run if interrupted.
--
-- Apply: this file is shipped with the migration; operator runs it once via
-- the Supabase SQL editor or `psql -f` BEFORE the dashboard's child picker
-- ships (i.e., before PR B merges). It is NOT executed automatically by
-- `db push` because it depends on existing production data.

DO $$
DECLARE
    anon_child_id UUID;
    parent_record RECORD;
BEGIN
    FOR parent_record IN
        SELECT DISTINCT parent_id
        FROM devices
        WHERE child_id IS NULL
          AND parent_id IS NOT NULL
    LOOP
        -- Insert synthetic Anónimo child for this parent. ON CONFLICT keeps
        -- the run idempotent if the row already exists from a prior run.
        INSERT INTO children (parent_id, first_name)
        VALUES (parent_record.parent_id, 'Anónimo')
        ON CONFLICT (parent_id, first_name) DO NOTHING
        RETURNING id INTO anon_child_id;

        -- If RETURNING id is null (conflict branch), SELECT the existing row.
        IF anon_child_id IS NULL THEN
            SELECT id INTO anon_child_id
            FROM children
            WHERE parent_id = parent_record.parent_id
              AND first_name = 'Anónimo';
        END IF;

        -- Link every NULL child_id device for this parent to the synthetic
        -- child. The WHERE clause makes a re-run a safe no-op.
        IF anon_child_id IS NOT NULL THEN
            UPDATE devices
            SET child_id = anon_child_id
            WHERE parent_id = parent_record.parent_id
              AND child_id IS NULL;
        END IF;
    END LOOP;
END $$;
