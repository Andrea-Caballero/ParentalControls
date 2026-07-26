# ParentalControl

Control parental para Android con arquitectura offline-first, enforcement robusto, y cumplimiento de privacidad.

## Características

- **Motor de reglas** (T02): Evaluación de políticas con 12 pasos de precedencia
- **Offline-first** (T18): Room + outbox + sync
- **Hard/Soft enforcement** (T31): Device Owner para máximo control
- **Anti-tampering** (T13): Detección de evasión y manipulación
- **Analytics** (T32): Eventos conductuales sin contenido del menor
- **Cumplimiento** (T34): Google Play compliant

## Arquitectura

```
┌─────────────────────────────────────────────────────────────────────┐
│  PARENTAL CONTROL ARCHITECTURE                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐          │
│  │ Child App   │     │ Parent App  │     │ Backend     │          │
│  │ (Kotlin)   │     │ (Compose)   │     │ (Supabase)  │          │
│  └──────┬──────┘     └──────┬──────┘     └──────┬──────┘          │
│         │                   │                   │                   │
│         │    ┌─────────────┘                   │                   │
│         │    │                                 │                   │
│         ▼    ▼                                 ▼                   │
│  ┌─────────────────────────────────────────────────────┐          │
│  │                    RULES ENGINE                      │          │
│  │                 (T02 - 12 pasos)                    │          │
│  └─────────────────────────────────────────────────────┘          │
│                              │                                     │
│         ┌───────────────────┼───────────────────┐                  │
│         ▼                   ▼                   ▼                  │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐          │
│  │ Overlay     │     │ LockNow     │     │ Suspend/    │          │
│  │ (T08)      │     │ (T09)       │     │ Hide (DO)  │          │
│  └─────────────┘     └─────────────┘     └─────────────┘          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Módulos

| Módulo | Descripción |
|--------|-------------|
| `domain/` | Motor de reglas, modelos de negocio |
| `data/` | Room, repositories, sync |
| `enforcement/` | EnforcementController, overlay, lock |
| `security/` | TamperDetector, Integrity |
| `ui/` | Compose UI para padre e hijo |
| `analytics/` | Tracking de eventos |
| `push/` | FCM despertador |
| `realtime/` | WebSocket para UI |
| `sync/` | REST sync offline-first |

## Stack Tecnológico

- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt
- **Datos**: Room (KSP), DataStore
- **Network**: Ktor + OkHttp
- **Backend**: Supabase (Edge Functions, RLS, Realtime)
- **Seguridad**: Tink, Play Integrity, TLS 1.3

## Seguridad

- **Consentimiento encriptado**: EncryptedSharedPreferences
- **TLS 1.3**: Certificate pinning con rotación
- **RLS**: Row Level Security en Supabase
- **Play Integrity**: Verificación server-side
- **Anti-tampering**: Detección de evasión

## Privacidad (§0.6)

- Sin contenido del menor
- Solo métricas de uso de apps
- Consentimiento requerido
- Datos encriptados
- Sin monetización

## Setup

### Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- Java 17
- Gradle 8.x
- Android SDK 35

### Configuración

1. **Clonar el repositorio**
   ```bash
   git clone <repository-url>
   cd ParentalControl
   ```

2. **Agregar google-services.json**
   - Descarga desde Firebase Console
   - Coloca en `app/google-services.json`
   - ⚠️ **No versionar este archivo**

3. **Configurar Supabase** (opcional para desarrollo)
   - Crea un proyecto en [Supabase](https://supabase.com)
   - Ejecuta las migraciones en `supabase/migrations/`
   - Actualiza las constantes en `app/src/main/java/.../network/SupabaseClientProvider.kt`

4. **Build**
   ```bash
   ./gradlew assembleDebug
   ```

### Secretos

⚠️ **No versionar secretos**

Los siguientes archivos **NO deben** subirse al repositorio:

| Archivo | Razón |
|---------|-------|
| `app/google-services.json` | Credenciales de Firebase |
| `*.keystore` | Firmas de release |
| `local.properties` | Paths locales |
| `gradle.properties` (con secrets) | Credenciales de build |

El `.gitignore` ya está configurado para excluir estos archivos.

## Testing

```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# ktlint
./gradlew ktlintCheck

# detekt
./gradlew detekt

# Full suite
./gradlew clean test lint detekt ktlintCheck assembleDebug
```

## CI/CD

El proyecto incluye GitHub Actions en `.github/workflows/android-ci.yml`:

1. Unit tests
2. Instrumented tests
3. Lint
4. Build

## Contribuir

1. Fork el repositorio
2. Crea una branch (`git checkout -b feature/tu-feature`)
3. Commit tus cambios (`git commit -am 'Agrega feature'`)
4. Push a la branch (`git push origin feature/tu-feature`)
5. Crea un Pull Request

## Licencia

[MIT License](LICENSE)

## Contratos

| Contrato | Descripción |
|----------|-------------|
| §0.1 | FCM como señal, no datos |
| §0.2 | Niveles de enforcement |
| §0.3 | Formato de políticas |
| §0.4 | Motor de reglas |
| §0.5 | Schema de base de datos |
| §0.6 | Privacidad y transparencia |
| §0.7 | Compatibilidad Android |
| §0.8 | Restricciones de implementación |
| §0.9 | Requisitos de seguridad y compliance |
