# Spec: proguard-keep-alignment

## Purpose
Keeps the R8 `-keep class … { *; }` rules in `app/proguard-rules.pro` aligned with the actual class names produced by the PR 2 structure refactor (`align-with-guia-fedora44`). The two-line drift introduced when `ForegroundAppService` → `AppMonitorService` and `UsageTrackingService` → `MonitorForegroundService` is folded into PR 4 so the release build's tamper-detection contract covers the renamed entry points.

## ADDED Requirements

### Requirement: Every kept entry-point class exists in the renamed structure
For every `-keep class com.tudominio.parentalcontrol.X { *; }` line under the `# ===== MAINTAIN CLASS NAMES (for tampered APK detection) =====` section of `app/proguard-rules.pro`, the class `X` SHALL resolve to a Kotlin source file under `app/src/main/java/com/tudominio/parentalcontrol/X.kt` (or under a subpackage matching the FQN).

#### Scenario: AppMonitorService keep rule points at a real class
- **WHEN** `app/proguard-rules.pro` line ~129 reads `-keep class com.tudominio.parentalcontrol.accessibility.AppMonitorService { *; }`,
- **THEN** `app/src/main/java/com/tudominio/parentalcontrol/accessibility/AppMonitorService.kt` SHALL exist (the file renamed from `ForegroundAppService.kt` in PR 2).

#### Scenario: MonitorForegroundService keep rule points at a real class
- **WHEN** `app/proguard-rules.pro` line ~130 reads `-keep class com.tudominio.parentalcontrol.service.MonitorForegroundService { *; }`,
- **THEN** `app/src/main/java/com/tudominio/parentalcontrol/service/MonitorForegroundService.kt` SHALL exist (the file renamed from `UsageTrackingService.kt` in PR 2).

#### Scenario: No stale keep rules reference pre-PR-2 class names
- **WHEN** `grep -nE "ForegroundAppService|UsageTrackingService|DeviceAdminReceiver|LockScreenReceiver" app/proguard-rules.pro` runs,
- **THEN** it SHALL return zero matches (the manifest already references the renamed classes per PR 2 task 5).

### Requirement: Kept entry points survive R8 obfuscation
A release-mode build (`./gradlew :app:assembleRelease` or `:app:bundleRelease`) SHALL emit the kept entry-point class names verbatim in the produced APK, so runtime tamper detection can compare the on-device signature against the expected whitelist.

#### Scenario: TamperDetector can resolve kept classes by FQN
- **WHEN** the release APK is installed and `TamperDetector` reflects `Class.forName("com.tudominio.parentalcontrol.accessibility.AppMonitorService")`,
- **THEN** the call SHALL succeed (the class was not repackaged to a short name).

#### Scenario: Manifest receivers are not stripped
- **WHEN** the release APK boots and Android instantiates `com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver`,
- **THEN** the receiver SHALL exist in the DEX (the `-keep class … admin.ParentalDeviceAdminReceiver { *; }` rule covers it; rule itself was not removed by PR 2 because the file was `git mv`'d, not deleted).

## Out of scope
- Adding new `-keep` rules for non-entry-point classes (e.g., repositories, ViewModels) — R8 default rules + the existing `-keep class com.tudominio.parentalcontrol.data.local.** { *; }` line at ~118 already cover them.
- Tuning `-repackageclasses`, `-allowaccessmodification`, or `-overloadaggressively` — those aggressiveness flags are unchanged.
- Pinning R8 / D8 versions in `gradle/libs.versions.toml` — out of PR 4 scope.
- Migrating from `proguard-rules.pro` to R8 full-mode configuration — the project already runs R8 full-mode by default with AGP 8.9.

## Verification hooks
- Static check: `grep -nE "ForegroundAppService|UsageTrackingService" app/proguard-rules.pro` → empty.
- Static check: every FQN inside `-keep class com.tudominio.parentalcontrol.<path> { *; }` resolves to an existing `.kt` file under `app/src/main/java/com/tudominio/parentalcontrol/<path>.kt` (a simple JUnit / shell test parses the file and walks the source tree).
- `./gradlew :app:assembleRelease` (or `:app:bundleRelease`) green.