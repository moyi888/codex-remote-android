package dev.codexremote.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codexremote.app.scanner.PairingScanner

enum class CameraPermission { GRANTED, REQUESTABLE, DENIED }

enum class PairingPrimaryAction { SCAN, REQUEST_CAMERA, PASTE }

data class PairingPresentation(
    val primaryAction: PairingPrimaryAction,
    val showScanner: Boolean,
    val pasteAvailable: Boolean = true,
    val pasteExpandedByDefault: Boolean,
    val showAppSettings: Boolean = false,
)

fun pairingPresentation(permission: CameraPermission): PairingPresentation = when (permission) {
    CameraPermission.GRANTED -> PairingPresentation(
        PairingPrimaryAction.SCAN,
        showScanner = true,
        pasteExpandedByDefault = false,
    )
    CameraPermission.REQUESTABLE -> PairingPresentation(
        PairingPrimaryAction.REQUEST_CAMERA,
        showScanner = false,
        pasteExpandedByDefault = false,
    )
    CameraPermission.DENIED -> PairingPresentation(
        PairingPrimaryAction.PASTE,
        showScanner = false,
        pasteExpandedByDefault = false,
        showAppSettings = true,
    )
}

@Composable
fun PairingScreen(
    busy: Boolean,
    errorMessage: String?,
    cameraPermission: CameraPermission,
    onScannedInvitation: (String) -> Unit,
    onRequestCamera: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPasteInvitation: (String) -> Unit,
    onOpenTailscale: () -> Unit,
    scanner: @Composable ((String) -> Unit, Modifier) -> Unit = { onInvitation, modifier ->
        PairingScanner(onInvitation = onInvitation, modifier = modifier)
    },
) {
    val presentation = pairingPresentation(cameraPermission)
    var scannerOpen by remember { mutableStateOf(false) }
    var pasteExpanded by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf("") }
    val closeScanner = { scannerOpen = false }
    BackHandler(enabled = scannerOpen, onBack = closeScanner)

    if (scannerOpen) {
        ScannerScreen(
            busy = busy,
            errorMessage = errorMessage,
            presentation = presentation,
            onScannedInvitation = { invitation ->
                closeScanner()
                onScannedInvitation(invitation)
            },
            onRequestCamera = onRequestCamera,
            onOpenAppSettings = onOpenAppSettings,
            onClose = closeScanner,
            scanner = scanner,
        )
        return
    }

    AddComputerScreen(
        busy = busy,
        errorMessage = errorMessage,
        pasteExpanded = pasteExpanded,
        invitation = invitation,
        onOpenScanner = {
            scannerOpen = true
            if (cameraPermission == CameraPermission.REQUESTABLE) onRequestCamera()
        },
        onOpenTailscale = onOpenTailscale,
        onExpandPaste = { pasteExpanded = true },
        onInvitationChange = { invitation = it },
        onPasteInvitation = { onPasteInvitation(invitation) },
    )
}

@Composable
private fun AddComputerScreen(
    busy: Boolean,
    errorMessage: String?,
    pasteExpanded: Boolean,
    invitation: String,
    onOpenScanner: () -> Unit,
    onOpenTailscale: () -> Unit,
    onExpandPaste: () -> Unit,
    onInvitationChange: (String) -> Unit,
    onPasteInvitation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Codex Remote", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("扫描电脑上的二维码，即可通过 Tailscale 连接家中 Codex。")
        Spacer(Modifier.height(20.dp))

        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在连接…")
        } else {
            Button(
                onClick = onOpenScanner,
                modifier = Modifier.fillMaxWidth().testTag("start-scanner"),
            ) {
                Text("扫码添加电脑")
            }
        }

        PairingError(errorMessage)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenTailscale, enabled = !busy) {
            Text("打开 Tailscale")
        }
        if (!pasteExpanded) {
            TextButton(
                onClick = onExpandPaste,
                enabled = !busy,
                modifier = Modifier.testTag("paste-toggle"),
            ) {
                Text("无法扫码？粘贴配对链接")
            }
        } else {
            OutlinedTextField(
                value = invitation,
                onValueChange = onInvitationChange,
                modifier = Modifier.fillMaxWidth().testTag("paste-field"),
                label = { Text("codex-remote:// 配对链接") },
                minLines = 3,
                enabled = !busy,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPasteInvitation,
                enabled = !busy && invitation.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("pair-from-paste"),
            ) {
                Text("使用链接配对")
            }
        }
    }
}

@Composable
private fun ScannerScreen(
    busy: Boolean,
    errorMessage: String?,
    presentation: PairingPresentation,
    onScannedInvitation: (String) -> Unit,
    onRequestCamera: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
    scanner: @Composable ((String) -> Unit, Modifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("close-scanner"),
            ) {
                Text("关闭扫码")
            }
        }
        Text("扫描电脑二维码", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("将电脑端显示的二维码放入取景框。")
        Spacer(Modifier.height(20.dp))

        when {
            busy -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在连接…")
            }
            presentation.showScanner -> scanner(
                onScannedInvitation,
                Modifier.fillMaxWidth().height(360.dp),
            )
            presentation.primaryAction == PairingPrimaryAction.REQUEST_CAMERA -> Button(
                onClick = onRequestCamera,
                modifier = Modifier.fillMaxWidth().testTag("request-camera"),
            ) {
                Text("允许相机并扫码")
            }
            else -> {
                Text("相机权限未开启，请在应用设置中允许相机权限。")
                if (presentation.showAppSettings) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenAppSettings,
                        modifier = Modifier.fillMaxWidth().testTag("open-app-settings"),
                    ) {
                        Text("打开应用设置")
                    }
                }
            }
        }
        PairingError(errorMessage)
    }
}

@Composable
private fun PairingError(errorMessage: String?) {
    errorMessage?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("pairing-error"),
        )
    }
}
