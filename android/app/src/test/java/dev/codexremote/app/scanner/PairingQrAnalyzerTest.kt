package dev.codexremote.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrAnalyzerTest {
    private val validInvitation =
        "codex-remote://pair?baseUrl=http%3A%2F%2F100.88.10.20%3A8787&token=one-time-secret"

    @Test
    fun parsesStrictPairingInvitations() {
        assertEquals(
            PairingScanResult.Invitation(validInvitation),
            PairingScanResult.parse(validInvitation),
        )
        assertEquals(
            PairingScanResult.Ignored,
            PairingScanResult.parse("https://example.com/not-a-pairing-code"),
        )
    }

    @Test
    fun rejectsMalformedAndOversizedPairingValuesWithoutLeakingSecrets() {
        val secret = "do-not-leak"
        val malformed = PairingScanResult.parse(
            "codex-remote://pair?baseUrl=http%3A%2F%2F100.88.10.20%3A8787&token=$secret%",
        )
        val oversized = PairingScanResult.parse(
            "codex-remote://pair?baseUrl=http%3A%2F%2F100.88.10.20%3A8787&token=" +
                secret + "x".repeat(PairingScanResult.MAX_RAW_LENGTH),
        )

        assertTrue(malformed is PairingScanResult.Rejected)
        assertTrue(oversized is PairingScanResult.Rejected)
        assertFalse(malformed.toString().contains(secret))
        assertFalse(oversized.toString().contains(secret))
    }

    @Test
    fun scanGateDeliversOnlyTheFirstValidInvitation() {
        val gate = PairingScanGate()

        assertEquals(PairingScanResult.Ignored, gate.accept("plain text"))
        assertEquals(PairingScanResult.Invitation(validInvitation), gate.accept(validInvitation))
        assertEquals(PairingScanResult.Ignored, gate.accept(validInvitation))
    }
}
