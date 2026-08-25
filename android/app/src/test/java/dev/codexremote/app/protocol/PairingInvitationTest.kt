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
    fun rejectsInvalidOuterHost() {
        assertRejected(
            "codex-remote://other?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
        )
    }

    @Test
    fun rejectsOuterAuthorityAndPathComponents() {
        listOf(
            "codex-remote://user@pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
            "codex-remote://pair:8787?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
            "codex-remote://pair/connect?baseUrl=https%3A%2F%2Fbridge.example&token=secret",
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret#fragment",
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsDuplicateQueryParameters() {
        listOf(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&baseUrl=https%3A%2F%2Fother.example&token=secret",
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret&token=other",
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsUnknownQueryParametersAndEmptySegments() {
        listOf(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=secret&extra=value",
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&&token=secret",
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsEmptyToken() {
        assertRejected(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=",
        )
    }

    @Test
    fun rejectsBlankToken() {
        assertRejected(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=%20%09",
        )
    }

    @Test
    fun rejectsBaseUrlThatIsNotAnOrigin() {
        listOf(
            "ftp%3A%2F%2Fbridge.example",
            "http%3A%2F%2F%2Fmissing-host",
            "https%3A%2F%2Fuser%40bridge.example",
            "https%3A%2F%2Fbridge.example%3Fmode%3Dpair",
            "https%3A%2F%2Fbridge.example%23fragment",
            "https%3A%2F%2Fbridge.example%2Fconnect",
        ).forEach { baseUrl ->
            assertRejected("codex-remote://pair?baseUrl=$baseUrl&token=secret")
        }
    }

    @Test
    fun normalizesRootPathFromBaseUrl() {
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example%3A8787%2F&token=secret",
        )

        assertEquals("https://bridge.example:8787", invitation.baseUrl)
    }

    @Test
    fun toStringDoesNotExposeToken() {
        val invitation = PairingInvitation.parse(
            "codex-remote://pair?baseUrl=http%3A%2F%2F%5Bfd7a%3A115c%3Aa1e0%3A%3A1%5D%3A8787&token=do-not-leak",
        )

        assertFalse(invitation.toString().contains("do-not-leak"))
    }

    @Test
    fun malformedInvitationErrorDoesNotExposeToken() {
        val secret = "do-not-leak"
        try {
            PairingInvitation.parse(
                "codex-remote://pair?baseUrl=https%3A%2F%2Fbridge.example&token=$secret%",
            )
            fail("malformed pairing invitation must be rejected")
        } catch (error: IllegalArgumentException) {
            var current: Throwable? = error
            while (current != null) {
                assertFalse(current.message.orEmpty().contains(secret))
                current = current.cause
            }
        }
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
