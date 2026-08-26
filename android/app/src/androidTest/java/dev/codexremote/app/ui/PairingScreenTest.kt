package dev.codexremote.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PairingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cameraRequestIsPrimaryAndPasteStartsCollapsed() {
        show(CameraPermission.REQUESTABLE)

        compose.onNodeWithTag("request-camera").assertIsDisplayed()
        compose.onNodeWithTag("paste-toggle").assertIsDisplayed()
        compose.onNodeWithTag("paste-field").assertDoesNotExist()
    }

    @Test
    fun deniedCameraKeepsPasteAndSettingsAvailable() {
        show(CameraPermission.DENIED)

        compose.onNodeWithTag("open-app-settings").assertIsDisplayed()
        compose.onNodeWithTag("paste-field").assertIsDisplayed()
        compose.onNodeWithTag("pair-from-paste").assertIsDisplayed()
    }

    @Test
    fun injectedScannerDeliversDecodedInvitation() {
        val invitation =
            "codex-remote://pair?baseUrl=http%3A%2F%2F100.88.10.20%3A8787&token=secret"
        var delivered: String? = null
        show(
            permission = CameraPermission.GRANTED,
            onScannedInvitation = { delivered = it },
            scanner = { onInvitation, modifier ->
                Button(
                    onClick = { onInvitation(invitation) },
                    modifier = modifier.testTag("scanner"),
                ) { Text("模拟扫码") }
            },
        )

        compose.onNodeWithTag("scanner").performClick()
        compose.runOnIdle { assertEquals(invitation, delivered) }
    }

    @Test
    fun expiredMessageIsSanitized() {
        val message = PairingFailureMessage.forKind(PairingFailureKind.EXPIRED).text
        show(CameraPermission.REQUESTABLE, errorMessage = message)

        compose.onNodeWithTag("pairing-error").assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("do-not-leak").assertDoesNotExist()
    }

    private fun show(
        permission: CameraPermission,
        errorMessage: String? = null,
        onScannedInvitation: (String) -> Unit = {},
        scanner: @androidx.compose.runtime.Composable (
            (String) -> Unit,
            Modifier,
        ) -> Unit = { _, modifier -> Text("scanner", modifier.fillMaxSize()) },
    ) {
        compose.setContent {
            MaterialTheme {
                PairingScreen(
                    busy = false,
                    errorMessage = errorMessage,
                    cameraPermission = permission,
                    onScannedInvitation = onScannedInvitation,
                    onRequestCamera = {},
                    onOpenAppSettings = {},
                    onPasteInvitation = {},
                    onOpenTailscale = {},
                    scanner = scanner,
                )
            }
        }
    }
}
