-- 013: Commit pairing writes and code consumption in one transaction.
--
-- The prior Edge Function consumed the code before creating the child/device,
-- updating agent metadata, and applying policy. Any later failure made a valid
-- code unusable. This RPC locks the code and moves the terminal CONSUMED write
-- to the end of the transaction. PostgreSQL rolls every write back if any
-- downstream statement raises, so the code remains ACTIVE and retryable.

CREATE OR REPLACE FUNCTION public.redeem_pairing_code_atomic(
    p_code TEXT,
    p_agent_user_id UUID,
    p_device_name TEXT,
    p_device_model TEXT,
    p_os_version TEXT,
    p_app_version TEXT,
    p_child_first_name TEXT,
    p_age_band TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_pairing pairing_codes%ROWTYPE;
    v_child_id UUID;
    v_device_id UUID;
    v_template_id UUID;
    v_policy_version INTEGER;
    v_age_band TEXT;
BEGIN
    SELECT *
      INTO v_pairing
      FROM pairing_codes
     WHERE code = p_code
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('error', 'INVALID_CODE');
    END IF;

    IF v_pairing.status = 'CONSUMED' THEN
        RETURN jsonb_build_object('error', 'ALREADY_USED');
    END IF;

    IF v_pairing.status = 'EXPIRED' OR v_pairing.expires_at <= NOW() THEN
        RETURN jsonb_build_object('error', 'EXPIRED_CODE');
    END IF;

    IF v_pairing.status = 'REVOKED' THEN
        RETURN jsonb_build_object('error', 'REVOKED_CODE');
    END IF;

    IF v_pairing.status <> 'ACTIVE' THEN
        RETURN jsonb_build_object('error', 'INACTIVE_CODE');
    END IF;

    INSERT INTO children (parent_id, first_name)
    VALUES (v_pairing.parent_id, p_child_first_name)
    ON CONFLICT (parent_id, first_name) DO UPDATE
        SET first_name = children.first_name
    RETURNING id INTO v_child_id;

    INSERT INTO devices (
        device_name,
        parent_id,
        device_model,
        os_version,
        app_version,
        device_state,
        policy_version,
        child_id
    ) VALUES (
        p_device_name,
        v_pairing.parent_id,
        p_device_model,
        p_os_version,
        p_app_version,
        'ACTIVE',
        1,
        v_child_id
    )
    RETURNING id INTO v_device_id;

    UPDATE auth.users
       SET raw_app_meta_data = COALESCE(raw_app_meta_data, '{}'::JSONB)
            || jsonb_build_object('device_id', v_device_id),
           updated_at = NOW()
     WHERE id = p_agent_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent user % not found', p_agent_user_id;
    END IF;

    v_age_band := COALESCE(
        NULLIF(p_age_band, ''),
        NULLIF(split_part(COALESCE(v_pairing.device_name, ''), '-', 1), ''),
        '7-12'
    );

    SELECT id
      INTO v_template_id
      FROM policy_templates
     WHERE age_band = v_age_band
     ORDER BY is_default DESC, created_at ASC
     LIMIT 1;

    IF v_template_id IS NOT NULL THEN
        PERFORM public.apply_policy_template(v_device_id, v_template_id);
    END IF;

    -- Terminal transition comes last. Any exception above rolls this statement
    -- and every preceding pairing write back in the same transaction.
    UPDATE pairing_codes
       SET status = 'CONSUMED',
           used_at = NOW()
     WHERE id = v_pairing.id;

    SELECT policy_version
      INTO v_policy_version
      FROM devices
     WHERE id = v_device_id;

    RETURN jsonb_build_object(
        'success', TRUE,
        'device_id', v_device_id,
        'parent_id', v_pairing.parent_id,
        'policy_version', v_policy_version
    );
END;
$$;

REVOKE ALL ON FUNCTION public.redeem_pairing_code_atomic(
    TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT
) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.redeem_pairing_code_atomic(
    TEXT, UUID, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT
) TO service_role;
