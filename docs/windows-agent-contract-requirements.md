# Windows Agent — Requisitos de Contrato API

> Documento de pre-implementacion. Lista los huecos y ambiguedades del contrato propuesto para el agente Windows que deben cerrarse **antes** de comenzar a codear. Pensado para enviarse al equipo de backend como respuesta formal a la lista inicial de endpoints.

## 1. Proposito

El equipo de backend envio una lista inicial de 11 endpoints/canales para que el agente Windows los implemente. Esta lista cubre razonablemente el flujo **agente -> servidor**, pero tiene huecos bloqueantes en el flujo inverso y en semanticas transversales (auth, retry, offline, tiempo, lifecycle).

Este documento:

1. Enumera los huecos criticos con propuesta concreta de resolucion.
2. Pide al backend artefactos entregables (OpenAPI, ejemplos, contratos de error) antes de iniciar implementacion.
3. Senala divergencias con el contrato Android existente (T15 / `supabase/README.md`) y pide justificacion o alineacion.

## 2. Resumen del contrato propuesto (estado actual)

| # | Metodo | Ruta | Direccion | Proposito declarado |
|---|--------|------|-----------|---------------------|
| 1 | POST | `/functions/v1/pairing` | agent -> server | Vincular PC con cuenta del padre |
| 2 | RPC | `get_device_policy(device_id)` | agent -> server | Descargar politica de control |
| 3 | POST | `/rest/v1/usage_logs` | agent -> server | Subir uso de apps |
| 4 | POST | `/rest/v1/device_alerts` | agent -> server | Subir alertas (servicio caido, manipulacion) |
| 5 | POST | `/rest/v1/behavioral_events` | agent -> server | Subir eventos de comportamiento |
| 6 | POST | `/rest/v1/time_requests` | agent -> server | Hijo pide tiempo extra |
| 7 | RPC | `heartbeat` | agent -> server | Salud del agente, offset de reloj |
| 8 | POST | `/rest/v1/integrity_reports` | agent -> server | Hash/firma del binario |
| 9 | WNS | push registration | agent -> server | Registrar canal de push |
| 10 | WS | `realtime` | server -> agent | Cambios de politica/grants mientras UI abierta |

## 3. Huecos criticos (bloqueantes)

### 3.1 Canal de comandos server -> agent (bloqueante)

**Problema.** Los 10 items del contrato son de subida o de suscripcion pasiva. No hay canal explicito para que el servidor envie comandos al agente cuando **no hay UI abierta**: lock inmediato, desinstalacion remota, revocacion de grants, push de politica cuando el agente esta en background.

**Contexto tecnico.** En Windows, los agentes en background se suspenden agresivamente (especialmente en laptops con Modern Standby / Connected Standby). La WS de Realtime **muere** cuando el proceso se suspende. WNS es el unico canal confiable para entregar mensajes con el proceso cerrado o en background, pero su contrato de payload hoy no esta especificado.

**Lo que pedimos.**

- **A.** Especificar el contrato de payload WNS: schema JSON, tipos de mensaje (`POLICY_UPDATED`, `LOCK_NOW`, `UNINSTALL`, `GRANT_REVOKED`, etc.), tamano maximo, TTL.
- **B.** Definir el comportamiento si WNS no entrega Y la WS no esta conectada. Propuesta: `GET /functions/v1/commands?device_id={id}&since={ts}&wait=30s` estilo long-poll, idempotente.
- **C.** Definir que pasa si el agente esta suspendido >24h y se despierta: como reconcilia.

### 3.2 Autenticacion y ciclo de vida del token post-pairing (bloqueante)

**Problema.** El endpoint de pairing devuelve `device_id` y `parent_id` (asumiendo paridad con T15), pero no esta claro:

- Devuelve un JWT? Cuanto vive?
- Hay refresh? Como se obtiene?
- Que pasa si el token se compromete o expira? El agente debe re-pairing?
- Las RPC y PostgREST usan el mismo token?

**Lo que pedimos.**

- **A.** Mostrar la respuesta completa de `/functions/v1/pairing` con todos los campos (access_token, refresh_token, expires_in, scope).
- **B.** Definir el endpoint o mecanismo de refresh (propuesta: `POST /functions/v1/refresh-token` con refresh_token).
- **C.** Definir el codigo de error y la semantica cuando el token expira (401 con `code: "token_expired"`, agente llama refresh una vez y reintenta; si falla, va a estado `UNPAIRED`).
- **D.** Confirmar que PostgREST (`/rest/v1/...`) y las RPCs (`get_device_policy`, `heartbeat`) validan el mismo Bearer JWT con la misma RLS.

### 3.3 Sincronizacion de tiempo (bloqueante para heartbeat)

**Problema.** El RPC `heartbeat` recibe `clock_offset_ms` desde el agente, pero no hay endpoint que el agente pueda usar para **medir** ese offset de forma confiable. Sin endpoint de tiempo, el offset es ficticio.

**Lo que pedimos.**

- **A.** Cualquier endpoint que devuelva un header `Date` estandar HTTP/1.1 (RFC 7231).
- **B.** Como refuerzo, agregar `server_time` en la respuesta de `heartbeat` (igual que T15 para Android).
- **C.** Definir la cadencia de recalculo del offset.

### 3.4 Grants y ACK de time_requests (bloqueante)

**Problema.** El hijo pide tiempo (`POST /rest/v1/time_requests`) pero no esta claro:

- Como recibe el agente la respuesta del padre (aprobado/rechazado)?
- Puede aprobarse sin Realtime conectado?
- Hay un fallback de polling?

**Lo que pedimos.**

- **A.** Confirmar que existe una tabla `grants` accesible via PostgREST para el agente, con filtros por `device_id` y `consumed_at IS NULL`.
- **B.** Definir un endpoint `GET /rest/v1/grants?device_id=eq.{id}&consumed_at=is.null` para polling de fallback.
- **C.** Especificar el flujo de ACK cuando el agente aplica un grant.

### 3.5 `usage_logs` vs `behavioral_events` (bloqueante, ambiguedad)

**Problema.** Las dos tablas se solapan en intencion. Sin definicion clara: duplicacion o perdida de eventos.

**Lo que pedimos.**

- **A.** Definir con ejemplo concreto que eventos van a cada tabla. Propuesta:
  - `usage_logs`: tuplas `(app, started_at, ended_at, foreground_seconds)` por sesion de app.
  - `behavioral_events`: eventos discretos con semantica de producto (`APP_BLOCKED`, `TIME_LIMIT_REACHED`, etc.).

### 3.6 Batching y tamano maximo (bloqueante para diseno de cola offline)

**Lo que pedimos.**

- **A.** `POST /rest/v1/usage_logs` acepta un array? O hay un endpoint batch?
- **B.** Tamano maximo del body (propuesta: 1 MB).
- **C.** Maximo de registros por request.
- **D.** Headers de idempotencia (`Idempotency-Key`).

### 3.7 Comportamiento offline (bloqueante)

**Lo que pedimos.**

- **A.** El agente debe encolar localmente y subir al reconectar? Hay limite de retencion (propuesta: 7 dias)?
- **B.** Hay deduplicacion server-side por algun `client_event_id`?
- **C.** Orden de subida al reconectar.
- **D.** Que pasa con eventos muy viejos?

### 3.8 Lifecycle del canal WNS (bloqueante)

**Problema.** El `channel URI` de WNS **expira** y Microsoft lanza `PushNotificationChannelClosedException`.

**Lo que pedimos.**

- **A.** Confirmar que el endpoint de registro es idempotente.
- **B.** Definir que metadata se guarda server-side por canal.
- **C.** Definir el flujo de rotacion cuando se detecta `ChannelClosed`.
- **D.** Si el padre desempareja, el backend invalida el canal WNS asociado?

### 3.9 Desvinculacion y baja (bloqueante)

**Problema.** No hay endpoint de "baja". Cuando el padre desvincula el dispositivo, queda huerfano.

**Lo que pedimos.**

- **A.** Endpoint `POST /functions/v1/unpair`.
- **B.** Hook de "ultimo latido" desde el agente.

## 4. Contrato de errores (transversal)

```json
// 4xx / 5xx
{
  "error": {
    "code": "INVALID_CODE",
    "message": "Pairing code invalid or expired",
    "details": { "expires_at": "..." }
  }
}
```

| HTTP | `code` ejemplo | Semantica del agente |
|------|----------------|----------------------|
| 400 | `INVALID_PAYLOAD` | No reintentar |
| 401 | `TOKEN_EXPIRED` | Refrescar y reintentar una vez |
| 401 | `TOKEN_INVALID` | No reintentar; ir a `UNPAIRED` |
| 403 | `DEVICE_NOT_OWNED` | No reintentar; log critico |
| 404 | `DEVICE_NOT_FOUND` | No reintentar; ir a `UNPAIRED` |
| 409 | `VERSION_CONFLICT` | Re-fetch policy |
| 410 | `CODE_EXPIRED` | No reintentar; pedir codigo nuevo |
| 429 | `RATE_LIMITED` | Respetar `Retry-After` |
| 500 | `INTERNAL` | Backoff exponencial, max 5 reintentos |
| 503 | `UNAVAILABLE` | Backoff exponencial, max 5 reintentos |

## 5. Versionado

- Header `X-API-Version: 1` en todas las respuestas.
- Path con prefijo de version para breaking changes.
- 30 dias de aviso para cambios incompatibles.

## 6. Comparativa con el contrato Android (T15)

| Concern | Android (T15) | Windows (propuesto) | Justificado? |
|---------|---------------|---------------------|--------------|
| Push | FCM | WNS | OK (especifico de plataforma) |
| Integridad | Play Integrity (token firmado) | `integrity_reports` (hash binario) | Pedir justificacion |
| Policy | Edge Function `get-policy` | RPC `get_device_policy` | Pedir justificacion |
| Heartbeat | Edge Function `heartbeat` | RPC `heartbeat` | OK |
| Pairing | Edge Function `pairing` | Edge Function `pairing` | OK |
| Grants | Edge Function `approve-request` | Polling `rest/v1/grants` | Pedir justificacion |

## 7. Artefactos entregables pedidos al backend

1. OpenAPI 3.1 para los 10 items + nuevos propuestos.
2. Ejemplo request/response para cada endpoint.
3. Esquema JSON del payload WNS.
4. Esquema de tabla `grants` y filtros.
5. Esquema de tablas `usage_logs`, `behavioral_events`, `device_alerts`, `time_requests`, `integrity_reports` con RLS.
6. Catalogo de errores.
7. Confirmacion de idempotencia en cada POST/PATCH.
8. Cobertura RLS con device JWT.

## 8. Decisiones pendientes

| # | Pregunta | Owner | Deadline |
|---|----------|-------|----------|
| 1 | Backend justifica divergencia con T15 o alinea? | Backend | Antes de implementar |
| 2 | WNS cubre el caso "agente suspendido >24h"? | Backend + Windows team | Antes de implementar |
| 3 | Long-poll fallback existe o solo WS+WNS? | Backend | Antes de implementar |
| 4 | Batching nativo o multiples POSTs? | Backend | Antes de disenar cola |
| 5 | `behavioral_events` se solapa con `usage_logs`? | Backend | Antes de tipar el agente |
| 6 | Endpoint de `unpair` | Backend | Antes de emparejar primer device |
| 7 | Catalogo de errores cerrado | Backend | Antes de implementar |

## 9. Riesgos si se implementa sin cerrar esto

- **Alta:** Canal de comandos incompleto -> politicas no se aplican cuando UI cerrada, grants no llegan, lock inmediato no funciona.
- **Alta:** `clock_offset_ms` sin endpoint de tiempo -> enforcement falla en relojes desincronizados.
- **Media:** `usage_logs` y `behavioral_events` solapadas -> metricas corruptas.
- **Media:** Sin contrato offline -> perdida de telemetria.
- **Baja:** Sin endpoint de unpair -> devices zombi.

---

**Estado:** Pendiente de revision por el equipo de backend.
**Proximo paso:** Una vez respondidos los puntos 1-7 de seccion 8, abrir spec formal del agente Windows.