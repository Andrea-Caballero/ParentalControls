// Minimal placeholder for the database.types module that the
// custom-access-token-hook source imports. The hook does NOT actually
// reference `Database` at runtime (the import is for type-elaboration
// convenience), so an empty interface is sufficient to unblock tests.
//
// Real `database.types.ts` is generated from the live Supabase project
// via `supabase gen types typescript --project-id <id> --schema public`
// and lives in `supabase/src/lib/database.types.ts` once the project is
// wired (Slice C). For now, an empty interface keeps the hook import
// resolvable in `deno test` and JVM tests that import the hook.
export type Database = unknown;