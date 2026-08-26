package dev.codexremote.app.service

import dev.codexremote.app.protocol.EventEnvelope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionPolicyTest {
    @Test
    fun browserAuthorizationProducesSafeSunloginInstruction() {
        val notice = AttentionPolicy.fromEvent(
            event(
                type = "thread.updated",
                category = "browser_authorization",
                site = "github.com",
                confidence = 0.9,
            ),
        )

        requireNotNull(notice)
        assertEquals("需要在电脑上完成授权", notice.title)
        assertTrue(notice.body.contains("github.com"))
        assertTrue(notice.body.contains("向日葵"))
        assertTrue(notice.body.contains("重试任务"))
    }

    @Test
    fun ordinaryCodexEventsDoNotCreateApprovalNotifications() {
        assertNull(
            AttentionPolicy.fromEvent(
                event("thread.updated", category = "codex_approval", site = "", confidence = 1.0),
            ),
        )
        assertNull(
            AttentionPolicy.fromEvent(
                event("turn.completed", category = "browser_authorization", site = "github.com", confidence = 1.0),
            ),
        )
    }

    @Test
    fun lowConfidenceAttentionIsIgnored() {
        assertNull(
            AttentionPolicy.fromEvent(
                event("thread.updated", category = "oauth", site = "github.com", confidence = 0.69),
            ),
        )
    }

    @Test
    fun unsafeSiteFallsBackToGenericTextAndNeverLeaksPayloadSecrets() {
        val secret = "secret-prompt-and-token"
        val envelope = event(
            type = "attention.required",
            category = "captcha",
            site = "github.com/$secret",
            confidence = 0.95,
            secret = secret,
        )

        val notice = AttentionPolicy.fromEvent(envelope)

        requireNotNull(notice)
        assertFalse(notice.body.contains(secret))
        assertFalse(notice.body.contains("github.com/"))
        assertEquals("需要在电脑上完成授权", notice.title)
    }

    private fun event(
        type: String,
        category: String,
        site: String,
        confidence: Double,
        secret: String = "not-for-notification",
    ) = EventEnvelope(
        protocolVersion = 1,
        eventCursor = 1,
        type = type,
        payload = buildJsonObject {
            put("id", "thread-1")
            put("prompt", secret)
            put("token", secret)
            put(
                "attention",
                buildJsonObject {
                    put("category", category)
                    put("site", site)
                    put("confidence", confidence)
                    put("detectedAt", "2026-08-26T01:00:00Z")
                },
            )
        },
    )
}
