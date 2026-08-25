package dev.codexremote.app.bridge

import android.content.Context
import android.content.SharedPreferences
import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.security.AndroidKeystoreSecretBox
import dev.codexremote.app.security.KeyValueStorage
import dev.codexremote.app.security.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class QueuedCommand(
    val command: CommandEnvelope,
    val attempts: Int = 0,
    val lastAttemptAt: String? = null,
) {
    override fun toString(): String =
        "QueuedCommand(command=<redacted>, attempts=$attempts, lastAttemptAt=$lastAttemptAt)"
}

class PendingCommandQueue(
    private val storage: KeyValueStorage,
    private val secretBox: SecretBox,
    private val json: Json = Json,
) {
    fun enqueue(command: CommandEnvelope): Boolean = synchronized(QUEUE_LOCK) {
        val commands = readQueue().toMutableList()
        if (commands.any { queued -> queued.matches(command.deviceId, command.idempotencyKey) }) {
            return@synchronized false
        }
        check(commands.size < MAX_COMMANDS) { "Pending command queue is full" }
        commands += QueuedCommand(command)
        writeQueue(commands)
        true
    }

    fun peek(): QueuedCommand? = synchronized(QUEUE_LOCK) {
        readQueue().firstOrNull()
    }

    fun markAttempt(
        deviceId: String,
        idempotencyKey: String,
        at: String,
    ) = synchronized(QUEUE_LOCK) {
        val commands = readQueue().toMutableList()
        val index = commands.indexOfFirst { it.matches(deviceId, idempotencyKey) }
        if (index >= 0) {
            val queued = commands[index]
            commands[index] = queued.copy(
                attempts = queued.attempts + 1,
                lastAttemptAt = at,
            )
            writeQueue(commands)
        }
    }

    fun removeCompleted(
        deviceId: String,
        idempotencyKey: String,
    ) = synchronized(QUEUE_LOCK) {
        val commands = readQueue().toMutableList()
        val index = commands.indexOfFirst { it.matches(deviceId, idempotencyKey) }
        if (index >= 0) {
            commands.removeAt(index)
            writeQueue(commands)
        }
    }

    fun list(): List<QueuedCommand> = synchronized(QUEUE_LOCK) {
        readQueue().toList()
    }

    private fun QueuedCommand.matches(deviceId: String, idempotencyKey: String): Boolean =
        command.deviceId == deviceId && command.idempotencyKey == idempotencyKey

    private fun readQueue(): List<QueuedCommand> {
        val encrypted = try {
            storage.get()
        } catch (_: Exception) {
            clearCorruptedQueue()
            return emptyList()
        } ?: return emptyList()

        return try {
            json.decodeFromString(secretBox.open(encrypted).decodeToString())
        } catch (_: Exception) {
            clearCorruptedQueue()
            emptyList()
        }
    }

    private fun writeQueue(commands: List<QueuedCommand>) {
        try {
            val plaintext = json.encodeToString(commands).encodeToByteArray()
            storage.put(secretBox.seal(plaintext))
        } catch (_: Exception) {
            throw IllegalStateException("Unable to securely save pending commands")
        }
    }

    private fun clearCorruptedQueue() {
        try {
            storage.remove()
        } catch (_: Exception) {
            // Reading remains fail-closed even if corrupted state cannot be removed.
        }
    }

    companion object {
        private const val MAX_COMMANDS = 100
        private val QUEUE_LOCK = Any()

        fun create(context: Context): PendingCommandQueue = PendingCommandQueue(
            storage = PendingCommandSharedPreferencesStorage(context.applicationContext),
            secretBox = AndroidKeystoreSecretBox(),
        )
    }
}

private class PendingCommandSharedPreferencesStorage(context: Context) : KeyValueStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun get(): String? = preferences.getString(VALUE_KEY, null)

    override fun put(value: String) {
        check(preferences.edit().putString(VALUE_KEY, value).commit()) {
            "Unable to persist encrypted pending commands"
        }
    }

    override fun remove() {
        check(preferences.edit().remove(VALUE_KEY).commit()) {
            "Unable to remove encrypted pending commands"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "pending_commands"
        const val VALUE_KEY = "encrypted_queue"
    }
}
