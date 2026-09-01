package dev.neura.syncplay

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWithAppContractTest {
    private val contract = OpenWithAppContract()

    @Test
    fun discoversVisibleFilePickerAppsWithoutDuplicateComponents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targets = findFilePickerTargets(context, listOf("video/*", "audio/*"))

        assertTrue(targets.isNotEmpty())
        assertEquals(targets.size, targets.map { it.component }.distinct().size)
        assertTrue(targets.all {
            it.action == Intent.ACTION_OPEN_DOCUMENT || it.action == Intent.ACTION_GET_CONTENT
        })
    }

    @Test
    fun createsOpenDocumentChooserWithMimeFiltersAsFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chooser = contract.createIntent(
            context,
            OpenWithAppRequest(listOf("video/*", "audio/*"), "Abrir video con…"),
        )
        val target = requireNotNull(
            IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
        )

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, target.action)
        assertTrue(target.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", target.type)
        assertArrayEquals(
            arrayOf("video/*", "audio/*"),
            target.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(target.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun createsWildcardOpenDocumentWithoutMimeExtras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chooser = contract.createIntent(
            context,
            OpenWithAppRequest(listOf("*/*"), "Abrir subtítulos con…"),
        )
        val target = requireNotNull(
            IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
        )

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, target.action)
        assertEquals("*/*", target.type)
        assertTrue(target.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertNull(target.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun launchesTheSelectedFileManagerDirectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName("com.example.files", "com.example.files.PickerActivity")
        val intent = contract.createIntent(
            context,
            OpenWithAppRequest(
                mimeTypes = listOf("video/*"),
                chooserTitle = "Abrir video con…",
                action = Intent.ACTION_GET_CONTENT,
                component = component,
            ),
        )

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals(component, intent.component)
        assertEquals("video/*", intent.type)
    }

    @Test
    fun parsesSuccessAndIgnoresCancellation() {
        val uri = Uri.parse("content://example/video.mp4")
        val data = Intent().setData(uri).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )

        val picked = requireNotNull(contract.parseResult(Activity.RESULT_OK, data))
        assertEquals(uri, picked.uri)
        assertTrue(picked.grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(picked.grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, data))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
        assertEquals(
            uri,
            contract.parseResult(
                Activity.RESULT_OK,
                Intent().apply { clipData = ClipData.newRawUri("media", uri) },
            )?.uri,
        )
    }
}
