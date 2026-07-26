-- T14: Policy Templates
-- Plantillas por age_band según §0.3

-- ============================================
-- PLANTILLA: 0-6 años (Preescolar)
-- ============================================
INSERT INTO policy_templates (name, description, age_band, is_default, config) VALUES (
    'Preescolar (0-6 años)',
    'Restricciones estrictas para niños pequeños. Sin juegos, solo apps educativas permitidas.',
    '0-6',
    FALSE,
    '{
        "device_state": "ACTIVE",
        "daily_screen_time_minutes": 60,
        "schedules": [
            {
                "id": "bedtime-early",
                "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"],
                "from": "19:00",
                "to": "07:00",
                "action": "LOCK"
            }
        ],
        "category_limits": [
            {"category": "games", "minutes": 0},
            {"category": "social", "minutes": 0},
            {"category": "entertainment", "minutes": 15}
        ],
        "app_policies": [
            {"package_name": "com.whatsapp", "state": "BLOCKED"},
            {"package_name": "com.instagram", "state": "BLOCKED"},
            {"package_name": "com.tiktok", "state": "BLOCKED"},
            {"package_name": "com.snapchat", "state": "BLOCKED"}
        ]
    }'::JSONB
);

-- ============================================
-- PLANTILLA: 7-12 años (Escuela primaria)
-- ============================================
INSERT INTO policy_templates (name, description, age_band, is_default, config) VALUES (
    'Escuela Primaria (7-12 años)',
    'Balance entre estudio y entretenimiento. Juegos limitados, sin redes sociales.',
    '7-12',
    TRUE,  -- Es la plantilla por defecto
    '{
        "device_state": "ACTIVE",
        "daily_screen_time_minutes": 120,
        "schedules": [
            {
                "id": "school-hours",
                "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
                "from": "08:00",
                "to": "15:00",
                "action": "LOCK"
            },
            {
                "id": "bedtime",
                "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
                "from": "21:00",
                "to": "07:00",
                "action": "LOCK"
            },
            {
                "id": "weekend-loose",
                "days": ["SATURDAY", "SUNDAY"],
                "from": "09:00",
                "to": "20:00",
                "action": "ALLOW_ONLY",
                "allow_list": ["com.example.educational", "com.example.games.kids"]
            }
        ],
        "category_limits": [
            {"category": "games", "minutes": 60},
            {"category": "social", "minutes": 0},
            {"category": "entertainment", "minutes": 45}
        ],
        "app_policies": [
            {"package_name": "com.whatsapp", "state": "BLOCKED"},
            {"package_name": "com.instagram", "state": "BLOCKED"},
            {"package_name": "com.tiktok", "state": "BLOCKED"},
            {"package_name": "com.snapchat", "state": "BLOCKED"},
            {"package_name": "com.facebook", "state": "BLOCKED"},
            {"package_name": "com.twitter", "state": "BLOCKED"}
        ]
    }'::JSONB
);

-- ============================================
-- PLANTILLA: 13-17 años (Adolescencia)
-- ============================================
INSERT INTO policy_templates (name, description, age_band, is_default, config) VALUES (
    'Adolescencia (13-17 años)',
    'Más libertad con supervisión. Redes sociales con límites, tiempo de pantalla razonable.',
    '13-17',
    FALSE,
    '{
        "device_state": "ACTIVE",
        "daily_screen_time_minutes": 180,
        "schedules": [
            {
                "id": "study-time",
                "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
                "from": "17:00",
                "to": "19:00",
                "action": "LOCK"
            },
            {
                "id": "bedtime",
                "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"],
                "from": "22:00",
                "to": "07:00",
                "action": "LOCK"
            },
            {
                "id": "sunday-night",
                "days": ["SUNDAY"],
                "from": "20:00",
                "to": "07:00",
                "action": "LOCK"
            }
        ],
        "category_limits": [
            {"category": "games", "minutes": 120},
            {"category": "social", "minutes": 90},
            {"category": "entertainment", "minutes": 60}
        ],
        "app_policies": [
            {"package_name": "com.tiktok", "state": "LIMITED", "daily_limit_minutes": 30},
            {"package_name": "com.instagram", "state": "LIMITED", "daily_limit_minutes": 30},
            {"package_name": "com.snapchat", "state": "LIMITED", "daily_limit_minutes": 15}
        ]
    }'::JSONB
);

-- ============================================
-- FUNCIÓN: Aplicar plantilla a dispositivo
-- ============================================
CREATE OR REPLACE FUNCTION apply_policy_template(
    target_device_id UUID,
    template_id UUID
)
RETURNS VOID AS $$
DECLARE
    template_config JSONB;
    app_policies_array JSONB;
    schedules_array JSONB;
    policy_item JSONB;
    schedule_item JSONB;
BEGIN
    -- Obtener configuración de la plantilla
    SELECT config INTO template_config 
    FROM policy_templates 
    WHERE id = template_id;
    
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Plantilla no encontrada';
    END IF;
    
    -- Actualizar estado del dispositivo
    UPDATE devices SET
        device_state = (template_config ->> 'device_state')::device_state,
        updated_at = NOW()
    WHERE id = target_device_id;
    
    -- Aplicar schedules
    schedules_array := template_config -> 'schedules';
    IF schedules_array IS NOT NULL THEN
        -- Eliminar schedules existentes
        DELETE FROM schedules WHERE device_id = target_device_id;
        
        -- Insertar nuevos schedules
        FOR schedule_item IN SELECT * FROM jsonb_array_elements(schedules_array)
        LOOP
            INSERT INTO schedules (
                device_id, name, days, from_time, to_time, action, allow_list
            ) VALUES (
                target_device_id,
                schedule_item ->> 'id',
                ARRAY(SELECT jsonb_array_elements_text(schedule_item -> 'days')),
                (schedule_item ->> 'from')::TIME,
                (schedule_item ->> 'to')::TIME,
                schedule_item ->> 'action',
                CASE 
                    WHEN schedule_item -> 'allow_list' IS NOT NULL 
                    THEN ARRAY(SELECT jsonb_array_elements_text(schedule_item -> 'allow_list'))
                    ELSE NULL
                END
            );
        END LOOP;
    END IF;
    
    -- Aplicar app_policies
    app_policies_array := template_config -> 'app_policies';
    IF app_policies_array IS NOT NULL THEN
        FOR policy_item IN SELECT * FROM jsonb_array_elements(app_policies_array)
        LOOP
            INSERT INTO app_policies (
                device_id, package_name, state, daily_limit_minutes, category
            ) VALUES (
                target_device_id,
                policy_item ->> 'package_name',
                (policy_item ->> 'state')::app_policy_state,
                NULLIF(policy_item ->> 'daily_limit_minutes', '')::INTEGER,
                NULLIF(policy_item ->> 'category', '')::TEXT
            )
            ON CONFLICT (device_id, package_name) DO UPDATE SET
                state = EXCLUDED.state,
                daily_limit_minutes = EXCLUDED.daily_limit_minutes,
                category = EXCLUDED.category;
        END LOOP;
    END IF;
    
    -- Bump de versión
    PERFORM bump_policy_version(target_device_id);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- FUNCIÓN: Obtener plantillas disponibles
-- ============================================
CREATE OR REPLACE FUNCTION get_available_templates()
RETURNS TABLE (
    id UUID,
    name TEXT,
    description TEXT,
    age_band TEXT,
    is_default BOOLEAN
) AS $$
BEGIN
    RETURN QUERY
    SELECT pt.id, pt.name, pt.description, pt.age_band, pt.is_default
    FROM policy_templates pt
    ORDER BY pt.age_band;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
