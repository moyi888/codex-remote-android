package dev.codexremote.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun keepsNewestEntriesWithinBound() {
        val logs = DiagnosticLogStore(capacity = 2)
        logs.info("pair", "one")
        logs.warn("pair", "two")
        logs.error("pair", "three")

        assertEquals(listOf("two", "three"), logs.snapshot().map { it.message })
    }

    @Test
    fun exportRedactsSecretsAndIncludesStructuredContext() {
        val logs = DiagnosticLogStore()
        logs.info(
            "http",
            "POST http://100.101.231.107:8787/v1/pair/exchange?token=secret-token",
        )
        logs.info("http", "Authorization: Device phone:credential-value")

        val rendered = logs.export()
        assertFalse(rendered.contains("secret-token"))
        assertFalse(rendered.contains("credential-value"))
        assertTrue(rendered.contains("[REDACTED]"))
        assertTrue(rendered.contains("http"))
    }

    @Test
    fun capturesExceptionTypeAndMessageWithoutStackSecrets() {
        val logs = DiagnosticLogStore()
        logs.error("pair", "request failed", IllegalStateException("token=secret"))

        val entry = logs.snapshot().single()
        assertEquals("ERROR", entry.level.name)
        assertTrue(entry.message.contains("request failed"))
        assertTrue(entry.message.contains("IllegalStateException"))
        assertFalse(logs.export().contains("secret"))
    }
}
