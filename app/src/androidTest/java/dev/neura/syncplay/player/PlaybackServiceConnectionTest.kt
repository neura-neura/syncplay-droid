package dev.neura.syncplay.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class PlaybackServiceConnectionTest {
    @Test
    fun ownApplicationCanBindToTheMpvPlaybackService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var serviceBinder: PlaybackService.LocalBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceBinder = binder as? PlaybackService.LocalBinder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(context, PlaybackService::class.java)

        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val session = requireNotNull(serviceBinder?.playbackSession)
            assertTrue(PlaybackService.currentSession() === session)
            assertFalse(session.hasMedia())
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun serviceManifestIsNotExportedToOtherApplications() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, PlaybackService::class.java)
        @Suppress("DEPRECATION")
        val serviceInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            context.packageManager.getServiceInfo(component, 0)
        }

        assertFalse(serviceInfo.exported)
        assertEquals(null, serviceInfo.permission)
    }
}
