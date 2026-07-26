# Certificate Pinning - Plan de Rotación

## Resumen

Este documento describe el plan de rotación de certificados para el certificate pinning de la app ParentalControl.

## Configuración Actual

```kotlin
// Dominio de Supabase
val supabaseHost = "your-project.supabase.co"

// Pines actuales (reemplazar con valores reales)
val PIN_PRIMARY = "sha256/..."
val PIN_SECONDARY = "sha256/..."
val PIN_BACKUP_CA = "sha256/..."
```

## Obtención de Pines

### Método 1: Usando OpenSSL

```bash
# Obtener el fingerprint SHA-256 del certificado
openssl s_client -servername TU_PROYECTO.supabase.co \
  -connect TU_PROYECTO.supabase.co:443 \
  </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256
```

### Método 2: Dashboard de Supabase

1. Ve a https://supabase.com/dashboard
2. Selecciona tu proyecto
3. Ve a Settings > API
4. Busca "Certificate SSL Pin" o la sección de configuración SSL

### Método 3: Certificado del navegador

1. Abre https://TU_PROYECTO.supabase.co en Chrome
2. Click en el candado → Conexión segura → Mostrar detalles del certificado
3. Ve a Detalles → Huella digital SHA-256

## Plan de Rotación (90 días)

### Día 0: Preparación
1. Generar nuevo certificado del servidor con CA válida
2. Calcular nuevo pin SHA-256 del certificado
3. **NO hacer cambios aún**

### Día 1-30: Fase de Backup
1. Actualizar `PIN_SECONDARY` con el nuevo pin
2. Desplegar actualización de la app
3. Monitorizar: todos los dispositivos deben actualizar

```kotlin
// Después de actualizar pines
const val PIN_PRIMARY = "sha256/CERTIFICADO_ACTUAL..."    // Sin cambios
const val PIN_SECONDARY = "sha256/NUEVO_CERTIFICADO..."  // ← Nuevo valor
```

### Día 30-60: Transición
1. Verificar que no hay errores de pinning en producción
2. Actualizar `PIN_PRIMARY` con el nuevo pin
3. Desplejar actualización de la app

```kotlin
// Después de transición
const val PIN_PRIMARY = "sha256/NUEVO_CERTIFICADO..."    // ← Nuevo valor
const val PIN_SECONDARY = "sha256/NUEVO_CERTIFICADO..."  // Mismo valor
```

### Día 60-90: Limpieza
1. Esperar a que todos los dispositivos tengan la nueva versión
2. Mantener `PIN_SECONDARY` como backup
3. El siguiente ciclo comienza en el día 90

### Día 90+: Ciclo completo
1. Si hay nuevo certificado, repetir el proceso
2. Remover `PIN_SECONDARY` antiguo si ya no es necesario

## Comando para verificar pines en producción

```bash
# Verificar que el certificado actual coincide con el pin
openssl s_client -servername TU_PROYECTO.supabase.co \
  -connect TU_PROYECTO.supabase.co:443 2>/dev/null | \
  openssl x509 -noout -fingerprint -sha256
```

## Manejo de Emergencia

### Si el certificado expira inesperadamente:
1. Usar `PIN_SECONDARY` si estaba configurado
2. Desplegar actualización con pines actualizados
3. Notificar a usuarios de actualización obligatoria

### Si hay breach de certificado:
1. Inmediatamente cambiar todos los pines
2. Desplegar actualización de emergencia (hotfix)
3. Invalidar el certificado comprometido

## Verificación de Implementación

### Test de Pin Correcto
```kotlin
// El certificado válido pasa el pinning
val certificate = getServerCertificate()
val isValid = validateCertificateChain(listOf(certificate))
assertTrue(isValid)  // ✓ Pasa
```

### Test de Pin Incorrecto (MITM)
```kotlin
// Un certificado falso debería fallar
val fakeCertificate = createFakeCertificate()
val isRejected = validateCertificateChain(listOf(fakeCertificate))
assertFalse(isRejected)  // ✓ Rechazado
```

## Monitoreo

### Logs esperados en producción:
```
CertificatePinning: Pin match for host: your-project.supabase.co
```

### Alertas a configurar:
- CertificatePinningException → Notificar a DevOps
- Aumento de errores de red → Investigar posibles problemas de TLS

## Referencias

- [OkHttp CertificatePinner](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/)
- [OWASP Certificate Pinning](https://cheatsheetseries.owasp.org/cheatsheets/Pinning_Cheat_Sheet.html)
- [Supabase SSL Configuration](https://supabase.com/docs/guides/database/ssl-connections)
