package dev.codexremote.app.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.io.Closeable

enum class DiagnosticLogLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticLogEntry(
    val timestamp: Long,
    val level: DiagnosticLogLevel,
    val stage: String,
    val message: String,
)

class DiagnosticLogStore(private val capacity: Int = DEFAULT_CAPACITY) {
    private val entries = CopyOnWriteArrayList<DiagnosticLogEntry>()
    private val listeners = CopyOnWriteArrayList<(List<DiagnosticLogEntry>) -> Unit>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun debug(stage: String, message: String) = append(DiagnosticLogLevel.DEBUG, stage, message, null)

    fun info(stage: String, message: String) = append(DiagnosticLogLevel.INFO, stage, message, null)

    fun warn(stage: String, message: String, error: Throwable? = null) =
        append(DiagnosticLogLevel.WARN, stage, message, error)

    fun error(stage: String, message: String, error: Throwable? = null) =
        append(DiagnosticLogLevel.ERROR, stage, message, error)

    fun snapshot(): List<DiagnosticLogEntry> = entries.toList()

    fun clear() {
        entries.clear()
        notifyListeners()
    }

    fun subscribe(listener: (List<DiagnosticLogEntry>) -> Unit): Closeable {
        listeners.add(listener)
        listener(snapshot())
        return Closeable { listeners.remove(listener) }
    }

    fun export(): String = snapshot().joinToString(separator = "\n") { entry ->
        val time = synchronized(DATE_FORMAT) { DATE_FORMAT.format(Date(entry.timestamp)) }
        "$time ${entry.level.name.padEnd(5)} [${entry.stage}] ${entry.message}"
    }

    private fun append(
        level: DiagnosticLogLevel,
        stage: String,
        message: String,
        error: Throwable?,
    ) {
        val suffix = error?.let { " (${it::class.java.simpleName}: ${it.message.orEmpty()})" }.orEmpty()
        entries.add(
            DiagnosticLogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                stage = stage,
                message = redact(message + suffix),
            ),
        )
        while (entries.size > capacity) entries.removeAt(0)
        notifyListeners()
    }

    private fun notifyListeners() {
        val current = snapshot()
        listeners.forEach { it(current) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
        val DATE_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

        fun redact(value: String): String = value
            .replace(Regex("(?i)(Device\\s+[^:\\s]+:)[^\\s]+"), "\$1[REDACTED]")
            .replace(Regex("(?i)(token|credential|authorization)(\\s*[:=]\\s*|%3D)[^&\\s,]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)(codex-remote://pair[^\\s]*)(token%3D|token=)[^&\\s]+"), "\$1\$2[REDACTED]")
    }
}

object DiagnosticLogs {
    val instance: DiagnosticLogStore = DiagnosticLogStore()
}
