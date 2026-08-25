package dev.codexremote.app.security

import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AesGcmCodecTest {
    @Test
    fun sealUsesProviderGeneratedIvAndOpensRoundTrip() {
        val codec = AesGcmCodec(
            SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"),
        )
        val plaintext = "bridge-credential".encodeToByteArray()

        val first = codec.seal(plaintext)
        val second = codec.seal(plaintext)

        assertArrayEquals(plaintext, codec.open(first))
        assertArrayEquals(plaintext, codec.open(second))
        assertNotEquals(first, second)
        assertFalse(
            Base64.getDecoder().decode(first.split('.')[1]) contentEquals
                Base64.getDecoder().decode(second.split('.')[1]),
        )
    }
}
