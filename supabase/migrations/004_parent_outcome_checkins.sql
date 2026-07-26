-- T33: Check-in de resultado percibido por el padre
-- Control Parental - Tabla de micro-feedback

-- ============================================
-- ENUMS ADICIONALES
-- ============================================
CREATE TYPE checkin_rating AS ENUM ('POSITIVE', 'NEUTRAL', 'NEGATIVE');
--   😊 = POSITIVE
--   😐 = NEUTRAL
--   ☹️ = NEGATIVE

-- ============================================
-- 13. PARENT_OUTCOME_CHECKINS
-- ============================================
CREATE TABLE parent_outcome_checkins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    parent_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,

    -- Rating: 😊/😐/☹️
    rating checkin_rating NOT NULL,

    -- Comentario opcional (max 500 chars)
    comment TEXT,

    -- Período al que refiere (quincenal)
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,

    -- Metadatos
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Un check-in por padre por período
    CONSTRAINT unique_parent_period UNIQUE (parent_id, period_start, period_end)
);

-- Índices
CREATE INDEX idx_checkins_parent ON parent_outcome_checkins(parent_id);
CREATE INDEX idx_checkins_period ON parent_outcome_checkins(period_start, period_end);
CREATE INDEX idx_checkins_created ON parent_outcome_checkins(created_at DESC);

-- Trigger para updated_at

CREATE TRIGGER parent_outcome_checkins_updated_at
    BEFORE UPDATE ON parent_outcome_checkins
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();

-- ============================================
-- 14. BEHAVIORAL_EVENTS (para T32/T33)
-- ============================================
CREATE TABLE behavioral_events (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    parent_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    client_ts TIMESTAMPTZ NOT NULL,
    props JSONB DEFAULT '{}',
    synced BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Índices
CREATE INDEX idx_events_type ON behavioral_events(event_type);
CREATE INDEX idx_events_device ON behavioral_events(device_id);
CREATE INDEX idx_events_parent ON behavioral_events(parent_id);
CREATE INDEX idx_events_created ON behavioral_events(created_at DESC);
CREATE INDEX idx_events_synced ON behavioral_events(synced) WHERE synced = FALSE;

-- ============================================
-- RLS: PARENT_OUTCOME_CHECKINS
-- ============================================
ALTER TABLE parent_outcome_checkins ENABLE ROW LEVEL SECURITY;

-- El padre solo ve sus propios check-ins
CREATE POLICY parent_own_checkins ON parent_outcome_checkins
    FOR ALL
    USING (parent_id = auth.uid());

-- ============================================
-- RLS: BEHAVIORAL_EVENTS
-- ============================================
ALTER TABLE behavioral_events ENABLE ROW LEVEL SECURITY;

-- El dispositivo puede insertar eventos (via service_role en Edge Functions)
-- El padre puede ver eventos de sus dispositivos
CREATE POLICY parent_events ON behavioral_events
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

-- ============================================
-- FUNCIÓN: Obtener check-in del padre
-- ============================================
CREATE OR REPLACE FUNCTION get_parent_checkins(
    p_parent_id UUID,
    p_limit INTEGER DEFAULT 12
)
RETURNS SETOF parent_outcome_checkins AS $$
BEGIN
    RETURN QUERY
    SELECT *
    FROM parent_outcome_checkins
    WHERE parent_id = p_parent_id
    ORDER BY period_start DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- FUNCIÓN: Insertar check-in (con RLS)
-- ============================================
CREATE OR REPLACE FUNCTION insert_parent_checkin(
    p_rating checkin_rating,
    p_period_start DATE,
    p_period_end DATE,
    p_comment TEXT DEFAULT NULL,
    p_device_id UUID DEFAULT NULL
)
RETURNS parent_outcome_checkins AS $$
DECLARE
    v_checkin parent_outcome_checkins;
BEGIN
    INSERT INTO parent_outcome_checkins (
        parent_id, device_id, rating, comment, period_start, period_end
    ) VALUES (
        auth.uid(), p_device_id, p_rating, p_comment, p_period_start, p_period_end
    )
    RETURNING * INTO v_checkin;

    RETURN v_checkin;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- COMENTARIOS DE DOCUMENTACIÓN
-- ============================================
COMMENT ON TABLE parent_outcome_checkins IS
    'T33: Micro-feedback de resultado percibido por el padre. Cadencia quincenal.';

COMMENT ON COLUMN parent_outcome_checkins.rating IS
    '😊 POSITIVE | 😐 NEUTRAL | ☹️ NEGATIVE';

COMMENT ON COLUMN parent_outcome_checkins.period_start IS
    'Inicio del período de referencia (quincenal)';

COMMENT ON COLUMN parent_outcome_checkins.comment IS
    'Comentario opcional libre, max 500 caracteres';

COMMENT ON COLUMN parent_outcome_checkins.period_end IS
    'Fin del período de referencia';
