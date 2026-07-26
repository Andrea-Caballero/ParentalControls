# Guía: Proyecto Android (Kotlin) de Control Parental en Fedora 44

> **Actualizada al 29 de mayo de 2026.** Todas las versiones son las últimas estables disponibles a esta fecha.

---

## Versiones de referencia

| Herramienta / Librería | Versión |
|---|---|
| Android Studio | Panda 4 · 2025.3.4 Patch 1 |
| Kotlin | 2.3.0 |
| Android Gradle Plugin (AGP) | 8.9.x (máx. soportado por Kotlin 2.3.0: 8.13.0) |
| Compose BOM | 2026.05.00 |
| Hilt | 2.56 |
| Navigation Compose | 2.9.x |
| Room | 2.7.x |
| KSP | 2.3.0-1.0.x |

---

## Paso 1 · Prerrequisitos del sistema (Fedora 44)

### 1.1 Dependencias del sistema

```bash
sudo dnf update -y

sudo dnf install -y \
  java-21-openjdk-devel \
  zlib.i686 \
  ncurses-libs.i686 \
  libstdc++.i686 \
  glibc.i686 \

  libX11 \
  libXext \
  libXrender \
  libXtst \
  libXi \
  alsa-lib \
  freetype \
  fontconfig \
  mesa-libGL \
  mesa-libGLU
```

### 1.2 Verificar Java

```bash
java -version
# Debe mostrar: openjdk version "21.x.x" ...
```

### 1.3 (Opcional) Variables de entorno de Java

En `~/.bashrc` o `~/.zshrc`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
```

---

## Paso 2 · Instalar Android Studio

### 2.1 Descargar

Ve a: https://developer.android.com/studio

Descarga el archivo `.tar.gz` para Linux (Android Studio Panda 4 · 2025.3.4 Patch 1).

### 2.2 Extraer e instalar

```bash
# Mover al directorio de instalación
sudo mkdir -p /opt/android-studio
sudo tar -xzf ~/Downloads/android-studio-*.tar.gz -C /opt/android-studio --strip-components=1

# Permisos de ejecución
sudo chmod +x /opt/android-studio/bin/studio.sh
```

### 2.3 Primer arranque

```bash
/opt/android-studio/bin/studio.sh
```

En el asistente de configuración:

1. Selecciona **Standard** installation.
2. Acepta las licencias del SDK.
3. Deja que descargue el Android SDK, el emulador y las imágenes del sistema.
4. Cuando termine, ve a **Tools → Create Desktop Entry** para crear el acceso directo.

### 2.4 (Opcional) Ejecutar el emulador con KVM

KVM acelera enormemente el emulador en Linux:

```bash
# Verificar soporte de virtualización
egrep -c '(vmx|svm)' /proc/cpuinfo   # debe ser > 0

# Instalar KVM
sudo dnf install -y @virtualization

# Agregar tu usuario al grupo kvm
sudo usermod -aG kvm $USER

# Reiniciar sesión o ejecutar:
newgrp kvm
```

---

## Paso 3 · Crear el proyecto

### 3.1 Desde Android Studio

1. **File → New → New Project**
2. Selecciona **Empty Activity** (usa Jetpack Compose por defecto).
3. Configura:

| Campo | Valor sugerido |
|---|---|
| Name | ParentalControl |
| Package name | com.tudominio.parentalcontrol |
| Save location | ~/AndroidProjects/ParentalControl |
| Language | Kotlin |
| Minimum SDK | API 26 (Android 8.0) |
| Build configuration language | Kotlin DSL (build.gradle.kts) |

4. Haz clic en **Finish**.

> **¿Por qué API 26?** Las APIs clave para control parental (`UsageStatsManager`, `DevicePolicyManager`, `AccessibilityService`) tienen soporte completo desde Android 8.0, y cubre más del 99 % de los dispositivos activos.

---

## Paso 4 · Configurar el catálogo de versiones

Android Studio genera automáticamente `gradle/libs.versions.toml`. Reemplaza su contenido completo:

```toml
# gradle/libs.versions.toml

[versions]
# Core
agp                     = "8.9.2"
kotlin                  = "2.3.0"
ksp                     = "2.3.0-1.0.24"

# Compose
composeBom              = "2026.05.00"

# AndroidX
coreKtx                 = "1.16.0"
lifecycle               = "2.9.0"
activityCompose         = "1.10.1"
navigation              = "2.9.0"
room                    = "2.7.1"
datastore               = "1.1.2"
work                    = "2.10.1"

# DI
hilt                    = "2.56"
hiltExt                 = "1.2.0"

# Coroutines
coroutines              = "1.10.2"

# Test
junit                   = "4.13.2"
junitAndroid            = "1.2.1"
espresso                = "3.6.1"

[libraries]
# Core
core-ktx                 = { group = "androidx.core",      name = "core-ktx",             version.ref = "coreKtx" }
lifecycle-runtime        = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-viewmodel      = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
activity-compose         = { group = "androidx.activity",  name = "activity-compose",      version.ref = "activityCompose" }

# Compose (versiones resueltas por el BOM)
compose-bom              = { group = "androidx.compose",   name = "compose-bom",           version.ref = "composeBom" }
compose-ui               = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling       = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3        = { group = "androidx.compose.material3", name = "material3" }
compose-icons            = { group = "androidx.compose.material", name = "material-icons-extended" }

# Navigation
navigation-compose       = { group = "androidx.navigation", name = "navigation-compose",   version.ref = "navigation" }

# Room
room-runtime             = { group = "androidx.room",      name = "room-runtime",          version.ref = "room" }
room-ktx                 = { group = "androidx.room",      name = "room-ktx",              version.ref = "room" }
room-compiler            = { group = "androidx.room",      name = "room-compiler",         version.ref = "room" }

# DataStore
datastore-preferences    = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Work Manager
work-runtime             = { group = "androidx.work",      name = "work-runtime-ktx",      version.ref = "work" }

# Hilt
hilt-android             = { group = "com.google.dagger",  name = "hilt-android",          version.ref = "hilt" }
hilt-compiler            = { group = "com.google.dagger",  name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose  = { group = "androidx.hilt",      name = "hilt-navigation-compose", version.ref = "hiltExt" }
hilt-work                = { group = "androidx.hilt",      name = "hilt-work",             version.ref = "hiltExt" }
hilt-work-compiler       = { group = "androidx.hilt",      name = "hilt-compiler",         version.ref = "hiltExt" }

# Coroutines
coroutines-android       = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# Test
junit                    = { group = "junit",              name = "junit",                 version.ref = "junit" }
junit-android            = { group = "androidx.test.ext",  name = "junit",                 version.ref = "junitAndroid" }
espresso                 = { group = "androidx.test.espresso", name = "espresso-core",     version.ref = "espresso" }
compose-test-junit4      = { group = "androidx.compose.ui", name = "ui-test-junit4" }

[plugins]
android-application     = { id = "com.android.application",            version.ref = "agp" }
kotlin-android          = { id = "org.jetbrains.kotlin.android",        version.ref = "kotlin" }
kotlin-compose          = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt                    = { id = "com.google.dagger.hilt.android",      version.ref = "hilt" }
ksp                     = { id = "com.google.devtools.ksp",             version.ref = "ksp" }
```

---

## Paso 5 · Configurar build scripts

### 5.1 `build.gradle.kts` (nivel proyecto)

```kotlin
// build.gradle.kts  ← raíz del proyecto

plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.kotlin.android)        apply false
    alias(libs.plugins.kotlin.compose)        apply false
    alias(libs.plugins.hilt)                  apply false
    alias(libs.plugins.ksp)                   apply false
}
```

### 5.2 `app/build.gradle.kts` (nivel módulo)

```kotlin
// app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.tudominio.parentalcontrol"
    compileSdk  = 36

    defaultConfig {
        applicationId = "com.tudominio.parentalcontrol"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.activity.compose)

    // Compose (versiones gestionadas por el BOM)
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.compose.test.junit4)
}
```

---

## Paso 6 · Permisos en AndroidManifest.xml

Las funciones de control parental requieren permisos especiales que **el usuario debe otorgar manualmente** (no se piden en tiempo de ejecución de la forma habitual).

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- ── Permisos normales ───────────────────────────────────────── -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- ── Permisos especiales (se solicitan con Intent al usuario) ── -->
    <!-- Monitoreo de uso de apps -->
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />

    <!-- Administrador de dispositivo (para bloquear pantalla / restricciones) -->
    <!-- Se activa vía DevicePolicyManager ACTION_ADD_DEVICE_ADMIN -->

    <!-- Superposición de pantalla (bloqueo visual) -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <application
        android:name=".ParentalControlApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ParentalControl">

        <!-- ── Actividad principal ──────────────────────────────────── -->
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- ── DeviceAdminReceiver ──────────────────────────────────── -->
        <receiver
            android:name=".admin.ParentalDeviceAdminReceiver"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin_policies" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>

        <!-- ── AccessibilityService (detectar app activa) ───────────── -->
        <service
            android:name=".accessibility.AppMonitorService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <!-- ── Servicio en primer plano (monitoreo continuo) ────────── -->
        <service
            android:name=".service.MonitorForegroundService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <!-- ── BroadcastReceiver para reinicio del dispositivo ───────── -->
        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

---

## Paso 7 · Archivos XML de configuración

### 7.1 Políticas de administrador de dispositivo

```xml
<!-- app/src/main/res/xml/device_admin_policies.xml -->
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <limit-password />
        <watch-login />
        <reset-password />
        <force-lock />
        <wipe-data />
    </uses-policies>
</device-admin>
```

### 7.2 Configuración del servicio de accesibilidad

```xml
<!-- app/src/main/res/xml/accessibility_service_config.xml -->
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:packageNames="" />
```

---

## Paso 8 · Estructura de paquetes recomendada

```
app/src/main/java/com/tudominio/parentalcontrol/
│
├── ParentalControlApp.kt           ← Application class + Hilt
├── MainActivity.kt
│
├── admin/
│   └── ParentalDeviceAdminReceiver.kt
│
├── accessibility/
│   └── AppMonitorService.kt
│
├── service/
│   └── MonitorForegroundService.kt
│
├── receiver/
│   └── BootReceiver.kt
│
├── data/
│   ├── db/
│   │   ├── ParentalDatabase.kt     ← Room database
│   │   ├── AppUsageDao.kt
│   │   └── AppRuleDao.kt
│   ├── model/
│   │   ├── AppUsageEntity.kt
│   │   └── AppRuleEntity.kt
│   └── repository/
│       ├── UsageRepository.kt
│       └── RulesRepository.kt
│
├── domain/
│   └── usecase/
│       ├── GetAppUsageUseCase.kt
│       ├── SetAppLimitUseCase.kt
│       └── IsAppBlockedUseCase.kt
│
├── di/
│   ├── DatabaseModule.kt
│   └── RepositoryModule.kt
│
└── ui/
    ├── navigation/
    │   └── NavGraph.kt
    ├── screen/
    │   ├── dashboard/
    │   │   ├── DashboardScreen.kt
    │   │   └── DashboardViewModel.kt
    │   ├── apps/
    │   │   ├── AppsScreen.kt
    │   │   └── AppsViewModel.kt
    │   └── settings/
    │       ├── SettingsScreen.kt
    │       └── SettingsViewModel.kt
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

---

## Paso 9 · Esqueletos de código esenciales

### 9.1 Application class con Hilt

```kotlin
// ParentalControlApp.kt
package com.tudominio.parentalcontrol

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ParentalControlApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

### 9.2 MainActivity con Compose

```kotlin
// MainActivity.kt
package com.tudominio.parentalcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tudominio.parentalcontrol.ui.navigation.NavGraph
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParentalControlTheme {
                NavGraph()
            }
        }
    }
}
```

### 9.3 DeviceAdminReceiver

```kotlin
// admin/ParentalDeviceAdminReceiver.kt
package com.tudominio.parentalcontrol.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ParentalDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Control parental activado", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Control parental desactivado", Toast.LENGTH_SHORT).show()
    }
}
```

### 9.4 AccessibilityService para monitorear apps

```kotlin
// accessibility/AppMonitorService.kt
package com.tudominio.parentalcontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppMonitorService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            // TODO: comprobar si packageName está bloqueado o superó su límite
        }
    }

    override fun onInterrupt() { /* requerido por la interfaz */ }
}
```

### 9.5 Room Database

```kotlin
// data/db/ParentalDatabase.kt
package com.tudominio.parentalcontrol.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tudominio.parentalcontrol.data.model.AppRuleEntity
import com.tudominio.parentalcontrol.data.model.AppUsageEntity

@Database(
    entities = [AppUsageEntity::class, AppRuleEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ParentalDatabase : RoomDatabase() {
    abstract fun appUsageDao(): AppUsageDao
    abstract fun appRuleDao(): AppRuleDao
}
```

### 9.6 Módulo de inyección de dependencias

```kotlin
// di/DatabaseModule.kt
package com.tudominio.parentalcontrol.di

import android.content.Context
import androidx.room.Room
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ParentalDatabase =
        Room.databaseBuilder(
            context,
            ParentalDatabase::class.java,
            "parental_control.db"
        ).build()

    @Provides
    fun provideAppUsageDao(db: ParentalDatabase) = db.appUsageDao()

    @Provides
    fun provideAppRuleDao(db: ParentalDatabase) = db.appRuleDao()
}
```

---

## Paso 10 · Sincronizar y compilar

```
En Android Studio:
  File → Sync Project with Gradle Files
  (o haz clic en el elefante de Gradle en la barra lateral)

Build → Make Project   (Ctrl + F9)
```

Si el build es exitoso, verás **BUILD SUCCESSFUL** en la pestaña **Build Output**.

---

## Paso 11 · Solicitar permisos especiales en tiempo de ejecución

Estos permisos **no se pueden pedir con `requestPermissions()`**; cada uno requiere llevar al usuario a una pantalla del sistema:

```kotlin
// En un ViewModel o pantalla de configuración inicial

// 1. Estadísticas de uso de apps
fun requestUsageStatsPermission(context: Context) {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    if (mode != AppOpsManager.MODE_ALLOWED) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

// 2. Superposición sobre otras apps
fun requestOverlayPermission(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

// 3. Administrador de dispositivo
fun requestDeviceAdmin(context: Context, adminComponent: ComponentName) {
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Necesario para aplicar restricciones de tiempo de pantalla."
        )
    }
    context.startActivity(intent)
}

// 4. Servicio de accesibilidad
fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
```

---

## Paso 12 · Configurar el emulador (AVD)

1. **Tools → Device Manager → Create Virtual Device**
2. Selecciona **Pixel 7** (o similar).
3. Elige una imagen del sistema: **API 35 / Android 15 (x86_64)** con Google Play si necesitas testear Google Play Services.
4. Finaliza y arranca el AVD.

Para probar funciones de administrador de dispositivo y estadísticas de uso, es más fiable usar un **dispositivo físico real** con opciones de desarrollador habilitadas.

---

## Resumen de las APIs del sistema que usarás

| Funcionalidad | API / Clase de Android |
|---|---|
| Monitorear tiempo de uso por app | `UsageStatsManager` |
| Bloquear/restringir apps | `DevicePolicyManager` + `DeviceAdminReceiver` |
| Detectar app en primer plano | `AccessibilityService` |
| Bloquear pantalla visualmente | `Settings.canDrawOverlays` + `WindowManager` |
| Persistir reglas y límites | `Room` + `DataStore` |
| Monitoreo continuo en background | `WorkManager` + Foreground Service |
| Notificaciones al padre/tutor | `NotificationManager` (canal propio) |

---

## Notas importantes

- El **AccessibilityService** es la forma más confiable de detectar qué app está en pantalla en Android moderno (reemplaza a `getRunningTasks()`, que fue deprecado en API 21).
- Las apps en **Google Play** que usan `DevicePolicyManager` deben declarar claramente su propósito de control parental; revisa las políticas de la Play Store antes de publicar.
- En Android 10+, el acceso a las estadísticas de uso requiere que el usuario otorgue el permiso explícitamente desde **Ajustes → Apps → Acceso especial → Acceso a datos de uso**.
- Para producción, considera cifrar la base de datos Room con **SQLCipher** para proteger los datos de los menores.
