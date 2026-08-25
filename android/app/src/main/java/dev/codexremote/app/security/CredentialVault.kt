package dev.codexremote.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface KeyValueStorage {
    fun get(): String?

    fun put(value: String)

    fun remove()
}

interface SecretBox {
    fun seal(plaintext: ByteArray): String

    fun open(ciphertext: String): ByteArray
}

class CredentialVault(
    private val storage: KeyValueStorage,
    private val secretBox: SecretBox,
    private val json: Json = Json,
) {
    @Synchronized
    fun save(connection: StoredBridgeConnection) {
        val encrypted = try {
            secretBox.seal(json.encodeToString(connection).encodeToByteArray())
        } catch (_: Exception) {
            throw IllegalStateException("Unable to securely save bridge connection")
        }
        try {
            storage.put(encrypted)
        } catch (_: Exception) {
            throw IllegalStateException("Unable to securely save bridge connection")
        }
    }

    @Synchronized
    fun load(): StoredBridgeConnection? {
        val encrypted = try {
            storage.get()
        } catch (_: Exception) {
            return null
        } ?: return null

        return try {
            json.decodeFromString(secretBox.open(encrypted).decodeToString())
        } catch (_: Exception) {
            try {
                storage.remove()
            } catch (_: Exception) {
                // Loading remains fail-closed even if the backing store is unavailable.
            }
            null
        }
    }

    @Synchronized
    fun clear() {
        try {
            storage.remove()
        } catch (_: Exception) {
            throw IllegalStateException("Unable to clear saved bridge connection")
        }
    }

    companion object {
        fun create(context: Context): CredentialVault = CredentialVault(
            storage = SharedPreferencesKeyValueStorage(context.applicationContext),
            secretBox = AndroidKeystoreSecretBox(),
        )
    }
}

class SharedPreferencesKeyValueStorage(context: Context) : KeyValueStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun get(): String? = preferences.getString(VALUE_KEY, null)

    override fun put(value: String) {
        check(preferences.edit().putString(VALUE_KEY, value).commit()) {
            "Unable to persist encrypted bridge connection"
        }
    }

    override fun remove() {
        check(preferences.edit().remove(VALUE_KEY).commit()) {
            "Unable to remove encrypted bridge connection"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bridge_credentials"
        const val VALUE_KEY = "encrypted_connection"
    }
}

class AndroidKeystoreSecretBox : SecretBox {
    override fun seal(plaintext: ByteArray): String = synchronized(KEYSTORE_LOCK) {
        val iv = ByteArray(IV_LENGTH_BYTES).also(SECURE_RANDOM::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        listOf(
            FORMAT_VERSION,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        ).joinToString(SEPARATOR)
    }

    override fun open(ciphertext: String): ByteArray = synchronized(KEYSTORE_LOCK) {
        val parts = ciphertext.split(SEPARATOR, limit = 3)
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unsupported encrypted value" }
        val iv = Base64.getDecoder().decode(parts[1])
        require(iv.size == IV_LENGTH_BYTES) { "Invalid encrypted value" }
        val encrypted = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        val KEYSTORE_LOCK = Any()
        val SECURE_RANDOM = SecureRandom()
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.codexremote.app.bridge_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val SEPARATOR = "."
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
    }
}
