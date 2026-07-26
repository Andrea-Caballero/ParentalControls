# Windows Agent — APIs compartidas con el Backend

> Contrato de superficie API entre el agente Windows y el backend Supabase.
> Documento de propuesta para confirmacion por el equipo de backend antes de implementacion.

## 1. Resumen

| Categoria | Direccion | Transporte |
|-----------|-----------|------------|
| Pairing e identidad | agent -> server | HTTPS (Edge Function) |
| Telemetria y eventos | agent -> server | HTTPS (PostgREST) |
| Salud y operacion | agent -> server | HTTPS (RPC / Edge Function) |
| Push bidireccional | server -> agent (WNS), agent -> server (registro) | WNS |
| Live updates | server -> agent | WebSocket (Supabase Realtime) |

Total: **10 endpoints/canales** entre los dos servicios.

---

## 2. Pairing e identidad

### `POST /functions/v1/pairing`

Emparejamiento - vincula la PC del hijo con la cuenta del padre.

- **Direccion:** agent -> server
- **Auth:** ninguno (primer contacto)
- **Body (propuesto):**
  ```json
  {
    "code": "ABC123",
    "device_name": "PC-Hijo-Escritorio",
    "device_model": "Dell XPS 15",
    "os_version": "Windows 11 23H2",
    "app_version": "1.0.0",
    "platform": "WINDOWS_DESKTOP",
    "age_band": "7-12",
    "child_first_name": "Juan"
  }
  ```
- **Respuesta esperada:** `device_id`, `parent_id`, token de sesion, `policy_version` inicial.
- **Errores relevantes:** codigo invalido (404), codigo expirado (410).

> **Campos obligatorios:** `code`, `device_name`, `app_version`, `child_first_name`.
>
> **`age_band`** (string): uno de `0-6`, `7-12`, `13-17`. Default `7-12` si el padre no lo especifica. Determina que plantilla de politica se aplica al dispositivo recien emparejado (ver `policy_templates` en migration `003_policy_templates.sql`). El backend hoy NO valida este campo contra un enum ni CHECK constraint, asi que cualquier string pasa la Edge Function pero la busqueda de plantilla puede no devolver nada y caer al warning `No template found for age_band`.
>
> **`child_first_name`** (string, 1-32 chars, trimmed): requerido por Bug #2 de la auditoria (ya arreglado). El padre lo captura en su UI antes de generar el codigo y queda persistido en `pairing_codes.child_first_name`.

---

## 3. Telemetria y eventos

### `POST /rest/v1/usage_logs`

Sube registros de uso de apps por el hijo (sesiones foreground).

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:** una fila o batch de filas por sesion de app
  ```json
  {
    "app_id": "com.roblox.robloxclient",
    "app_name": "Roblox",
    "started_at": "2026-07-17T14:00:00Z",
    "ended_at": "2026-07-17T14:32:00Z",
    "foreground_seconds": 1920,
    "was_blocked": false
  }
  ```

### `POST /rest/v1/device_alerts`

Sube alertas operacionales del agente.

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:**
  ```json
  {
    "alert_type": "SERVICE_DOWN | TAMPER_ATTEMPT | ENFORCEMENT_LOST | UNUSUAL_HOUR",
    "severity": "INFO | WARN | CRITICAL",
    "message": "Agent service stopped unexpectedly",
    "context": { "restart_count": 3 }
  }
  ```

### `POST /rest/v1/behavioral_events`

Sube eventos discretos de comportamiento con semantica de producto.

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:**
  ```json
  {
    "event_type": "APP_BLOCKED | TIME_LIMIT_REACHED | CATEGORY_BLOCKED | EMERGENCY_OVERRIDE_USED | SUSPICIOUS_ACTIVITY",
    "subject": "com.roblox.robloxclient",
    "occurred_at": "2026-07-17T14:32:00Z",
    "metadata": { "daily_used_minutes": 121, "limit_minutes": 120 }
  }
  ```

### `POST /rest/v1/time_requests`

El hijo pide tiempo extra al padre.

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:**
  ```json
  {
    "requested_minutes": 30,
    "reason": "Termine la tarea, puedo jugar 30 min mas?"
  }
  ```

---

## 4. Salud y operacion

### `RPC get_device_policy(device_id)`

Descarga la politica de control activa (apps bloqueadas, limites, horarios, grants).

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Argumento:** `device_id` (UUID)
- **Retorno esperado:** snapshot completo de politica (estructura a definir).

### `RPC heartbeat`

Envia estado de salud del agente.

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:**
  ```json
  {
    "battery_level": 0.85,
    "is_charging": false,
    "app_in_foreground": null,
    "enforcement_level": "STANDARD | STRICT | DEGRADED",
    "clock_offset_ms": -1200,
    "policy_version": 5
  }
  ```
- **Retorno esperado:** confirmacion + `policy_version` actual del servidor.

### `POST /rest/v1/integrity_reports`

Envia hash/firma del binario del agente.

- **Direccion:** agent -> server
- **Auth:** Bearer `<device_jwt>`
- **Body:**
  ```json
  {
    "binary_path": "C:\\Program Files\\ParentalControl\\agent.exe",
    "binary_sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "signature_valid": true,
    "signer": "CN=ParentalControl",
    "reported_at": "2026-07-17T15:00:00Z"
  }
  ```

---

## 5. Canales server -> agent

### `WNS push registration`

Registra el canal de push.

- **Direccion:** agent -> server (registro); server -> agent (entrega)
- **Auth:** Bearer `<device_jwt>` para el registro
- **Registro:**
  ```json
  POST /rest/v1/push_channels
  {
    "wns_channel_uri": "https://db5.notify.windows.com/?token=...",
    "platform": "WINDOWS_DESKTOP",
    "app_version": "1.0.0"
  }
  ```
- **Payload WNS esperado del backend:** a definir.

### `realtime (WebSocket)`

Se suscribe a cambios de politica/grants mientras la UI esta abierta.

- **Direccion:** server -> agent (push)
- **Auth:** Bearer `<device_jwt>` al conectar
- **Suscripciones esperadas:**
  - `device_policy:{device_id}`
  - `grants:{device_id}`
  - `commands:{device_id}`

---

## 6. Pendientes de definicion conjunta

1. Shape exacta del retorno de `get_device_policy`.
2. Schema del payload WNS (tipos de mensaje, TTL, prioridad).
3. Convencion de batch vs POST unitario.
4. Headers de idempotencia (`Idempotency-Key`).
5. Catalogo de errores uniforme.
6. Politica de retencion y deduplicacion offline.
7. Endpoint de unpair y baja de dispositivo.

Detalles en `docs/windows-agent-contract-requirements.md`.

---

## 7. Fuera de alcance

- App movil del padre (Android) - usa contrato T15 separado, ver `supabase/README.md`.
- Panel web del padre - fuera de esta lista.

---

**Estado:** Propuesta inicial del equipo del agente Windows para confirmacion por el equipo de backend.
**Documento complementario:** `docs/windows-agent-contract-requirements.md`.