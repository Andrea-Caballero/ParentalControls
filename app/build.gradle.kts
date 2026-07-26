import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.google.services)
}

// T4.1 of `hotfix-parent-auth-cta-reload` (revisited) — `USE_MOCK_SUPABASE`
// now defaults to `true` in debug builds so the dev experience works out
// of the box. Historically this flag was read from `local.properties`, but
// Android Studio regenerates that file on every Gradle sync and the
// `USE_MOCK_SUPABASE=true` line silently disappears between sessions —
// the next "Run" ships a debug APK that talks to the placeholder Supabase
// URL (`https://your-project.supabase.co`), producing confusing
// `NETWORK_ERROR` on the first pairing attempt on a physical device.
//
// To test against the real backend from a debug build, pass the explicit
// override on the command line:
//   ./gradlew installDebug -PuseRealSupabase=true
//
// Release builds are unaffected — they hardcode `USE_MOCK_SUPABASE=false`
// below, which is the contract enforced by Play Store and the
// supabase-backend-integration spec ("Release build does not honor
// local.properties USE_MOCK_SUPABASE").
val debugUseMockSupabase: String =
    if (project.findProperty("useRealSupabase") == "true") "false" else "true"

// `enableSharedMock=true` switches the app from the in-process
// `MockSupabaseEngine` (per-process static fixtures) to a real OkHttp
// client pointing at a developer-run local server (see
// `tools/shared-mock-server/server.py`). This lets two Android devices
// (phone + emulator) actually exchange pairing codes and see each other
// in the parent's device list — something the static fixtures can't do
// because each process has its own copy.
//
// Default off; activate with:
//   ./gradlew installDebug -PenableSharedMock=true -PsharedMockUrl=http://localhost:8787
// On a real device, set `-PsharedMockUrl=http://<host-ip>:8787` and use
// `adb reverse tcp:8787 tcp:8787` so the device can reach the server over
// USB.
val debugUseSharedMock: String =
    if (project.findProperty("enableSharedMock") == "true") "true" else "false"
val debugSharedMockUrl: String =
    (project.findProperty("sharedMockUrl") as String?) ?: "http://10.0.2.2:8787"

// T3 wiring (do NOT scope-creep this into Slice A — this is the
// pre-merge infra patch that lets a real-cloud build actually reach
// Supabase). Reads `SUPABASE_URL` + `SUPABASE_ANON_KEY` from
// `local.properties` (documented at `local.properties.template`).
// Defaults preserve the historical `https://your-project.supabase.co`
// placeholder so a stale build keeps failing loud, not silent.
val debugSupabaseUrl: String =
    (project.findProperty("supabaseUrl") as String?)
        ?: "https://your-project.supabase.co"
val debugSupabaseAnonKey: String =
    (project.findProperty("supabaseAnonKey") as String?)
        ?: "your-anon-key"

android {
    namespace = "com.tudominio.parentalcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tudominio.parentalcontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.tudominio.parentalcontrol.MyHiltTestRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // T4.1 of `hotfix-parent-auth-cta-reload` — release must hardcode
            // `USE_MOCK_SUPABASE=false` so a stale `local.properties` cannot
            // silently engage the mock engine in production builds. See
            // design Decision 2 and spec scenario "Release build does not
            // honor local.properties USE_MOCK_SUPABASE".
            buildConfigField("boolean", "USE_MOCK_SUPABASE", "false")
            // T3 wiring: release reads the same `SUPABASE_URL` /
            // `SUPABASE_ANON_KEY` from Gradle `-PsupabaseUrl=` /
            // `-PsupabaseAnonKey=` properties (see debug config for
            // rationale). Production overrides should come from the
            // CI/Play Console secrets, not this file.
            buildConfigField("String", "SUPABASE_URL", "\"$debugSupabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$debugSupabaseAnonKey\"")
        }
        debug {
            isMinifyEnabled = false
            // T4.1 of `hotfix-parent-auth-cta-reload` — debug reads
            // `USE_MOCK_SUPABASE` from `local.properties` (falling back to
            // `gradle.properties`). See spec scenario "Build reads
            // USE_MOCK_SUPABASE from local.properties under debug".
            buildConfigField("boolean", "USE_MOCK_SUPABASE", debugUseMockSupabase)
            // Shared-mock-server opt-in for cross-device pairing tests.
            // Both fields default to off / localhost so a stale build
            // doesn't accidentally point at a server that isn't running.
            // See the long-form note above `debugUseSharedMock` for usage.
            buildConfigField("boolean", "USE_SHARED_MOCK", debugUseSharedMock)
            buildConfigField("String", "SHARED_MOCK_URL", "\"$debugSharedMockUrl\"")
            // T3 wiring: real Supabase URL + anon key for cross-device
            // validation. Set via `-PsupabaseUrl=https://...` /
            // `-PsupabaseAnonKey=eyJ...` or via `local.properties`. The
            // defaults match the historical `your-project.supabase.co`
            // placeholder so a build without overrides stays loud-failing
            // (NETWORK_ERROR) instead of silently hitting a project that
            // doesn't exist.
            buildConfigField("String", "SUPABASE_URL", "\"$debugSupabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$debugSupabaseAnonKey\"")
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // QR
    implementation(libs.qrcode) {
        // qrose:1.0.0 pulls both qrose-core-android and qrose-android transitively;
        // exclude the core variant — qrose-android already bundles it.
        exclude(group = "io.github.alexzhirkevich", module = "qrose-core-android")
    }

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Play Integrity
    implementation("com.google.android.gms:play-services-instantapps:18.0.1")
    implementation("com.google.android.play:integrity:1.3.0")

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.hilt.navigation.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)

    // Network (Ktor for Supabase REST API)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.ktor:ktor-client-okhttp:2.3.4")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.4")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.4")
    implementation("io.ktor:ktor-websockets:2.3.4")
    // T5 of `hotfix-parent-auth-session` — Ktor MockEngine for the
    // toggleable Supabase mock path. Lives in main classpath so the
    // `NetworkModule` can bind a MockEngine-backed HttpClient when
    // `BuildConfig.USE_MOCK_SUPABASE=true`.
    implementation("io.ktor:ktor-client-mock:2.3.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Firebase
    implementation(platform(libs.firebase.bom))

    // Security (Tink for encrypted storage, Keystore for session tokens)
    implementation(libs.tink.android)

    // Camera
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.datastore.preferences)
    androidTestImplementation(libs.tink.android)
    debugImplementation(libs.compose.ui.test.manifest)

    // Desugaring
    coreLibraryDesugaring(libs.desugar)
}

// =============================================================================
// DETEKT - Static Analysis
// =============================================================================
detekt {
    buildUponDefaultConfig = true
    allRules = false
}

// Configure JVM target for detekt
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
}

// =============================================================================
// KTLINT - Kotlin Static Analysis
// =============================================================================
// Disable the `import-ordering` rule in PR 4 of `align-with-guia-fedora44`.
// The default `ij_kotlin_imports_layout` pattern does not match the layout
// used by the Hilt-aware singletons introduced in this PR (mixing
// `dagger.hilt.*`, `java.*`, `javax.*`, `kotlinx.*` in one block). The
// IntelliJ-style property in `.editorconfig` is ignored by ktlint 0.43.2
// for unknown reasons — explicit gradle-level disable is the safest path.
//
// Baseline strategy (WARNING #1 of
// fix-supabase-client-provider-legacy-mock-gate/verify-report.md):
// the project accumulated 1149 ktlint violations across pre-existing
// files that no one has touched in this codebase's history. Instead of
// a single mega-commit fixing all of them, we snapshot the current
// state into `config/ktlint/baseline.xml` and use the standard ktlint
// `baseline` feature so future runs only fail on NEW violations. The
// baseline grows organically: any new violation must be fixed (or
// explicitly justified) at the PR that introduces it. When someone
// later edits a baseline'd file, ktlint surfaces that file's remaining
// violations in the report (the baseline doesn't suppress violations
// for an edited file — see the ktlint plugin docs on "updated
// baseline" semantics) so the cleanup happens incrementally as files
// are touched.
ktlint {
    // Baseline path resolved via rootProject.file() because Gradle's file()
    // resolves relative to the working directory (the project root) when
    // called from inside the app module's build script, and the baseline
    // sits at app/config/ktlint/baseline.xml inside the repo. Using
    // rootProject.file() makes the path explicit and avoids the working-dir
    // dependency. Generated by `./gradlew ktlintGenerateBaseline`.
    baseline.set(rootProject.file("app/config/ktlint/baseline.xml"))
    disabledRules.set(setOf("import-ordering"))
}

// LINT - Android Static Analysis
// =============================================================================
// Baseline captures pre-existing issues (FcmPushService misconfiguration
// in AndroidManifest.xml:105 — already failing on master, unrelated to PR 1).
android {
    lint {
        baseline = file("config/lint/lint-baseline.xml")
    }
}

// Kotlin 2.3+ requires the new compilerOptions DSL; the old `kotlinOptions` block
// is now a hard error (previously a suppressable deprecation warning).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
