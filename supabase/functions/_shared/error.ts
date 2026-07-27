// Shared safe-error helpers for ParentalControl Supabase Edge Functions.
//
// Centralises the `e instanceof Error ? e.message : String(e)` narrowing
// so every edge function narrows thrown values the same way. The catch
// blocks in each function already do this inline; the throw-site template
// literals (e.g. `throw new Error(\`rpc: ${error.message}\`)`) used to
// reach for `.message` directly. Both styles now go through this helper
// so the contract lives in one place — same rationale as `_shared/jwt.ts`.

/**
 * Return a human-readable message for any thrown value without unsafe
 * member access. If `e` is an `Error` instance, returns `e.message`;
 * otherwise coerces the value to a string. Use in throw expressions
 * (`throw new Error(\`prefix: ${errorMessage(err)}\`)`) and in
 * `console.error(...)` sites that previously did `err.message` on a
 * possibly-non-Error value.
 */
export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}