package dev.codexremote.app.security

import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun vaultInstancesSerializeLoadCleanupAndSaveForSharedStorage() {
        val storage = InMemoryKeyValueStorage("corrupted")
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val saveAttempted = CountDownLatch(1)
        val saveEntered = CountDownLatch(1)
        val loadingVault = CredentialVault(
            storage,
            BlockingFailingSecretBox(loadEntered, releaseLoad),
        )
        val savingVault = CredentialVault(storage, SignalingSecretBox(saveEntered))
        val saved = StoredBridgeConnection(
            baseUrl = "https://new-bridge.example",
            credential = DeviceCredential(1, "phone-2", "new-credential"),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val load = executor.submit<StoredBridgeConnection?> { loadingVault.load() }
            assertEquals(true, loadEntered.await(1, TimeUnit.SECONDS))
            val save = executor.submit<Unit> {
                saveAttempted.countDown()
                savingVault.save(saved)
            }

            assertEquals(true, saveAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(saveEntered.await(200, TimeUnit.MILLISECONDS))
            releaseLoad.countDown()

            assertNull(load.get(1, TimeUnit.SECONDS))
            save.get(1, TimeUnit.SECONDS)
            assertEquals(saved, savingVault.load())
        } finally {
            releaseLoad.countDown()
            executor.shutdownNow()
        }
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

    private class BlockingFailingSecretBox(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : SecretBox {
        override fun seal(plaintext: ByteArray): String = error("not used")

        override fun open(ciphertext: String): ByteArray {
            entered.countDown()
            check(release.await(1, TimeUnit.SECONDS)) { "test did not release blocked load" }
            throw IllegalArgumentException("corrupted")
        }
    }

    private class SignalingSecretBox(
        private val sealEntered: CountDownLatch,
        private val delegate: SecretBox = XorSecretBox(),
    ) : SecretBox {
        override fun seal(plaintext: ByteArray): String {
            sealEntered.countDown()
            return delegate.seal(plaintext)
        }

        override fun open(ciphertext: String): ByteArray = delegate.open(ciphertext)
    }
}
