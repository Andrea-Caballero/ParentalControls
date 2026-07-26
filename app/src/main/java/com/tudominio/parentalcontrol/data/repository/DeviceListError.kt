package com.tudominio.parentalcontrol.data.repository

/**
 * Typed reason for a failed device-list load.
 *
 * Per design §D5 of `openspec/changes/hotfix-parent-auth-session/design.md`:
 * the parent dashboard pattern-matches on this sealed class instead of
 * string-matching `error.message.contains("not authenticated")`. The
 * literal "not authenticated" string is preserved in the underlying
 * exception for log compatibility, but the UI contract is carried by the
 * type.
 *
 * Extends [RuntimeException] so it can be used directly in
 * `Result.failure(...)` without an additional wrapper.
 *
 *  - [AuthMissing] — no parent session; the error banner should swap
 *    retry/back for the "Iniciar sesión como padre" CTA.
 *  - [Transient] — any other failure (network, HTTP non-2xx, parse
 *    error); the error banner keeps the retry/back CTAs.
 */
sealed class DeviceListError(message: String? = null) : RuntimeException(message) {
    data object AuthMissing : DeviceListError(message = "not authenticated")
    data class Transient(val reason: String) : DeviceListError(message = reason)
}
