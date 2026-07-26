# §0.6 Pre-Submission Checklist

Este checklist debe completarse antes de cada envío a Google Play.

## Disclosure & Transparency

- [ ] **Disclosure visible**: La pantalla de divulgación es visible antes de solicitar permisos
- [ ] **Describe monitoreo**: El texto describe qué se monitorea (uso de apps) y qué NO se monitorea (contenido)
- [ ] **Transparencia accesible**: La pantalla "qué se monitorea" está accesible desde configuración
- [ ] **Link a privacidad**: Hay link a la política de privacidad

## Consent

- [ ] **Consentimiento requerido**: El consentimiento del padre es requerido antes de monitorear
- [ ] **Consentimiento encriptado**: El consentimiento se guarda en EncryptedSharedPreferences
- [ ] **Opción de retirar**: El usuario puede retirar el consentimiento desinstalando la app

## Data Minimization

- [ ] **Sin contenido del menor**: No se captura mensajes, fotos, videos, ubicación o contactos
- [ ] **Solo uso de apps**: Solo se registra tiempo de uso (no contenido)
- [ ] **Analytics anonimizado**: Los eventos de analytics no contienen PII del menor

## Security

- [ ] **Consentimiento encriptado**: Datos sensibles en EncryptedSharedPreferences
- [ ] **TLS 1.3**: Conexiones usan TLS 1.3 (API 30+)
- [ ] **Certificate pinning**: Certificate pinning activo
- [ ] **RLS**: Row Level Security habilitado en todas las tablas de Supabase

## Families Compliance

- [ ] **isAccessibilityTool=false**: Configurado en accessibility_service_config.xml
- [ ] **Sin cuentas de menores**: Los menores no pueden crear cuentas
- [ ] **Padres supervisan**: Todo el monitoreo es configurado y supervisado por los padres

## Google Play Requirements

### Permission Declaration Form
- [ ] **SYSTEM_ALERT_WINDOW**: Formulario completado con justificación clara
- [ ] **PACKAGE_USAGE_STATS**: Formulario completado con descripción del uso

### Data Safety
- [ ] **Datos correctos**: El reporte en Play Console coincide con las prácticas reales
- [ ] **Sin datos de niños**: Data Safety indica que no se recopilan datos de niños
- [ ] **Encriptación**: Indicado que se usa encriptación

### Video
- [ ] **Video preparado**: Video del flujo de la app preparado
- [ ] **Muestra divulgación**: El video muestra la divulgación prominente
- [ ] **Duración adecuada**: Video entre 30-60 segundos

### Policy
- [ ] **Privacidad publicada**: Política de privacidad accesible públicamente
- [ ] **URL válida**: El link en Play Store apunta a la política correcta
- [ ] **Datos completos**: La política incluye todos los puntos requeridos

## Code Quality

- [ ] **Build pasa**: `./gradlew assembleDebug` succeeds
- [ ] **Lint limpio**: `./gradlew lint` no tiene errores críticos
- [ ] **Tests pasan**: `./gradlew test` y `./gradlew connectedAndroidTest` pasan
- [ ] **ProGuard**: Rules configuradas para R8

## Version Requirements

- [ ] **VersionCode**: Incrementado desde el release anterior
- [ ] **VersionName**: Siguiente versión semántica
- [ ] **Min SDK**: API 26 (Android 8.0) o superior
- [ ] **Target SDK**: API 35 (Android 15)

## Device Owner (si aplica)

- [ ] **OOBE documentado**: Guía de provisioning QR/NFC/zero-touch disponible
- [ ] **Alternativa STD**: El modo estándar (sin DO) funciona correctamente
- [ ] **Advertido**: El padre es advertído sobre la limitación de VPN única

## DNS Filter (si aplica)

- [ ] **VPN advertida**: Limitación de VPN única comunicada al usuario
- [ ] **No monetiza**: No hay monetización del tráfico DNS
- [ ] **Data Safety**: Declarado en Data Safety como "local only"

---

## Firmas

| Rol | Nombre | Fecha |
|-----|--------|-------|
| Developer | | |
| QA | | |
| Product Owner | | |

## Notas de Release

_(Agregar notas de release específicas para esta versión)_
