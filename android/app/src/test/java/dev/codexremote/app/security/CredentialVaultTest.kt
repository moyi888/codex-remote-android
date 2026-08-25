package dev.codexremote.app.security

import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialVaultTest {
    @Test
    fun saveEncryptsConnectionAndLoadRestoresIt() {
        val storage = InMemoryKeyValueStorage()
        val vault = CredentialVault(storage, XorSecretBox())
        val connection = StoredBridgeConnection(
            baseUrl = "https://bridge.example",
            credential = DeviceCredential(1, "phone-1", "credential-do-not-store-in-plain-text"),
        )

        vault.save(connection)

        val persisted = storage.value
        requireNotNull(persisted)
        assertFalse(persisted.contains(connection.baseUrl))
        assertFalse(persisted.contains(connection.credential.credential))
        assertEquals(connection, vault.load())
    }

    @Test
    fun clearRemovesSavedConnection() {
        val storage = InMemoryKeyValueStorage()
        val vault = CredentialVault(storage, XorSecretBox())
        vault.save(
            StoredBridgeConnection(
                baseUrl = "https://bridge.example",
                credential = DeviceCredential(1, "phone-1", "credential-1"),
            ),
        )

        vault.clear()

        assertNull(storage.value)
        assertNull(vault.load())
    }

    @Test
    fun corruptedCiphertextReturnsNullAndIsRemoved() {
        val storage = InMemoryKeyValueStorage("not-valid-ciphertext")
        val vault = CredentialVault(storage, XorSecretBox())

        assertNull(vault.load())
        assertNull(storage.value)
    }

    @Test
    fun corruptedPlaintextReturnsNullAndIsRemoved() {
        val secretBox = XorSecretBox()
        val storage = InMemoryKeyValueStorage(secretBox.seal("not-json".encodeToByteArray()))
        val vault = CredentialVault(storage, secretBox)

        assertNull(vault.load())
        assertNull(storage.value)
    }

    private class InMemoryKeyValueStorage(initialValue: String? = null) : KeyValueStorage {
        var value: String? = initialValue

        override fun get(): String? = value

        override fun put(value: String) {
            this.value = value
        }

        override fun remove() {
            value = null
        }
    }

    private class XorSecretBox : SecretBox {
        override fun seal(plaintext: ByteArray): String =
            Base64.getEncoder().encodeToString(plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray())

        override fun open(ciphertext: String): ByteArray =
            Base64.getDecoder().decode(ciphertext).map { (it.toInt() xor MASK).toByte() }.toByteArray()

        private companion object {
            const val MASK = 0x5a
        }
    }
}
