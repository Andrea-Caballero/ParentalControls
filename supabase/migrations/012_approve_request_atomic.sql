-- ============================================
-- 012: Atomic + idempotent time-request approval
-- ============================================
--
-- Why:
--   The legacy `approve-request` Edge Function updated
--   `time_requests` and inserted into `grants` in two separate
--   REST calls. A retry after a network failure between those
--   two writes could either create a duplicate grant (if the
--   verdict was committed but the second call retried) or
--   leave the request APPROVED with no grant (if the verdict
--   was committed but the second call never retried). Both
--   outcomes are unacceptable for a parental-control decision.
--
--   This migration moves the commit boundary into Postgres
--   via a single `approve_request_atomic(...)` RPC and adds
--   a UNIQUE(request_id) constraint on `grants` so the
--   database itself enforces "one grant per approved request".
--
-- Contract (per design.md §Interfaces):
--   approve_request_atomic(
--     p_request_id    uuid,
--     p_parent_id     uuid,
--     p_minutes       integer DEFAULT NULL,
--     p_response_text text    DEFAULT NULL
--   ) RETURNS jsonb
--
--   - p_minutes IS NULL  → DENY action
--   - p_minutes IS NOT NULL → APPROVE action
--   - Ownership is verified server-side via device.parent_id
--   - The request row is locked FOR UPDATE so concurrent
--     retries serialize; only the first committer wins.
--   - Idempotency: a retry of an already-APPROVED request
--     returns the original grant_id with `idempotent: true`.
--   - Conflicts (DENY after APPROVE, APPROVE after DENY)
--     return a jsonb `{ error, code }` shape so the Edge
--     Function can map to 409.
--
-- Safety on existing data:
--   Before adding UNIQUE(request_id), this migration
--   deduplicates any existing rows by keeping the earliest
--   `granted_at` per non-NULL `request_id` and NULLing the
--   rest. This is the only way the constraint can be added
--   without a multi-step out-of-band cleanup.
-- ============================================

-- 1) Deduplicate existing grants.request_id values.
--    Keep the earliest grant per request_id; null the rest.
UPDATE grants
SET request_id = NULL
WHERE id IN (
  SELECT id
  FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY request_id
             ORDER BY granted_at ASC, id ASC
           ) AS rn
    FROM grants
    WHERE request_id IS NOT NULL
  ) ranked
  WHERE rn > 1
);

-- 2) Enforce one grant per approved request.
ALTER TABLE grants
  ADD CONSTRAINT grants_request_id_unique UNIQUE (request_id);

-- 3) Atomic approval/denial RPC.
--    SECURITY DEFINER so the Edge Function can call it with the
--    service_role key and bypass RLS while still enforcing the
--    ownership check inside the function body.
CREATE OR REPLACE FUNCTION approve_request_atomic(
  p_request_id    uuid,
  p_parent_id     uuid,
  p_minutes       integer DEFAULT NULL,
  p_response_text text    DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_request_id       uuid;
  v_device_id        uuid;
  v_device_parent    uuid;
  v_current_status   request_status;
  v_package_name     text;
  v_policy_version   integer;
  v_is_approve       boolean;
  v_now              timestamptz := NOW();
  v_expires_at       timestamptz;
  v_grant_id         uuid;
  v_existing_grant_id   uuid;
  v_existing_expires_at timestamptz;
  v_existing_minutes    integer;
BEGIN
  v_is_approve := p_minutes IS NOT NULL;

  -- Lock the request row + read the device owner in one round trip.
  -- FOR UPDATE serializes concurrent retries; the second caller
  -- waits, then re-reads the post-commit state and returns the
  -- idempotent shape.
  SELECT tr.id,
         tr.device_id,
         tr.status,
         tr.package_name,
         d.parent_id,
         d.policy_version
    INTO v_request_id,
         v_device_id,
         v_current_status,
         v_package_name,
         v_device_parent,
         v_policy_version
  FROM time_requests tr
  JOIN devices d ON d.id = tr.device_id
  WHERE tr.id = p_request_id
  FOR UPDATE OF tr;

  IF v_request_id IS NULL THEN
    RETURN jsonb_build_object(
      'error',  'Solicitud no encontrada',
      'code',   'NOT_FOUND'
    );
  END IF;

  -- Server-side ownership check. The Edge Function also enforces
  -- this, but doing it again here is the only way the RPC stays
  -- safe if a future caller skips the JWT precheck.
  IF v_device_parent IS DISTINCT FROM p_parent_id THEN
    RETURN jsonb_build_object(
      'error', 'No autorizado para aprobar esta solicitud',
      'code',  'UNAUTHORIZED'
    );
  END IF;

  -- ===== APPROVE branch =====
  IF v_is_approve THEN
    -- Already approved? Look up the existing grant and return it
    -- (idempotent retry).
    IF v_current_status = 'APPROVED' THEN
      SELECT id, expires_at, minutes
        INTO v_existing_grant_id, v_existing_expires_at, v_existing_minutes
      FROM grants
      WHERE request_id = p_request_id
      LIMIT 1;

      IF v_existing_grant_id IS NOT NULL THEN
        RETURN jsonb_build_object(
          'success',        true,
          'decision',       'APPROVED',
          'idempotent',     true,
          'grant_id',       v_existing_grant_id,
          'minutes',        v_existing_minutes,
          'expires_at',     v_existing_expires_at,
          'policy_version', v_policy_version
        );
      END IF;
      -- Defensive: status is APPROVED but the grant is missing
      -- (e.g., legacy data). Fall through to the insert path so
      -- the row is brought into a consistent state.
    END IF;

    -- Conflict: a previous DENY makes a later APPROVE semantically
    -- ambiguous. Surface the conflict so the Edge Function maps it
    -- to 409 — never overwrite a denial.
    IF v_current_status = 'DENIED' THEN
      RETURN jsonb_build_object(
        'error', 'La solicitud ya fue denegada',
        'code',  'ALREADY_DENIED'
      );
    END IF;

    -- Atomic commit: verdict + grant in one transaction.
    v_expires_at := v_now + (p_minutes::text || ' minutes')::interval;

    UPDATE time_requests
       SET status          = 'APPROVED',
           parent_response = p_response_text,
           responded_at    = v_now
     WHERE id = p_request_id;

    -- UNIQUE(request_id) on grants guarantees no second insert
    -- even under concurrent retries.
    INSERT INTO grants (
      device_id, request_id, scope, minutes, source, status,
      expires_at, granted_at
    ) VALUES (
      v_device_id, p_request_id,
      COALESCE(v_package_name, 'device'),
      p_minutes, 'EXTRA_TIME', 'APPROVED',
      v_expires_at, v_now
    )
    RETURNING id INTO v_grant_id;

    -- `grants_version_bump` already increments devices.policy_version.
    -- Read back the post-commit value instead of bumping it again.
    SELECT policy_version
      INTO v_policy_version
      FROM devices
     WHERE id = v_device_id;

    RETURN jsonb_build_object(
      'success',        true,
      'decision',       'APPROVED',
      'grant_id',       v_grant_id,
      'minutes',        p_minutes,
      'expires_at',     v_expires_at,
      'policy_version', v_policy_version
    );
  END IF;

  -- ===== DENY branch =====
  -- Already denied? Return idempotent success.
  IF v_current_status = 'DENIED' THEN
    RETURN jsonb_build_object(
      'success',    true,
      'decision',   'DENIED',
      'idempotent', true
    );
  END IF;

  -- Conflict: cannot un-approve a request that already produced
  -- a grant.
  IF v_current_status = 'APPROVED' THEN
    RETURN jsonb_build_object(
      'error', 'Solicitud ya aprobada, no se puede denegar',
      'code',  'ALREADY_APPROVED'
    );
  END IF;

  -- Atomic commit: just the verdict, no grant.
  UPDATE time_requests
     SET status          = 'DENIED',
         parent_response = p_response_text,
         responded_at    = v_now
   WHERE id = p_request_id;

  RETURN jsonb_build_object(
    'success',  true,
    'decision', 'DENIED'
  );
END;
$$;

-- 4) Allow the Edge Function to invoke the RPC.
--    The Edge Function uses the service_role key, which is
--    intentionally outside RLS — but the function body enforces
--    the same ownership check via `p_parent_id`. Granting to
--    `service_role` and `postgres` keeps the function callable
--    from migration tooling and from the edge runtime.
REVOKE ALL ON FUNCTION approve_request_atomic(uuid, uuid, integer, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION approve_request_atomic(uuid, uuid, integer, text) TO service_role;
GRANT EXECUTE ON FUNCTION approve_request_atomic(uuid, uuid, integer, text) TO postgres;

COMMENT ON FUNCTION approve_request_atomic IS
  'Atomic approve or deny of a time_request. Returns jsonb { success, decision, ... } and is safe to retry.';
