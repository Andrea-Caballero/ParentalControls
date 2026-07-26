-- T14: Row Level Security (RLS)
-- Políticas exactas según §0.5

-- ============================================
-- HABILITAR RLS EN TODAS LAS TABLAS
-- ============================================
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_push_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_heartbeats ENABLE ROW LEVEL SECURITY;
ALTER TABLE pairing_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;

-- ============================================
-- POLÍTICAS PARA DEVICES
-- ============================================

-- Agente: puede leer/actualizar su propio dispositivo
CREATE POLICY devices_agent_select ON devices
    FOR SELECT
    USING (
        (auth.jwt() ->> 'device_id')::uuid = id
    );

CREATE POLICY devices_agent_update ON devices
    FOR UPDATE
    USING (
        (auth.jwt() ->> 'device_id')::uuid = id
    );

-- Padre: puede ver/eliminar los dispositivos de sus hijos
CREATE POLICY devices_parent_select ON devices
    FOR SELECT
    USING (
        parent_id = auth.uid()
    );

CREATE POLICY devices_parent_delete ON devices
    FOR DELETE
    USING (
        parent_id = auth.uid()
    );

-- Inserción: solo el agente o proceso de pairing
CREATE POLICY devices_insert ON devices
    FOR INSERT
    WITH CHECK (
        -- Si tiene device_id en JWT, es un dispositivo
        (auth.jwt() ->> 'device_id') IS NOT NULL
        OR
        -- O si es un padre creando código de emparejamiento
        (auth.uid() IS NOT NULL)
    );

-- ============================================
-- POLÍTICAS PARA APP_POLICIES
-- ============================================

-- Agente: puede leer/gestionar sus propias políticas
CREATE POLICY app_policies_agent_all ON app_policies
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede leer/crear/actualizar políticas de sus dispositivos
CREATE POLICY app_policies_parent_select ON app_policies
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = app_policies.device_id 
              AND d.parent_id = auth.uid()
        )
    );

CREATE POLICY app_policies_parent_insert ON app_policies
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = app_policies.device_id 
              AND d.parent_id = auth.uid()
        )
    );

CREATE POLICY app_policies_parent_update ON app_policies
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = app_policies.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- ============================================
-- POLÍTICAS PARA GRANTS
-- ============================================

-- Agente: puede leer sus grants
CREATE POLICY grants_agent_select ON grants
    FOR SELECT
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede ver/crear grants para sus dispositivos
CREATE POLICY grants_parent_select ON grants
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = grants.device_id 
              AND d.parent_id = auth.uid()
        )
    );

CREATE POLICY grants_parent_insert ON grants
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = grants.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- Agente: puede actualizar grants (para marcar expirados)
CREATE POLICY grants_agent_update ON grants
    FOR UPDATE
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- ============================================
-- POLÍTICAS PARA TIME_REQUESTS
-- ============================================

-- Agente: puede crear y leer sus solicitudes
CREATE POLICY time_requests_agent_all ON time_requests
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede ver y responder solicitudes de sus dispositivos
CREATE POLICY time_requests_parent_select ON time_requests
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = time_requests.device_id 
              AND d.parent_id = auth.uid()
        )
    );

CREATE POLICY time_requests_parent_update ON time_requests
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = time_requests.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- ============================================
-- POLÍTICAS PARA USAGE_LOGS
-- ============================================

-- Agente: puede leer y escribir sus logs de uso
CREATE POLICY usage_logs_agent_all ON usage_logs
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede leer logs de sus dispositivos
CREATE POLICY usage_logs_parent_select ON usage_logs
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = usage_logs.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- ============================================
-- POLÍTICAS PARA DEVICE_PUSH_TOKENS
-- ============================================

-- Agente: puede gestionar sus tokens
CREATE POLICY device_push_tokens_agent_all ON device_push_tokens
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- ============================================
-- POLÍTICAS PARA DEVICE_HEARTBEATS
-- ============================================

-- Agente: puede insertar y leer sus heartbeats
CREATE POLICY device_heartbeats_agent_all ON device_heartbeats
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede leer heartbeats de sus dispositivos
CREATE POLICY device_heartbeats_parent_select ON device_heartbeats
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = device_heartbeats.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- ============================================
-- POLÍTICAS PARA PAIRING_CODES
-- ============================================

-- Solo el padre dueño puede ver/gestionar sus códigos
CREATE POLICY pairing_codes_owner_all ON pairing_codes
    FOR ALL
    USING (
        parent_id = auth.uid()
    );

-- ============================================
-- POLÍTICAS PARA POLICY_TEMPLATES
-- ============================================

-- Todos los usuarios autenticados pueden leer plantillas
CREATE POLICY policy_templates_read ON policy_templates
    FOR SELECT
    USING (
        auth.role() IN ('authenticated', 'anon')
    );

-- Solo service_role puede modificar plantillas
-- (No crear POLICY para INSERT/UPDATE/DELETE - solo service_role)

-- ============================================
-- POLÍTICAS PARA SCHEDULES
-- ============================================

-- Agente: puede leer sus schedules
CREATE POLICY schedules_agent_select ON schedules
    FOR SELECT
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- Padre: puede gestionar schedules de sus dispositivos
CREATE POLICY schedules_parent_all ON schedules
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM devices d 
            WHERE d.id = schedules.device_id 
              AND d.parent_id = auth.uid()
        )
    );

-- ============================================
-- POLÍTICAS PARA OUTBOX
-- ============================================

-- Agente: puede leer y escribir en su outbox
CREATE POLICY outbox_agent_all ON outbox
    FOR ALL
    USING (
        (auth.jwt() ->> 'device_id')::uuid = device_id
    );

-- ============================================
-- FUNCIONES AUXILIARES PARA RLS
-- ============================================

-- Función para verificar si es dispositivo
CREATE OR REPLACE FUNCTION is_device_user()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (auth.jwt() ->> 'device_id') IS NOT NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Función para verificar si es padre
CREATE OR REPLACE FUNCTION is_parent_user()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN auth.uid() IS NOT NULL AND (auth.jwt() ->> 'device_id') IS NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- CREAR ROLES ADICIONALES
-- ============================================

-- Los roles padrão de Supabase son:
-- authenticated: usuarios normales (padres)
-- anon: usuarios anónimos
-- service_role: para backend (bypasses RLS)

-- Para el dispositivo, usamos el mismo authenticated
-- pero con device_id en el JWT via Custom Access Token Hook
