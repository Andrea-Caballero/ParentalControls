package com.tudominio.parentalcontrol.util

/**
 * Email format validator used by [com.tudominio.parentalcontrol.ui.auth.MagicLinkSignInScreen]
 * to gate the send-button enable state, by
 * [com.tudominio.parentalcontrol.auth.DeviceAuthManager.signInWithMagicLink] for
 * the client-side fast-fail on the API path, and by Compose UI tests
 * to keep both predicates in sync.
 *
 * Pattern: `^[^\s@]+@[^\s@]+\.[^\s@]+$`
 *
 *  - Local-part: one-or-more characters that are not whitespace or `@`.
 *  - Literal `@`.
 *  - Domain: one-or-more non-whitespace, non-`@` characters.
 *  - Literal `.`.
 *  - TLD: one-or-more non-whitespace, non-`@` characters (does NOT
 *    enforce the alpha-only TLD rule that the production API uses; the
 *    client-side check is intentionally permissive so the server
 *    remains the source of truth on `invalid_email`).
 *
 * Intentionally NOT a full RFC-5322 grammar — that regex is 600+
 * lines and has well-known false negatives on legitimate addresses.
 * The pragmatic regex catches the common malformed cases
 * (no `@`, missing TLD, leading/trailing whitespace).
 *
 * Lives in `com.tudominio.parentalcontrol.util` (not `auth`) so the UI
 * layer can import it without dragging the auth package onto the
 * screen's classpath.
 */
object EmailValidator {
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /**
     * True when [email] matches the format above AND is non-empty AND
     * has no leading/trailing whitespace. Whitespace handling is here
     * because Compose text fields can pass strings with a trailing
     * space when the user types in quick succession.
     */
    fun isValid(email: String): Boolean {
        if (email.isBlank()) return false
        val trimmed = email.trim()
        return EMAIL_REGEX.matches(trimmed)
    }
}
