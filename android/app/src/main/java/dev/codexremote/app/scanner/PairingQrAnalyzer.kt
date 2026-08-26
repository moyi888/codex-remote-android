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

class PairingQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onInvitation: (String) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val processing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val gate = PairingScanGate()

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get() || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        try {
            scanner.process(image)
                .addOnSuccessListener(::acceptBarcodes)
                .addOnCompleteListener {
                    processing.set(false)
                    imageProxy.close()
                }
        } catch (_: RuntimeException) {
            processing.set(false)
            imageProxy.close()
        }
    }

    private fun acceptBarcodes(barcodes: List<Barcode>) {
        if (closed.get()) return
        barcodes.asSequence()
            .filter { it.format == Barcode.FORMAT_QR_CODE }
            .mapNotNull(Barcode::getRawValue)
            .map(gate::accept)
            .filterIsInstance<PairingScanResult.Invitation>()
            .firstOrNull()
            ?.let { invitation ->
                if (!closed.get()) onInvitation(invitation.raw)
            }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scanner.close()
    }
}
