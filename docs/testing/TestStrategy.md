# T36 — Estrategia de Pruebas Transversal + Matriz de Compatibilidad

## Resumen

Estrategia de testing completa para el proyecto ParentalControl, incluyendo cobertura de unit tests, integración, instrumentadas, y matriz de compatibilidad Android 8-15.

---

## 1. Stack de Pruebas

```
┌─────────────────────────────────────────────────────────────────────┐
│  STACK DE PRUEBAS                                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  UNIT (JVM)                                                        │
│  ├── JUnit 5                                                      │
│  ├── MockK (mocking Kotlin)                                        │
│  ├── Turbine (Flow testing)                                        │
│  └── kotlinx-coroutines-test                                       │
│                                                                     │
│  INTEGRACIÓN                                                        │
│  ├── Room In-Memory Database                                       │
│  ├── Ktor MockEngine (HTTP mocking)                                 │
│  └── WorkManagerTestInitHelper                                     │
│                                                                     │
│  INSTRUMENTADAS                                                     │
│  ├── Robolectric (UI sin emulator)                                 │
│  ├── Compose UI Test                                               │
│  ├── androidx.test (ActivityTestRule, etc.)                        │
│  └── androidTestOrchestrator                                       │
│                                                                     │
│  E2E (Futuro)                                                      │
│  └── Firebase Test Lab (cuando sea necesario)                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Unit Tests (JVM)

### 2.1 Cobertura Requerida

| Módulo | Archivos | Cobertura Mínima |
|--------|----------|------------------|
| T01 - Domain Model | Models.kt | 90% |
| T02 - Rules Engine | RulesEngine.kt | 95% |
| T04 - Time Provider | TimeProvider.kt, DefaultTimeProvider | 90% |
| T18 - Sync | SyncManager.kt | 80% |
| T28 - Time Extra | TimeExtraRepository.kt | 85% |
| T29 - Rewards | RewardManager.kt | 85% |
| T32 - Analytics | AnalyticsManager.kt | 85% |

### 2.2 Ejemplo de Test

```kotlin
// RulesEngineTest.kt
class RulesEngineTest {
    
    @Test
    fun `blocked app returns BLOCKED`() {
        // Given
        val policy = createPolicy(
            apps = mapOf("com.bad.app" to AppPolicyState.BLOCKED)
        )
        val context = UsageContext.empty()
        
        // When
        val decision = rulesEngine.evaluate(
            policy = policy,
            packageName = "com.bad.app",
            context = context,
            now = LocalDateTime.now(),
            zoneId = ZoneId.systemDefault()
        )
        
        // Then
        assertTrue(decision is Decision.Bloquear)
    }
    
    @Test
    fun `time limit exceeded returns BLOCKED`() {
        // Given
        val policy = createPolicy(
            apps = mapOf("com.game.app" to AppPolicyState.LIMITED),
            dailyLimitMinutes = 60
        )
        val context = UsageContext(usageTodayMinutes = 65)
        
        // When
        val decision = rulesEngine.evaluate(...)
        
        // Then
        assertTrue(decision is Decision.Bloquear)
    }
    
    @Test
    fun `within time limit returns ALLOWED`() {
        // Given
        val policy = createPolicy(
            apps = mapOf("com.safe.app" to AppPolicyState.LIMITED),
            dailyLimitMinutes = 60
        )
        val context = UsageContext(usageTodayMinutes = 30)
        
        // When
        val decision = rulesEngine.evaluate(...)
        
        // Then
        assertTrue(decision is Decision.Permitir)
    }
}
```

### 2.3 Time Provider Mocking

```kotlin
// TimeProviderTest.kt
class TimeProviderTest {
    
    @Test
    fun `fake time provider returns fixed time`() {
        val fakeTime = Instant.parse("2024-01-01T12:00:00Z")
        val fakeProvider = FakeTimeProvider(fakeTime)
        
        assertEquals(fakeTime, fakeProvider.wallInstant())
    }
    
    @Test
    fun `default time provider returns current time`() {
        val provider = DefaultTimeProvider(RuntimeEnvironment.application)
        val now = Instant.now()
        
        // Dentro de 1 segundo
        assertTrue(
            Duration.between(provider.wallInstant(), now).seconds < 1
        )
    }
}
```

---

## 3. Integración

### 3.1 Room In-Memory

```kotlin
// AppDatabaseTest.kt
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    
    @Rule
    @JvmField
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    
    private lateinit var database: AppDatabase
    
    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().context,
            AppDatabase::class.java
        ).build()
    }
    
    @Test
    fun `grant persists and retrieves correctly`() = runTest {
        val grant = GrantEntity(
            id = "test_grant",
            scope = "extra_time",
            minutes = 30,
            grantedAt = Instant.now().toString(),
            expiresAt = Instant.now().plusSeconds(3600).toString()
        )
        
        database.grantDao().insertGrant(grant)
        val retrieved = database.grantDao().getGrantsForScope("extra_time").first()
        
        assertEquals(1, retrieved.size)
        assertEquals("test_grant", retrieved[0].id)
    }
}
```

### 3.2 Ktor MockEngine

```kotlin
// SyncManagerTest.kt
class SyncManagerTest {
    
    private lateinit var mockEngine: MockEngine
    private lateinit var syncManager: SyncManager
    
    @Before
    fun setup() {
        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/sync/policy" -> respond(
                    json.encodeToString(policyResponse),
                    status = HttpStatusCode.OK
                )
                "/sync/grants" -> respond(
                    json.encodeToString(grantsResponse),
                    status = HttpStatusCode.OK
                )
                else -> respondNotFound()
            }
        }
        
        syncManager = SyncManager(
            httpClient = HttpClient(mockEngine),
            database = inMemoryDatabase
        )
    }
    
    @Test
    fun `sync downloads policy correctly`() = runTest {
        syncManager.sync()
        
        val policy = database.policyDao().getPolicyFlow("default").first()
        assertNotNull(policy)
        assertEquals("test_policy", policy.id)
    }
}
```

### 3.3 WorkManager Test Helper

```kotlin
// WorkersTest.kt
class WorkersTest {
    
    @Test
    fun `reconciliation worker runs on schedule`() {
        val workManager = WorkManager.getInstance(context)
        
        // Programar worker
        ReconciliationWorker.schedule(context)
        
        // Obtener trabajo programado
        val workInfo = workManager.getWorkInfoById(
            ReconciliationWorker.workName.toWorkId()
        ).get()
        
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, workInfo.state)
    }
}
```

---

## 4. Tests Instrumentados

### 4.1 Overlay sobre App Bloqueada

```kotlin
// BlockOverlayServiceTest.kt
@RunWith(AndroidJUnit4::class)
class BlockOverlayServiceTest {
    
    @Test
    fun `overlay shows when app blocked`() {
        // Given: app bloqueada por límite de tiempo
        val packageName = "com.game.app"
        val reason = "Límite de tiempo alcanzado"
        
        // When: se evalúa la app
        enforcementController.evaluateAndEnforce(packageName)
        
        // Then: overlay visible
        assertTrue(BlockOverlayService.isShowing())
    }
    
    @Test
    fun `overlay shows permission request button`() {
        // When
        BlockOverlayService.show(context, "App bloqueada") {
            // Callback de "Pedir permiso"
        }
        
        // Then: botón visible
        onView(withId(R.id.btnRequestPermission)).check(matches(isDisplayed()))
    }
}
```

### 4.2 lockNow (Device Admin)

```kotlin
// LockManagerTest.kt
@RunWith(AndroidJUnit4::class)
class LockManagerTest {
    
    @Test
    fun `lockNow triggers admin lock`() {
        // Given
        val lockManager = LockManager(context)
        
        // When
        lockManager.lockNow()
        
        // Then: dispositivo debería bloquearse
        // (En instrumented test con device admin)
    }
}
```

### 4.3 Re-armado tras Boot

```kotlin
// BootReceiverTest.kt
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    
    @Test
    fun `boot receiver schedules workers`() {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        
        // When
        BootReceiver().onReceive(context, intent)
        
        // Then
        verify(workManager).enqueueUniquePeriodicWork(
            eq("reconciliation"),
            any(),
            any()
        )
    }
}
```

### 4.4 Anti-Tampering

```kotlin
// TamperDetectorTest.kt
@RunWith(AndroidJUnit4::class)
class TamperDetectorTest {
    
    @Test
    fun `disabling accessibility triggers degraded alert`() {
        // Given
        val healthMonitor = HealthMonitor(context)
        
        // When: accessibility se desactiva
        AccessibilityService.disableForTest()
        healthMonitor.checkHealth()
        
        // Then: estado DEGRADED
        assertEquals(EnforcementLevel.DEGRADED, healthMonitor.getEnforcementLevel())
        
        // Y alerta mostrada
        verify(analyticsManager).track("degraded_alert_shown")
    }
    
    @Test
    fun `timezone change triggers tamper alert`() {
        // Given
        val tamperDetector = TamperDetector(context)
        
        // When: zona horaria cambia
        tamperDetector.onTimeZoneChanged("America/New_York", "Europe/London")
        
        // Then
        verify(analyticsManager).track("timezone_changed")
    }
}
```

### 4.5 Doze / App Standby

```kotlin
// DozeCompatibilityTest.kt
@RunWith(AndroidJUnit4::class)
class DozeCompatibilityTest {
    
    @Test
    fun `fcm wakes device from doze`() {
        // Given: dispositivo en Doze
        PowerManagerCompat.simulateDoze(context)
        
        // When: FCM de alta prioridad llega
        FcmPushService.simulateHighPriorityPush(context)
        
        // Then: WorkManager ejecuta sincronización
        WorkManager.getInstance(context)
            .getWorkInfoById(syncRequest.id)
            .get()
            .let { assertTrue(it.state is WorkInfo.State.ENQUEUED) }
    }
    
    @Test
    fun `reconciliation runs after doze exits`() {
        // Given: dispositivo sale de Doze
        PowerManagerCompat.exitDoze(context)
        
        // Then: reconciliación se ejecuta
        // (Verificado por WorkManagerTestInitHelper)
    }
}
```

---

## 5. Matriz de Compatibilidad Android 8-15

```
┌─────────────────────────────────────────────────────────────────────┐
│  MATRIZ DE COMPATIBILIDAD                                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  API 26 (8.0)          │ API 31 (12)        │ API 35 (15)          │
│  ───────────────────────┼────────────────────┼──────────────────────│
│  ⚠️ Basic Block       │ ✅ Full Support    │ ✅ Full Support      │
│  ⚠️ Usage Stats       │ ✅ Full Support    │ ✅ Full Support      │
│  ✅ Foreground Svc    │ ✅ Full Support    │ ✅ Full Support      │
│  ⚠️ Notifications    │ ✅ Full Support    │ ✅ Full Support      │
│  ❌ Doze Improvements │ ✅ Doze Improved   │ ✅ Doze Advanced    │
│  ───────────────────────┼────────────────────┼──────────────────────│
│                                                                         │
│  API 27 (8.1)          │ API 32 (12L)       │                       │
│  ───────────────────────┼────────────────────┤                       │
│  ⚠️ Basic Block       │ ✅ Full Support    │                       │
│  ⚠️ Usage Stats       │ ✅ Full Support    │                       │
│  ✅ Foreground Svc    │ ✅ Full Support    │                       │
│  ✅ Notifications     │ ✅ Full Support    │                       │
│  ───────────────────────┼────────────────────┤                       │
│                                                                         │
│  API 28 (9.0)          │ API 33 (13)        │                       │
│  ───────────────────────┼────────────────────┤                       │
│  ✅ Full Support       │ ✅ Full Support    │                       │
│  ✅ QR Provisioning    │ ✅ Full Support    │                       │
│  ✅ Doze               │ ✅ Doze Improved   │                       │
│  ───────────────────────┼────────────────────┤                       │
│                                                                         │
│  API 29 (10)           │ API 34 (14)        │                       │
│  ───────────────────────┼────────────────────┤                       │
│  ✅ Full Support       │ ✅ Full Support    │                       │
│  ✅ Scoped Storage    │ ✅ Full Support    │                       │
│  ✅ Doze              │ ✅ Doze Advanced   │                       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────┘

LEYENDA:
✅ Full Support     = Funcionalidad completa soportada
⚠️ Basic/Limited   = Funcionalidad parcial o con limitaciones
❌ Not Supported    = Funcionalidad no disponible
```

### 5.1 Compatibilidad Detallada por Feature

| Feature | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 | 35 |
|---------|----|----|----|----|----|----|----|----|----|----|
| **Overlay** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Usage Stats** | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Device Admin** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Device Owner** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Accessibility** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Foreground Svc** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Doze** | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Battery Optim.** | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **FCM High Priority** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **VPN Service** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **QR Provisioning** | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **TLS 1.3** | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Play Integrity** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |

### 5.2 Notas de Compatibilidad

```
API 26-27:
- Usage Stats requiere configuración manual de " acceso ilimitado "
- Doze tiene limitaciones en el conteo de tiempo

API 28-30:
- QR Provisioning disponible desde API 28
- TLS 1.3 requiere API 30+
- Play Integrity requiere API 31+

API 31-35:
- Soporte completo de todas las features
- Mejoras en Doze y App Standby
- Better Together / Material You
```

---

## 6. CI Pipeline

### 6.1 GitHub Actions Workflow

```yaml
# .github/workflows/android.yml
name: Android CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  # ============================================================
  # UNIT TESTS (JVM)
  # ============================================================
  unit-tests:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Setup Gradle
        uses: gradle/wrapper-validation-action@v1
      
      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: ~/.gradle/caches
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}
      
      - name: Run unit tests
        run: ./gradlew test --no-daemon
      
      - name: Upload unit test reports
        uses: actions/upload-artifact@v3
        with:
          name: unit-test-reports
          path: app/build/reports/tests/testDebugUnitTest/

  # ============================================================
  # INSTRUMENTED TESTS (API 28)
  # ============================================================
  instrumented-api28:
    runs-on: ubuntu-latest
    needs: unit-tests
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Run instrumented tests (API 28)
        run: ./gradlew connectedAndroidTest --no-daemon

  # ============================================================
  # LINT
  # ============================================================
  lint:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Run lint
        run: ./gradlew lint --no-daemon
      
      - name: Upload lint reports
        uses: actions/upload-artifact@v3
        with:
          name: lint-reports
          path: app/build/reports/lint/

  # ============================================================
  # BUILD VERIFICATION
  # ============================================================
  build:
    runs-on: ubuntu-latest
    needs: [unit-tests, lint]
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon
      
      - name: Upload APK
        uses: actions/upload-artifact@v3
        with:
          name: app-debug.apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

### 6.2 Comandos §0.9

```bash
# §0.9 Commands para cada PR
# ============================================================

# 1. Unit Tests
./gradlew test

# 2. Instrumented Tests
./gradlew connectedAndroidTest

# 3. Lint
./gradlew lint

# 4. Build Debug APK
./gradlew assembleDebug

# 5. Build Release APK (si hay signing config)
# ./gradlew assembleRelease

# ============================================================
# Ejecución completa
# ============================================================
./gradlew clean test lint assembleDebug connectedAndroidTest
```

### 6.3 Checklist §0.6 Pre-Envío

```markdown
## §0.6 Checklist antes de cada envío a Play

### Disclosure & Transparency
- [ ] Disclosure visible antes de permisos
- [ ] Pantalla "qué se monitorea" accesible
- [ ] Link a política de privacidad

### Consent
- [ ] Consentimiento requerido antes de monitorear
- [ ] Consentimiento guardado en EncryptedSharedPreferences
- [ ] Opción de retirar consentimiento

### Data Minimization
- [ ] No se captura contenido del menor
- [ ] No se captura mensajes, fotos, videos
- [ ] No se captura ubicación

### Security
- [ ] Consentimiento encriptado
- [ ] TLS 1.3 configurado
- [ ] Certificate pinning activo
- [ ] RLS en todas las tablas

### Families
- [ ] isAccessibilityTool="false"
- [ ] Menores no crean cuentas
- [ ] Padres supervisan configuración

### Play Compliance
- [ ] Data Safety reportee coincide con prácticas
- [ ] Permission Declaration Form completado
- [ ] Video del flujo preparado
- [ ] Política de privacidad publicada
```

---

## 7. Cobertura de Tests

### 7.1 Targets de Cobertura

| Tipo | Target | Mínimo |
|------|--------|--------|
| Unit | 80% | 70% |
| Integration | 70% | 60% |
| Instrumented | Key flows | N/A |

### 7.2 Comandos de Cobertura

```bash
# Generar reporte de cobertura
./gradlew testDebugUnitTest jacocoTestReport

# Ver reporte en:
# app/build/reports/jacoco/testDebugUnitTestCoverage/html/index.html
```

---

## 8. Done Checklist

```
☐ unit tests verdes en JVM
☐ integración verdes con MockEngine
☐ instrumentadas verdes en API 28/31/35
☐ anti-tamper verificado
☐ Doze/App Standby verificado
☐ MockEngine configurado
☐ checklist §0.6 en CI
☐ §0.9 commands documentados
☐ matriz de compatibilidad Android 8-15
```
