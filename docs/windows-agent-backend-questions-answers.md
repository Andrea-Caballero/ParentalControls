# Respuesta al equipo de backend — Emparejamiento del agente desktop (Windows)

> Documento de respuesta a las 3 preguntas del equipo de backend sobre el flujo de emparejamiento del agente desktop Windows. Enfocado en qué necesita construir el backend para que el agente funcione de punta a punta.

## 1. URL base del backend

```
https://fbuiwtzybalatpeakdiw.supabase.co
```

- Edge Functions: `https://fbuiwtzybalatpeakdiw.supabase.co/functions/v1/...`
- PostgREST: `https://fbuiwtzybalatpeakdiw.supabase.co/rest/v1/...`

> **A confirmar antes de producción:** ¿este project ID corresponde también a staging/prod o son proyectos separados? ¿Qué service-role key va con cada ambiente? Hoy el agente usa esta URL en dev.

---

## 2. Estado de las Edge Functions de emparejamiento

**Ambas funciones existen** y son las que tiene que consumir el agente desktop. No hay que crear nada nuevo en este flujo, pero sí hay que cerrar bugs.

### 2.1 `POST /functions/v1/create-pairing-code` — el tutor genera el código

**Auth:** Bearer `<parent_jwt>`

**Request:**

```json
{ "device_name": "PC-Hijo", "age_band": "7-12", "ttl_minutes": 10 }
```

> **`age_band`** (string): uno de `0-6`, `7-12`, `13-17`. Default `7-12` si el padre no lo especifica. Hoy se valida solo contra las plantillas existentes en `policy_templates` (migration `003_policy_templates.sql`); un valor fuera de esos tres devuelve warning en logs y no aplica plantilla. Recomendacion para hardenizar: crear un enum `age_band_enum` o CHECK constraint en una migration nueva (propuesta en el issue paralelo, ver `windows-agent-contract-requirements.md`).

**Response (200):**

```json
{
  "success": true,
  "code": "ABCD2345",
  "expires_at": "2026-07-17T15:00:00Z",
  "qr_data": "<base64({code, parent, exp})>",
  "deeplink": "parentalcontrol://pair?code=ABCD2345"
}
```

**Quién la llama:** la app/panel del tutor. Hoy la llama la app Android del padre desde el botón "Generar código" del dashboard. **El agente desktop NO la llama** — el tutor la llama cuando quiere vincular un dispositivo.

**Para soportar al agente desktop:** confirmar que existe o se va a crear una UI en la app del tutor (Android o panel web) que invoque este endpoint y muestre el código + QR para que el hijo lo ingrese desde el desktop. Si no hay panel web todavía, ese es un gap a resolver del lado del front, no del backend.

### 2.2 `POST /functions/v1/pairing` — el agente desktop reclama el código

**Auth:** ninguna (es el primer contacto del agente).

**Request:**

```json
{
  "code": "ABCD2345",
  "device_name": "PC-Hijo",
  "device_model": "Dell XPS 15",
  "os_version": "Windows 11 23H2",
  "app_version": "1.0.0",
  "age_band": "7-12",
  "child_first_name": "Juan"
}
```

> `child_first_name` es **obligatorio** (1–32 chars, trimmed). El padre lo captura en su UI antes de generar el código y queda persistido en `pairing_codes.child_first_name`. Sin esto el agente recibe 400.
>
> `age_band` es **opcional** en el body del agente pero igual de importante: si no se manda, el backend hace fallback al valor guardado en `pairing_codes.device_name?.split("-")[0]` o al default `7-12`. Valores validos: `0-6`, `7-12`, `13-17`. El campo esta como `TEXT` en la DB (sin enum ni CHECK), asi que cualquier string pasa la Edge Function pero la busqueda de plantilla puede no devolver nada.

**Response (200):**

```json
{ "success": true, "device_id": "uuid", "parent_id": "uuid", "policy_version": 1 }
```

**Errores:**

- `400` — campos faltantes o `child_first_name` inválido
- `404` — código inválido o ya consumido
- `410` — código expirado
- `500` — error interno

**Lo que hace internamente (resumen para que el equipo valide la implementación):**

1. Lee `pairing_codes` con `status='ACTIVE'` y `expires_at > now()`
2. Crea o reutiliza un usuario anónimo en `auth.users` con email `device_<hash>@parentalcontrol.local`
3. Resuelve o crea fila en `children` ligada a `parent_id` + `child_first_name` (idempotente)
4. Inserta fila en `devices` con `device_state='ACTIVE'`, `policy_version=1`, `child_id` linkeado
5. Escribe `device_id` en `app_metadata` del usuario agente
6. Aplica plantilla de política según `age_band` (inserts en `schedules` y `app_policies`)
7. Marca `used_at` en el código
8. **Debería** enviar push al tutor (FCM hoy) — **actualmente NO lo hace**

### 2.3 Tabla `pairing_codes`

```sql
CREATE TABLE pairing_codes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code TEXT NOT NULL UNIQUE,
    parent_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    device_name TEXT,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
    status pairing_status NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | EXPIRED | CONSUMED
    used_at TIMESTAMPTZ,
    child_first_name TEXT,  -- agregado por feat-multi-child-picker
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Índices por `code`, `expires_at`, `status`, `parent_id`. Función `cleanup_expired_pairing_codes()` definida pero el `cron.schedule` está como SQL comentada — **verificar si está activada**.

### 2.4 Bugs que bloquean la prueba end-to-end con desktop

El equipo de backend tiene que resolver estos 4 antes de que el emparejamiento desktop funcione de punta a punta:

1. **`create-pairing-code` decodifica el JWT sin validar firma.** Reemplazar el `atob(token.split(".")[1])` por `supabase.auth.getUser(token)` o el middleware estándar. Hoy cualquiera con un token偽造造ado puede generar códigos bajo cualquier `parent_id`.

2. **Códigos usados no transicionan a `CONSUMED`.** Después de un pairing exitoso, `status` sigue en `ACTIVE` (solo se setea `used_at`). El agente desktop puede re-intentar con el mismo código antes de que expire el TTL y volver a entrar al flujo. En el paso 7 de `pairing/index.ts`, cambiar `status: "ACTIVE"` por `status: "CONSUMED"`.

3. **Push al tutor NO se está enviando.** Las líneas de FCM en `pairing/index.ts` están comentadas con un TODO. Si la prueba end-to-end con desktop espera que el padre reciba una notificación push cuando el hijo se empareja, **no va a pasar**.

4. **Cron de cleanup comentado.** Si no se activa el `cron.schedule('cleanup-pairing-codes', '*/15 * * * *', ...)` en el dashboard de Supabase, los códigos expirados se acumulan indefinidamente.

---

## 3. Contrato de `get_device_policy`

**El archivo `docs/windows-agent-contract-requirements.md` SÍ existe** (se había perdido por un problema de sesión, ya está restaurado). La sección 3.3 de ese documento lista lo que el agente desktop espera del endpoint. Pero como el equipo de backend necesita definir la implementación, lo concreto es:

**Hoy el contrato de política vive como Edge Function para Android** (`/functions/v1/get-policy`) y devuelve este shape:

```json
{
  "device_id": "...",
  "version": 5,
  "device_state": "ACTIVE",
  "daily_screen_time_minutes": 120,
  "schedules": [...],
  "category_limits": [...],
  "app_policies": [...],
  "category_assignments": { },
  "grants": [...],
  "device_name": "...",
  "app_version": "1.0.0",
  "last_sync_at": "...",
  "fetched_at": "..."
}
```

**Para implementar `get_device_policy(device_id)` como RPC para desktop, el equipo tiene que decidir:**

- **A.** ¿Devuelve **exactamente el mismo shape** que `get-policy` de Android? **Recomendación: sí**, así el agente desktop puede reusar los DTOs/parsers del lado Android como referencia. Si después divergen, lo hacen en v2.
- **B.** ¿Auth igual que Android — Bearer device JWT — con la misma RLS sobre `devices.id`? **Recomendación: sí.**
- **C.** Documentar explícitamente los enums:
  - `device_state`: `ACTIVE` / `LOCKED` / `UNPAIRED` / ...
  - `app_policies[].state`: `BLOCKED` / `ALLOWED` / `TIME_LIMITED` / ...
  - `schedules[].action`: `LOCK` / `ALLOW_ONLY`
  - Días de la semana: `MONDAY` .. `SUNDAY`
- **D.** Definir comportamiento cuando `policy_version` local es menor que el del servidor: ¿la RPC devuelve snapshot completo y bumpea la versión? ¿O un delta? **Recomendación: snapshot completo + nuevo `version`.**
- **E.** `grants` en el response: ¿vienen grants activos (no expirados, no consumidos)? ¿Con `expires_at` y `remaining_minutes`?

**Mientras eso se cierra, propuesta mínima viable:** que la RPC devuelva idéntico shape al de `get-policy` Android. Eso destraba la integración del agente desktop inmediatamente.

---

## Siguiente paso del backend team, en orden

1. Resolver bugs #1–#4 de la sección 2. Sin #2, el emparejamiento es re-ejecutable. Sin #3, el push no llega. Sin #1, hay agujero de seguridad.
2. Confirmar/decidir los puntos A–E de la sección 3 para definir el shape de `get_device_policy`.
3. Definir el schema del payload WNS (lo pide el documento `windows-agent-contract-requirements.md` sección 3.1, pero aplica a Windows y por tanto también al backend).

---

**Documentos relacionados:**

- `docs/windows-agent-contract-requirements.md` — requisitos detallados del contrato del agente desktop, huegos críticos, riesgos.
- `docs/windows-agent-shared-apis.md` — superficie API compartida entre agente desktop y backend.