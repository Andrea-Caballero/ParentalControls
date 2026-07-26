# Informe de preparación del backend para Windows

## Propósito y alcance

Este informe determina si el backend Supabase disponible en `../parentalControl/supabase` y el cliente Windows de `ControlParental` permiten completar recorridos reales de punta a punta. El alcance excluye explícitamente Android: no se evaluaron su código ni sus documentos.

La revisión fue estática y se realizó sobre los archivos presentes en disco el 23 de julio de 2026. No se consultó el proyecto Supabase remoto, no se inspeccionaron secretos ni configuración del entorno y no se ejecutó un despliegue. Por lo tanto, el estado desplegado sigue siendo desconocido salvo por el inventario de tablas públicas proporcionado por el usuario.

## Respuesta ejecutiva

**El backend tiene fundamentos reutilizables, pero NO está listo de punta a punta para Windows.** Existen esquema, RLS, funciones, autenticación anónima, telemetría, política, heartbeat, solicitudes de tiempo y una outbox local. Sin embargo, los contratos versionados y los que consume Windows no coinciden en puntos esenciales; tampoco existe prueba de despliegue, WNS server-side, veredicto de integridad Windows ni un recorrido de staging completo.

La clasificación resumida es **0 recorridos Windows completos, 10 áreas parciales y 3 áreas faltantes para Windows**. **“0 completos” significa cero recorridos Windows cerrados de punta a punta; no significa cero componentes de backend.**

## Evidencia y certeza

| Marca | Significado | Uso en este informe |
|---|---|---|
| **Confirmado en repositorio** | Existe evidencia directa en los archivos actuales, con ruta y línea. | Código, migraciones, pruebas unitarias o documentos presentes en disco. |
| **Reportado en Supabase desplegado, pero no versionado** | El usuario informó que existe en el inventario público remoto, pero no hay migración canónica que lo reproduzca. | Tabla `children`. |
| **Despliegue/configuración no verificados** | No se consultó Supabase remoto ni se inspeccionaron secretos, hooks, flags o variables del entorno. | Funciones desplegadas, `verify_jwt`, hook habilitado, RLS efectiva, WNS y staging. |

> Una función o tabla presente en el repositorio demuestra una base implementada; no demuestra que esté desplegada ni que el cliente Windows pueda usarla con su contrato actual.

## Correcciones a afirmaciones anteriores

1. **No se puede afirmar que `device_alerts` o `integrity_reports` estén ausentes del Supabase desplegado.** Solo se confirmó que no aparecen en las migraciones versionadas `001` a `004` y que tampoco aparecen en el inventario de tablas públicas en vivo proporcionado por el usuario. El remoto no fue consultado.
2. **El inventario en vivo incluye `children`, pero las migraciones no la crean.** La migración inicial crea once tablas (`../parentalControl/supabase/migrations/001_initial_schema.sql:24-226`) y la migración `004` agrega dos (`../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:15-63`). Esta diferencia prueba deriva entre despliegue y repositorio.
3. **`device_alerts` e `integrity_reports` provienen de documentos y propuestas del lado Windows, no de migraciones canónicas del backend.** Se proponen como REST en `apis.md:59-75` y `apis.md:137-159`, y el handoff propone crearlas en `docs/handoff/backend-team-handoff-2026-07-22.md:83-120`.
4. **Decodificar manualmente el payload de un JWT no es automáticamente explotable si la plataforma ejecuta la función con `verify_jwt = true`.** El código sí usa `atob` sin verificar dentro del handler, por ejemplo en `../parentalControl/supabase/functions/get-policy/index.ts:18-43` y `heartbeat/index.ts:18-50`. Sin embargo, el repositorio no contiene `supabase/config.toml` ni evidencia de configuración/despliegue. La guía oficial actual recomienda mantener la verificación de plataforma y obtener un contexto autenticado dentro de la función; por eso el riesgo es **configuración no demostrada más bypass de RLS con `service_role`**, no una explotación confirmada.
5. **El custom access token hook no tiene solo un problema de import.** El import inexistente está en `../parentalControl/supabase/functions/custom-access-token-hook/index.ts:8`, pero la forma completa tampoco coincide con el contrato oficial actual. Supabase entrega `{ user_id, claims, authentication_method }` y espera `{ claims }`; el código espera `event.event.jwt` y `event.event.claims` (`index.ts:10-40`) y devuelve claims crudos (`index.ts:43-55`). Los hooks HTTP están soportados oficialmente, pero esta implementación no está alineada con su entrada, verificación de webhook ni salida actuales.
6. **Un INSERT directo podría almacenar evidencia de integridad, pero no produce por sí solo el veredicto prometido.** Windows publica en una tabla y espera `{ verdict }` (`src/ControlParental.Service/BackendClient.cs:372-414`); la propuesta SQL solo almacena campos y no calcula ese valor (`docs/handoff/backend-team-handoff-2026-07-22.md:198-238`). Se recomienda una Edge Function o RPC para validación y lógica de negocio. No se afirma que sea el único diseño teóricamente posible.

## Inventario actual del backend

Las 13 tablas siguientes son reproducibles desde migraciones. `children` pertenece únicamente al inventario en vivo informado por el usuario.

| Tabla pública | Certeza | Evidencia |
|---|---|---|
| `devices` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:24-36` |
| `app_policies` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:44-56` |
| `time_requests` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:64-75` |
| `grants` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:84-95` |
| `usage_logs` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:104-114` |
| `device_push_tokens` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:123-133` |
| `device_heartbeats` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:141-152` |
| `pairing_codes` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:161-170` |
| `policy_templates` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:180-189` |
| `schedules` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:197-209` |
| `outbox` | Confirmado en repositorio | `../parentalControl/supabase/migrations/001_initial_schema.sql:217-226` |
| `parent_outcome_checkins` | Confirmado en repositorio | `../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:15-36` |
| `behavioral_events` | Confirmado en repositorio | `../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:53-63` |
| `children` | Reportado en Supabase desplegado, pero no versionado | Inventario de tablas públicas proporcionado por el usuario; no existe `CREATE TABLE children` en las migraciones `001` a `004`. |

No se incluyen `device_alerts` ni `integrity_reports` en el inventario actual: no están en las migraciones y tampoco estaban en el inventario en vivo proporcionado. Esto **no prueba** que sean imposibles de encontrar en otro esquema, rama o estado remoto no consultado.

## Matriz de preparación

Definiciones:

- **COMPLETE FOUNDATION**: la pieza base existe y su contrato local es coherente, pero puede faltar prueba de despliegue o integración.
- **PARTIAL**: existe código útil, pero hay una brecha contractual, de seguridad, atomicidad o conexión de extremo a extremo.
- **MISSING FOR WINDOWS**: no existe una implementación backend adecuada para el recorrido Windows requerido.

| Área | Estado | Evidencia exacta y conclusión | Prueba de despliegue |
|---|---|---|---|
| Contrato y reproducibilidad del esquema | **PARTIAL** | Hay 13 tablas versionadas (`../parentalControl/supabase/migrations/001_initial_schema.sql:24-226`; `../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:15-63`), pero `children` está reportada solo en vivo y los contratos Windows agregan superficies no migradas (`apis.md:59-75`, `apis.md:137-159`). | No verificada. |
| Pairing, sesión y uso único | **PARTIAL** | Windows crea una sesión anónima (`src/ControlParental.Service/DeviceAuthenticator.cs:196-254`) y luego llama pairing (`src/ControlParental.Service/PairingService.cs:70-105`). El backend crea o busca otro usuario y escribe allí `device_id` (`../parentalControl/supabase/functions/pairing/index.ts:65-125`), no devuelve sesión (`../parentalControl/supabase/functions/pairing/index.ts:151-159`) y deja el código `ACTIVE` aunque marca `used_at` (`../parentalControl/supabase/functions/pairing/index.ts:135-142`). Windows persiste el `device_id`, pero no refresca el JWT antes de pedir policy (`src/ControlParental.Service/PairingService.cs:163-200`). | No verificada; Anonymous Sign-Ins y hook habilitado son desconocidos. |
| Custom access token hook | **PARTIAL** | Existe, pero tiene import inexistente (`../parentalControl/supabase/functions/custom-access-token-hook/index.ts:8`) y contrato de evento/salida desalineado (`../parentalControl/supabase/functions/custom-access-token-hook/index.ts:10-55`). | Hook HTTP, secreto, activación y ejecución no verificados. |
| RLS | **COMPLETE FOUNDATION** | RLS se habilita en las once tablas iniciales (`../parentalControl/supabase/migrations/002_rls_policies.sql:7-17`) y usa `device_id` para varias políticas, por ejemplo `usage_logs` (`../parentalControl/supabase/migrations/002_rls_policies.sql:174-193`). `behavioral_events` habilita RLS pero solo define SELECT de padre (`../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:83-99`), por lo que un device no puede hacer el INSERT directo propuesto. | Migraciones aplicadas y claims efectivos no verificados. |
| Obtención de policy | **PARTIAL** | El backend ofrece Edge Function y RPC con argumento `target_device_id` (`../parentalControl/supabase/functions/get-policy/index.ts:45-48`; `../parentalControl/supabase/migrations/001_initial_schema.sql:321-404`). Windows llama directamente `/rest/v1/rpc/get_device_policy`, envía `p_device_id` y espera `{ version, policyJson }` (`src/ControlParental.Service/BackendClient.cs:60-90`, `src/ControlParental.Service/BackendClient.cs:494-501`), forma que no coincide con el JSON devuelto por la función SQL. Además, el sync periódico usa literalmente `default` (`src/ControlParental.Service/ScheduledWorkService.cs:589-605`). | No verificada. |
| Heartbeat | **PARTIAL** | Existe una Edge Function que inserta `device_heartbeats` y actualiza `last_seen_at` (`../parentalControl/supabase/functions/heartbeat/index.ts:66-90`). Windows llama `/rest/v1/rpc/heartbeat` con nombres diferentes (`src/ControlParental.Service/BackendClient.cs:253-286`); no hay RPC `heartbeat` en las migraciones. | No verificada. |
| `usage_logs` | **PARTIAL** | La tabla exige `device_id`, `package_name`, `bucket_date`, `usage_minutes` y unicidad por esos campos (`../parentalControl/supabase/migrations/001_initial_schema.sql:104-114`). Windows envía `app_id`, `minutes`, `server_date`, `dedup_key` sin `device_id` (`src/ControlParental.Service/BackendClient.cs:106-142`). | No verificada. |
| `time_requests` y atomicidad de aprobación | **PARTIAL** | La tabla existe (`../parentalControl/supabase/migrations/001_initial_schema.sql:64-75`), pero Windows envía `request_id`, `minutes` y omite `device_id`/`minutes_requested` (`src/ControlParental.Service/BackendClient.cs:342-368`). `approve-request` actualiza la solicitud y después inserta el grant en dos operaciones (`../parentalControl/supabase/functions/approve-request/index.ts:174-205`); no hay transacción/RPC atómica y `grants.request_id` no es UNIQUE (`../parentalControl/supabase/migrations/001_initial_schema.sql:84-99`). | No verificada. |
| `behavioral_events` | **PARTIAL** | La tabla existe (`../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:53-63`), pero no hay policy INSERT del agente (`../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:83-99`). Windows envía `timestamp`, `metadata` y `dedup_key`, mientras el esquema exige `client_ts` y usa `props`; tampoco hay constraint para `dedup_key` (`src/ControlParental.Service/BackendClient.cs:204-240`). | No verificada. |
| Alertas | **PARTIAL** | El backend ya genera alertas de reloj e integridad en `outbox` (`../parentalControl/supabase/functions/heartbeat/index.ts:92-106`; `../parentalControl/supabase/functions/verify-integrity/index.ts:57-80`). Windows intenta escribir `/rest/v1/device_alerts` (`src/ControlParental.Service/BackendClient.cs:155-191`), tabla ausente de migraciones y del inventario en vivo informado. Existe propuesta SQL, no contrato canónico aplicado (`docs/handoff/backend-team-handoff-2026-07-22.md:156-196`). | No verificada. |
| WNS | **MISSING FOR WINDOWS** | Windows crea un canal en UI (`src/ControlParental.App.UI/WnsPushNotificationHandler.cs:69-125`), pero lo registra con la clave pública y sin JWT de sesión (`src/ControlParental.App.UI/WnsPushNotificationHandler.cs:168-202`); además, `OnPushReceived` no llama al método que dispara sync (`src/ControlParental.App.UI/WnsPushNotificationHandler.cs:148-163`, `src/ControlParental.App.UI/WnsPushNotificationHandler.cs:210-237`). El servicio Windows también intenta guardar credenciales de envío y solicitar un canal (`src/ControlParental.Service/Program.cs:383-396`; `src/ControlParental.Service/WnsNotificationService.cs:109-193`), dirección contraria al modelo oficial actual. No hay función WNS en el backend versionado. | Configuración Entra/PFN, canal, almacenamiento y envío no verificados. |
| Integridad Windows | **MISSING FOR WINDOWS** | Windows calcula firma/hash y reporta (`src/ControlParental.Service/IntegrityChecker.cs:86-107`; `src/ControlParental.Service/AntiTamperMonitor.cs:373-418`), pero llama una tabla no migrada y espera un veredicto (`src/ControlParental.Service/BackendClient.cs:372-414`). La única función backend de integridad implementa otro proveedor y escribe en `outbox` (`../parentalControl/supabase/functions/verify-integrity/index.ts:40-87`); no valida evidencia Windows. El handler queda en shadow mode por defecto (`src/ControlParental.Service/IntegrityVerdictHandler.cs:85-107`). | No verificada. |
| Idempotencia y errores | **PARTIAL** | La outbox local tiene índice único por `dedup_key` (`src/ControlParental.Service/ControlParentalDbContext.cs:98-112`) y reintentos/backoff (`src/ControlParental.Service/ScheduledWorkService.cs:365-539`). Sin embargo, los payloads remotos no coinciden, varias tablas no tienen clave remota equivalente y, si falla un grupo, se incrementan intentos de todas las entradas pendientes (`src/ControlParental.Service/ScheduledWorkService.cs:526-533`). Pairing tampoco es de uso único. | Comportamiento real no verificado. |
| Staging y E2E | **MISSING FOR WINDOWS** | Las pruebas backend encontradas usan mocks, no un proyecto vivo (`../parentalControl/supabase/functions/get-devices-for-parent/index_test.ts:3-14`; `../parentalControl/supabase/functions/approve-request/index_test.ts:10-13`). No existe evidencia versionada de un recorrido staging Windows que cubra autenticación, pairing, policy, telemetría, aprobación, WNS e integridad. | No verificada. |

## Dos direcciones distintas

**WNS: backend → Windows.** Windows solicita a WNS un *Channel URI* (una dirección temporal para ese cliente) y lo registra en el backend. Cuando cambia una policy o se aprueba tiempo, el backend obtiene credenciales de servidor, envía una notificación a ese canal y Windows vuelve a leer datos autenticados. El secreto usado para enviar no debe residir en el cliente Windows.

**Telemetría y alertas: Windows → backend.** Windows produce usage, eventos, heartbeat, solicitudes y evidencia de integridad. Esos datos salen por HTTPS hacia Supabase. WNS no reemplaza esta subida ni sirve para transportar telemetría al backend.

En forma compacta:

```text
Policy/grant cambia: backend -> WNS -> Windows -> GET policy autenticado
Uso/alerta/integridad: Windows -> REST, Edge Function o RPC -> backend
```

## Mapa de contrato recomendado

| Capacidad | Ruta recomendada | Regla de diseño |
|---|---|---|
| Sesión del agente | Supabase Anonymous Sign-In | Crear una sesión para el agente, conservar refresh token y distinguir usuario anónimo mediante claims/RLS. |
| Pairing | Reutilizar y corregir `/functions/v1/pairing` | Operar sobre el usuario autenticado que llamó, consumir el código una sola vez y forzar/entregar una sesión refrescada con `device_id`. |
| Claims del dispositivo | Custom Access Token Hook oficial | Aceptar `{ user_id, claims, authentication_method }`, verificar el webhook HTTP y devolver `{ claims }`. |
| Policy | Reutilizar `/functions/v1/get-policy` o publicar una RPC canónica | Elegir una sola ruta y un solo DTO; no mantener Edge Function, RPC y cliente con formas distintas. |
| Heartbeat | Reutilizar `/functions/v1/heartbeat` | Alinear nombres y respuesta; autenticar antes de usar acceso privilegiado. |
| Usage, eventos y solicitudes simples | Data REST API + RLS | INSERT/UPSERT directo cuando solo se almacena una fila y `WITH CHECK` puede asegurar que `device_id` coincide con el claim. |
| Aprobación y grant | RPC transaccional o Edge Function que llame una RPC | Actualizar solicitud y crear un único grant en una transacción, con constraint de idempotencia. |
| Alertas | Tabla versionada + REST/RLS, o reutilizar `outbox` con contrato explícito | Elegir una representación canónica; no sostener dos modelos implícitos. |
| Integridad Windows | Edge Function o RPC de veredicto | Validar evidencia, aplicar reglas y devolver `{ verdict: "trust" | "revoked" | "unknown" }`; una tabla directa puede conservar evidencia, pero no sustituye el cálculo. |
| WNS | Canal creado en Windows; envío desde backend | Registrar/renovar el Channel URI autenticado. Mantener Tenant/App ID/secret y token de envío únicamente en infraestructura server-side. |
| Reintentos | Outbox local + claves únicas remotas | Cada evento lleva una identidad estable; un retry debe producir el mismo resultado sin duplicar filas ni grants. |

## Plan de acción ordenado

### Equipo Backend

- [ ] Publicar un inventario reproducible del esquema desplegado y llevar `children` y cualquier otra deriva a migraciones versionadas.
- [ ] Congelar contratos canónicos por ruta: request, response, errores, autenticación, idempotency key y propietario del `device_id`.
- [ ] Corregir pairing para usar la identidad anónima llamante, consumir el código atómicamente y completar el refresh de JWT con `device_id`.
- [ ] Reimplementar el custom access token hook con el contrato HTTP oficial `{ user_id, claims, authentication_method } -> { claims }` y versionar su configuración segura.
- [ ] Confirmar y probar `verify_jwt = true` para funciones de usuario; usar contexto autenticado/RLS y reservar `service_role` para operaciones privilegiadas justificadas.
- [ ] Elegir una ruta canónica para policy y otra para heartbeat, reutilizando las Edge Functions existentes cuando corresponda.
- [ ] Alinear esquema y RLS de `usage_logs`, `behavioral_events` y `time_requests`; agregar constraints de idempotencia donde el cliente reintenta.
- [ ] Hacer aprobación + grant atómicos mediante RPC/transacción y garantizar un solo grant por request.
- [ ] Definir el modelo canónico de alertas: tabla versionada o `outbox`, con DTO y lectura por padre explícitos.
- [ ] Implementar la validación/veredicto de integridad Windows en Edge Function o RPC, separando evidencia almacenada de decisión calculada.
- [ ] Implementar registro autenticado de canales WNS y envío server-side con credenciales protegidas; gestionar renovación y respuestas de canal expirado.
- [ ] Desplegar un entorno staging y automatizar el recorrido de aceptación completo.

### Equipo Windows

- [ ] Consumir únicamente los DTO y rutas canónicos publicados por Backend; eliminar nombres alternativos como `p_device_id`, `default` y campos que no existen en esquema.
- [ ] Tras pairing, refrescar la sesión y comprobar que el JWT nuevo contiene el `device_id` asignado antes de cualquier policy o escritura.
- [ ] Incluir el identificador estable y la clave de idempotencia exigidos en usage, eventos, solicitudes y alertas.
- [ ] Mantener la outbox y backoff, pero confirmar cada entrada por separado para no penalizar eventos ya enviados.
- [ ] Crear/renovar el Channel URI en Windows, registrarlo con el JWT del dispositivo y hacer que la recepción raw dispare realmente un fetch de policy.
- [ ] Retirar del cliente las credenciales y la lógica de envío WNS; deben permanecer server-side.
- [ ] Enviar evidencia de integridad al endpoint de veredicto acordado y salir de shadow mode solo después de calibración y prueba de falsos positivos.
- [ ] Ejecutar el recorrido completo contra staging, incluyendo desconexión y reintento.

## Checklist de aceptación E2E en staging

El backend estará listo para Windows cuando una ejecución trazable complete, en este orden, todos los puntos:

- [ ] Se crea una sesión anónima y se obtiene un refresh token válido.
- [ ] Un código vigente empareja el dispositivo exactamente una vez; una segunda llamada no crea otro device.
- [ ] Se refresca el JWT y el nuevo token contiene el `device_id` asignado.
- [ ] El dispositivo obtiene su policy y no puede obtener la de otro device.
- [ ] El heartbeat actualiza `last_seen_at` y conserva el contrato acordado.
- [ ] Se suben usage, un behavioral event y una time request con RLS y DTO correctos.
- [ ] La aprobación cambia la request y crea exactamente un grant de forma atómica.
- [ ] El cambio de grant provoca un WNS raw de sync y Windows vuelve a obtener la policy actualizada.
- [ ] Windows envía evidencia de integridad y recibe un veredicto calculado y auditable.
- [ ] Se simula pérdida de red; al volver la conexión, la outbox reintenta sin duplicar usage, eventos, requests, grants, alertas ni reportes.
- [ ] Se comprueba aislamiento entre dos dispositivos y entre dos padres.
- [ ] Los logs de staging permiten correlacionar sesión, `device_id`, request, grant, push y dedup key sin exponer secretos.

## Fuentes oficiales

### Microsoft Learn

- [Windows Push Notification Services (WNS) overview](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/wns-overview): el cliente solicita el canal, lo registra en su servicio y el servicio envía hacia Windows.
- [Quickstart: Push notifications in the Windows App SDK](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/push-notifications/push-quickstart): identidad Entra, creación del Channel URI en la aplicación, renovación por lanzamiento y credenciales de envío.
- [WinVerifyTrust function](https://learn.microsoft.com/en-us/windows/win32/api/wintrust/nf-wintrust-winverifytrust): verifica una acción de confianza local; cero es el único resultado exitoso. No define un contrato de attestation o veredicto cloud.

### Supabase

- [Custom Access Token Hook](https://supabase.com/docs/guides/auth/auth-hooks/custom-access-token-hook): entrada, claims obligatorios, salida `{ claims }` y soporte para HTTP hooks firmados.
- [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous): usuarios anónimos autenticados, claim `is_anonymous`, RLS y límites operativos.
- [Securing Edge Functions](https://supabase.com/docs/guides/functions/auth): `verify_jwt = true`, contexto autenticado y separación entre cliente RLS y cliente administrativo.
- [Data REST API](https://supabase.com/docs/guides/api): REST autogenerado desde el esquema y protegido mediante el modelo de seguridad de Postgres/RLS.
- [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security): RLS en esquemas expuestos, `WITH CHECK` para INSERT y prohibición de exponer claves que omiten RLS.

## Apéndice de evidencia del proyecto

| Tema | Evidencia principal |
|---|---|
| Esquema canónico | `../parentalControl/supabase/migrations/001_initial_schema.sql:24-226`; `../parentalControl/supabase/migrations/004_parent_outcome_checkins.sql:15-99` |
| RLS y claim `device_id` | `../parentalControl/supabase/migrations/002_rls_policies.sql:7-17`, `002_rls_policies.sql:174-215`, `002_rls_policies.sql:315-316` |
| Pairing backend | `../parentalControl/supabase/functions/pairing/index.ts:35-159` |
| Hook actual | `../parentalControl/supabase/functions/custom-access-token-hook/index.ts:8-55` |
| Policy y heartbeat backend | `../parentalControl/supabase/functions/get-policy/index.ts:18-78`; `../parentalControl/supabase/functions/heartbeat/index.ts:18-115` |
| Aprobación | `../parentalControl/supabase/functions/approve-request/index.ts:152-235` |
| Cliente REST Windows | `src/ControlParental.Service/BackendClient.cs:60-463` |
| Sesión y pairing Windows | `src/ControlParental.Service/DeviceAuthenticator.cs:196-368`; `src/ControlParental.Service/PairingService.cs:70-200` |
| Outbox y retry Windows | `src/ControlParental.Service/OutboxManager.cs:30-118`; `src/ControlParental.Service/ScheduledWorkService.cs:365-539` |
| WNS Windows | `src/ControlParental.App.UI/WnsPushNotificationHandler.cs:69-237`; `src/ControlParental.Service/WnsNotificationService.cs:109-193` |
| Integridad Windows | `src/ControlParental.Service/IntegrityChecker.cs:46-115`; `src/ControlParental.Service/AntiTamperMonitor.cs:373-418` |
| Propuestas, no backend canónico | `apis.md:59-75`, `apis.md:137-159`; `docs/handoff/backend-team-handoff-2026-07-22.md:83-238` |
| Alcance de pruebas backend | `../parentalControl/supabase/functions/get-devices-for-parent/index_test.ts:3-14`; `../parentalControl/supabase/functions/approve-request/index_test.ts:1-13` |

## Declaración de cambios

Esta tarea creó únicamente `ControlParental/docs/backend-windows-readiness-report.md`. **No se modificó código, pruebas, configuración ni documentación existente.** El repositorio `parentalControl` permaneció en modo de solo lectura durante toda la revisión.
