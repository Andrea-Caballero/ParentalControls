-- 010: Enable pg_cron job to cleanup expired pairing_codes
--
-- Why: Bug #4 — `cleanup_expired_pairing_codes()` is defined in
-- migration 001_initial_schema.sql (lines 458-470) but the
-- `cron.schedule()` call was left commented out (lines 476-479).
-- Without it, expired pairing_codes accumulate in the database.
--
-- Prerequisite: the `pg_cron` extension must be enabled on the
-- Supabase project. Enable it via:
--   Supabase Dashboard → Database → Extensions → pg_cron → Enable
--
-- This migration guards against the extension being absent (e.g. on
-- a fresh dev project) and degrades gracefully: it raises a NOTICE
-- instead of failing, so the migration is safe to apply before or
-- after enabling the extension.
--
-- Once applied with pg_cron available, the job will run every 15
-- minutes and set status='EXPIRED' on any ACTIVE pairing_codes
-- whose expires_at < NOW(). Already-CONSUMED codes are left alone.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        PERFORM cron.schedule(
            'cleanup-pairing-codes',
            '*/15 * * * *',
            $cmd$SELECT cleanup_expired_pairing_codes()$cmd$
        );
        RAISE NOTICE 'Scheduled cron job cleanup-pairing-codes (*/15 * * * *)';
    ELSE
        RAISE NOTICE 'pg_cron extension is not enabled; skipping cron job setup. '
                     'Enable pg_cron in the Supabase Dashboard, then re-run this '
                     'migration manually to activate the schedule.';
    END IF;
END
$$;