package dev.codexremote.app.service

import android.app.Service
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidServiceContractTest {
    @Test
    fun remoteServiceIsAnAndroidService() {
        assertTrue(Service::class.java.isAssignableFrom(CodexRemoteService::class.java))
    }

    @Test
    fun manifestDeclaresForegroundNetworkAndNotificationContract() {
        val manifest = locateManifest()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val permissions = document.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length).map { index ->
            permissions.item(index).attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue
        }.toSet()
        assertTrue(permissionNames.containsAll(REQUIRED_PERMISSIONS))

        val services = document.getElementsByTagName("service")
        val remoteService = (0 until services.length)
            .map(services::item)
            .single { node ->
                node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue ==
                    ".service.CodexRemoteService"
            }
        assertEquals(
            "false",
            remoteService.attributes.getNamedItemNS(ANDROID_NAMESPACE, "exported").nodeValue,
        )
        assertEquals(
            "remoteMessaging",
            remoteService.attributes.getNamedItemNS(
                ANDROID_NAMESPACE,
                "foregroundServiceType",
            ).nodeValue,
        )
    }

    private fun locateManifest(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
        File("android/app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile) ?: error("Unable to locate AndroidManifest.xml")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val REQUIRED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
        )
    }
}
