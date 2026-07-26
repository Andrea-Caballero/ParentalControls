# Matriz de Compatibilidad Android 8-15

## Overview

Este documento lista la compatibilidad de features del ParentalControl con cada versión de Android desde API 26 (Android 8.0) hasta API 35 (Android 15).

## Matriz de Compatibilidad

| Feature | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 |
|---------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **Core** |
| Overlay de bloqueo | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Usage Stats | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Foreground Service | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Notifications | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Security** |
| Device Admin | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Device Owner | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Accessibility Service | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| VPN Service | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Provisioning** |
| QR Provisioning | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| NFC Provisioning | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Zero-Touch | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Network** |
| TLS 1.3 | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Certificate Pinning | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Safety** |
| Play Integrity | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R8 Obfuscation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Battery** |
| Doze Mode | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| App Standby | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Battery Optimization | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Push** |
| FCM High Priority | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Sync** |
| REST Sync | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Realtime (Foreground) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| WorkManager | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

## Leyenda

| Símbolo | Significado |
|---------|-------------|
| ✅ | Full Support - Funcionalidad completa y soportada |
| ⚠️ | Limited - Funcionalidad parcial o con limitaciones conocidas |
| ❌ | Not Supported - Funcionalidad no disponible |

## Notas por Feature

### Overlay de Bloqueo (SYSTEM_ALERT_WINDOW)
- **API 26+**: Requiere permiso `SYSTEM_ALERT_WINDOW`
- **API 28+**: Settings accesible directamente
- **API 33+**: Mejoras en manejo de permisos

### Usage Stats (PACKAGE_USAGE_STATS)
- **API 26-27**: Requiere ser app de "Usage Access" default
- **API 28+**: Flujo de permisos mejorado
- **API 31+**: Mejoras en precisión del conteo

### Doze Mode
- **API 26-27**: Doze básico, conteo puede ser impreciso
- **API 28+**: App Standby buckets introducidos
- **API 31+**: Better Doze con detección de movimiento
- **API 33+**: Idle toast improvements
- **API 35+**: Advanced Doze con ML

### TLS 1.3
- **API 26-29**: TLS 1.2 máximo
- **API 30+**: TLS 1.3 habilitado por defecto

### Play Integrity
- **API 26-30**: IntegrityTokenProvider no disponible
- **API 31+**: Play Integrity API completa disponible

### QR Provisioning
- **API 26-27**: No disponible
- **API 28+**: QR provisioning introducido

## Testing Strategy

| API Level | Dispositivo de Test | Coverage Target |
|-----------|---------------------|-----------------|
| 28 | Pixel 3 / Emulator | Critical flows |
| 31 | Pixel 6 / Emulator | Full coverage |
| 35 | Pixel 8 / Emulator | Latest features |

## Known Limitations

### API 26-27
- Conteo de tiempo puede ser impreciso en Doze
- QR provisioning no disponible
- TLS 1.3 no disponible

### API 28-30
- Sin Play Integrity (verificar graceful degradation)
- Sin TLS 1.3

### API 31-35
- Soporte completo de todas las features
- Testing en emulador suficiente para la mayoría de casos

## Minimum SDK

**Recomendado**: API 28 (Android 9.0)
- QR provisioning disponible
- Mejores APIs de battery
- Flujo de permisos mejorado

**Mínimo posible**: API 26 (Android 8.0)
- Features limitados
- Testing manual recomendado
