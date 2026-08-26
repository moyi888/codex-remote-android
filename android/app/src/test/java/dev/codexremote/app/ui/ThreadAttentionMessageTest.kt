package dev.codexremote.app.ui

import dev.codexremote.app.protocol.Attention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadAttentionMessageTest {
    @Test
    fun rendersOnlySanitizedBrowserAttention() {
        val message = ThreadAttentionMessage.from(
            Attention(
                category = "browser_authorization",
                site = "GitHub.com",
                confidence = 0.9,
                detectedAt = "2026-08-26T10:00:00Z",
            ),
        )

        assertTrue(requireNotNull(message).contains("github.com"))
        assertTrue(message.contains("向日葵"))
    }

    @Test
    fun doesNotRenderUnsafeSiteText() {
        val secret = "secret-token"
        val message = ThreadAttentionMessage.from(
            Attention(
                category = "oauth",
                site = "github.com/$secret",
                confidence = 1.0,
                detectedAt = "2026-08-26T10:00:00Z",
            ),
        )

        assertFalse(requireNotNull(message).contains(secret))
    }

    @Test
    fun ignoresOrdinaryOrLowConfidenceAttention() {
        assertNull(ThreadAttentionMessage.from(attention("filesystem_approval", 1.0)))
        assertNull(ThreadAttentionMessage.from(attention("captcha", 0.69)))
    }

    private fun attention(category: String, confidence: Double) = Attention(
        category = category,
        site = "example.com",
        confidence = confidence,
        detectedAt = "2026-08-26T10:00:00Z",
    )
}
