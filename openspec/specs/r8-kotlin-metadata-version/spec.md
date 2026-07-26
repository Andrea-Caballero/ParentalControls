# Spec: r8-kotlin-metadata-version

## Purpose

Verifies the AGP version chosen for this change bundles `kotlin-metadata-jvm` ≥ 2.3.0, so R8 stops emitting `malformed kotlin.Metadata` warnings on every Kotlin class during `:app:minifyReleaseWithR8` (1171-warning regression: Kotlin 2.3.0 vs AGP 8.9.2's R8 cap at metadata v2.1.0). Owns the verification gate, the warning-free success criterion, the rollback proof, and the audit trail.

## ADDED Requirements

### Requirement: AGP version ships compatible Kotlin metadata library

The chosen AGP version SHALL bundle an R8 build whose `kotlin-metadata-jvm` library (shaded into the R8 jar under `com.android.tools.r8.kotlin.*`) supports Kotlin metadata ≥ 2.3.0, verified by **bytecode inspection of the AGP `builder` jar and the bundled R8 jar fetched from Google Maven** before any build edit lands. Maven Central POM grep does NOT work (R8 is not published to Maven Central; the R8 POM has zero dependencies).

#### Scenario: Primary candidate (8.13.2) verification
- **WHEN** the bytecode-inspection gate runs against AGP 8.13.2 — specifically, `javap -p -c -constants` on `com.android.builder.dexing.R8Version` from `builder-8.13.2.jar` (fetched from `https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.13.2/builder-8.13.2.jar`) identifies the bundled R8 version, then `javap -c` on the R8 version's metadata-version constant class (located via `strings r8-<r8>.jar | grep "while maximum supported version is"`) identifies the max supported Kotlin metadata version,
- **THEN** the max supported Kotlin metadata version SHALL be ≥ `2.3.0` (the version emitted by Kotlin 2.3.0 used in this project),
- **AND** the apply phase proceeds to edit `gradle/libs.versions.toml`.

#### Scenario: Fallback candidate (9.2.1) when primary fails
- **WHEN** the primary bytecode-inspection gate reports max supported Kotlin metadata < `2.3.0`,
- **THEN** the apply phase SHALL repeat the same two-step bytecode mechanism against `builder-9.2.1.jar` and the matching `r8-<r8>.jar` from Google Maven,
- **AND** if the fallback passes, the edit SHALL use `agp = "9.2.1"` instead of `8.13.2`,
- **AND** if both fail, the change SHALL abort with the verification transcripts pasted as evidence.

#### Scenario: Verification command is reproducible
- **WHEN** the verification bytecode inspection runs during apply,
- **THEN** its full transcript (every `curl` URL, every `javap` invocation, and the decompiled constant outputs) SHALL land in `openspec/changes/bump-agp-fix-r8-metadata-warnings/verification-evidence.md` as the authoritative evidence file,
- **AND** any reviewer SHALL be able to re-run the exact same commands against Google Maven to reproduce the transcript.

### Requirement: Release build emits zero metadata warnings

After the bump, `:app:minifyReleaseWithR8` SHALL finish warning-free and the full quality gate SHALL stay green at the existing baseline (604+ unit tests, no new detekt/ktlint findings).

#### Scenario: minifyReleaseWithR8 is warning-free
- **WHEN** `./gradlew :app:minifyReleaseWithR8` runs after the bump,
- **THEN** `grep -cE "malformed kotlin\.Metadata|kotlin metadata version is not supported"` over the build log SHALL return `0`,
- **AND** the task SHALL exit 0.

#### Scenario: Full quality gate stays green
- **WHEN** `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck` runs after the bump,
- **THEN** all four tasks SHALL exit 0,
- **AND** `:app:testDebugUnitTest` SHALL report ≥ 604 passing tests,
- **AND** no new detekt or ktlint findings SHALL appear.

### Requirement: Rollback reproduces the original warnings

Reverting the bump commit SHALL restore the original 1171 warnings, proving the toolchain mismatch is the sole cause.

#### Scenario: Revert restores the warning count
- **WHEN** the bump commit is reverted via `git revert <sha>` on the post-bump tree,
- **THEN** `./gradlew :app:minifyReleaseWithR8` SHALL re-emit ≥ 1000 `malformed kotlin.Metadata` lines,
- **AND** re-applying the bump (or a second `git revert`) SHALL make them disappear,
- **AND** the cycle SHALL be recorded as evidence in the change folder.

### Requirement: Toolchain coordinates are recorded

The chosen AGP version, the path taken, and the verification evidence SHALL persist as an audit trail.

#### Scenario: Audit trail is committed
- **WHEN** the apply commit lands,
- **THEN** `gradle/libs.versions.toml` SHALL read `agp = "8.13.2"` or `agp = "9.2.1"` (matching what was verified),
- **AND** the apply commit message SHALL state the path taken with a reference to the bytecode-inspection transcript in `verification-evidence.md`,
- **AND** this spec SHALL be archived alongside `proposal.md` and `tasks.md`.

## Out of scope
- Behavior change in any feature spec (`app-block-policy`, `pairing-flow`, `outbox-drain`, etc.) — toolchain-only.
- Kotlin version changes — Kotlin 2.3.0 stays.
- `proguard-rules.pro` syntax edits — only if R8 8.13 rejects an existing keep rule; separate delta to `proguard-keep-alignment` if so.
- Bumping Compose BOM, Hilt, Room, Ktor, Firebase, or any other library.
- JVM target bump (17 → 21) — CI pins Java 17.

## Verification hooks
- `grep -cE "agp = \"(8\.13\.2|9\.2\.1)\"" gradle/libs.versions.toml` → `1`.
- `verification-evidence.md` exists in the change folder and contains a `# Verification — primary path` section whose `javap` output on the bundled R8 jar shows max Kotlin metadata version ≥ `2.3.0`.
- `./gradlew :app:minifyReleaseWithR8` → 0 `malformed kotlin.Metadata` lines, exit 0.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck` → all exit 0; tests ≥ 604.
- Rollback: `git revert <bump-sha>` + `:app:minifyReleaseWithR8` → ≥ 1000 warning lines.
