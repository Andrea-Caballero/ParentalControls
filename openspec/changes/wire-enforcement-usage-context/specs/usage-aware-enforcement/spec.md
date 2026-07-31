# Usage-Aware Enforcement Specification

## Purpose

Daily-limit enforcement SHALL use the latest available persisted usage context while preserving existing policy, time, identity, and persistence semantics.

## Requirements

### Requirement: Daily limits use real usage context

For the active device and current local date, enforcement MUST evaluate per-app, per-category, and global daily limits against the latest available `UsageContext`, in minutes. It MUST NOT always evaluate limits against an empty context, and MUST preserve existing precedence, grants, and limit calculations.

#### Scenario: App limit includes persisted usage

- GIVEN the active app has a daily limit and persisted usage for today
- WHEN enforcement evaluates the active policy
- THEN the decision SHALL include that app's persisted minutes

#### Scenario: Category and global limits include persisted usage

- GIVEN category and global daily limits have persisted usage for today
- WHEN enforcement evaluates the active policy
- THEN both decisions SHALL include their corresponding aggregated minutes

### Requirement: Usage changes reevaluate relevant enforcement

When usage for the active device and current date emits a changed context, enforcement MUST reevaluate the currently relevant foreground app and policy without requiring an unrelated policy refresh. The synchronous contract of an individual enforcement evaluation MUST remain observable to its caller.

#### Scenario: Usage crossing a limit blocks the foreground app

- GIVEN the foreground app is currently permitted and a new usage context reaches its limit
- WHEN that usage context is emitted
- THEN the relevant policy SHALL be reevaluated and enforcement SHALL apply the resulting limit decision

#### Scenario: Unrelated policy refresh is not required

- GIVEN usage changes while the same foreground app remains relevant
- WHEN the usage context emits
- THEN reevaluation SHALL occur without a separate policy mutation or refresh

### Requirement: Usage loading has deterministic behavior

Before the first valid usage context is emitted, enforcement MUST use the existing empty-context behavior deterministically. It MUST NOT invent usage, reuse an unrelated device or date, or block solely because usage is still loading. A valid emitted context MUST replace that initial behavior for subsequent evaluations.

#### Scenario: Evaluation precedes first usage emission

- GIVEN usage has not yet emitted for the active device and current date
- WHEN enforcement evaluates a foreground policy
- THEN it SHALL use the empty-context behavior and produce the existing deterministic decision

#### Scenario: First valid emission becomes authoritative

- GIVEN enforcement previously evaluated before usage was available
- WHEN a valid usage context for the active device and current date emits
- THEN the next evaluation SHALL use that context rather than the initial empty context

### Requirement: Identity and date boundaries remain unchanged

Usage selection MUST remain gated by the active device identity and the existing current local date-key convention. This change MUST NOT claim that the existing usage table provides device isolation when it does not.

#### Scenario: Active device and current date are retained

- GIVEN enforcement requests usage for the active device on the current local date
- WHEN usage is selected for evaluation
- THEN the existing device identity gate and date key SHALL be used unchanged

### Requirement: Date rollover resubscribes to the new date

When the current local date advances (e.g. around midnight), the controller MUST resubscribe to the usage provider with the new date key so post-rollover usage is observed instead of the previous day's stale data. The date source MUST be an injectable seam so production can schedule rollover events and tests can drive date changes deterministically.

#### Scenario: Midnight rollover switches the date key

- GIVEN enforcement is observing usage for the active device on the previous local date
- WHEN the current local date advances to the next day
- THEN the usage provider SHALL be re-invoked with the new date key
- AND subsequent evaluations SHALL use the new day's persisted usage rather than the previous day's data

### Requirement: Trusted-time fail-closed behavior is preserved

Usage-aware enforcement MUST NOT alter trusted-time validation or its fail-closed outcome.

#### Scenario: Untrusted time remains fail-closed

- GIVEN trusted time is unavailable or invalid
- WHEN enforcement evaluates any policy, with or without usage
- THEN the existing fail-closed behavior SHALL occur

## Out of Scope

- Production scheduling of the midnight rollover signal (e.g. a midnight alarm driving the date flow); the controller consumes the seam, but how the seam is driven at runtime is owned outside this change.
- Changes to the `usage_today` schema, including device isolation or new device-scoped persistence.
