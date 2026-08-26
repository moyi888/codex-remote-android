package dev.codexremote.app.ui

import dev.codexremote.app.bridge.BridgeApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingFailureMessageTest {
    @Test
    fun classifiesFailuresByCauseAndOperation() {
        assertEquals(
            PairingFailureKind.EXPIRED,
            PairingFailureMessage.from(
                BridgeApiException(401, "pair rejected"),
                PairingOperation.PAIR,
            ).kind,
        )
        assertEquals(
            PairingFailureKind.REVOKED,
            PairingFailureMessage.from(
                BridgeApiException(401, "credential rejected"),
                PairingOperation.RESUME,
            ).kind,
        )
        assertEquals(
            PairingFailureKind.UNREACHABLE,
            PairingFailureMessage.from(IOException("tailnet secret"), PairingOperation.PAIR).kind,
        )
        assertEquals(
            PairingFailureKind.UNKNOWN,
            PairingFailureMessage.from(IllegalStateException("unknown"), PairingOperation.PAIR).kind,
        )
    }

    @Test
    fun messagesAreDistinctAndNeverIncludeRawErrorsOrInvitations() {
        val secret = "do-not-leak"
        val messages = PairingFailureKind.entries.map { kind ->
            PairingFailureMessage.forKind(kind).text
        }
        val classifiedUnknown = PairingFailureMessage.from(
            IllegalStateException("codex-remote://pair?token=$secret"),
            PairingOperation.PAIR,
        ).text

        assertEquals(PairingFailureKind.entries.size, messages.toSet().size)
        assertEquals(PairingFailureMessage.forKind(PairingFailureKind.UNKNOWN).text, classifiedUnknown)
        messages.forEach { message ->
            assertFalse(message.contains(secret))
            assertFalse(message.contains("codex-remote://"))
        }
        assertFalse(classifiedUnknown.contains(secret))
        assertFalse(classifiedUnknown.contains("codex-remote://"))
    }
}
