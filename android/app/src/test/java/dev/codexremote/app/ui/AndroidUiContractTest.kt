package dev.codexremote.app.ui

import android.app.Activity
import android.content.Intent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUiContractTest {
    @Test
    fun mainActivityIsAnAndroidActivity() {
        assertTrue(Activity::class.java.isAssignableFrom(dev.codexremote.app.MainActivity::class.java))
    }

    @Test
    fun manifestDeclaresLauncherAndStrictPairingDeepLink() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(locateManifest())
        val activities = document.getElementsByTagName("activity")
        val activity = (0 until activities.length)
            .map(activities::item)
            .single { node ->
                node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue ==
                    ".MainActivity"
            }

        assertEquals(
            "true",
            activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "exported").nodeValue,
        )
        assertEquals(
            "singleTop",
            activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "launchMode").nodeValue,
        )

        val filters = activity.childNodes.asSequence().filter { it.nodeName == "intent-filter" }.toList()
        assertTrue(filters.any { filter ->
            filter.attributeValues("action", "name").contains(Intent.ACTION_MAIN) &&
                filter.attributeValues("category", "name").contains(Intent.CATEGORY_LAUNCHER)
        })
        assertTrue(filters.any { filter ->
            val actions = filter.attributeValues("action", "name")
            if (!actions.contains(Intent.ACTION_VIEW)) return@any false
            val categories = filter.attributeValues("category", "name")
            val data = filter.childNodes.asSequence()
                .filter { it.nodeName == "data" }
                .singleOrNull() ?: return@any false
            categories.contains(Intent.CATEGORY_DEFAULT) &&
                categories.contains(Intent.CATEGORY_BROWSABLE) &&
                data.attributes.getNamedItemNS(ANDROID_NAMESPACE, "scheme").nodeValue ==
                "codex-remote" &&
                data.attributes.getNamedItemNS(ANDROID_NAMESPACE, "host").nodeValue == "pair"
        })
    }

    @Test
    fun notificationPermissionIsRequestedOnlyWhenRuntimePermissionApplies() {
        assertFalse(NotificationPermissionPolicy.shouldRequest(sdkInt = 32, granted = false))
        assertFalse(NotificationPermissionPolicy.shouldRequest(sdkInt = 36, granted = true))
        assertTrue(NotificationPermissionPolicy.shouldRequest(sdkInt = 33, granted = false))
    }

    @Test
    fun manifestDeclaresCameraPermissionForQrPairing() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(locateManifest())
        val permissions = document.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { index ->
            permissions.item(index).attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "name").nodeValue
        }

        assertTrue(names.contains("android.permission.CAMERA"))
    }

    private fun org.w3c.dom.Node.attributeValues(
        childName: String,
        attributeName: String,
    ): Set<String> = childNodes.asSequence()
        .filter { it.nodeName == childName }
        .map { it.attributes.getNamedItemNS(ANDROID_NAMESPACE, attributeName).nodeValue }
        .toSet()

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    private fun locateManifest(): File = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
        File("android/app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile) ?: error("Unable to locate AndroidManifest.xml")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
