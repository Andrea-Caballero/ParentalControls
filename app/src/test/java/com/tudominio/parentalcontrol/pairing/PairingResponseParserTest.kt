package com.tudominio.parentalcontrol.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function tests for [PairingResponseParser].
 *
 * SUGGESTION #2 of `verify-report.md`: the parser replaces three hand-rolled
 * regex extractors that used to live in `PairingManager.parsePairingResponse`.
 * These tests pin the new contract: kotlinx-serialization handles pretty-vs-
 * compact tolerance, missing optional fields, and unknown-key pass-through
 * transparently, while still rejecting malformed input with a `null` result
 * (so `parsePairingResponse` can map it to `INVALID_RESPONSE`).
 *
 * The parser is `internal object` so these tests live in the same package
 * without needing Robolectric, Hilt, or any production collaborators.
 */
class PairingResponseParserTest {

    // -------------------- parseSuccess --------------------

    @Test
    fun parseSuccess_returnsDeviceAndParentFromCompactJson() {
        val body = """{"device_id":"device-abc","parent_id":"parent-xyz"}"""

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNotNull("compact JSON must parse successfully", parsed)
        assertEquals("device-abc", parsed!!.device_id)
        assertEquals("parent-xyz", parsed.parent_id)
    }

    @Test
    fun parseSuccess_toleratesPrettyPrintedJson() {
        // The fixture file is pretty-printed; the regex replacement
        // previously needed explicit `\s*:\s*` tolerance for this case.
        // kotlinx-serialization handles whitespace for free.
        val body = """
            {
              "device_id": "device-abc",
              "parent_id": "parent-xyz"
            }
        """.trimIndent()

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNotNull("pretty-printed JSON must parse successfully", parsed)
        assertEquals("device-abc", parsed!!.device_id)
        assertEquals("parent-xyz", parsed.parent_id)
    }

    @Test
    fun parseSuccess_acceptsMissingOptionalParentId() {
        // `parent_id` is nullable in PairingResponse. A 200 body without
        // it (e.g. a future server version that omits the field when the
        // device was registered without a parent link) must still parse
        // and yield Success(device_id, parent_id=null).
        val body = """{"device_id":"device-abc"}"""

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNotNull("missing optional parent_id must not fail parsing", parsed)
        assertEquals("device-abc", parsed!!.device_id)
        assertNull("parent_id must be null when absent", parsed.parent_id)
    }

    @Test
    fun parseSuccess_ignoresUnknownKeys() {
        // Real Supabase responses may include extra fields (request_id,
        // created_at, server-side metadata). The lenient config must
        // ignore them so a server-side schema addition doesn't break
        // the client.
        val body = """
            {
              "device_id": "device-abc",
              "parent_id": "parent-xyz",
              "request_id": "req-1234",
              "created_at": "2026-06-24T20:00:00Z",
              "extra_unexpected_field": true
            }
        """.trimIndent()

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNotNull("unknown extra fields must not fail parsing", parsed)
        assertEquals("device-abc", parsed!!.device_id)
        assertEquals("parent-xyz", parsed.parent_id)
    }

    @Test
    fun parseSuccess_returnsNullOnMissingRequiredDeviceId() {
        // `device_id` is required. If the server omits it, the parser
        // returns null and `parsePairingResponse` maps to INVALID_RESPONSE
        // instead of crashing.
        val body = """{"parent_id":"parent-xyz"}"""

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNull("missing required device_id must return null", parsed)
    }

    @Test
    fun parseSuccess_returnsNullOnMalformedJson() {
        val body = "{ this is not json"

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNull("malformed JSON must return null, not throw", parsed)
    }

    @Test
    fun parseSuccess_returnsNullOnEmptyBody() {
        val parsed = PairingResponseParser.parseSuccess("")

        assertNull("empty body must return null", parsed)
    }

    @Test
    fun parseSuccess_returnsNullOnArrayInsteadOfObject() {
        // Defensive: a server returning `[1,2,3]` is technically valid
        // JSON but doesn't match the PairingResponse shape. Return null.
        val body = "[1,2,3]"

        val parsed = PairingResponseParser.parseSuccess(body)

        assertNull("array body must not be coerced into PairingResponse", parsed)
    }

    // -------------------- parseError --------------------

    @Test
    fun parseError_returnsMessageFromCompactJson() {
        val body = """{"error":"code expired"}"""

        val message = PairingResponseParser.parseError(body)

        assertEquals("code expired", message)
    }

    @Test
    fun parseError_toleratesPrettyPrintedJson() {
        val body = """
            {
              "error": "código no encontrado"
            }
        """.trimIndent()

        val message = PairingResponseParser.parseError(body)

        assertEquals("código no encontrado", message)
    }

    @Test
    fun parseError_ignoresUnknownKeys() {
        val body = """
            {
              "error": "rate limited",
              "retry_after_seconds": 30,
              "trace_id": "abc-123"
            }
        """.trimIndent()

        val message = PairingResponseParser.parseError(body)

        assertEquals("rate limited", message)
    }

    @Test
    fun parseError_returnsNullOnMissingErrorField() {
        // Server returned 500 with no error body, or a body that uses a
        // different field name (e.g. `message`). Caller falls back to
        // a generic "Error del servidor".
        val body = """{"detail":"something went wrong"}"""

        val message = PairingResponseParser.parseError(body)

        assertNull("body without `error` field must return null", message)
    }

    @Test
    fun parseError_returnsNullOnMalformedJson() {
        val message = PairingResponseParser.parseError("not json at all")

        assertNull("malformed JSON must return null", message)
    }

    @Test
    fun parseError_returnsNullOnEmptyBody() {
        val message = PairingResponseParser.parseError("")

        assertNull("empty body must return null", message)
    }
}
