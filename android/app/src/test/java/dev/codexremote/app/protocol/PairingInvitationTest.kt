package dev.codexremote.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class PairingInvitationTest {
    @Test
    fun parsesValidInvitation() {
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example%3A8787&token=one%20time%2Ftoken",
        )

        assertEquals("https://bridge.example:8787", invitation.baseUrl)
        assertEquals("one time/token", invitation.token)
    }

    @Test
    fun rejectsInvalidScheme() {
        assertRejected(
            "https://pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
        )
    }

    @Test
    fun rejectsEmptyToken() {
        assertRejected(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=",
        )
    }

    @Test
    fun toStringDoesNotExposeToken() {
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=http%3A%2F%2F%5Bfd7a%3A115c%3Aa1e0%3A%3A1%5D%3A8787&token=do-not-leak",
        )

        assertFalse(invitation.toString().contains("do-not-leak"))
    }

    private fun assertRejected(raw: String) {
        try {
            PairingInvitation.parse(raw)
            fail("invalid pairing invitation must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
