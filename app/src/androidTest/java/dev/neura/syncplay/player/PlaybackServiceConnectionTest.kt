package dev.neura.syncplay.player

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceConnectionTest {
    @Test
    fun ownApplicationCanConnectToTheProtectedMediaSessionService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, PlaybackService::class.java)
        val token = SessionToken(context, component)
        val future = MediaController.Builder(context, token).buildAsync()

        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            assertTrue(controller.isConnected)
        } finally {
            instrumentation.runOnMainSync(controller::release)
        }
    }

    @Test
    fun mediaSessionCanSwitchToVlcAndBackWithoutDisconnectingController() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)

        try {
            instrumentation.runOnMainSync {
                assertTrue(
                    PlaybackService.setPlaybackEngineNow(
                        PlaybackEngine.MPV,
                        PlaybackEngineReason.USER_SELECTION,
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            assertTrue(controller.isConnected)
            assertEquals(PlaybackEngine.MPV, PlaybackEngineStore.state.value.active)

            instrumentation.runOnMainSync {
                assertTrue(
                    PlaybackService.setPlaybackEngineNow(
                        PlaybackEngine.MEDIA3,
                        PlaybackEngineReason.USER_SELECTION,
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            assertTrue(controller.isConnected)
            assertEquals(PlaybackEngine.MEDIA3, PlaybackEngineStore.state.value.active)
        } finally {
            instrumentation.runOnMainSync {
                PlaybackService.setPlaybackEngineNow(
                    PlaybackEngine.MEDIA3,
                    PlaybackEngineReason.USER_SELECTION,
                )
                controller.release()
            }
        }
    }

    @Test
    fun exportedServiceRequiresThePrivilegedSystemMediaPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, PlaybackService::class.java)
        @Suppress("DEPRECATION")
        val serviceInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            context.packageManager.getServiceInfo(component, 0)
        }

        assertTrue(serviceInfo.exported)
        assertEquals("android.permission.MEDIA_CONTENT_CONTROL", serviceInfo.permission)
    }
}
