package dev.codexremote.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import dev.codexremote.app.bridge.BridgeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PairingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pairingStartsOnAddComputerScreenWithoutOpeningCamera() {
        show(CameraPermission.GRANTED)

        compose.onNodeWithTag("start-scanner").assertIsDisplayed()
        compose.onNodeWithTag("scanner").assertDoesNotExist()
        compose.onNodeWithTag("paste-toggle").assertIsDisplayed()
        compose.onNodeWithTag("paste-field").assertDoesNotExist()
    }

    @Test
    fun cameraPermissionIsRequestedOnlyAfterOpeningScanner() {
        var requests = 0
        show(CameraPermission.REQUESTABLE, onRequestCamera = { requests += 1 })

        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("request-camera").assertDoesNotExist()
        compose.onNodeWithTag("start-scanner").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("request-camera").assertIsDisplayed()
        compose.onNodeWithTag("close-scanner").assertIsDisplayed()
    }

    @Test
    fun deniedCameraKeepsSettingsAvailableAndCanReturnToPaste() {
        show(CameraPermission.DENIED)

        compose.onNodeWithTag("start-scanner").performClick()
        compose.onNodeWithTag("open-app-settings").assertIsDisplayed()
        compose.onNodeWithTag("close-scanner").performClick()
        compose.onNodeWithTag("paste-toggle").performClick()
        compose.onNodeWithTag("paste-field").assertIsDisplayed()
        compose.onNodeWithTag("pair-from-paste").assertIsDisplayed()
    }

    @Test
    fun closeButtonStopsScannerAndReturnsToAddComputer() {
        var disposed = false
        show(
            permission = CameraPermission.GRANTED,
            scanner = { _, modifier ->
                DisposableEffect(Unit) {
                    onDispose { disposed = true }
                }
                Text("scanner", modifier.fillMaxSize().testTag("scanner"))
            },
        )

        compose.onNodeWithTag("start-scanner").performClick()
        compose.onNodeWithTag("scanner").assertIsDisplayed()
        compose.onNodeWithTag("close-scanner").performClick()
        compose.onNodeWithTag("scanner").assertDoesNotExist()
        compose.onNodeWithTag("start-scanner").assertIsDisplayed()
        compose.runOnIdle { assertTrue(disposed) }
    }

    @Test
    fun systemBackStopsScannerAndReturnsToAddComputer() {
        show(CameraPermission.GRANTED)

        compose.onNodeWithTag("start-scanner").performClick()
        compose.onNodeWithTag("scanner").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("scanner").assertDoesNotExist()
        compose.onNodeWithTag("start-scanner").assertIsDisplayed()
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

        compose.onNodeWithTag("start-scanner").performClick()
        compose.onNodeWithTag("scanner").performClick()
        compose.runOnIdle { assertEquals(invitation, delivered) }
        compose.onNodeWithTag("scanner").assertDoesNotExist()
        compose.onNodeWithTag("start-scanner").assertIsDisplayed()
    }

    @Test
    fun expiredMessageIsSanitized() {
        val message = PairingFailureMessage.from(
            BridgeApiException(
                401,
                "codex-remote://pair?token=do-not-leak",
            ),
            PairingOperation.PAIR,
        ).text
        show(CameraPermission.REQUESTABLE, errorMessage = message)

        compose.onNodeWithTag("pairing-error").assertIsDisplayed()
        compose.onNodeWithText(
            PairingFailureMessage.forKind(PairingFailureKind.EXPIRED).text,
        ).assertIsDisplayed()
        compose.onNodeWithText("do-not-leak", substring = true).assertDoesNotExist()
        compose.onNodeWithText("codex-remote://", substring = true).assertDoesNotExist()
    }

    private fun show(
        permission: CameraPermission,
        errorMessage: String? = null,
        onScannedInvitation: (String) -> Unit = {},
        onRequestCamera: () -> Unit = {},
        scanner: @androidx.compose.runtime.Composable (
            (String) -> Unit,
            Modifier,
        ) -> Unit = { _, modifier ->
            Text("scanner", modifier.fillMaxSize().testTag("scanner"))
        },
    ) {
        compose.setContent {
            MaterialTheme {
                PairingScreen(
                    busy = false,
                    errorMessage = errorMessage,
                    cameraPermission = permission,
                    onScannedInvitation = onScannedInvitation,
                    onRequestCamera = onRequestCamera,
                    onOpenAppSettings = {},
                    onPasteInvitation = {},
                    onOpenTailscale = {},
                    scanner = scanner,
                )
            }
        }
    }
}
