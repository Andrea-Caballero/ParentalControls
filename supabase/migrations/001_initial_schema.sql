-- T14: Esquema Supabase + RLS
-- Control Parental - Todas las tablas de §0.5

-- ============================================
-- EXTENSIONES
-- ============================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================
-- ENUMS
-- ============================================
CREATE TYPE device_state AS ENUM ('ACTIVE', 'LOCKED', 'DOWNTIME');
CREATE TYPE app_policy_state AS ENUM ('ALLOWED', 'BLOCKED', 'LIMITED', 'ALWAYS_ALLOWED');
CREATE TYPE grant_source AS ENUM ('EXTRA_TIME', 'REWARD', 'MANUAL');
CREATE TYPE grant_status AS ENUM ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED');
CREATE TYPE request_status AS ENUM ('PENDING', 'APPROVED', 'DENIED');
CREATE TYPE pairing_status AS ENUM ('ACTIVE', 'EXPIRED', 'REVOKED');
CREATE TYPE push_token_platform AS ENUM ('ANDROID', 'IOS');

-- ============================================
-- 1. DEVICES
-- ============================================
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_name TEXT NOT NULL,
    parent_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    device_model TEXT,
    os_version TEXT,
    app_version TEXT NOT NULL,
    policy_version INTEGER NOT NULL DEFAULT 1,
    device_state device_state NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_parent ON devices(parent_id);
CREATE INDEX idx_devices_created ON devices(created_at);

-- ============================================
-- 2. APP_POLICIES ( Policies por app )
-- ============================================
CREATE TABLE app_policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    state app_policy_state NOT NULL DEFAULT 'ALLOWED',
    daily_limit_minutes INTEGER,
    allowed_windows JSONB DEFAULT '[]'::JSONB,
    category TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(device_id, package_name)
);

CREATE INDEX idx_app_policies_device ON app_policies(device_id);
CREATE INDEX idx_app_policies_package ON app_policies(package_name);

-- ============================================
-- 3. TIME_REQUESTS ( Solicitudes de tiempo )
-- ============================================
CREATE TABLE time_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    child_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    package_name TEXT, -- NULL para solicitud global
    minutes_requested INTEGER NOT NULL,
    reason TEXT,
    status request_status NOT NULL DEFAULT 'PENDING',
    parent_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at TIMESTAMPTZ
);

CREATE INDEX idx_time_requests_device ON time_requests(device_id);
CREATE INDEX idx_time_requests_status ON time_requests(status);
CREATE INDEX idx_time_requests_created ON time_requests(created_at);

-- ============================================
-- 4. GRANTS ( Concesiones de tiempo )
-- ============================================
CREATE TABLE grants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    request_id UUID REFERENCES time_requests(id) ON DELETE SET NULL,
    scope TEXT NOT NULL, -- 'device', package_name o category
    minutes INTEGER NOT NULL,
    source grant_source NOT NULL DEFAULT 'MANUAL',
    status grant_status NOT NULL DEFAULT 'APPROVED',
    expires_at TIMESTAMPTZ NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_grants_device ON grants(device_id);
CREATE INDEX idx_grants_expires ON grants(expires_at);
CREATE INDEX idx_grants_request ON grants(request_id);

-- ============================================
-- 5. USAGE_LOGS ( Registro de uso )
-- ============================================
CREATE TABLE usage_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    bucket_date DATE NOT NULL,
    usage_minutes INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(device_id, package_name, bucket_date)
);

CREATE INDEX idx_usage_logs_device_date ON usage_logs(device_id, bucket_date);
CREATE INDEX idx_usage_logs_package ON usage_logs(package_name);
CREATE INDEX idx_usage_logs_bucket ON usage_logs(bucket_date);

-- ============================================
-- 6. DEVICE_PUSH_TOKENS ( Tokens FCM )
-- ============================================
CREATE TABLE device_push_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    platform push_token_platform NOT NULL DEFAULT 'ANDROID',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(device_id, token)
);

CREATE INDEX idx_push_tokens_device ON device_push_tokens(device_id);
CREATE INDEX idx_push_tokens_active ON device_push_tokens(is_active);

-- ============================================
-- 7. DEVICE_HEARTBEATS ( Latidos del dispositivo )
-- ============================================
CREATE TABLE device_heartbeats (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    battery_level INTEGER,
    is_charging BOOLEAN DEFAULT FALSE,
    app_in_foreground TEXT,
    policy_version INTEGER NOT NULL,
    enforcement_level TEXT, -- 'DEVICE_OWNER', 'STANDARD', 'DEGRADED'
    suspicion_level TEXT, -- 'NONE', 'LOW', 'MEDIUM', 'HIGH'
    payload JSONB DEFAULT '{}'::JSONB
);

CREATE INDEX idx_heartbeats_device ON device_heartbeats(device_id);
CREATE INDEX idx_heartbeats_timestamp ON device_heartbeats(timestamp);
CREATE INDEX idx_heartbeats_device_timestamp ON device_heartbeats(device_id, timestamp);

-- ============================================
-- 8. PAIRING_CODES ( Códigos de emparejamiento )
-- ============================================
CREATE TABLE pairing_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code TEXT NOT NULL UNIQUE,
    parent_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name TEXT,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
    status pairing_status NOT NULL DEFAULT 'ACTIVE',
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pairing_codes_code ON pairing_codes(code);
CREATE INDEX idx_pairing_codes_expires ON pairing_codes(expires_at);
CREATE INDEX idx_pairing_codes_status ON pairing_codes(status);
CREATE INDEX idx_pairing_codes_parent ON pairing_codes(parent_id);

-- ============================================
-- 9. POLICY_TEMPLATES ( Plantillas de política )
-- ============================================
CREATE TABLE policy_templates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT,
    age_band TEXT NOT NULL, -- '0-6', '7-12', '13-17'
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    config JSONB NOT NULL, -- Política completa según §0.3
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_templates_age_band ON policy_templates(age_band);
CREATE INDEX idx_templates_default ON policy_templates(is_default);

-- ============================================
-- 10. SCHEDULES
-- ============================================
CREATE TABLE schedules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    days TEXT[] NOT NULL, -- ['MONDAY', 'TUESDAY', ...]
    from_time TIME NOT NULL, -- HH:MM
    to_time TIME NOT NULL,   -- HH:MM
    action TEXT NOT NULL, -- 'LOCK', 'ALLOW_ONLY'
    allow_list TEXT[], -- Paquetes permitidos durante ALLOW_ONLY
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_schedules_device ON schedules(device_id);
CREATE INDEX idx_schedules_active ON schedules(device_id, is_active);

-- ============================================
-- 11. OUTBOX ( Cola de eventos )
-- ============================================
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL,
    payload JSONB NOT NULL,
    dedup_key TEXT,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_device ON outbox(device_id);
CREATE INDEX idx_outbox_unprocessed ON outbox(processed) WHERE NOT processed;
CREATE INDEX idx_outbox_dedup ON outbox(dedup_key) WHERE dedup_key IS NOT NULL;

-- ============================================
-- FUNCIONES DE UTILIDAD
-- ============================================

-- Actualizar updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Bump de versión de política
CREATE OR REPLACE FUNCTION bump_policy_version(target_device_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE devices
    SET policy_version = policy_version + 1,
        updated_at = NOW()
    WHERE id = target_device_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- TRIGGERS PARA UPDATED_AT
-- ============================================
CREATE TRIGGER devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER app_policies_updated_at
    BEFORE UPDATE ON app_policies
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER grants_updated_at
    BEFORE UPDATE ON grants
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER usage_logs_updated_at
    BEFORE UPDATE ON usage_logs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER device_push_tokens_updated_at
    BEFORE UPDATE ON device_push_tokens
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER policy_templates_updated_at
    BEFORE UPDATE ON policy_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER schedules_updated_at
    BEFORE UPDATE ON schedules
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================
-- TRIGGERS PARA BUMP DE VERSIÓN
-- ============================================
CREATE OR REPLACE FUNCTION bump_policy_version_trigger()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE devices
    SET policy_version = policy_version + 1,
        updated_at = NOW()
    WHERE id = COALESCE(NEW.device_id, OLD.device_id);

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER app_policies_version_bump
AFTER INSERT OR UPDATE OR DELETE ON app_policies
FOR EACH ROW
EXECUTE FUNCTION bump_policy_version_trigger();

CREATE TRIGGER grants_version_bump
AFTER INSERT OR UPDATE OR DELETE ON grants
FOR EACH ROW
EXECUTE FUNCTION bump_policy_version_trigger();

CREATE TRIGGER schedules_version_bump
AFTER INSERT OR UPDATE OR DELETE ON schedules
FOR EACH ROW
EXECUTE FUNCTION bump_policy_version_trigger();

-- ============================================
-- FUNCIÓN: get_device_policy
-- Ensambla el JSON de política según §0.3
-- ============================================
CREATE OR REPLACE FUNCTION get_device_policy(target_device_id UUID)
RETURNS JSONB AS $$
DECLARE
    device_data RECORD;
    app_policies_data JSONB;
    schedules_data JSONB;
    grants_data JSONB;
    category_assignments_data JSONB;
    category_limits_data JSONB;
BEGIN
    -- Obtener datos del dispositivo
    SELECT * INTO device_data FROM devices WHERE id = target_device_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    -- Obtener app_policies
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'package_name', ap.package_name,
            'state', ap.state,
            'daily_limit_minutes', ap.daily_limit_minutes,
            'allowed_windows', ap.allowed_windows,
            'category', ap.category
        )
    ), '[]'::JSONB) INTO app_policies_data
    FROM app_policies ap
    WHERE ap.device_id = target_device_id;

    -- Obtener schedules
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'id', s.id,
            'days', s.days,
            'from', s.from_time::TEXT,
            'to', s.to_time::TEXT,
            'action', s.action,
            'allow_list', s.allow_list
        )
    ), '[]'::JSONB) INTO schedules_data
    FROM schedules s
    WHERE s.device_id = target_device_id AND s.is_active = TRUE;

    -- Obtener grants vigentes
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'id', g.id,
            'request_id', g.request_id,
            'scope', g.scope,
            'minutes', g.minutes,
            'source', g.source,
            'granted_at', g.granted_at,
            'expires_at', g.expires_at
        )
    ), '[]'::JSONB) INTO grants_data
    FROM grants g
    WHERE g.device_id = target_device_id
      AND g.expires_at > NOW()
      AND g.status = 'APPROVED';

    -- Derivar category_assignments de app_policies
    SELECT COALESCE(jsonb_object_agg(ap.package_name, ap.category), '{}'::JSONB)
    INTO category_assignments_data
    FROM app_policies ap
    WHERE ap.device_id = target_device_id AND ap.category IS NOT NULL;

    -- Category limits (podría venir de plantilla)
    category_limits_data := '[]'::JSONB;

    -- Ensamblar respuesta
    RETURN jsonb_build_object(
        'device_id', device_data.id,
        'version', device_data.policy_version,
        'device_state', device_data.device_state,
        'daily_screen_time_minutes', 120, -- Valor por defecto, podría venir de plantilla
        'schedules', schedules_data,
        'category_limits', category_limits_data,
        'app_policies', app_policies_data,
        'category_assignments', category_assignments_data,
        'grants', grants_data
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- FUNCIÓN: Cleanup de grants expirados
-- ============================================
CREATE OR REPLACE FUNCTION cleanup_expired_grants()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    UPDATE grants
    SET status = 'EXPIRED'
    WHERE expires_at < NOW() AND status = 'APPROVED';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- FUNCIÓN: Cleanup de usage_logs antiguos
-- ============================================
CREATE OR REPLACE FUNCTION cleanup_old_usage_logs(retention_days INTEGER DEFAULT 90)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM usage_logs
    WHERE bucket_date < CURRENT_DATE - (retention_days || ' days')::INTERVAL;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- FUNCIÓN: Cleanup de heartbeats antiguos
-- ============================================
CREATE OR REPLACE FUNCTION cleanup_old_heartbeats(retention_days INTEGER DEFAULT 30)
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM device_heartbeats
    WHERE timestamp < NOW() - (retention_days || ' days')::INTERVAL;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- FUNCIÓN: Cleanup de pairing codes expirados
-- ============================================
CREATE OR REPLACE FUNCTION cleanup_expired_pairing_codes()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    UPDATE pairing_codes
    SET status = 'EXPIRED'
    WHERE expires_at < NOW() AND status = 'ACTIVE';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- PG_CRON: Jobs de retención
-- ============================================
-- Nota: Estos jobs se crean manualmente o via migrate
-- SELECT cron.schedule('cleanup-grants', '0 3 * * *', 'SELECT cleanup_expired_grants()');
-- SELECT cron.schedule('cleanup-usage-logs', '0 4 * * *', 'SELECT cleanup_old_usage_logs()');
-- SELECT cron.schedule('cleanup-heartbeats', '0 5 * * *', 'SELECT cleanup_old_heartbeats()');
-- SELECT cron.schedule('cleanup-pairing-codes', '*/15 * * * *', 'SELECT cleanup_expired_pairing_codes()');
