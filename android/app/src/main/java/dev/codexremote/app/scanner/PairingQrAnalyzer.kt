package dev.codexremote.app.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.codexremote.app.protocol.PairingInvitation
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PairingScanResult {
    class Invitation(val raw: String) : PairingScanResult {
        override fun equals(other: Any?): Boolean = other is Invitation && other.raw == raw

        override fun hashCode(): Int = raw.hashCode()

        override fun toString(): String = "Invitation(<redacted>)"
    }

    data object Ignored : PairingScanResult

    data object Rejected : PairingScanResult

    companion object {
        const val MAX_RAW_LENGTH = 4096

        fun parse(raw: String): PairingScanResult {
            if (!raw.startsWith("codex-remote:")) return Ignored
            if (raw.length > MAX_RAW_LENGTH) return Rejected
            return try {
                PairingInvitation.parse(raw)
                Invitation(raw)
            } catch (_: IllegalArgumentException) {
                Rejected
            }
        }
    }
}

class PairingScanGate {
    private val delivered = AtomicBoolean(false)

    fun accept(raw: String): PairingScanResult {
        if (delivered.get()) return PairingScanResult.Ignored
        val result = PairingScanResult.parse(raw)
        if (result is PairingScanResult.Invitation && !delivered.compareAndSet(false, true)) {
            return PairingScanResult.Ignored
        }
        return result
    }
}

class PairingAnalysisSession(
    private val onInvitation: (String) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val gate = PairingScanGate()
    private var closed = false
    private var processing = false
    private var finishActiveFrame: (() -> Unit)? = null

    fun analyze(
        start: (((List<String>) -> Unit) -> Unit),
        closeFrame: () -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        lateinit var finish: (List<String>) -> Unit
        finish = finish@{ rawValues ->
            if (!finished.compareAndSet(false, true)) return@finish
            try {
                synchronized(lock) {
                    processing = false
                    finishActiveFrame = null
                    if (!closed) {
                        rawValues.asSequence()
                            .map(gate::accept)
                            .filterIsInstance<PairingScanResult.Invitation>()
                            .firstOrNull()
                            ?.let { onInvitation(it.raw) }
                    }
                }
            } finally {
                closeFrame()
            }
        }
        val accepted = synchronized(lock) {
            if (closed || processing) {
                false
            } else {
                processing = true
                finishActiveFrame = { finish(emptyList()) }
                true
            }
        }
        if (!accepted) {
            closeFrame()
            return
        }
        try {
            start(finish)
        } catch (_: RuntimeException) {
            finish(emptyList())
        }
    }

    override fun close() {
        beginClose()?.invoke()
    }

    fun beginClose(): (() -> Unit)? {
        synchronized(lock) {
            if (closed) return null
            closed = true
            return finishActiveFrame
        }
    }
}

class PairingQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onInvitation: (String) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val session = PairingAnalysisSession(onInvitation)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        session.analyze(
            start = { complete ->
                val mediaImage = requireNotNull(imageProxy.image) { "camera frame has no image" }
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                )
                scanner.process(image).addOnCompleteListener { task ->
                    val values = try {
                        if (task.isSuccessful) qrValues(task.result) else emptyList()
                    } catch (_: RuntimeException) {
                        emptyList()
                    }
                    complete(values)
                }
            },
            closeFrame = imageProxy::close,
        )
    }

    private fun qrValues(barcodes: List<Barcode>): List<String> = barcodes.mapNotNull { barcode ->
        if (barcode.format == Barcode.FORMAT_QR_CODE) barcode.rawValue else null
    }

    override fun close() {
        val finishFrame = session.beginClose()
        try {
            scanner.close()
        } finally {
            finishFrame?.invoke()
        }
    }
}
