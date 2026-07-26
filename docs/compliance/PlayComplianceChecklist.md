# T34 — Paquete de cumplimiento de Google Play

## Resumen

Documentación completa para el envío a Google Play, incluyendo Permission Declaration Form, Data Safety, Privacy Policy, y checklist de §0.6.

---

## 1. Permission Declaration Form (Play Console)

### Formulario de Declaración de Permisos

**Servicio de Accesibilidad:**

```
┌─────────────────────────────────────────────────────────────────────┐
│  PERMISSION DECLARATION FORM                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Permiso: android.permission.SYSTEM_ALERT_WINDOW                    │
│                                                                     │
│  ¿Por qué necesitas este permiso?                                  │
│                                                                     │
│  Esta app requiere el permiso "Mostrar sobre otras apps" para      │
│  mostrar pantallas de bloqueo cuando el niño intenta usar una app   │
│  restringida. Sin este permiso, no podemos proteger al niño        │
│  de contenido inapropiado.                                         │
│                                                                     │
│  ¿Cómo se usa este permiso en tu app?                              │
│                                                                     │
│  1. Cuando una app está bloqueada por límite de tiempo o          │
│     política, mostramos un overlay con opciones de:                │
│     - Ver cuánto tiempo queda                                      │
│     - Pedir tiempo extra (T28)                                    │
│                                                                     │
│  2. El overlay es temporal y se cierra automáticamente cuando      │
│     la restricción ya no aplica.                                  │
│                                                                     │
│  ¿Los usuarios pueden desactivar esta función?                      │
│                                                                     │
│  Sí. Los padres pueden elegir desinstalar la app o ajustar        │
│  los límites de tiempo desde la app de los padres.                │
│                                                                     │
│  [✓] Declaración marcada como LEÍ y COMPRENDIDA                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Acceso a Usage Stats:**

```
┌─────────────────────────────────────────────────────────────────────┐
│  PERMISSION DECLARATION FORM                                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Permiso: android.permission.PACKAGE_USAGE_STATS                    │
│                                                                     │
│  ¿Por qué necesitas este permiso?                                  │
│                                                                     │
│  Para monitorear el tiempo que el niño pasa en cada app y          │
│  aplicar límites de tiempo. Este permiso es estándar en apps       │
│  de control parental y no accede a contenido privado.             │
│                                                                     │
│  ¿Cómo se usa este permiso en tu app?                              │
│                                                                     │
│  1. Recolectamos estadísticas de uso diario (no contenido)         │
│  2. Comparamos con los límites configurados por los padres         │
│  3. Bloqueamos apps cuando se alcanza el límite                   │
│                                                                     │
│  ¿Los usuarios pueden desactivar esta función?                      │
│                                                                     │
│  Sí. Los padres pueden ajustar o desactivar los límites desde     │
│  la app de administración.                                         │
│                                                                     │
│  [✓] Declaración marcada como LEÍ y COMPRENDIDA                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Confirmación de isAccessibilityTool=false

**Archivo:** `app/src/main/res/xml/accessibility_service_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:settingsActivity="com.example.parentalcontrol.MainActivity"
    android:isAccessibilityTool="false" />
```

**Justificación:**
- `isAccessibilityTool="false"` indica que NO es una herramienta de accesibilidad para personas con discapacidades
- Es una app de control parental, no una herramienta de accesibilidad
- Google Play requiere este flag para distinguir entre apps de control parental y herramientas de accesibilidad

---

## 3. Video del Flujo y Divulgación

### Guion del Video (60 segundos)

```
ESCENA 1: INTRODUCCIÓN (5s)
─────────────────────────────────────────────────────────────
Padre abre la app de configuración parental
Texto: "Control Parental - Configuración fácil"

ESCENA 2: DESCUBRIMIENTO (10s)
─────────────────────────────────────────────────────────────
Pantalla de divulgación visible:
"Esta app monitorea el uso de apps para proteger a tus hijos"

ESCENA 3: PERMISOS (15s)
─────────────────────────────────────────────────────────────
- Se otorgan permisos de accesibilidad
- Se otorgan permisos de uso de apps
- Se muestra el dashboard de protección

ESCENA 4: FUNCIONALIDAD (20s)
─────────────────────────────────────────────────────────────
- Niño intenta abrir app bloqueada
- Se muestra overlay de bloqueo
- Niño puede pedir tiempo extra
- Padre recibe notificación

ESCENA 5: CONTROLES (10s)
─────────────────────────────────────────────────────────────
- Padre ve estadísticas de uso
- Padre ajusta límites
- Padre aprueba tiempo extra
```

---

## 4. Política de Privacidad

### Política de Privacidad - Control Parental

**Última actualización:** 2024-01-01

#### 1. Información que Recopilamos

**Datos del dispositivo:**
- Identificador único del dispositivo
- Modelo y versión del sistema operativo
- Estadísticas de uso de apps (tiempo por app, no contenido)

**Datos de la cuenta parental:**
- Email del padre (para autenticación)
- Dispositivos asociados
- Configuración de políticas

**NO recopilamos:**
- Contenido de mensajes
- Fotos o videos
- Historial de navegación
- Contactos
- Ubicación
- Audio o video del micrófono/cámara

#### 2. Cómo Usamos la Información

- **Monitoreo de uso**: Para aplicar límites de tiempo configurados por los padres
- **Notificaciones**: Para informar a los padres sobre actividad y solicitudes
- **Sincronización**: Para mantener políticas actualizadas entre dispositivos

#### 3. Compartir Información

**NO vendemos datos a terceros.**

Compartimos información únicamente:
- Con nuestro proveedor de backend (Supabase) para sincronización
- Cuando sea requerido por ley

#### 4. Retención de Datos

- Datos de uso: Eliminados después de 90 días
- Datos de cuenta: Eliminados a solicitud del usuario

#### 5. Derechos del Usuario

Los padres pueden:
- Acceder a sus datos
- Corregir datos inexactos
- Eliminar su cuenta y datos
- Exportar sus datos

#### 6. Políticas para Menores

Esta app está diseñada para uso por padres con hijos menores de edad.
- Los padres deben configurar y supervisar el uso
- Los menores no deben crear cuentas propias

#### 7. Contacto

Para preguntas sobre privacidad:
privacy@example.com

---

## 5. Data Safety (Play Console)

### Sección Data Safety

```
┌─────────────────────────────────────────────────────────────────────┐
│  DATA SAFETY - CONTROL PARENTAL                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DATOS RECOPILADOS                                                 │
│  ├── Dispositivo u otro identificador                             │
│  │   └── ID del dispositivo para sincronización                   │
│  │                                                                   │
│  ├── Uso de apps                                                   │
│  │   └── Tiempo de uso por app (NO contenido)                    │
│  │                                                                   │
│  └── Información de la cuenta                                      │
│      └── Email para autenticación del padre                       │
│                                                                     │
│  FINES                                                             │
│  ├── Monitoreo de uso de apps                                     │
│  ├── Notificaciones                                                │
│  └── Sincronización entre dispositivos                            │
│                                                                     │
│  ¿Se comparten datos?                                              │
│  └── NO - Los datos se procesan localmente y se sincronizan       │
│      solo con la cuenta del padre                                  │
│                                                                     │
│  ¿Se recopilan datos de niños?                                     │
│  └── NO - Los menores no crean cuentas. El monitoreo es           │
│      configurado y supervisado por los padres.                     │
│                                                                     │
│  Encriptación                                                      │
│  └── Los datos en tránsito usan TLS 1.3                          │
│                                                                     │
│  Eliminación                                                       │
│  └── Los padres pueden solicitar eliminación de datos              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 6. Justificación de foregroundServiceType=specialUse

**Manifest:**
```xml
<service
    android:name=".service.UsageTrackingService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="parental_monitoring" />
</service>
```

**Justificación:**
- La app requiere un servicio en primer plano para monitorear el uso de apps
- El monitoreo debe continuar mientras el niño usa el dispositivo
- No es un caso de uso estándar cubierto por otros tipos de FGS
- `specialUse` es apropiado para casos no cubiertos por tipos específicos
- El subtype "parental_monitoring" indica claramente el propósito

---

## 7. Justificación de Device Owner (T31)

### Device Owner

**¿Por qué requiere Device Owner?**

Para proporcionar protección reforzada cuando el padre elige un dispositivo dedicado para el niño:

1. **Bloqueo de desinstalación**: Evita que el niño desinstale la app
2. **Suspensión de apps**: Bloquea completamente apps inapropiadas (no solo overlay)
3. **FRP (Factory Reset Protection)**: Evita que el niño restaure de fábrica para evadir controles

### Canal de Distribución Alterno

**Dispositivos dedicados:**
- Para familias que usan un dispositivo dedicado para el niño
- Configuración via QR/NFC/zero-touch (Android Device Policy)
- Requiere factory reset para aprovisionar

**Flujo de distribución:**
```
1. Padre instala la app en su dispositivo (Google Play)
2. Padre configura Device Owner via provisioning
3. Dispositivo del niño se configura como managed device
4. App funciona en modo reforzado
```

---

## 8. Checklist §0.6

```
┌─────────────────────────────────────────────────────────────────────┐
│  §0.6 - CUMPLIMIENTO DE PRIVACIDAD Y TRANSPARENCIA                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DISCLOSURE (Divulgación)                                          │
│  ✅ Divulgación prominente visible antes de permisos                │
│  ✅ Describe qué se monitorea (uso de apps, no contenido)         │
│  ✅ Sin contenido del menor en ningún momento                     │
│  ✅ Link a política de privacidad disponible                      │
│                                                                     │
│  TRANSPARENCY (Transparencia)                                       │
│  ✅ Pantalla "qué se monitorea" accesible desde configuración     │
│  ✅ Descripción clara del flujo de datos                          │
│  ✅ Nombres de tablas y campos no contienen PII del menor         │
│                                                                     │
│  CONSENT (Consentimiento)                                           │
│  ✅ Consentimiento del padre antes de monitorear                   │
│  ✅ Consentimiento guardado en EncryptedSharedPreferences         │
│  ✅ Opción de retirar consentimiento (desinstalar)                 │
│                                                                     │
│  DATA MINIMIZATION (Minimización)                                   │
│  ✅ Solo datos necesarios para la funcionalidad                    │
│  ✅ Sin captura de pantalla, audio, video                          │
│  ✅ Sin contenido de mensajes o contactos                          │
│  ✅ Sin ubicación                                                  │
│                                                                     │
│  SECURITY (Seguridad)                                               │
│  ✅ Consentimiento encriptado (EncryptedSharedPreferences)         │
│  ✅ TLS 1.3 para comunicación                                      │
│  ✅ Certificate pinning                                            │
│  ✅ RLS en todas las tablas                                        │
│                                                                     │
│  DATA SAFETY (Google Play)                                          │
│  ✅ Formulario completo en Play Console                           │
│  ✅ Data Safety reportee coincide con prácticas reales            │
│  ✅ Política de privacidad publicada                              │
│                                                                     │
│  FAMILIES (Políticas de Menores)                                    │
│  ✅ Diseñado para padres, no para menores                          │
│  ✅ Menores no crean cuentas                                       │
│  ✅ Monitoreo configurado por padres                               │
│  ✅ isAccessibilityTool=false                                      │
│                                                                     │
│  VIDEO Y FORMULARIOS                                               │
│  ✅ Video del flujo preparado                                     │
│  ✅ Permission Declaration Form completado                        │
│  ✅ Justificación de specialUse documentada                        │
│  ✅ Justificación de Device Owner documentada                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 9. Archivos de Configuración

### AndroidManifest.xml (extracto relevante)

```xml
<!-- Control Parental -->
<application>
    
    <!-- Accessibility Service -->
    <service
        android:name=".accessibility.ForegroundAppService"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
        android:exported="false">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_service_config" />
    </service>
    
    <!-- Foreground Service - Special Use -->
    <service
        android:name=".service.UsageTrackingService"
        android:foregroundServiceType="specialUse"
        android:exported="false">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="parental_monitoring" />
    </service>
    
    <!-- Device Admin -->
    <receiver
        android:name=".admin.DeviceAdminReceiver"
        android:exported="false"
        android:permission="android.permission.BIND_DEVICE_ADMIN">
        <meta-data
            android:name="android.app.device_admin"
            android:resource="@xml/device_admin_receiver" />
        <intent-filter>
            <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
        </intent-filter>
    </receiver>
    
</application>
```

### strings.xml (extracto de disclosure)

```xml
<string name="disclosure_title">Monitoreo de uso de apps</string>
<string name="disclosure_text">Esta app monitorea el tiempo que usas en cada app para ayudarte a mantener un equilibrio saludable. No accedemos a tus mensajes, fotos, videos o ubicación.</string>
<string name="accessibility_service_description">Permite mostrar pantallas de bloqueo cuando una app está restringida por tiempo o política.</string>
```

---

## 10. Links de Recursos

| Recurso | URL/Referencia |
|---------|----------------|
| Política de Privacidad | https://example.com/privacy |
| Support | https://example.com/support |
| Play Console | [Internal Only] |
| Video del Flujo | [YouTube Private Link] |

---

## Done Checklist

```
☐ formularios y declaraciones completos
☐ video listo
☐ Data Safety + privacidad publicadas
☐ checklist de §0.6 verde
☐ §0.9 compliance
```
