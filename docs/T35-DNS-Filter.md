# T35 — Filtrado DNS por VpnService

## Resumen

Implementación de filtrado DNS local via VPN para bloquear dominios prohibidos, con declaración de limitaciones y cumplimiento de §0.6.

---

## Arquitectura

```
┌─────────────────────────────────────────────────────────────────────┐
│  DNS FILTER ARCHITECTURE                                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  DISPOSITIVO                                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  App del niño                                               │   │
│  │       │                                                      │   │
│  │       ▼                                                      │   │
│  │  ┌─────────────────────────────────────────────────────┐    │   │
│  │  │         DnsFilterService (VpnService)               │    │   │
│  │  │  ┌───────────────────────────────────────────────┐  │    │   │
│  │  │  │  DNS Query → Extract domain → Check block    │  │    │   │
│  │  │  │                     │                        │  │    │   │
│  │  │  │         ┌───────────┴───────────┐            │  │    │   │
│  │  │  │         ▼                       ▼            │  │    │   │
│  │  │  │     BLOCKED               FORWARD           │  │    │   │
│  │  │  │   (NXDOMAIN)           (8.8.8.8)          │  │    │   │
│  │  │  └───────────────────────────────────────────────┘  │    │   │
│  │  └─────────────────────────────────────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Componentes

### DnsFilterService

Servicio VPN que intercepta tráfico DNS y filtra dominios bloqueados.

**Características:**
- Túnel VPN local (no conecta a servidor externo)
- Intercepta consultas DNS del dispositivo
- Bloquea dominios en lista negra (devuelve NXDOMAIN)
- Reenvía otros dominios a DNS upstream (8.8.8.8)

**Flujo:**
1. Lee paquetes del túnel VPN
2. Verifica si es consulta DNS
3. Extrae dominio consultado
4. Compara con lista de bloqueados
5. Si bloqueado → responde NXDOMAIN
6. Si permitido → reenvía a upstream

### DnsFilterManager

Manager para controlar el servicio desde la app.

**Responsabilidades:**
- Verificar si hay otra VPN activa
- Solicitar permiso VPN
- Iniciar/detener servicio
- Configurar Always-On VPN (Device Owner)

---

## Limitación de VPN Única

```
┌─────────────────────────────────────────────────────────────────────┐
│  ⚠️ LIMITACIÓN IMPORTANTE                                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Android solo permite UNA conexión VPN activa a la vez.            │
│                                                                     │
│  Si el usuario tiene otra VPN (NordVPN, ExpressVPN, etc.),         │
│  el control parental no podrá activar su VPN de filtrado.          │
│                                                                     │
│  SOLUCIONES:                                                       │
│  1. Desactivar temporalmente la otra VPN                          │
│  2. Usar el modo reforzado (Device Owner) con Always-On VPN      │
│  3. Alternar entre VPN según necesidad                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Advertir al Padre

En la configuración del control parental, mostrar:

```
┌─────────────────────────────────────────────────────────────────────┐
│  🔒 Filtrado de contenido web                                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Usa una VPN local para bloquear sitios inapropiados.              │
│                                                                     │
│  ⚠️ Solo puede haber una VPN activa a la vez.                      │
│     Si usas otra VPN, desactívala primero.                         │
│                                                                     │
│  [ ] Activar filtrado DNS                                          │
│  [ ] Usar modo reforzado (Device Owner)                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Integración con Device Owner

En modo Device Owner, se puede configurar `setAlwaysOnVpnPackage`:

```kotlin
// En DeviceOwnerManager.kt
fun setAlwaysOnVpnPackage(packageName: String?, lockdown: Boolean = true): Boolean {
    if (!isDeviceOwner()) {
        return false
    }
    
    devicePolicyManager.setAlwaysOnVpnPackage(
        adminComponent,
        packageName,  // null para desactivar
        lockdown       // true = no se puede saltar
    )
}
```

**Advertir:**
- Esta opción requiere Device Owner
- Una vez configurada, la VPN no se puede desactivar sin permisos de admin
- El padre debe entender las implicaciones antes de activar

---

## Declaración en Google Play

### Data Safety

**Recopilación de datos:**
- NO recopilamos datos de navegación
- NO registramos sitios visitados
- El filtrado es 100% local

**Seguridad:**
- El tráfico no se envía a servidores externos
- Solo se usa VPN local para interceptar DNS
- No hay monetización del tráfico

### Permission Declaration

**Permiso requerido:**
- `BIND_VPN_SERVICE` (implícito con VpnService)

**Justificación:**
Esta app usa VpnService para crear un túnel VPN local que intercepta consultas DNS y filtra dominios bloqueados. El tráfico no se envía a servidores externos.

---

## §0.6 Compliance

```
┌─────────────────────────────────────────────────────────────────────┐
│  §0.6 - DNS FILTER COMPLIANCE                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ✅ DECLARACIÓN EN PLAY                                             │
│  ├── Data Safety declarado correctamente                           │
│  └── Justificación del permiso VPN documentada                     │
│                                                                     │
│  ✅ CIFRADO                                                        │
│  ├── Tráfico DNS cifrado en tránsito (TLS si upstream lo soporta) │
│  └── VPN local no expone datos                                    │
│                                                                     │
│  ✅ NO MONETIZACIÓN DE TRÁFICO                                     │
│  ├── No vendemos datos de navegación                               │
│  ├── No hay publicidad basada en navegación                        │
│  └── Filtrado 100% local                                          │
│                                                                     │
│  ✅ TRANSPARENCIA                                                  │
│  ├── Usuario sabe que hay una VPN activa                           │
│  ├── Limitación de VPN única advertida                            │
│  └── Siempre-On documentado para Device Owner                     │
│                                                                     │
│  ✅ SIN CONTENIDO DEL MENOR                                        │
│  ├── No se captura contenido de navegación                        │
│  ├── Solo se bloquean dominios por lista                          │
│  └── No hay logs de sitios específicos                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Permisos del Manifest

```xml
<!-- DNS Filter Service -->
<service
    android:name=".dnsfilter.DnsFilterService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

---

## Métricas de Filtrado (Opcional)

Para analytics (T32), podemos registrar:

```kotlin
// Solo métricas agregadas, no URLs específicas
analyticsManager.track(
    "dns_filter_blocked",
    mapOf(
        "category" to "social_media",  // Categoría, no dominio
        "count" to "1"                // Contador agregado
    )
)
```

**Nunca registrar:**
- URLs específicas visitadas
- Contenido de navegación
- Datos personales del menor

---

## Tests

### Instrumentado

```kotlin
@Test
fun `blocked domains do not resolve`() {
    // Configurar dominio bloqueado
    val blockedDomain = "example-blocked.com"
    
    // Verificar que responde NXDOMAIN
    val response = dnsFilter.processQuery(blockedDomain)
    
    assertTrue(response.isBlocked)
}

@Test
fun `warning shown when other VPN active`() {
    // Simular otra VPN activa
    val hasConflict = dnsFilterManager.hasActiveVpn()
    
    assertTrue(hasConflict)
    
    // Verificar que se muestra advertencia
    val warning = dnsFilterManager.getVpnWarningMessage()
    assertTrue(warning.contains("VPN"))
}
```

---

## Done Checklist

```
☐ filtra DNS (bloquea dominios)
☐ advierte el límite de VPN única
☐ declarado en Play (Data Safety)
☐ no recolecta tráfico sensible
☐ §0.9 compliance
```
