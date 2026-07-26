-- ============================================
-- Migracion 011: Completa el schema que el cliente
-- Windows necesita segun contrato en
-- ControlParental/apis.md y
-- ControlParental/windows-agent-contract-requirements.md.
-- BLOQ-4 del informe de auditoria 2026-07-22.
--
-- Nota de numeracion: el handoff original pedia 005_, pero
-- 005_ ya estaba ocupado por 005_children_table.sql; las
-- migraciones del repo ya llegan hasta 010. Se usa 011_
-- para mantener el orden cronologico real de aplicacion.
-- El contenido es identico al propuesto en el handoff.
--
-- Decisiones de diseno (para la IA que asista):
--   - INSERT directo del device con RLS (patron
--     Supabase recomendado, NO Edge Function).
--   - Columna `synced` queda como legado del diseno
--     previo, no la modifiques aca.
--
-- Que hace esta migracion:
--   1. Agrega policies de INSERT y SELECT del agente
--      a behavioral_events (la tabla YA EXISTE desde
--      la migracion 004, solo le faltan policies).
--   2. Crea device_alerts desde cero.
--   3. Crea integrity_reports desde cero.
--
-- Optimizaciones aplicadas (de la doc oficial de Supabase
-- sobre RLS performance, https://supabase.com/docs/guides/auth/row-level-security#rls-performance-recommendations):
--   - `TO authenticated` limita la evaluacion de la
--     policy al rol autenticado (mejora ~99% vs anon).
--   - `(SELECT auth.jwt() ->> 'device_id')` wrappea la
--     llamada a la funcion para que Postgres la cachee
--     via initPlan (mejora 94-99% en tablas con muchas filas).
--   - Indices en las columnas usadas por las policies.
-- ============================================

-- ============================================
-- PARTE 1: Policies faltantes en behavioral_events
-- (la tabla YA EXISTE desde la migracion 004,
-- solo le faltan policies de INSERT y SELECT del agente)
-- ============================================

-- Policy: el device puede insertar eventos propios.
-- Verifica que el device_id del JWT coincida con el device_id del row.
CREATE POLICY behavioral_events_agent_insert ON behavioral_events
    FOR INSERT
    TO authenticated
    WITH CHECK (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

-- Policy: el device puede leer sus propios eventos
CREATE POLICY behavioral_events_agent_select ON behavioral_events
    FOR SELECT
    TO authenticated
    USING (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

-- ============================================
-- PARTE 2: Crear device_alerts
-- ============================================
CREATE TABLE IF NOT EXISTS device_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    alert_type TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dedup_key TEXT UNIQUE
);

CREATE INDEX idx_device_alerts_device_id ON device_alerts(device_id);
CREATE INDEX idx_device_alerts_created_at ON device_alerts(created_at DESC);

ALTER TABLE device_alerts ENABLE ROW LEVEL SECURITY;

CREATE POLICY device_alerts_agent_insert ON device_alerts
    FOR INSERT
    TO authenticated
    WITH CHECK (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

CREATE POLICY device_alerts_agent_select ON device_alerts
    FOR SELECT
    TO authenticated
    USING (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

CREATE POLICY device_alerts_parent_select ON device_alerts
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM devices d
            WHERE d.id = device_alerts.device_id
              AND d.parent_id = (SELECT auth.uid())
        )
    );

-- Tabla: integrity_reports
CREATE TABLE IF NOT EXISTS integrity_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    report_hash TEXT NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    agent_version TEXT NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('WINDOWS', 'ANDROID', 'IOS', 'WEB')),
    raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_integrity_reports_device_id ON integrity_reports(device_id);
CREATE INDEX idx_integrity_reports_reported_at ON integrity_reports(reported_at DESC);

ALTER TABLE integrity_reports ENABLE ROW LEVEL SECURITY;

CREATE POLICY integrity_reports_agent_insert ON integrity_reports
    FOR INSERT
    TO authenticated
    WITH CHECK (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

CREATE POLICY integrity_reports_agent_select ON integrity_reports
    FOR SELECT
    TO authenticated
    USING (
        (SELECT (auth.jwt() ->> 'device_id'))::uuid = device_id
    );

CREATE POLICY integrity_reports_parent_select ON integrity_reports
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM devices d
            WHERE d.id = integrity_reports.device_id
              AND d.parent_id = (SELECT auth.uid())
        )
    );