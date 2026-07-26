# Spec: database-initialization

## Purpose
Establishes `DatabaseModule` (Hilt) as the single source of truth for constructing `ParentalDatabase`, and removes the duplicate `companion object` singleton (`INSTANCE` + `getInstance(Context)`) from `ParentalDatabase.kt`. PR 4 of `align-with-guia-fedora44` ships this dedup so callers depend on one well-defined DI seam.

## ADDED Requirements

### Requirement: Hilt is the sole provider of ParentalDatabase
`ParentalDatabase` SHALL be constructed exclusively by `DatabaseModule.provideDatabase(@ApplicationContext context)` using `Room.databaseBuilder(context, ParentalDatabase::class.java, ParentalDatabase.DATABASE_NAME).build()`. No other code path SHALL instantiate `ParentalDatabase`.

#### Scenario: Companion INSTANCE singleton is removed
- **WHEN** `data/db/ParentalDatabase.kt` is read,
- **THEN** it SHALL NOT declare `@Volatile private var INSTANCE`, `fun getInstance(context: Context)`, or any other static constructor for `ParentalDatabase`.

#### Scenario: No static call sites survive the dedup
- **WHEN** `grep -rn "ParentalDatabase\.getInstance\|ParentalDatabase\.INSTANCE\|ParentalDatabase\.Companion" app/src/main/java/com/tudominio/parentalcontrol/` runs,
- **THEN** it SHALL return zero matches (excluding `DatabaseModule.kt` which references the constants only).

### Requirement: Callers receive ParentalDatabase via dependency injection
Every non-DI consumer that previously called `ParentalDatabase.getInstance(context)` SHALL obtain the database through Hilt (constructor `@Inject` or `@Inject lateinit var`).

#### Scenario: ViewModels receive ParentalDatabase by injection
- **WHEN** `ChildStatusViewModel`, `RealtimeViewModel`, and other ViewModels that previously called `ParentalDatabase.getInstance(context)` are inspected,
- **THEN** the database dependency SHALL be a constructor parameter (Hilt-injected) and the file SHALL NOT contain `ParentalDatabase.getInstance(`.

#### Scenario: Services and managers receive ParentalDatabase by injection
- **WHEN** `MonitorForegroundService`, `OutboxManager`, `HealthMonitor`, `TimeExtraRepository`, `AntiEvasionService`, `RewardManager`, `AnalyticsManager`, and `SyncManager` are inspected,
- **THEN** each SHALL accept `ParentalDatabase` via constructor injection or `@Inject lateinit var` and SHALL NOT call `ParentalDatabase.getInstance(`.

#### Scenario: Robolectric test resolves ParentalDatabase
- **WHEN** a Robolectric test launches `MainActivity` (which transitively requires `ChildStatusViewModel` → `ParentalDatabase`),
- **THEN** the Hilt graph SHALL resolve `ParentalDatabase` through `DatabaseModule` and the database file SHALL be created at `ParentalDatabase.DATABASE_NAME`.

### Requirement: Schema constants and migrations remain reachable
The database filename and the migration objects SHALL remain accessible to `DatabaseModule.kt` and to the instrumented migration tests (`OutboxMigrationTest`, `AppPolicyMigrationTest`) without going through `getInstance`.

#### Scenario: DATABASE_NAME is reachable from DatabaseModule
- **WHEN** `DatabaseModule.provideDatabase` is compiled,
- **THEN** it SHALL reference `ParentalDatabase.DATABASE_NAME` (or a relocated constant) so the database file name has one source of truth.

#### Scenario: Migration objects are reachable from migration tests
- **WHEN** `OutboxMigrationTest` calls `helper.runMigrationsAndValidate(name, 5, true, ParentalDatabase.MIGRATION_4_5)`,
- **THEN** `ParentalDatabase.MIGRATION_4_5` SHALL resolve (top-level `val` in the same file, or relocated to a `Migrations.kt` next to `ParentalDatabase.kt`).

#### Scenario: AppPolicyMigration v5 → v6 still passes
- **WHEN** `AppPolicyMigrationTest` runs the v5 → v6 migration,
- **THEN** `ParentalDatabase.MIGRATION_5_6` SHALL resolve and the test SHALL rebuild the `app_policies` table with composite PK `(device_id, package_name)`.

### Requirement: Application boot does not regress
Removing the companion singleton SHALL NOT change cold-start behavior, schema version, or the v4 → v5 → v6 upgrade path.

#### Scenario: Existing migrations are still registered
- **WHEN** an upgrade install moves a user from schema v4 or v5 to v6,
- **THEN** `DatabaseModule.provideDatabase` SHALL register `MIGRATION_4_5` and `MIGRATION_5_6` (inline `.addMigrations(...)` or via a builder helper) so the upgrade path is unchanged.

## Out of scope
- Migrating `DeviceAuthManager.getInstance(context)` / `PairingManager.getInstance(context)` / `CopyManager.getInstance(context)` / `SupabaseClientProvider.getInstance(context)` — separate concerns, addressed elsewhere.
- Changing the schema version (stays at `6`) or adding new migrations.
- Replacing `Room.databaseBuilder` with `Room.inMemoryDatabaseBuilder` for tests.
- Introducing a `RoomDatabase.Callback` for seeding.