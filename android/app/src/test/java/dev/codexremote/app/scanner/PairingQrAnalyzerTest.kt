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

    @Test
    fun analysisSessionClosesFrameAndRecoversAfterSynchronousFailure() {
        val delivered = mutableListOf<String>()
        val session = PairingAnalysisSession(delivered::add)
        var closes = 0

        session.analyze(
            start = { throw IllegalArgumentException("invalid camera frame") },
            closeFrame = { closes += 1 },
        )
        session.analyze(
            start = { complete -> complete(listOf(validInvitation)) },
            closeFrame = { closes += 1 },
        )

        assertEquals(2, closes)
        assertEquals(listOf(validInvitation), delivered)
    }

    @Test
    fun analysisSessionClosesEachFrameExactlyOnce() {
        val session = PairingAnalysisSession(onInvitation = {})
        var closes = 0

        session.analyze(
            start = { complete ->
                complete(emptyList())
                complete(emptyList())
            },
            closeFrame = { closes += 1 },
        )

        assertEquals(1, closes)
    }

    @Test
    fun closePreventsLateInvitationCallbacks() {
        val delivered = mutableListOf<String>()
        val session = PairingAnalysisSession(delivered::add)
        var completion: ((List<String>) -> Unit)? = null
        var closes = 0
        session.analyze(
            start = { completion = it },
            closeFrame = { closes += 1 },
        )

        session.close()
        completion?.invoke(listOf(validInvitation))

        assertTrue(delivered.isEmpty())
        assertEquals(1, closes)
    }
}
