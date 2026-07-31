-- 015: SECURITY DEFINER RPC hardening (BLK-03 Slice 2).
-- Forward-only; historical migrations 001–014 are unchanged.
--
-- Inventory after 001–014:
--   Service-only (called by service-backed Edge Functions or chained
--   SECURITY DEFINER):
--     1.  public.get_device_policy(uuid)                           -- 001, def
--     2.  public.apply_policy_template(uuid, uuid)                 -- 003, def
--     3.  public.bump_policy_version(uuid)                        -- 001, plain
--     4.  public.approve_request_atomic(uuid, uuid, integer, text) -- 012, def
--     5.  public.redeem_pairing_code_atomic(                       -- 013, def
--           text, uuid, text, text, text, text, text, text)
--
--   Trigger / dead helpers (no legitimate API caller):
--     6.  public.bump_policy_version_trigger()                     -- 001, plain
--     7.  public.is_device_user()                                  -- 002, def
--     8.  public.is_parent_user()                                  -- 002, def
--     9.  public.get_available_templates()                         -- 003, def
--
--   Omitted trigger helper (only fired by BEFORE UPDATE triggers):
--     12. public.update_updated_at()                               -- 001, plain
--
--   Omitted pg_cron helpers (only invoked as the postgres role):
--     13. public.cleanup_expired_grants()                          -- 001, plain
--     14. public.cleanup_old_usage_logs(integer)                   -- 001, plain
--     15. public.cleanup_old_heartbeats(integer)                   -- 001, plain
--     16. public.cleanup_expired_pairing_codes()                   -- 001, plain,
--                                                                    cron'd by 010
--
--   Parent-facing (caller-bound to its own UID):
--     10. public.get_parent_checkins(uuid, integer)                -- 004 → INVOKER
--     11. public.insert_parent_checkin(                            -- 004 → INVOKER
--           checkin_rating, date, date, text, uuid)
--
-- Guarantees:
--   * Every hardened function pins search_path = pg_catalog, public, pg_temp.
--   * PUBLIC, anon, authenticated default EXECUTE is dropped everywhere.
--   * Service-only RPCs grant service_role (+ postgres for migration tooling).
--   * pg_cron helpers grant only postgres (the pg_cron executor role); they
--     are unreachable from anon/authenticated/service_role.
--   * Trigger helpers (bump_policy_version_trigger, update_updated_at) and
--     dead helpers (is_device_user, is_parent_user, get_available_templates)
--     are revoked from every API role; they fire only from within the schema.
--   * Parent RPCs grant authenticated and use SECURITY INVOKER so 014 RLS is
--     the single authority for ownership.
--   * Schema public loses CREATE from PUBLIC/anon/authenticated/service_role
--     so the fixed search_path = pg_catalog, public, pg_temp invariant cannot
--     be hijacked by an attacker installing a shadow function/table.

-- ---------- (1) get_device_policy ------------------------------------------
ALTER FUNCTION public.get_device_policy(uuid)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.get_device_policy(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_device_policy(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.get_device_policy(uuid) TO postgres;

-- ---------- (2) apply_policy_template --------------------------------------
ALTER FUNCTION public.apply_policy_template(uuid, uuid)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.apply_policy_template(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_policy_template(uuid, uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.apply_policy_template(uuid, uuid) TO postgres;

-- ---------- (3) bump_policy_version ----------------------------------------
-- Plain function in 001; promoted to SECURITY DEFINER for defense in depth.
ALTER FUNCTION public.bump_policy_version(uuid)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.bump_policy_version(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bump_policy_version(uuid) TO service_role;
GRANT EXECUTE ON FUNCTION public.bump_policy_version(uuid) TO postgres;

-- ---------- (4) approve_request_atomic -------------------------------------
ALTER FUNCTION public.approve_request_atomic(uuid, uuid, integer, text)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.approve_request_atomic(uuid, uuid, integer, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.approve_request_atomic(uuid, uuid, integer, text)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.approve_request_atomic(uuid, uuid, integer, text)
  TO postgres;

-- ---------- (5) redeem_pairing_code_atomic ---------------------------------
-- Body references `auth.users` already schema-qualified, so dropping `auth`
-- from search_path is safe.
ALTER FUNCTION public.redeem_pairing_code_atomic(
  text, uuid, text, text, text, text, text, text
) SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.redeem_pairing_code_atomic(
  text, uuid, text, text, text, text, text, text
) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.redeem_pairing_code_atomic(
  text, uuid, text, text, text, text, text, text
) TO service_role;
GRANT EXECUTE ON FUNCTION public.redeem_pairing_code_atomic(
  text, uuid, text, text, text, text, text, text
) TO postgres;

-- ---------- (6) bump_policy_version_trigger --------------------------------
ALTER FUNCTION public.bump_policy_version_trigger()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.bump_policy_version_trigger()
  FROM PUBLIC, anon, authenticated;

-- ---------- (7) is_device_user (dead helper) -------------------------------
ALTER FUNCTION public.is_device_user()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.is_device_user() FROM PUBLIC, anon, authenticated;

-- ---------- (8) is_parent_user (dead helper) -------------------------------
ALTER FUNCTION public.is_parent_user()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.is_parent_user() FROM PUBLIC, anon, authenticated;

-- ---------- (9) get_available_templates (dead RPC) -------------------------
-- Clients SELECT public.policy_templates via the policy_templates_read
-- policy granted in 014; this RPC is dead.
ALTER FUNCTION public.get_available_templates()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.get_available_templates() FROM PUBLIC, anon, authenticated;

-- ---------- (10) get_parent_checkins → SECURITY INVOKER ---------------------
CREATE OR REPLACE FUNCTION public.get_parent_checkins(
  p_parent_id  uuid,
  p_limit      integer DEFAULT 12
) RETURNS SETOF public.parent_outcome_checkins
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE v_caller uuid := auth.uid();
BEGIN
  IF v_caller IS NULL THEN
    RAISE EXCEPTION 'authenticated role required' USING ERRCODE = '42501';
  END IF;
  IF v_caller <> p_parent_id THEN
    RAISE EXCEPTION 'cannot read another parent''s checkins' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
    SELECT * FROM public.parent_outcome_checkins
    WHERE parent_id = p_parent_id
    ORDER BY period_start DESC LIMIT p_limit;
END;
$$;
REVOKE ALL ON FUNCTION public.get_parent_checkins(uuid, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_parent_checkins(uuid, integer) TO authenticated;

-- ---------- (11) insert_parent_checkin → SECURITY INVOKER ------------------
-- RLS (014 parent_checkins_parent_insert) enforces parent_id = auth.uid() and
-- device ownership, so the function can run as the caller.
CREATE OR REPLACE FUNCTION public.insert_parent_checkin(
  p_rating       public.checkin_rating,
  p_period_start date,
  p_period_end   date,
  p_comment      text DEFAULT NULL,
  p_device_id    uuid DEFAULT NULL
) RETURNS public.parent_outcome_checkins
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE v_checkin public.parent_outcome_checkins;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authenticated role required' USING ERRCODE = '42501';
  END IF;
  INSERT INTO public.parent_outcome_checkins (
    parent_id, device_id, rating, comment, period_start, period_end
  ) VALUES (
    auth.uid(), p_device_id, p_rating, p_comment, p_period_start, p_period_end
  ) RETURNING * INTO v_checkin;
  RETURN v_checkin;
END;
$$;
REVOKE ALL ON FUNCTION public.insert_parent_checkin(
  public.checkin_rating, date, date, text, uuid
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.insert_parent_checkin(
  public.checkin_rating, date, date, text, uuid
) TO authenticated;

-- ---------- (12) update_updated_at (trigger helper) ----------------------
-- Trigger-only (8 BEFORE UPDATE triggers in 001); no API caller. Pinned
-- search_path prevents an attacker-installed type shadowing the column.
ALTER FUNCTION public.update_updated_at()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.update_updated_at() FROM PUBLIC, anon, authenticated;

-- ---------- (13) cleanup_expired_grants (pg_cron helper) -----------------
-- Invoked by pg_cron as the postgres role.
ALTER FUNCTION public.cleanup_expired_grants()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.cleanup_expired_grants() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_grants() TO postgres;

-- ---------- (14) cleanup_old_usage_logs (pg_cron helper) -----------------
ALTER FUNCTION public.cleanup_old_usage_logs(integer)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.cleanup_old_usage_logs(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_old_usage_logs(integer) TO postgres;

-- ---------- (15) cleanup_old_heartbeats (pg_cron helper) -----------------
ALTER FUNCTION public.cleanup_old_heartbeats(integer)
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.cleanup_old_heartbeats(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_old_heartbeats(integer) TO postgres;

-- ---------- (16) cleanup_expired_pairing_codes (pg_cron helper) ----------
-- Already scheduled by pg_cron job `cleanup-pairing-codes` (migration 010).
ALTER FUNCTION public.cleanup_expired_pairing_codes()
  SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp;
REVOKE ALL ON FUNCTION public.cleanup_expired_pairing_codes() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_pairing_codes() TO postgres;

-- ---------- (Z) Lock schema public CREATE for API roles ------------------
-- Closing the search_path object-hijacking risk: an attacker who can
-- CREATE in `public` could install a function/table that shadows a
-- SECURITY DEFINER lookup pinned to `search_path = pg_catalog, public,
-- pg_temp`. Revoke CREATE from PUBLIC/anon/authenticated/service_role;
-- only the owner/admin role retains CREATE. Schema USAGE for API roles is
-- preserved (existing tables/views remain queryable through existing
-- table-level grants in 014).
REVOKE CREATE ON SCHEMA public FROM PUBLIC, anon, authenticated, service_role;

-- PostgREST cache reload so anon/authenticated grants take effect immediately.
NOTIFY pgrst, 'reload schema';
