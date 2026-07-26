# T15 — Contrato Backend (Edge Functions)

## Edge Functions

| Función | Endpoint | Descripción |
|---------|----------|-------------|
| `pairing` | `/functions/v1/pairing` | Emparejamiento dispositivo-padre |
| `get-policy` | `/functions/v1/get-policy` | Obtener política actual |
| `approve-request` | `/functions/v1/approve-request` | Aprobar solicitud de tiempo |
| `reward` | `/functions/v1/reward` | Crear grant de recompensa |
| `heartbeat` | `/functions/v1/heartbeat` | Registro de latido del dispositivo |
| `register-token` | `/functions/v1/register-token` | Registrar token FCM |
| `verify-integrity` | `/functions/v1/verify-integrity` | Verificar Play Integrity |
| `fcm-send` | `/functions/v1/fcm-send` | Fan-out de notificaciones |

## API Reference

### POST /functions/v1/pairing

Empareja un dispositivo con un padre usando código de emparejamiento.

```json
// Request
{
  "code": "ABC123",
  "device_name": "Samsung Galaxy S21",
  "device_model": "SM-G991B",
  "os_version": "Android 14",
  "app_version": "1.0.0",
  "age_band": "7-12"
}

// Response (200)
{
  "success": true,
  "device_id": "uuid-dispositivo",
  "parent_id": "uuid-padre",
  "policy_version": 1
}

// Errors
// 404: Código inválido
// 410: Código expirado
```

### GET /functions/v1/get-policy

Obtiene la política actual del dispositivo.

```
Authorization: Bearer <device_jwt>
```

```json
// Response (200)
{
  "device_id": "...",
  "version": 5,
  "device_state": "ACTIVE",
  "daily_screen_time_minutes": 120,
  "schedules": [...],
  "category_limits": [...],
  "app_policies": [...],
  "category_assignments": {...},
  "grants": [...],
  "device_name": "...",
  "app_version": "1.0.0",
  "last_sync_at": "...",
  "fetched_at": "..."
}
```

### POST /functions/v1/approve-request

Aprueba una solicitud de tiempo (idempotente).

```
Authorization: Bearer <parent_jwt>
```

```json
// Request
{
  "request_id": "uuid-solicitud",
  "minutes": 30,
  "response_text": "¡Buen trabajo!"
}

// Response (200)
{
  "success": true,
  "grant_id": "uuid-grant",
  "minutes": 30,
  "expires_at": "2026-06-04T15:30:00Z",
  "policy_version": 6
}

// Idempotent response (si ya fue aprobada)
{
  "success": true,
  "idempotent": true,
  "grant_id": "uuid-grant-existente",
  "message": "Solicitud ya aprobada anteriormente"
}
```

### POST /functions/v1/reward

Crea un grant de recompensa (respeta topes diarios/semanales).

```
Authorization: Bearer <parent_jwt>
```

```json
// Request
{
  "device_id": "uuid-dispositivo",
  "minutes": 15,
  "reason": "Terminó la tarea"
}

// Response (200)
{
  "success": true,
  "grant_id": "uuid-grant",
  "minutes": 15,
  "expires_at": "2026-06-11T12:00:00Z",
  "remaining_daily": 45,
  "remaining_weekly": 165
}

// Error (429 - límite excedido)
{
  "error": "Excede límite diario. Disponible: 10 minutos",
  "daily_limit": 60,
  "daily_used": 50,
  "requested": 15
}
```

### POST /functions/v1/heartbeat

Envía latido del dispositivo.

```
Authorization: Bearer <device_jwt>
```

```json
// Request
{
  "battery_level": 85,
  "is_charging": false,
  "app_in_foreground": "com.instagram.android",
  "enforcement_level": "STANDARD",
  "suspicion_level": "NONE",
  "clock_offset_ms": -1200
}

// Response (200)
{
  "success": true,
  "policy_version": 5,
  "server_time": "2026-06-04T12:00:00Z"
}
```

### POST /functions/v1/register-token

Registra token FCM del dispositivo.

```
Authorization: Bearer <device_jwt>
```

```json
// Request
{
  "fcm_token": "bk3RNwTe3p0T...",
  "platform": "ANDROID"
}

// Response (200)
{
  "success": true,
  "token_id": "uuid-token"
}
```

### POST /functions/v1/verify-integrity

Verifica token de Play Integrity.

```
Authorization: Bearer <device_jwt>
```

```json
// Request
{
  "integrity_token": "base64-encoded-token"
}

// Response (200)
{
  "valid": true,
  "details": {
    "is_valid": true,
    "device_integrity": "MEETS_PLAY_INTEGRITY",
    "app_integrity": "APP_VALID",
    "account_details": "LICENSED"
  }
}

// Response (200 - integridad fallida)
{
  "valid": false,
  "details": {
    "is_valid": false,
    "device_integrity": "",
    "app_integrity": "APP_NOT_INSTALLED",
    "failure_reason": "device=, app=APP_NOT_INSTALLED, account="
  }
}
```

### POST /functions/v1/fcm-send

Envía notificación FCM (solo service_role).

```
Authorization: Bearer <service_role_key>
```

```json
// Request
{
  "device_id": "uuid-dispositivo",
  "payload": {
    "type": "POLICY_UPDATED",
    "message": "Tu política fue actualizada"
  },
  "priority": "high"
}

// Response (200)
{
  "success": true,
  "sent_to": "uuid-dispositivo",
  "fcm_response": {
    "success": true,
    "message_id": "0:xxx..."
  }
}
```

## Variables de Entorno

```env
# Supabase
SUPABASE_URL=https://xxx.supabase.co
SUPABASE_SERVICE_ROLE_KEY=eyJ...

# FCM
FCM_SERVER_KEY=AAA...

# Play Integrity
PLAY_PACKAGE_NAME=com.example.parentalcontrol
GOOGLE_SERVICE_ACCOUNT={"type":"service_account",...}
```

## Testing

### Pairing Flow

```bash
# 1. Crear código de emparejamiento (via SQL o dashboard)
INSERT INTO pairing_codes (code, parent_id, device_name, expires_at)
VALUES ('ABC123', 'parent-uuid', 'hijo-10', NOW() + INTERVAL '10 minutes');

# 2. Llamar endpoint de pairing
curl -X POST https://xxx.supabase.co/functions/v1/pairing \
  -H "Content-Type: application/json" \
  -d '{"code":"ABC123","device_name":"Galaxy S21","app_version":"1.0.0"}'

# 3. Verificar que device_id aparece en auth.users.app_metadata
```

### Verificar Idempotencia

```bash
# Primera aprobación
curl -X POST https://xxx.supabase.co/functions/v1/approve-request \
  -H "Authorization: Bearer $PARENT_TOKEN" \
  -d '{"request_id":"req-uuid","minutes":30}'

# Segunda aprobación (debe retornar idempotent)
curl -X POST https://xxx.supabase.co/functions/v1/approve-request \
  -H "Authorization: Bearer $PARENT_TOKEN" \
  -d '{"request_id":"req-uuid","minutes":30}'

# Verificar que solo existe UN grant
SELECT * FROM grants WHERE request_id = 'req-uuid';
```

### Verificar RLS con device token

```bash
# Obtener token del dispositivo
DEVICE_TOKEN=$(cat device_jwt.json)

# Intentar acceder a política
curl https://xxx.supabase.co/functions/v1/get-policy \
  -H "Authorization: Bearer $DEVICE_TOKEN"

# Debe funcionar si device_id coincide

# Intentar acceder a otro dispositivo
WRONG_TOKEN=$(cat wrong_device_jwt.json)
curl https://xxx.supabase.co/functions/v1/get-policy \
  -H "Authorization: Bearer $WRONG_TOKEN"

# Debe fallar con 403
```

## Notas de Implementación

1. **Emparejamiento**: Usa `uuid_generate_v4()` para device_id
2. **Idempotencia**: `approve-request` verifica si ya existe grant para evitar duplicados
3. **Bump de versión**: Triggers automáticos en grants y app_policies
4. **FCM Fan-out**: Se dispara tras cambios de política
5. **Play Integrity**: Usa service account para verificar tokens

## Dependencias

```json
// import_map.json o deno.json
{
  "imports": {
    "@supabase/supabase-js": "https://esm.sh/@supabase/supabase-js@2",
    "@std/http": "https://deno.land/std@0.177.0/http/server.ts"
  }
}
```
