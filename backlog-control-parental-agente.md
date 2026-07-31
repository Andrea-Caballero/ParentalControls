---
title: "Backlog ejecutable por agente — App de Control Parental (Agente Android + Backend)"
description: "Backlog autocontenido y optimizado para ejecución por un agente de IA. Cada tarea es una unidad ejecutable con formato fijo: Hacer · Depende de · Contratos · Implementar · Restricciones · Probar · Done. Los contratos canónicos (política JSON, motor, modelo de datos, cumplimiento Play, compatibilidad, stack obligatorio) están inlineados en §0."
platform: "Android (agente, Kotlin) + Supabase (backend)"
language: "Kotlin"
self_contained: true
---

# Backlog ejecutable — Construir la app completa

## Cómo ejecutar este backlog

- **Construyes** un **agente Android** (app en el teléfono del menor) que aplica políticas de control parental **localmente, incluso sin red**, y el **contrato de backend** (Supabase: esquema, RLS, funciones) del que depende. La UI del panel del padre queda fuera de alcance; donde una feature la necesita, aquí se define el contrato que el padre debe consumir.
- **Punto de partida:** el proyecto Android vacío ya existe (módulo `app`, clase `Application` con Hilt, Gradle Kotlin DSL). Tú especificas todo lo demás.
- **§0 contiene los contratos canónicos.** Cuando una tarea dice "según §0.x", ese contrato está inlineado en este archivo. No hay documentos externos.
- **Orden:** ejecuta en el orden de §3. Respeta `Depende de` de cada tarea.

### Formato de cada tarea (fijo)

```
## Tnn — <título>
**Hacer:** qué entregar (1 frase).
**Depende de:** Tnn… (o —).
**Contratos:** §0.x que aplican.
**Implementar:** pasos concretos en orden.
**Restricciones:** reglas duras de esta tarea (usar X, nunca Y).
**Probar:** casos que deben pasar.
**Done:** checklist verificable. Incluye siempre `☐ §0.9` (build + tests + lint verdes).
```

### Reglas globales (aplican a TODA tarea)

- **No marques una tarea como hecha** sin completar la verificación de §0.9 (build, tests y linters en verde). Si un paso no aplica, decláralo.
- **Calidad de código:** cumple la barra de §0.9 (capas separadas, inmutabilidad por defecto, errores explícitos, Coroutines/Flow, DI con Hilt).
- **Stack obligatorio:** usa exactamente lo de §0.8. Cada tarea repite las reglas que le aplican.
- **Compatibilidad:** todo lo sensible a versión va detrás de una interfaz con implementación por nivel de API (§0.7). Probar en API 28, 31 y 35.
- **Privacidad:** recolecta solo uso por app. Nunca contenido, texto, mensajes ni capturas (§0.6).

---

# §0 — Contratos canónicos

## §0.1 — Arquitectura (qué es y cómo fluye)

App nativa Kotlin que actúa como **agente** en el teléfono del menor. Capacidades: permitir/prohibir apps; límites de tiempo (global, por app, por categoría); horarios y *downtime*; bloqueo total; "tiempo extra" y recompensas; monitoreo casi en vivo; anti-manipulación proporcional; UI mínima no configurable por el menor.

**Modelo:** el padre **compone reglas** (apps + tiempo + horario + bloqueo); un **motor con precedencia determinista** (§0.4) las resuelve. No hay "modos" cerrados.

**Offline-first (invariantes que el agente debe garantizar):**
- El enforcement **nunca** depende de la red. El motor opera sobre la caché local (Room). Sin red, sigue aplicando la última política.
- **FCM de alta prioridad es señal, no dato.** El push solo dice "sincroniza ahora"; el agente hace un `GET` autenticado y nunca confía en el payload del push.
- **Realtime de Supabase solo en primer plano** (refresco de UI). Nunca como canal de control en background.
- **Versionado:** una política con `version = N` se aplica **solo si** `N > version local`.

```
Panel del padre ─► Backend/Supabase (escribe policy v+1)
                          ├─► FCM high-priority ─► Agente: "sync ahora"
Agente ◄─ REST GET policy ┘  (trae v+1 → Room → el motor reevalúa)
Agente ─ REST POST ─► usage_logs, heartbeat, alerts, eventos  (WorkManager con reintentos)
Agente (solo foreground) ─ Realtime ─► refresco instantáneo de UI
```

## §0.2 — Niveles de enforcement (el agente detecta el suyo en runtime)

El **mismo motor y el mismo modelo de datos** operan en dos niveles. El agente **degrada** acciones según el nivel disponible y reporta el nivel efectivo al backend. `enforcementLevel ∈ {DEVICE_OWNER, STANDARD, DEGRADED}`.

| Capacidad | **STANDARD** (app normal) | **DEVICE_OWNER** (equipo aprovisionado) |
|-----------|---------------------------|------------------------------------------|
| Bloquear app | Soft: overlay + volver al home | Hard: `setPackagesSuspended` / `setApplicationHidden` |
| Bloqueo total | `lockNow()` + overlay persistente | `lockNow()` + Lock Task/Kiosk |
| Impedir desinstalación | Limitado (overlay + alerta) | `setUninstallBlocked(true)` |
| Resistir force-stop / Safe Mode / FRP | No garantizado | Sí |

**STANDARD es el modo por defecto.** DEVICE_OWNER es "modo reforzado", ofrecido al configurar un teléfono nuevo/dedicado. No existe API pública tipo "Family Link" para terceros.

**DEGRADED** se activa si falta un permiso clave. En DEGRADED el agente **alerta y guía la reparación; nunca finge que protege.** Estados a manejar:

| Situación | Detección | Respuesta |
|-----------|-----------|-----------|
| Accesibilidad desactivada | El servicio deja de recibir eventos / flag del sistema | DEGRADED + alerta + overlay "reparar" |
| Overlay revocado | `Settings.canDrawOverlays()` falso | No puede bloquear soft → alerta + guía |
| Exención de batería retirada | `isIgnoringBatteryOptimizations` falso | Reabrir diálogo; marcar riesgo |
| Sin red prolongada | Heartbeat fallido N veces | Aplicar última política cacheada; alertar inactividad |
| Reloj manipulado | `SystemClock.elapsedRealtime` vs hora de servidor | Recalcular ventanas con hora de servidor; marcar sospecha |
| Zona horaria cambiada | El `ZoneId` cambia entre evaluaciones | Reevaluar `schedules`/`allowed_windows`; alertar `timezone_changed` |

## §0.3 — Contrato JSON de política (el agente lo cachea en Room)

El agente aplica una política recibida **solo si** su `version` es mayor que la local.

```json
{
  "device_id": "uuid",
  "version": 42,
  "device_state": "active",
  "daily_screen_time_minutes": 240,
  "schedules": [
    { "id": "bedtime", "days": ["MON","TUE","WED","THU","SUN"],
      "from": "22:00", "to": "07:00", "action": "lock" },
    { "id": "homework", "days": ["MON","TUE","WED","THU","FRI"],
      "from": "16:00", "to": "18:00", "action": "allow_only",
      "allow_list": ["com.google.android.apps.classroom"] }
  ],
  "category_limits": [{ "category": "games", "minutes": 60 }],
  "app_policies": [
    { "package_name": "com.whatsapp", "state": "always_allowed" },
    { "package_name": "com.instagram.android", "state": "limited",
      "daily_limit_minutes": 30, "category": "social",
      "allowed_windows": [
        { "days": ["MON","TUE","WED","THU","FRI"], "from": "15:00", "to": "20:00" }
      ] },
    { "package_name": "com.supercell.clashroyale", "state": "blocked", "category": "games" }
  ],
  "category_assignments": { "com.instagram.android": "social", "com.supercell.clashroyale": "games" },
  "grants": [
    { "id": "g1", "request_id": "tr_77", "scope": "device", "minutes": 30,
      "granted_at": "2026-05-25T20:30:00Z", "expires_at": "2026-05-25T21:00:00Z",
      "source": "extra_time" }
  ]
}
```

Semántica exacta (vinculante para el motor §0.4 y los tipos de T01):

- **`device_state` ∈ `active | locked | downtime`.**
  - `active`: el motor decide por reglas.
  - `locked`: **bloqueo total duro** (paso 2 de §0.4). Solo lo elude el paso 1 (sistema crítico + app del agente). Ni `always_allowed` ni los grants lo levantan.
  - `downtime`: **bloqueo global blando** (paso 7 de §0.4). Lo eluden el paso 1, un grant que cubra el scope (paso 6) y las apps `always_allowed`. Es el "a dormir ya" de un toque, sin crear un `schedule`.
- **`app_policy.state` ∈ `allowed | blocked | limited | always_allowed`.** `always_allowed` ignora los pasos 7–11 de §0.4 (downtime, downtime programado, límites de app/categoría/global). No elude `locked` (paso 2). `limited` exige `daily_limit_minutes`.
- **`app_policy.allowed_windows`** (opcional): lista de `{ days, from, to }` (`HH:mm`, días `MON…SUN`). Si está presente y **no vacía**, la app solo se permite **dentro** de alguna ventana (paso 5). `from > to` = cruza medianoche.
- **`schedule.action` ∈ `lock | allow_only`** (lista blanca `allow_list` durante la ventana).
- **`category_assignments`**: mapa `package_name → category`. Resuelve a qué categoría pertenece cada app para los `category_limits` (paso 10). El agente no deduce la categoría offline; el backend la inyecta. App sin entrada aquí ⇒ no cuenta para ningún límite de categoría.
- **`grant`**: concesión temporal. `scope ∈ device | <package_name> | <category>`; `source ∈ extra_time | reward | manual`. Es una **ventana de pared** (`granted_at → expires_at`, con `expires_at = granted_at + minutes`), **no** un saldo de minutos consumibles: si el menor no usa el dispositivo, la ventana corre igual. `request_id` enlaza con su `time_requests` para idempotencia (1 request aprobado ⇒ 1 grant). `granted_at`/`expires_at` se evalúan con **hora de servidor**, nunca con la hora local cruda.

## §0.4 — Algoritmo de precedencia del motor (núcleo)

Para cada cambio de app en primer plano, evaluar en este orden exacto; **la primera coincidencia decide**. El orden separa **prohibiciones duras** (ningún grant las levanta) de **bloqueos por tiempo/presupuesto** (un grant que cubra el scope sí los levanta):

```
 1. ¿App del agente o app de sistema crítica?                     → PERMITIR (nunca bloquear marcador/emergencias/ajustes del agente)
 2. ¿device_state == locked?                                      → BLOQUEAR (total; ni always_allowed ni grants lo levantan)
 3. ¿app_policy.state == blocked?                                 → BLOQUEAR (dura)
 4. ¿Schedule 'allow_only' activo y la app NO está en allow_list? → BLOQUEAR (dura)
 5. ¿La app define allowed_windows y AHORA está fuera de todas?   → BLOQUEAR (dura)
 6. ¿Grant vigente que cubre el scope (device | <pkg> | <cat>)?   → PERMITIR (levanta los pasos 7–11)
 7. ¿device_state == downtime y la app NO es always_allowed?      → BLOQUEAR
 8. ¿Dentro de un schedule action 'lock' y NO always_allowed?     → BLOQUEAR
 9. ¿Excedió daily_limit_minutes de la app y NO always_allowed?   → BLOQUEAR
10. ¿Excedió el límite de su categoría y NO always_allowed?       → BLOQUEAR
11. ¿Excedió daily_screen_time global (app no exenta)?            → BLOQUEAR
12. En cualquier otro caso                                        → PERMITIR
```

Reglas normativas (vinculantes para T02):

- **Grant (paso 6):** cubre la app si `scope == device`, `scope == packageName`, o `scope == category_assignments[packageName]`. Vigente ⇔ `granted_at ≤ now_servidor < expires_at`. **Solo levanta 7–11; nunca 2–5.** (Un "tiempo extra" no desbloquea apps `blocked` ni la franja `allow_only`.)
- **`always_allowed`:** ignora 7–11. No ignora el paso 2 (`locked`).
- **Categoría (paso 10):** consumo de categoría = suma del uso de hoy de los paquetes con `category_assignments[pkg] == cat`.
- **Motivo legible:** cada BLOQUEAR devuelve un motivo para el overlay (tomado del copy de T25). Ej.: paso 2 "El dispositivo está bloqueado"; 7/8 "Es hora de dormir"; 9 "Se acabó el tiempo de esta app"; 10 "Se acabó el tiempo de juegos"; 11 "Se acabó el tiempo de hoy".
- **Determinismo:** la hora entra por parámetro; sin reloj global ni estado oculto.

**Bordes obligatorios (suite de T02):** cruce de medianoche en `schedule`/`allowed_windows`/`grant`; cambio de día (reseteo de `usage_today` por fecha de servidor); grant vencido al filo; zona horaria y cambio de zona; `always_allowed` que ignora 7–11; grant `device` que levanta el global pero no desbloquea una app `blocked`.

## §0.5 — Modelo de datos (Supabase / Postgres)

```sql
devices (
  id            uuid primary key default gen_random_uuid(),
  parent_id     uuid references auth.users(id),
  display_name  text,
  age_band      text,           -- '6-9' | '10-12' | '13-15' | '16+'
  enforcement   text check (enforcement in ('device_owner','standard','degraded')),
  last_seen_at  timestamptz,
  created_at    timestamptz default now()
);

device_policies (                -- política versionada; una fila vigente por dispositivo
  device_id     uuid references devices(id),
  version       bigint not null,
  device_state  text default 'active',
  daily_screen_time_minutes int,
  schedules     jsonb default '[]',
  category_limits jsonb default '[]',
  updated_at    timestamptz default now(),
  primary key (device_id, version)
);

app_policies (
  id            uuid primary key default gen_random_uuid(),
  device_id     uuid references devices(id),
  package_name  text not null,
  state         text check (state in ('allowed','blocked','limited','always_allowed')),
  daily_limit_minutes int,
  allowed_windows jsonb default '[]',
  category      text,
  unique (device_id, package_name)
);

grants (
  id          uuid primary key default gen_random_uuid(),
  device_id   uuid references devices(id),
  request_id  uuid references time_requests(id),  -- enlaza con la solicitud de origen
  scope       text,             -- 'device' | <package_name> | <category>
  minutes     int,
  source      text check (source in ('extra_time','reward','manual')),
  granted_at  timestamptz default now(),
  expires_at  timestamptz,      -- = granted_at + minutes
  unique (request_id)           -- idempotencia: 1 request aprobado ⇒ ≤1 grant
);

usage_logs (
  id           bigserial primary key,
  device_id    uuid references devices(id),
  package_name text,
  minutes      int,
  bucket_date  date,
  created_at   timestamptz default now()
);

device_alerts (
  id          bigserial primary key,
  device_id   uuid references devices(id),
  type        text,             -- 'disconnected' | 'accessibility_off' | 'uninstall_attempt' | ...
  payload     jsonb,
  created_at  timestamptz default now()
);

time_requests (                  -- solicitudes de "tiempo extra" del menor
  id            uuid primary key default gen_random_uuid(),
  device_id     uuid references devices(id),
  scope         text,
  minutes       int,
  reason        text,
  status        text check (status in ('pending','approved','denied')) default 'pending',
  resolved_minutes int,
  created_at    timestamptz default now(),
  resolved_at   timestamptz
);

behavioral_events (              -- instrumentación de conducta (sin contenido del menor)
  id            bigserial primary key,
  device_id     uuid references devices(id),
  name          text,
  props         jsonb,
  event_version int default 1,
  client_ts     timestamptz,
  created_at    timestamptz default now()
);

policy_templates (               -- semillas de política inicial por edad
  id            uuid primary key default gen_random_uuid(),
  age_band      text,
  payload       jsonb            -- estructura de §0.3
);

device_push_tokens (             -- token FCM por dispositivo (rotable)
  device_id     uuid references devices(id) primary key,
  fcm_token     text not null,
  platform      text default 'android',
  updated_at    timestamptz default now()
);

device_heartbeats (              -- último latido (detección de desconexión/manipulación)
  device_id       uuid references devices(id) primary key,
  beat_at         timestamptz default now(),
  enforcement     text,
  battery_pct     int,
  clock_offset_ms bigint
);

pairing_codes (                  -- emparejamiento de un solo uso con TTL
  code          text primary key,            -- corto, alta entropía
  parent_id     uuid references auth.users(id),
  device_id     uuid references devices(id),
  age_band      text,
  expires_at    timestamptz not null,        -- TTL corto (~10 min)
  consumed_at   timestamptz,                 -- NULL = no usado
  created_at    timestamptz default now()
);
```

**Auth del dispositivo (cómo el JWT porta `device_id`).** El agente abre una **sesión anónima** de Supabase Auth. El emparejamiento (T15) escribe `device_id` en `auth.users.app_metadata`. Un **Custom Access Token Hook** (`custom_access_token_hook(event jsonb)`) copia ese valor a un claim de primer nivel `device_id` en cada emisión/refresh. (Plan B: una Edge Function firma un JWT propio con el `jwt_secret` y se pasa por la opción `accessToken` del cliente.)

**RLS (predicados exactos).** Toda tabla con `device_id`:
- Agente: `((auth.jwt() ->> 'device_id')::uuid = device_id)` en `USING` (lectura) y `WITH CHECK` (inserción). No lee ni inserta filas de otro dispositivo.
- Padre: `EXISTS (select 1 from devices d where d.id = <tabla>.device_id and d.parent_id = auth.uid())`.
- `pairing_codes`: el padre ve/crea solo los suyos (`parent_id = auth.uid()`); el consumo lo hace una Edge Function con `service_role`.
- El cliente usa **publishable key** (`sb_publishable_…`). La `service_role` nunca sale del backend.

**Versión monotónica única.** El agente compara una sola `version` (la de `device_policies`), pero la política efectiva se compone de varias tablas. Por tanto **cualquier** cambio (editar `app_policies`, crear/expirar `grants`, cambiar `device_state`/`schedules`) **debe** subir `device_policies.version`. Implementar `bump_policy_version(device_id)` + triggers `AFTER INSERT/UPDATE/DELETE` en `app_policies` y `grants`, e invocarla desde las Edge Functions que tocan la política.

**Política ensamblada (lo que el agente hace `GET`).** Vista/RPC `get_device_policy(device_id)` que devuelve el JSON exacto de §0.3 uniendo: fila vigente de `device_policies` + `app_policies` (con `allowed_windows`, derivando `category_assignments`) + `grants` con `expires_at > now()`. El agente nunca arma el JSON desde tablas sueltas.

**Migraciones y retención.** `grants.request_id` referencia `time_requests`: crear `time_requests` antes de esa FK (o añadirla en migración posterior). Job (pg_cron) que purga `usage_logs`/`device_heartbeats` antiguos y `grants` vencidos.

## §0.6 — Cumplimiento de Google Play (checklist obligatorio)

El control parental está permitido (incluso impedir desinstalación) con requisitos estrictos. Incumplirlos = retiro de la app.

**AccessibilityService**
- ☐ `isAccessibilityTool="false"`.
- ☐ **Permission Declaration Form** completado en Play Console.
- ☐ **Divulgación prominente in-app** que cumpla TODO: está dentro de la app (no solo en Play/web); se muestra en uso normal sin navegar menús; describe qué datos se acceden vía accesibilidad y cómo se usan/comparten; requiere **acción afirmativa** (tap/checkbox); separada de la política de privacidad.
- ☐ **Video** del flujo y la divulgación.
- ☐ No usar accesibilidad más allá del control parental declarado.

**VpnService (si se implementa T35):** declararlo en la ficha; cifrar todo; no recolectar datos sensibles sin consentimiento; no redirigir tráfico para monetizar.

**General**
- ☐ Política de privacidad + sección **Data Safety** describiendo monitoreo y datos tratados; cumplir políticas de datos de menores/Familias.
- ☐ `foregroundServiceType="specialUse"` justificado en Play.
- ☐ Canal de distribución alterno documentado para el modo Device Owner (riesgo residual: incluso apps que cumplen han sido retiradas).
- ☐ **Minimización:** recolectar solo uso por app. Nunca contenido, texto, mensajes ni capturas. Retención limitada de `usage_logs`.

## §0.7 — Compatibilidad Android (probar en API 28, 31, 35)

| Tema | A tener en cuenta |
|------|-------------------|
| `lockNow` (Device Admin consumo) | OK en todas; usar solo APIs no deprecadas |
| Device Owner | OK; provisión OOBE (QR/NFC/zero-touch) |
| `AccessibilityService` | API 31+: declaración en consola. API 34+: revisión reforzada |
| Overlay `TYPE_APPLICATION_OVERLAY` | OK; cuidado con full-screen intent en 34+ |
| Foreground service | API 31+: restricción de inicio en background. API 34+: `foregroundServiceType` obligatorio |
| `POST_NOTIFICATIONS` | Requerido runtime en API 33+ |
| `UsageStatsManager.queryEvents` | OK en todas |

**Regla:** cada llamada sensible a versión va detrás de una interfaz (ej. `Enforcer`) con implementación por nivel de API.

## §0.8 — Stack obligatorio (usar exactamente esto)

| Tema | USA | NUNCA |
|------|-----|-------|
| Secretos cifrados en disco | DataStore (Preferences) + Google Tink (AEAD), clave maestra en Android Keystore | `androidx.security:security-crypto` / `EncryptedSharedPreferences` |
| Anotaciones | KSP | kapt |
| Compilador Compose | Plugin `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.x) | fijar `kotlinCompilerExtensionVersion` a mano |
| Versiones | Gradle Version Catalog (`libs.versions.toml`), última estable | versiones hardcodeadas |
| Supabase | `supabase-kt` 3.x por BOM; `auth-kt`, `postgrest-kt`, `realtime-kt`; motor `ktor-client-okhttp` | módulos pre-3.0 (`gotrue-kt`) |
| Claves Supabase | publishable key (`sb_publishable_…`) + JWT de dispositivo con claim `device_id` | `anon`/`service_role` en el cliente |
| Target Play | `compileSdk 35` / `targetSdk 35` (o el nivel vigente que Play exija); `minSdk 26` | `targetSdk` por debajo del exigido por Play |
| FGS del contador | `foregroundServiceType="specialUse"` + propiedad + justificación | otros tipos de FGS |
| Avisos de tiempo | cálculo in-process en el servicio (Coroutines/Flow) | exact alarms (`SCHEDULE_EXACT_ALARM`) |
| Device Admin consumo | solo APIs no deprecadas (`lockNow`); políticas fuertes solo bajo Device Owner | políticas enterprise en modo consumo |
| Mock de red en pruebas | Ktor `MockEngine` | OkHttp `MockWebServer` para Supabase |
| Concurrencia | Coroutines + Flow; estado como `StateFlow` | `AsyncTask`, callbacks crudos, `GlobalScope` |

## §0.9 — Calidad y verificación (DoD universal)

Toda tarea cumple esto además de su Done específico. La marca `☐ §0.9` remite aquí.

**Barra de código**
- Nombres significativos (sin `tmp`/`aux`); funciones cortas, una responsabilidad; sin números mágicos (constantes nombradas).
- Inmutabilidad por defecto (`val`, `data class`, `copy()`); `var` solo con razón.
- Errores explícitos: `Result<T>` o `sealed class` para casos esperados; excepciones solo para lo excepcional. Prohibido `catch {}` vacío.
- Nullability explícita: prohibido `!!` salvo justificación en comentario.
- **Capas separadas:** el dominio (T01, T02, T04) es JVM puro, sin imports de `android.*`.
- DI con Hilt; `Dispatchers` inyectados. Coroutines con scope apropiado; nunca `GlobalScope`; nunca bloquear el hilo principal.
- Compose: composables puros; side effects en `LaunchedEffect`/`DisposableEffect`; estado elevado al ViewModel; `@Preview` en pantallas principales.
- Sin código muerto. Cada `TODO` con id de issue. Sin secretos en el repo (`google-services.json` real, claves, certificados fuera de control de versiones).
- Comentarios para el porqué. El motor (T02) referencia §0.4 y marca cada paso de precedencia en el código.

**Herramientas estáticas (configuradas en T00, ejecutadas en cada tarea)**
- detekt (`detekt.yml` estricto; 0 nuevos warnings en código nuevo), ktlint, `.editorconfig`.
- R8 en `release`: no romper reflexión de Tink, Ktor, kotlinx-serialization, Room ni Hilt; añadir reglas Proguard cuando una librería las exija.

**Pruebas significativas**
- Probar comportamiento observable, no detalles de implementación. Una prueba que no fallaría con un cambio realista, se elimina.
- T01/T02/T04 (JVM puro): test-first; cubrir **bordes**, no líneas.
- Compose: `ComposeTestRule` para flujos reales. Sync/red: `Ktor MockEngine` con 500/timeout/JSON malformado, idempotencia y versionado.

**Verificación pre-finalización (ejecutar y confirmar VERDE antes de marcar hecho)**

| # | Comando / chequeo | Cuándo |
|---|-------------------|--------|
| 1 | `./gradlew :app:assembleDebug` | Toda tarea con código de app |
| 2 | `./gradlew testDebugUnitTest` | Toda tarea con unit tests |
| 3 | `./gradlew connectedDebugAndroidTest` | Tareas con pruebas instrumentadas |
| 4 | `./gradlew detekt` | Toda tarea de código |
| 5 | `./gradlew ktlintCheck` | Toda tarea de código |
| 6 | `./gradlew :app:assembleRelease` (R8) | Cierre de milestone y al tocar Tink/Ktor/serialization/Room/Hilt |
| 7 | Migraciones SQL aplican limpio contra base vacía y con datos | T14, T15 |
| 8 | 0 secretos en el diff | Toda tarea |
| 9 | Eventos/logs nuevos NO contienen contenido del menor | T13, T32 |

Si un paso falla, la tarea **no** está hecha. Si no aplica, decláralo. Los comandos 1–5 corren en CI por cada PR (formalizado en T36); un PR que rompe el pipeline se revierte.

---

# §1 — Tareas

## T00 — Dependencias, tooling y build

**Hacer:** dejar el proyecto compilable y con tooling listo para todas las tareas.
**Depende de:** —
**Contratos:** §0.8, §0.9.

**Implementar**
1. Catálogo `libs.versions.toml` (última estable de cada cosa) con:
   - Plugins: `kotlin-android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`, `com.google.dagger.hilt.android`, `com.google.gms.google-services`, `io.gitlab.arturbosch.detekt`, `org.jlleitschuh.gradle.ktlint`.
   - UI: `core-ktx`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`, `activity-compose`, Compose BOM (`material3`, `ui`, `ui-tooling-preview`, `ui-tooling` en debug), `navigation-compose`.
   - Datos: Room (`room-runtime`, `room-ktx`, `room-compiler` vía KSP), DataStore (`datastore-preferences`), WorkManager (`work-runtime-ktx`).
   - DI: Hilt (`hilt-android`, compilador vía KSP, `androidx.hilt:hilt-work`).
   - Cripto: Google Tink (`tink-android`).
   - Red: supabase-kt BOM + `auth-kt`, `postgrest-kt`, `realtime-kt`; `io.ktor:ktor-client-okhttp`; `kotlinx-serialization-json`.
   - Firebase: Firebase BoM + `firebase-messaging`; Play Integrity (`com.google.android.play:integrity`).
   - Escaneo (T24): CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) + ML Kit (`com.google.mlkit:barcode-scanning`).
   - Pruebas: JUnit, Turbine, MockK, Robolectric, `androidx.test:core/runner/rules`, espresso, Compose UI Test (`ui-test-junit4`, `ui-test-manifest` debug), `room-testing`, `androidx.work:work-testing`, `hilt-android-testing` (compilador test vía KSP), `ktor-client-mock`.
2. Build: `compileSdk=35`, `targetSdk=35`, `minSdk=26`, Java 17, `buildFeatures { compose = true }`. Habilitar **core library desugaring** (lo exige supabase-kt con minSdk 26 y `java.time`).
3. `release`: `isMinifyEnabled=true` + reglas R8/Proguard para Tink, Ktor, kotlinx-serialization, Supabase y Room.
4. Manifest base: `application` con `@HiltAndroidApp`; permisos `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.
5. `testInstrumentationRunner` = `HiltTestRunner` propio (subclase de `AndroidJUnitRunner` con `HiltTestApplication`).
6. `detekt.yml` estricto, ktlint, `.editorconfig`. Tareas `detekt` y `ktlintCheck` verdes con el código inicial.
7. `google-services.json` integrado; README documenta que no se versiona ningún secreto.

**Restricciones:** Version Catalog, no versiones hardcodeadas. KSP, no kapt. `targetSdk` ≥ el exigido por Play. No `security-crypto`.
**Probar:** `assembleDebug`, `assembleRelease` (R8), un unit test trivial con dependencia inyectada por Hilt, `detekt`, `ktlintCheck` — todos verdes.
**Done:** ☐ catálogo completo en última estable · ☐ debug y release (R8) compilan · ☐ desugaring activo · ☐ HiltTestRunner configurado · ☐ sin kapt, sin security-crypto · ☐ §0.9.

---

## T01 — Modelo de dominio + (de)serialización de la política

**Hacer:** tipos Kotlin puros que representan y (de)serializan la política de §0.3.
**Depende de:** T00
**Contratos:** §0.3.

**Implementar**
1. `Policy(device_id, version, device_state, daily_screen_time_minutes, schedules, category_limits, app_policies, category_assignments, grants)`; `Schedule(id, days, from, to, action, allow_list?)`; `Window(days, from, to)` (reutilizable por `schedule` y `allowed_windows`); `CategoryLimit(category, minutes)`; `AppPolicy(package_name, state, daily_limit_minutes?, allowed_windows: List<Window>, category?)`; `Grant(id, request_id?, scope, minutes, source, granted_at, expires_at)`.
2. Enums para `device_state`, `app_policy.state`, `schedule.action`, `grant.source` (valores exactos de §0.3).
3. `@Serializable` con nombres snake_case de §0.3 (incluye `allowed_windows`, `category_assignments`, `granted_at`, `request_id`).
4. Validar invariantes: `limited` ⇒ `daily_limit_minutes != null`; `allow_only` ⇒ `allow_list` no vacía; horas `HH:mm`; `expires_at > granted_at`; ventanas con `from`/`to` válidos (`from > to` = cruce de medianoche permitido).

**Restricciones:** 0 imports de `android.*`. `kotlinx-serialization-json`.
**Probar:** round-trip del JSON de §0.3 sin pérdida (con campos nuevos); políticas inválidas rechazadas con error claro.
**Done:** ☐ deserializa el ejemplo de §0.3 exactamente · ☐ valida invariantes · ☐ JVM puro · ☐ §0.9.

---

## T02 — Motor de reglas (precedencia determinista)

**Hacer:** función pura que decide `PERMITIR | BLOQUEAR(motivo)` aplicando exactamente §0.4.
**Depende de:** T01
**Contratos:** §0.4.

**Implementar**
1. Firma `evaluar(policy, packageName, usage, now, zonaHoraria) → Decision`. `usage` aporta uso por paquete y agregados por categoría/global ya calculados (los deriva T03; el motor no recalcula categorías).
2. Los **12 pasos de §0.4** en orden; primera coincidencia decide; cada BLOQUEAR devuelve motivo (desde una tabla de motivos).
3. Grant (paso 6) solo levanta 7–11; nunca 2–5. `always_allowed` ignora 7–11 pero no el paso 2. Match de grant por scope (`device` | `<pkg>` | `<categoría del pkg>`).
4. `device_state = downtime` (paso 7) y `= locked` (paso 2) son casos distintos; `allowed_windows` (paso 5) y `schedules` que cruzan medianoche; grants vigentes vs vencidos; cambio de día y zona.
5. Comentar cada paso referenciando §0.4. Determinismo total (la hora entra por parámetro).

**Restricciones:** Kotlin puro, 0 imports de `android.*`. Sin estado oculto ni reloj global.
**Probar (suite exhaustiva, la pieza más testeable):** una prueba por paso y por combinación conflictiva — "solo Classroom durante allow_only de tareas"; "2 h de juegos pero downtime 22:00–07:00"; "grant `device` levanta el global pero NO desbloquea una app `blocked` ni la franja `allow_only`"; "`always_allowed` ignora downtime y límites pero no `locked`"; bordes de medianoche/zona/grant vencido.
**Done:** ☐ cubre los 12 pasos y combinaciones · ☐ grant levanta solo 7–11 · ☐ bordes verdes · ☐ determinista · ☐ JVM puro · ☐ cada bloqueo trae motivo · ☐ §0.9.

---

## T03 — Persistencia local con Room (fuente de verdad offline)

**Hacer:** caché de la política (§0.3), uso del día y una outbox de subida.
**Depende de:** T00, T01
**Contratos:** §0.1 (versionado), §0.3.

**Implementar**
1. Entidades + DAOs: política vigente (`version`, `category_assignments`), `app_policies` (con `allowed_windows`), `grants` (`granted_at`/`expires_at`), `usage_today` con clave `(package_name, server_date)`, y outbox genérica `(tipo, payload_json, dedup_key, intentos, created_at)` para `usage_logs`/`device_alerts`/`behavioral_events`/`time_requests`/`heartbeat`.
2. Exponer política y uso como `Flow`.
3. **Guard de versión atómico:** upsert de política que aplica solo si `nuevaVersion > versionLocal` (descarta downgrades incluso bajo carrera).
4. **Agregados para el motor:** función/Flow que entrega uso por paquete + sumado por categoría (vía `category_assignments`) + global (excluyendo `always_allowed`). Único lugar que conoce el mapeo paquete→categoría.
5. `usage_today` clavado por **fecha de servidor** (no hora local). Rollover diario: al cambiar la fecha de servidor, "hoy" cambia (no se borra histórico; lo purga la retención de §0.5).
6. Migraciones versionadas; `exportSchema=true`.

**Restricciones:** Room vía KSP (no kapt). Coroutines/Flow.
**Probar (Room in-memory, instrumentado):** persistir/leer política; acumular `usage_today`; agregados por categoría/global correctos; outbox encola y drena; un downgrade de `version` no sobrescribe (paso 3); cambiar la fecha de servidor cambia el "hoy".
**Done:** ☐ DAOs con Flow · ☐ guard de versión atómico · ☐ agregados correctos · ☐ `usage_today` por fecha de servidor con rollover · ☐ outbox funcional · ☐ esquema exportado · ☐ KSP · ☐ §0.9.

---

## T04 — Servicio de tiempo confiable

**Hacer:** fuente de tiempo/zona robusta para evaluar ventanas (§0.4) y fechar el uso.
**Depende de:** T00
**Contratos:** §0.4.

**Implementar**
1. Interfaz `TimeProvider` (hora monotónica `SystemClock.elapsedRealtime` + hora de pared), **inyectable y faleable**.
2. Resolver y **observar** el `ZoneId`; emitir señal al cambiar (escucha `ACTION_TIMEZONE_CHANGED` o compara al evaluar) para T13.
3. "Fecha de servidor" inyectable (la rellena T18); fallback a hora local marcando incertidumbre.
4. Detectar saltos de reloj (relación monotónica↔pared inconsistente) y distinguirlos de cambios de zona (mismo instante, distinto `ZoneId`): son evasiones distintas. Emitir bandera para T13.

**Restricciones:** `java.time` (desugared). JVM puro (sin `android.*` en la lógica de tiempo; el observador de zona se inyecta).
**Probar (JVM, TimeProvider falso):** cruces de medianoche, cambio de día, salto de reloj y cambio de zona detectados.
**Done:** ☐ interfaz inyectable/faleable · ☐ detección de salto y de cambio de zona · ☐ JVM puro · ☐ §0.9.

---

## T05 — AccessibilityService vigilante de primer plano

**Hacer:** detectar el `packageName` en primer plano y emitirlo para el integrador.
**Depende de:** T00
**Contratos:** §0.6.

**Implementar**
1. Servicio en manifest con `accessibility_service_config.xml`: `accessibilityEventTypes=typeWindowStateChanged`, `canRetrieveWindowContent=false`, `isAccessibilityTool="false"`. Permiso `BIND_ACCESSIBILITY_SERVICE`.
2. Capturar `TYPE_WINDOW_STATE_CHANGED` → extraer el paquete en primer plano; filtrar ruido (lanzadores, IME).
3. Emitir el paquete activo como `Flow` para T11.
4. El usuario lo activa en Ajustes (intent guiado), siempre **después** de la divulgación de T25.

**Restricciones:** `isAccessibilityTool="false"` (obligatorio Play). **No** leer contenido de ventana (minimización, §0.6).
**Probar (instrumentado, API 28/31/35):** abrir 2–3 apps → el `Flow` emite el paquete correcto en orden.
**Done:** ☐ detecta cambios de app fiable en las 3 APIs · ☐ `isAccessibilityTool=false` · ☐ no recolecta contenido · ☐ §0.9.

---

## T06 — Foreground service contador de tiempo en vivo

**Hacer:** acumular el tiempo de la app en primer plano con tick regular; es la **fuente primaria** de tiempo (UsageStats solo reconcilia).
**Depende de:** T03, T04, T05
**Contratos:** §0.7, §0.8 (FGS specialUse, avisos in-process).

**Implementar**
1. FGS con `foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …>`. Notificación persistente discreta en canal propio. Permiso `POST_NOTIFICATIONS` (runtime 13+).
2. Arranque por versión (detrás de interfaz): API 31–33 solo desde contexto permitido (boot receiver con exención, app en foreground, expedited job — coordina con T10); <34 el atributo de tipo no aplica pero se declara igual.
3. Al cambiar el foreground (T05), marcar `t0`. Tick cada 5–10 s → `usage_today[pkg] += delta` en Room (fecha de servidor de T04). Intervalo como constante nombrada.
4. Calcular minutos restantes por scope. Cuando el restante cruce 10 y 5 min, **emitir notificación de aviso desde este servicio** (no desde la pantalla de T27), para que llegue con la app cerrada.
5. Al iniciar, pedir backfill a T07.

**Restricciones:** FGS `specialUse` (no otros tipos). Avisos in-process con Coroutines/Flow; **no** exact alarms.
**Probar (instrumentado, tick acelerado):** acumulado coincide (±1 tick) con el tiempo real; avisos 10/5 min se emiten con la UI cerrada; el conteo sobrevive a matar el servicio (se re-arma y reconcilia).
**Done:** ☐ FGS specialUse válido en 34+ y arranque correcto en 31–33 · ☐ conteo exacto · ☐ avisos sin UI abierta · ☐ continúa tras muerte del servicio · ☐ §0.9.

---

## T07 — Reconciliación con UsageStatsManager

**Hacer:** cross-check y backfill del uso del día con `queryEvents` cuando el contador no estuvo activo (ej. tras reinicio).
**Depende de:** T03, T04
**Contratos:** §0.7.

**Implementar**
1. Al arrancar y periódicamente, reconstruir el tiempo consumido hoy con `queryEvents`.
2. Conciliar con `usage_today` tomando el máximo razonable; registrar discrepancias. Operación **idempotente** (re-ejecutar no duplica).
3. Si falta el permiso `PACKAGE_USAGE_STATS` (acceso especial), no romper: marcar degradación parcial (T12).

**Restricciones:** `UsageStatsManager` detrás de interfaz por versión si aplica.
**Probar (instrumentado):** tras simular uso, el backfill rellena `usage_today` coherentemente; re-ejecutar no duplica; permiso ausente no crashea.
**Done:** ☐ backfill correcto en arranque · ☐ idempotente · ☐ maneja permiso ausente · ☐ §0.9.

---

## T08 — Overlay de bloqueo (motivo + acción)

**Hacer:** pantalla de bloqueo sin salida, con motivo y CTA "Pedir permiso".
**Depende de:** T00
**Contratos:** §0.7 (overlay), §0.4 (motivo).
**Ten en cuenta:** mostrar el motivo y ofrecer "Pedir permiso" convierte un callejón sin salida en acción legítima (enlaza con T28).

**Implementar**
1. Overlay con `WindowManager` + `TYPE_APPLICATION_OVERLAY`; contenido en Compose. Permiso `SYSTEM_ALERT_WINDOW`.
2. Mostrar/ocultar según orden de T11. Render con el `motivo` del motor (T02) y tono del copy de T25.
3. CTA "Pedir permiso" → dispara el flujo de tiempo extra (T28).
4. Sin ninguna ruta para que el menor lo cierre. Cuidar restricciones de full-screen intent en API 34+.

**Restricciones:** sin botón de salida. Copy desde T25 (no strings sueltos).
**Probar (instrumentado, 28/31/35):** el overlay aparece sobre cualquier app de terceros; siempre muestra motivo; el CTA dispara el callback.
**Done:** ☐ aparece sobre apps de terceros · ☐ siempre muestra motivo · ☐ sin botón de salto · ☐ CTA conectado · ☐ §0.9.

---

## T09 — Device Admin de consumo: lockNow + bloqueo total

**Hacer:** bloqueo total del dispositivo (manual o programado) en modo STANDARD.
**Depende de:** T00
**Contratos:** §0.8 (Device Admin consumo).

**Implementar**
1. Receiver de Device Admin (`device_admin_receiver.xml`, `BIND_DEVICE_ADMIN`) + activación guiada.
2. Exponer `lockNow()`.
3. Si `device_state == locked` (§0.3), al desbloquear reaparece el overlay persistente (coordinado con T08/T11).

**Restricciones:** solo APIs no deprecadas para consumo (`lockNow`); **no** políticas enterprise.
**Probar (instrumentado):** activar admin → `lockNow()` bloquea; con `device_state=locked`, el overlay vuelve al desbloquear.
**Done:** ☐ `lockNow` funciona · ☐ sin políticas deprecadas · ☐ overlay persistente coherente · ☐ §0.9.

---

## T10 — Re-armado tras reinicio y muerte de proceso

**Hacer:** reactivar el agente tras boot, actualización o muerte del proceso.
**Depende de:** T06
**Contratos:** §0.7.

**Implementar**
1. `BroadcastReceiver` para `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED` (`RECEIVE_BOOT_COMPLETED`).
2. En esos eventos: reiniciar el FGS (T06) y reconciliar uso (T07), sin duplicar servicios.
3. Verificar permisos/servicios (delegar el cálculo a T12).
4. Encolar una sincronización inicial (T18) vía WorkManager.

**Restricciones:** respetar la restricción de inicio de FGS en background (coordina con T06).
**Probar (instrumentado):** simular el broadcast → el contador queda activo, el uso se reconstruye, sin servicios duplicados.
**Done:** ☐ re-arma tras boot y actualización · ☐ reconcilia uso · ☐ no duplica servicios · ☐ §0.9.

---

## T11 — Integración del enforcement STANDARD (rebanada E2E offline)

**Hacer:** cablear T02+T03+T05+T06+T08+T09 en un bucle funcional **sin red** con política mock en Room. Núcleo jugable del producto.
**Depende de:** T02, T03, T05, T06, T08, T09
**Contratos:** §0.3, §0.4.

**Implementar**
1. Evento de accesibilidad (T05) → el motor (T02) evalúa sobre Room (T03) → decisión.
2. BLOQUEAR ⇒ overlay (T08) + `performGlobalAction(GLOBAL_ACTION_HOME)`; si `device_state=locked` ⇒ `lockNow()` (T09).
3. PERMITIR ⇒ el contador (T06) marca el foreground.
4. Reevaluar cuando `usage_today` cruza un umbral.

**Restricciones:** todo el bucle debe funcionar **offline** (modo avión).
**Probar (instrumentado, política mock que ejerza cada regla de §0.3, en modo avión):** bloquea `blocked`; respeta `always_allowed`; aplica `daily_limit` (bloquea al exceder); aplica `downtime`.
**Done:** ☐ bloquea/permite/limita según política mock · ☐ funciona offline · ☐ reevalúa al exceder límite · ☐ §0.9.

---

## T12 — Vigilante de salud + enforcementLevel + estados de error

**Hacer:** calcular `enforcementLevel ∈ {DEVICE_OWNER, STANDARD, DEGRADED}` y manejar cada situación de la tabla de §0.2.
**Depende de:** T03, T05, T08
**Contratos:** §0.2.

**Implementar**
1. Verificar: accesibilidad activa, `Settings.canDrawOverlays()`, `isIgnoringBatteryOptimizations`, permiso de uso, y si es Device Owner. WorkManager periódico + chequeo en arranque.
2. Derivar `enforcementLevel`; si falta un permiso clave ⇒ DEGRADED.
3. Mapear cada fila de §0.2 a su respuesta (alerta + pantalla "reparar" de T30).
4. Encolar la alerta a la outbox (T03) **sin fingir que protege**.

**Probar (instrumentado):** revocar accesibilidad ⇒ DEGRADED + alerta encolada; restaurar ⇒ STANDARD.
**Done:** ☐ nivel correcto en cada combinación · ☐ alerta al degradar · ☐ no oculta el estado real · ☐ §0.9.

---

## T13 — Anti-tamper STANDARD + anti-evasión de reloj/zona

**Hacer:** defensa best-effort en STANDARD y anti-evasión de tiempo.
**Depende de:** T04, T12
**Contratos:** §0.2, §0.3 (hora de servidor).

**Implementar**
1. Detectar (vía eventos de accesibilidad) intentos de abrir los Ajustes de la app o desactivar accesibilidad → alerta.
2. Detectar saltos de reloj y cambios de zona (banderas de T04) → recalcular ventanas con hora/fecha de servidor; marcar sospecha.
3. Mantener el consumo clavado por fecha de servidor (T03): cambiar la hora no "regala" tiempo.
4. Encolar los eventos de evasión **directamente en la outbox de Room (T03)**; la API de tracking y la subida las formaliza T32 (reutiliza esta misma outbox). Nombres: `accessibility_off_detected`, `uninstall_attempt`, `clock_tamper_suspected`, `timezone_changed`.

**Restricciones:** los eventos no contienen contenido del menor (§0.6).
**Probar (instrumentado):** cambiar hora o zona no resetea `usage_today` y genera la señal; desactivar accesibilidad genera alerta.
**Done:** ☐ reloj/zona manipulados no regalan tiempo · ☐ alertas de tamper emitidas · ☐ eventos encolados en la outbox · ☐ §0.9.

---

## T14 — Esquema Supabase + RLS

**Hacer:** crear todas las tablas de §0.5 con RLS, versión monotónica, política ensamblada y retención.
**Depende de:** —
**Contratos:** §0.5, §0.8 (publishable keys).

**Implementar**
1. Crear todas las tablas de §0.5 (incluidas `device_push_tokens`, `device_heartbeats`, `pairing_codes`) con índices (`device_id`, `bucket_date`, `status`, `pairing_codes.expires_at`). Ordenar FKs: `time_requests` antes de la FK `grants.request_id` (o esa FK en migración posterior).
2. **RLS con los predicados exactos de §0.5**: agente vía `((auth.jwt() ->> 'device_id')::uuid = device_id)` en `USING` y `WITH CHECK`; padre vía `EXISTS(... devices.parent_id = auth.uid())`; `pairing_codes` solo del padre dueño.
3. **Custom Access Token Hook** `custom_access_token_hook(event jsonb)` que copia `app_metadata.device_id` al claim de primer nivel `device_id`; registrarlo en Authentication → Hooks.
4. **Versión monotónica:** `bump_policy_version(device_id)` + triggers `AFTER INSERT/UPDATE/DELETE` en `app_policies` y `grants`.
5. **Vista/RPC `get_device_policy(device_id)`** que ensambla el JSON de §0.3 (política vigente + `app_policies` con `allowed_windows` + `category_assignments` derivado + `grants` con `expires_at > now()`).
6. Sembrar `policy_templates` (una por `age_band`): downtime 22:00–07:00, límite de "games", comunicación `always_allowed`, etc. Validar contra §0.3.
7. **Retención:** job pg_cron que purga `usage_logs`/`device_heartbeats` antiguos y `grants` vencidos.

**Restricciones:** publishable keys en cliente; `service_role` solo en backend.
**Probar (integración SQL):** JWT del dispositivo A no lee ni inserta filas de B; el padre dueño sí; `get_device_policy` produce JSON válido por §0.3; un cambio en `app_policies`/`grants` sube `version`; el hook inyecta `device_id`; las plantillas validan; la purga corre sin tocar datos recientes; migraciones aplican contra base vacía y con datos.
**Done:** ☐ tablas + índices · ☐ RLS exacta probada (lectura y escritura) · ☐ hook de claim registrado · ☐ trigger de versión · ☐ `get_device_policy` válido · ☐ plantillas · ☐ retención · ☐ publishable keys · ☐ §0.9.

---

## T15 — Contrato backend (Edge Functions)

**Hacer:** la lógica server-side que consumen el agente y el padre.
**Depende de:** T14
**Contratos:** §0.3, §0.5.

**Implementar (cada punto idempotente)**
1. **Emparejamiento (un solo uso, TTL):** el padre genera un `pairing_codes` (código + QR, `expires_at` ~10 min, `age_band`). El agente lo presenta a una Edge Function que valida vigencia y no-consumo, crea/asocia `device_id ↔ parent_id`, marca `consumed_at`, y escribe `device_id` en `auth.users.app_metadata` del usuario anónimo del agente (de ahí el hook de T14 inyecta el claim).
2. **Política inicial:** generar `device_policies v1` + `app_policies` desde `policy_templates` según `age_band`.
3. **GET de política:** RPC/endpoint que devuelve el JSON ensamblado de §0.3 vía `get_device_policy`.
4. **Tiempo extra:** al aprobar un `time_requests`, crear **un** `grants(source='extra_time', request_id, granted_at=now, expires_at=now+minutes)` (la unicidad de `request_id` evita duplicados) y subir versión (`bump_policy_version`).
5. **Recompensa:** regla que crea `grants(source='reward')` respetando topes (T29) y sube versión.
6. **Registro de token FCM:** endpoint que hace upsert en `device_push_tokens`.
7. **Heartbeat:** RPC que actualiza `device_heartbeats`/`devices.last_seen_at` con `enforcement`, `battery_pct`, `clock_offset_ms`.
8. **Verificación de Play Integrity:** endpoint que recibe el token de integridad (T23), lo verifica contra Google y registra el veredicto (alerta si negativo).
9. **FCM fan-out:** ante cualquier cambio de política o resolución de solicitud, leer el token de `device_push_tokens` y disparar push de alta prioridad ("sync ahora").

**Probar (integración):** `pairing_codes` vencido o consumido → rechazado; aprobar un `time_requests` dos veces → un solo grant + una subida de versión + un push (mock); `get_device_policy` válido por §0.3; token de integridad alterado → rechazado.
**Done:** ☐ emparejamiento un-solo-uso+TTL → claim `device_id` · ☐ `get_device_policy` ensambla §0.3 · ☐ aprobación→grant idempotente+versión+push · ☐ token FCM y heartbeat persistidos · ☐ integridad verificada en servidor · ☐ §0.9.

---

## T16 — Almacenamiento seguro de secretos

**Hacer:** guardar el token de dispositivo y el secreto de emparejamiento cifrados.
**Depende de:** T00
**Contratos:** §0.8.

**Implementar**
1. Generar/recuperar la clave maestra en Android Keystore.
2. Cifrar valores con Tink AEAD antes de escribir en DataStore (Preferences).
3. API `suspend` (todo fuera del hilo principal).
4. Excluir el almacén de Auto Backup.
5. Manejar "keyset corrupto": regeneración controlada sin crashear.

**Restricciones:** DataStore + Tink + Keystore. **No** `security-crypto`/`EncryptedSharedPreferences`.
**Probar (instrumentado):** escribir/leer un secreto persiste cifrado; inspeccionar el archivo no revela el valor en claro.
**Done:** ☐ lectura/escritura cifradas y asíncronas · ☐ clave en Keystore · ☐ excluido de backup · ☐ sin librerías prohibidas · ☐ §0.9.

---

## T17 — Autenticación de dispositivo

**Hacer:** sesión del agente contra Supabase con claim `device_id` (RLS de §0.5).
**Depende de:** T15, T16
**Contratos:** §0.5, §0.8.

**Implementar**
1. Abrir **sesión anónima** de `auth-kt` (crea el `auth.users` del dispositivo). Tras emparejar (T15), el `device_id` queda en `app_metadata` y el hook lo inyecta como claim. Persistir la sesión (access + refresh) cifrada con T16.
2. Refresh automático; al detectar anomalía (ej. veredicto de integridad negativo de T23), forzar re-emparejamiento o rotación.
3. Configurar el cliente Supabase con **publishable key** + la sesión del dispositivo.
4. Todas las llamadas REST/Realtime usan esta sesión.

**Restricciones:** publishable key (no anon/service_role). supabase-kt 3.x.
**Probar (integración, backend de prueba):** el agente abre sesión, el token incluye el claim `device_id`, refresca al expirar y solo accede a sus filas.
**Done:** ☐ sesión anónima persistente y refrescable · ☐ claim `device_id` presente · ☐ rotación ante anomalía · ☐ respeta RLS · ☐ §0.9.

---

## T18 — Sincronización REST (offline-first)

**Hacer:** traer la política nueva y subir datos, con Room como fuente de verdad.
**Depende de:** T03, T17
**Contratos:** §0.1, §0.3, §0.5.

**Implementar**
1. **Pull:** RPC `get_device_policy` (JSON ensamblado de §0.3) → aplicar solo si `version > local` con el guard de T03 → el motor reevalúa.
2. **Push:** drenar la outbox (T03) mapeando cada `tipo` a su destino: `usage_logs`/`device_alerts`/`behavioral_events`/`time_requests` a sus tablas; `heartbeat` a la RPC de T15. Idempotencia por `dedup_key`/ids.
3. Mantener vivo el token FCM (reenviar si `device_push_tokens` no tiene el actual; coordina con T19).
4. Rellenar la "fecha de servidor" de T04 desde la respuesta del servidor.
5. **Nunca** bloquear el enforcement por red: si falla, seguir con la política cacheada.

**Restricciones:** `postgrest-kt`. Pruebas con **Ktor MockEngine** (no MockWebServer).
**Probar (MockEngine):** aplica `v+1` y descarta `v-1`; drena la outbox y reintenta sin duplicar (mismo `dedup_key`); ejercita 500/timeout/JSON malformado; offline → el motor sigue con la última política.
**Done:** ☐ pull usa `get_device_policy` · ☐ versionado/idempotencia por `dedup_key` · ☐ outbox drena con reintentos · ☐ heartbeat enviado · ☐ offline-tolerante · ☐ MockEngine · ☐ §0.9.

---

## T19 — FCM despertador de alta prioridad

**Hacer:** recibir el push "sync ahora" y registrar el token.
**Depende de:** T18
**Contratos:** §0.1 (FCM es señal, no dato).

**Implementar**
1. `onNewToken` → upsert en `device_push_tokens` vía el endpoint de T15; encolar en la outbox si no hay red.
2. Al recibir un push de alta prioridad, **encolar sync** (T18/T20). El agente hace `GET` autenticado; no confía en el payload.
3. Sobrevivir a Doze/App Standby.

**Restricciones:** nunca aplicar datos del payload del push.
**Probar:** un push de prueba → el agente hace pull y aplica la nueva política en segundos, incluso en background/Doze.
**Done:** ☐ token registrado/renovado · ☐ push dispara sync sin confiar en payload · ☐ funciona en Doze · ☐ §0.9.

---

## T20 — Jobs de WorkManager

**Hacer:** trabajo programado fiable: heartbeat, subida de outbox, reconciliación, reintentos.
**Depende de:** T07, T18
**Contratos:** §0.5.

**Implementar**
1. Heartbeat periódico vía la RPC de T15 con `enforcement` (T12), `battery_pct` y `clock_offset_ms` (T13).
2. Subida periódica de la outbox.
3. Reconciliación de uso (T07).
4. Backoff exponencial + constraints de red.
5. Encadenar con el re-arme tras boot (T10).

**Restricciones:** inyección Hilt en workers (`androidx.hilt:hilt-work`).
**Probar (`WorkManagerTestInitHelper`):** cada worker corre, reintenta con backoff y respeta constraints; inyección Hilt funciona.
**Done:** ☐ heartbeat/subida/reconciliación programados · ☐ reintentos con backoff · ☐ inyección Hilt · ☐ §0.9.

---

## T21 — Realtime solo en primer plano

**Hacer:** refrescar la UI al instante sin gastar batería en background.
**Depende de:** T17
**Contratos:** §0.1.

**Implementar**
1. Suscribirse a cambios de política/grants **solo** mientras hay UI en foreground (atado al lifecycle).
2. Al pasar a background, cerrar el socket (el control va por FCM, no por Realtime).
3. Refrescar la pantalla de estado (T27) y el resultado de solicitudes (T28).

**Restricciones:** `realtime-kt`. Nunca usar Realtime como canal de control en background.
**Probar (instrumentado):** con la app en foreground, un cambio en backend refresca la UI; al ir a background, el socket se cierra.
**Done:** ☐ Realtime solo en foreground · ☐ cierra socket en background · ☐ refresca UI relevante · ☐ §0.9.

---

## T22 — TLS 1.3 + certificate pinning

**Hacer:** asegurar el canal contra el backend.
**Depende de:** T17
**Contratos:** —

**Implementar**
1. Forzar TLS 1.3 en el motor `ktor-client-okhttp`.
2. `CertificatePinner` del dominio del backend, con plan de rotación de pines documentado.
3. Aplicarlo al cliente que usa supabase-kt.

**Probar (instrumentado):** conexión con pin correcto OK; con pin alterado, falla (MITM bloqueado).
**Done:** ☐ TLS 1.3 · ☐ pinning con rotación documentada · ☐ MITM rechazado · ☐ §0.9.

---

## T23 — Play Integrity API + ofuscación R8

**Hacer:** verificar la integridad del agente y dificultar el reempaquetado.
**Depende de:** T00, T15, T17
**Contratos:** §0.9 (R8).

**Implementar**
1. Solicitar el veredicto de integridad y enviarlo al endpoint de verificación server-side de T15 (no confiar solo en el cliente).
2. Reaccionar a un veredicto negativo (alerta + degradación) sin romper ante falsos positivos.
3. Reglas R8/ofuscación + anti-debug básico (no como única defensa); preservar reflexión de Tink/Ktor/serialization/Room.

**Probar (integración):** una build firmada produce veredicto válido; una build alterada se detecta server-side.
**Done:** ☐ veredicto verificado en servidor · ☐ reacción sin falsos positivos catastróficos · ☐ R8 sin romper reflexión · ☐ §0.9.

---

## T24 — Emparejamiento (QR / código)

**Hacer:** vincular el dispositivo del menor con la cuenta del padre.
**Depende de:** T15, T16, T17
**Contratos:** §0.5 (`pairing_codes`).

**Implementar**
1. Escanear el QR (CameraX + ML Kit, declarados en T00) o ingresar el código del panel.
2. Tras abrir/recuperar la sesión anónima (T17), llamar al emparejamiento (T15) y guardar la sesión con T16.
3. Estados de éxito/error claros, incluyendo **código vencido** y **código ya usado** (uso único + TTL), con opción de pedir uno nuevo.

**Probar (instrumentado):** un código válido empareja y persiste la sesión; uno inválido, vencido o usado muestra el error correcto.
**Done:** ☐ empareja por QR y por código · ☐ persiste sesión · ☐ maneja errores (inválido/vencido/usado) · ☐ §0.9.

---

## T25 — Divulgación + consentimiento + transparencia + sistema de copy

**Hacer:** la divulgación prominente de Play (§0.6), la pantalla de transparencia para el menor y la guía de tono centralizada.
**Depende de:** T00
**Contratos:** §0.6.
**Ten en cuenta:** un control transparente es más duradero; el menor que lo descubre oculto lo combate.

**Implementar**
1. **Divulgación prominente in-app** que cumpla los puntos de §0.6 (dentro de la app, en uso normal, describe los datos de accesibilidad y su uso/compartición, **acción afirmativa** para consentir, separada de la política de privacidad).
2. Pantalla "qué se monitorea" siempre accesible para el menor. **No** implementar ningún modo oculto.
3. Sistema de copy: strings externalizados, positivos, con motivo, con variantes por edad. **Origen único** de todos los textos de cara al menor (overlay T08, avisos T27, alertas T30).
4. Bloquear el avance del onboarding hasta consentir.

**Restricciones:** sin modo oculto. Copy localizable.
**Probar (instrumentado):** no se avanza sin consentimiento afirmativo; la transparencia es accesible; checklist de §0.6 cubierto.
**Done:** ☐ cumple los puntos de divulgación · ☐ transparencia accesible · ☐ copy centralizado/localizable · ☐ sin modo oculto · ☐ §0.9.

---

## T26 — Onboarding por valor + guía de permisos con progreso

**Hacer:** setup ordenado por valor, con barra de progreso real y una "primera victoria" antes de los permisos caros.
**Depende de:** T08, T12, T24, T25
**Contratos:** §0.2 (progreso = estado real).
**Ten en cuenta:** el mayor abandono es un setup largo cuyo beneficio no se ve; entregar una victoria temprana sostiene el empujón final.

**Implementar**
1. Orden: emparejar (T24) → divulgación/consentimiento (T25) → activar accesibilidad + overlay → **auto-demostración local** ("Probemos tu protección": muestra el overlay 2–3 s) → luego batería, notificaciones y Device Admin como "subir el nivel de protección".
2. Barra "Protección N de M" que refleje el estado **real** de T12 (nunca inflado).
3. Cada permiso pendiente abre el Ajuste del sistema correcto y reverifica al volver (deep links válidos en 28/31/35).
4. Onboarding **reanudable** (estado persistido).
5. Emitir eventos del embudo (T32): `onboarding_step_reached`, `onboarding_first_win`, `onboarding_completed`, `onboarding_abandoned`.

**Restricciones:** `navigation-compose`. El progreso nunca miente sobre el estado real.
**Probar (usabilidad + instrumentado):** llegar a la primera victoria sin instrucciones; reanudar tras cerrar; el progreso coincide con T12; los deep links funcionan en 28/31/35.
**Done:** ☐ primera victoria antes de los permisos caros · ☐ progreso real · ☐ reanudable · ☐ eventos de embudo emitidos · ☐ §0.9.

---

## T27 — Pantalla de estado del menor (límites visibles + avisos)

**Hacer:** estado diario con tiempo restante, próximo downtime, avisos previos y acceso a "pedir tiempo extra".
**Depende de:** T06, T21
**Contratos:** §0.8 (sin exact alarms).
**Ten en cuenta:** un corte predecible y anunciado genera mucho menos rechazo que uno súbito.

**Implementar**
1. Mostrar "Quedan X min", qué está permitido ahora y el próximo bloqueo (un solo foco visual por pantalla).
2. Reflejar en pantalla los avisos de 10/5 min; **la notificación la emite T06** (para que llegue con la app cerrada) y esta pantalla la espeja cuando está visible.
3. Botón "Pedir tiempo extra" → flujo de T28.
4. Refresco en vivo vía Realtime foreground (T21).
5. Nada configurable por el menor; textos desde el copy de T25.

**Restricciones:** sin exact alarms. No configurable por el menor.
**Probar (instrumentado, contador acelerado):** el restante coincide con Room; los avisos disparan a 10/5 min; sin corte sorpresa.
**Done:** ☐ restante y próximos cortes visibles · ☐ avisos exactos · ☐ sin exact alarms · ☐ no configurable por el menor · ☐ §0.9.

---

## T28 — Bucle "Pedir tiempo extra" (feature ancla)

**Hacer:** que el menor solicite tiempo y reciba respuesta rápido; al aprobarse, el motor aplica el grant (paso 6 de §0.4).
**Depende de:** T08, T15, T18, T19, T21, T27
**Contratos:** §0.3 (grant), §0.4 (paso 6).
**Ten en cuenta:** sin vía legítima para pedir, la única salida del menor es evadir el control; este bucle convierte "romper" en "pedir".

**Implementar**
1. Desde la pantalla de estado (T27) o el overlay (T08), crear un `time_requests` (scope, minutos, motivo opcional). Offline: encolar en la outbox (T03) y sincronizar al reconectar.
2. Esperar la resolución vía FCM (T19) / Realtime (T21).
3. Al aprobarse, T15 crea el `grant(source='extra_time')` idempotente y sube versión → sync (T18) → el motor lo aplica en el **paso 6 de §0.4** (levanta los límites de tiempo del scope; no desbloquea `blocked` ni la franja `allow_only`).
4. Mostrar el resultado inmediato y claro ("Te dieron 20 min" / "Ahora no").
5. Throttle anti-spam (local + servidor).

**Probar (E2E, backend de prueba):** solicitud → aprobación → grant aplicado y acceso recuperado; tolerante a offline (se aplica al reconectar por versionado); throttle activo.
**Done:** ☐ flujo completo solicitud→grant aplicado · ☐ resultado mostrado rápido · ☐ offline-tolerante · ☐ throttle activo · ☐ §0.9.

---

## T29 — Banco de tiempo / recompensas

**Hacer:** mostrar y aplicar "tiempo ganado" como refuerzo positivo.
**Depende de:** T18, T19, T28
**Contratos:** §0.3 (`grant.source='reward'`).

**Implementar**
1. Recibir grants de recompensa (regla server-side de T15).
2. Mostrar el "tiempo ganado" en la pantalla de estado (T27) con confirmación positiva.
3. Respetar topes del padre (sin saldo infinito).

**Restricciones:** reutilizar `grants` (sin ruta paralela).
**Probar (E2E):** una recompensa server-side aplica un grant; el menor ve el saldo y la confirmación.
**Done:** ☐ recompensa aplicada vía grants · ☐ saldo visible · ☐ topes respetados · ☐ §0.9.

---

## T30 — Reparación de un toque desde alertas de degradación

**Hacer:** convertir cada degradación (§0.2) en una acción de reparación de un toque.
**Depende de:** T12
**Contratos:** §0.2.
**Ten en cuenta:** una alerta vaga genera ansiedad y se silencia; una con causa concreta + acción se repara.

**Implementar**
1. Por cada causa de §0.2 (accesibilidad off, overlay revocado, batería sin exención), una pantalla/overlay con **causa concreta + CTA "Reparar"** que abre el Ajuste exacto (deep links válidos por versión).
2. Copy con encuadre honesto (T25), sin urgencia falsa.
3. Anti-fatiga: agrupar y limitar alertas por incidente.
4. Al recuperarse, confirmación positiva.
5. Emitir eventos (T32): `degraded_alert_shown`, `repair_tapped`, `protection_restored`.

**Probar (instrumentado, 28/31/35):** cada tipo de degradación abre el deep link correcto; tras reparar, vuelve a STANDARD.
**Done:** ☐ reparación de un toque por cada causa · ☐ deep links válidos por versión · ☐ anti-spam · ☐ eventos emitidos · ☐ §0.9.

---

## T31 — Provisión Device Owner + enforcement reforzado + oferta re-temporizada

**Hacer:** hard enforcement real y su oferta en el momento adecuado.
**Depende de:** T11, T12
**Contratos:** §0.2.
**Ten en cuenta:** pedir un factory reset en frío genera abandono; ofrecer el "modo reforzado" al estrenar/dedicar un teléfono lo hace viable.

**Implementar**
1. Detectar el nivel en runtime; el **mismo motor (T02)** sirve a ambos niveles, degradando acciones en STANDARD.
2. En Device Owner: `setPackagesSuspended`/`setApplicationHidden` (hard block), `setUninstallBlocked(true)`, bloqueo de Safe Mode, Factory Reset Protection, exenciones de FGS, Lock Task/Kiosk para bloqueo total, opcional `setAlwaysOnVpnPackage`.
3. Documentar el aprovisionamiento OOBE (QR/NFC/zero-touch).
4. **Oferta re-temporizada:** en STANDARD **no** exigir reset; ofrecer "modo reforzado" como tarjeta honesta y guiar el OOBE solo cuando haya un equipo nuevo/dedicado.
5. Reportar `enforcement` al backend (campo en `devices`).

**Restricciones:** políticas fuertes solo bajo Device Owner (no en consumo).
**Probar:** en un dispositivo aprovisionado como Device Owner, el menor no puede desinstalar ni desactivar; en STANDARD no aparece ninguna exigencia bloqueante de reset.
**Done:** ☐ hard enforcement en DO (uninstall block, suspensión, FRP) · ☐ mismo motor en ambos niveles · ☐ la oferta nunca bloquea STANDARD · ☐ nivel reportado · ☐ §0.9.

---

## T32 — Instrumentación de eventos conductuales

**Hacer:** emitir y subir el catálogo de eventos de conducta, con minimización de datos.
**Depende de:** T03, T18, T20
**Contratos:** §0.5 (`behavioral_events`), §0.6.

**Implementar**
1. API de tracking interna que **encola** eventos (no bloquea el enforcement) en la outbox de Room (T03).
2. Cablear eventos en sus features: embudo de activación (T26), bucle de tiempo (T27/T28), recompensas (T29), reparación (T30), evasión como señal (T13, que ya encola con esta outbox).
3. Cada evento con `device_id`, `event_version`, `client_ts` y `props` mínimos. **Nunca** contenido ni texto del menor.
4. Subida resiliente y por lotes (T18/T20).

**Catálogo mínimo:** `onboarding_step_reached`, `onboarding_first_win`, `onboarding_completed`, `onboarding_abandoned`, `protection_progress`, `permission_granted`, `device_owner_offered/adopted/declined`, `degraded_alert_shown`, `repair_tapped`, `protection_restored`, `time_warning_shown`, `limit_reached`, `block_overlay_shown`, `ask_permission_tapped`, `extra_time_requested`, `extra_time_resolved`, `reward_granted`, `reward_seen`, `accessibility_off_detected`, `uninstall_attempt`, `clock_tamper_suspected`, `timezone_changed`.

**Restricciones:** sin contenido del menor (§0.6). Esquema de eventos versionado (`event_version`).
**Probar (E2E):** cada evento del catálogo se emite y llega a `behavioral_events`; resiliente a offline; sin datos de contenido.
**Done:** ☐ catálogo completo emitido y recibido · ☐ no afecta el enforcement · ☐ minimización de datos · ☐ esquema versionado · ☐ §0.9.

---

## T33 — Check-in de resultado percibido por el padre

**Hacer:** definir el contrato + evento de micro-feedback de outcome ("¿esto está ayudando?"). La UI vive en la app del padre (fuera de alcance).
**Depende de:** T14
**Contratos:** §0.5 (`behavioral_events`, RLS del padre).

**Implementar**
1. Definir cadencia (ej. quincenal) y payload (escala simple 😊/😐/☹️ + comentario opcional).
2. Evento `parent_outcome_checkin` insertado respetando la RLS del padre.
3. Descartable y no bloqueante.

**Probar (integración):** insertar un check-in respeta RLS y queda disponible para análisis.
**Done:** ☐ evento definido y aceptado por RLS · ☐ cadencia/descartable documentados · ☐ contrato claro para el panel · ☐ §0.9.

---

## T34 — Paquete de cumplimiento de Google Play

**Hacer:** todo lo no-código que Play exige (§0.6).
**Depende de:** T05, T25, T31
**Contratos:** §0.6.

**Implementar**
1. **Permission Declaration Form** (uso de `AccessibilityService`) en Play Console.
2. Confirmar `isAccessibilityTool=false` (T05) y la divulgación prominente (T25).
3. **Video** del flujo y la divulgación.
4. Política de privacidad + **Data Safety** describiendo el monitoreo y los datos; cumplir políticas de menores/Familias.
5. Justificación del `foregroundServiceType=specialUse`.
6. Justificación de Device Owner (T31) + canal de distribución alterno documentado.

**Probar:** una "revisión interna" contra el checklist de §0.6 pasa antes de enviar; formularios y build de prueba completos.
**Done:** ☐ formularios y declaraciones completos · ☐ video listo · ☐ Data Safety + privacidad publicadas · ☐ checklist de §0.6 verde · ☐ §0.9.

---

## T35 — (Opcional) Filtrado DNS por VpnService

**Hacer:** filtrar dominios prohibidos vía VPN local, con sus límites declarados.
**Depende de:** T11
**Contratos:** §0.6.

**Implementar**
1. Túnel local que filtra el DNS de los dominios bloqueados (`VpnService.Builder`, `BIND_VPN_SERVICE`).
2. **Advertir al padre:** solo puede haber una VPN activa a la vez (compite con la del usuario).
3. En Device Owner, opción `setAlwaysOnVpnPackage`.

**Restricciones:** declarar en Play; cifrar; no monetizar tráfico (§0.6).
**Probar (instrumentado):** los dominios bloqueados no resuelven; se muestra la advertencia; la coexistencia está documentada.
**Done:** ☐ filtra DNS · ☐ advierte el límite de VPN única · ☐ declarado en Play · ☐ no recolecta tráfico sensible · ☐ §0.9.

---

## T36 — Estrategia de pruebas transversal + matriz de compatibilidad

**Hacer:** cobertura completa y compatibilidad de Android 8–15 (§0.7), formalizar el CI.
**Depende de:** se construye en paralelo; se cierra al final.
**Contratos:** §0.7, §0.8, §0.9.

**Implementar**
1. Unit JVM: motor (T02), tiempo (T04), modelo (T01).
2. Repositorio/sync: Room in-memory + **Ktor MockEngine** (no MockWebServer) para versionado/idempotencia.
3. Instrumentadas: overlay sobre app bloqueada, `lockNow`, re-arme tras boot, revocar accesibilidad → DEGRADED.
4. Anti-tamper: desinstalar, force-stop, cambiar hora/zona, desactivar accesibilidad.
5. Batería: Doze/App Standby (FCM despierta, el contador reconcilia).
6. **Matriz de §0.7:** instrumentadas en al menos API 28, 31, 35.
7. CI: comandos 1–5 de §0.9 por cada PR; checklist de §0.6 antes de cada envío.

**Stack de prueba:** JUnit, Turbine, MockK, Robolectric, Compose UI Test, `androidx.test`, Ktor MockEngine, Room in-memory, `WorkManagerTestInitHelper`.
**Probar:** el pipeline corre toda la suite en los 3 niveles de API y reporta cobertura.
**Done:** ☐ unit + integración + instrumentadas verdes en 28/31/35 · ☐ Doze y anti-tamper · ☐ MockEngine · ☐ checklist de §0.6 en CI · ☐ §0.9.

---

# §2 — Trazabilidad (nada omitido)

| Capacidad | Tarea(s) |
|-----------|----------|
| Motor de reglas + precedencia (§0.4) | T01, T02 |
| Caché y uso offline (§0.3) | T03, T04 |
| Detección de app + conteo de tiempo | T05, T06, T07 |
| Bloqueo soft (overlay) y total (`lockNow`) | T08, T09 |
| Persistencia del enforcement (boot/muerte) | T10 |
| Enforcement STANDARD integrado (offline) | T11 |
| Salud, `enforcementLevel`, degradación (§0.2) | T12 |
| Anti-tamper + anti-evasión de reloj/zona | T13 |
| Backend: datos + RLS (§0.5) | T14 |
| Claim `device_id` en JWT (hook) + versión monotónica + política ensamblada | T14, T15, T17, T18 |
| Tokens FCM, heartbeat y `pairing_codes` (esquema) | T14, T19, T20, T24 |
| Backend: emparejamiento, política inicial, tiempo extra, recompensa, FCM, integridad | T15 |
| Secretos cifrados | T16 |
| Auth de dispositivo | T17 |
| Sync REST offline-first | T18 |
| Despertador FCM | T19 |
| Trabajo programado | T20 |
| Realtime de UI (foreground) | T21 |
| TLS + pinning | T22 |
| Integridad (Play Integrity) + R8 | T23 |
| Emparejamiento (UI) | T24 |
| Divulgación/consentimiento + transparencia + copy | T25 |
| Onboarding por valor + permisos con progreso | T26 |
| Estado del menor + límites visibles | T27 |
| Bucle "pedir tiempo extra" | T28 |
| Banco de tiempo / recompensas | T29 |
| Reparación de un toque (degradación) | T30 |
| Device Owner + hard enforcement + oferta | T31 |
| Instrumentación conductual | T32 |
| Check-in de resultado del padre | T33 |
| Cumplimiento de Play (no-código) | T34 |
| Filtrado de red (opcional) | T35 |
| Pruebas + matriz de compatibilidad | T36 |

---

# §3 — Orden de ejecución

1. **T00** (base).
2. **T01 → T02 → T03 → T04** (núcleo offline; todo JVM/Room testeable).
3. **T05 → T06 → T07 → T08 → T09 → T10 → T11** (enforcement STANDARD; T11 cierra una rebanada E2E offline jugable).
4. **T12 → T13** (salud y anti-tamper).
5. En paralelo: **T14 → T15** (backend) y **T16 → T17 → T18 → T19 → T20 → T21** (auth/sync).
6. **T22 → T23** (seguridad e integridad).
7. **T24 → T25 → T26 → T27 → T28** (no dejar T28 para el final) **→ T29 → T30 → T31** (UX y conducta).
8. **T32 → T33** (instrumentación), **T34** (cumplimiento), **T35** (opcional), **T36** (cierre de pruebas y matriz).
