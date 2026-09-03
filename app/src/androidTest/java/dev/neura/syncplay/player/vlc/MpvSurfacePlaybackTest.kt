package dev.neura.syncplay.player.vlc

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neura.syncplay.MainActivity
import dev.neura.syncplay.player.PlaybackEngine
import dev.neura.syncplay.player.PlaybackEngineReason
import dev.neura.syncplay.player.PlaybackService
import dev.neura.syncplay.ui.bindPlayerViewToEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end guard for the regression where native audio played behind PlayerView's black shutter. */
@RunWith(AndroidJUnit4::class)
class MpvSurfacePlaybackTest {
    @Test
    fun hevcMain10RendersVisiblePixelsThroughMedia3PlayerView() {
        assumeTrue("PixelCopy requires API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(instrumentation.targetContext.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app external files directory", fixture.isFile)

        val firstFrame = CountDownLatch(1)
        val playerRef = AtomicReference<VlcPlayer>()
        val viewRef = AtomicReference<PlayerView>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val playerView = PlayerView(activity).apply {
                useController = false
                setBackgroundColor(Color.BLACK)
            }
            val player = VlcPlayer(activity, LibMpvEngine(activity)).apply {
                addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrame.countDown()
                        }
                    },
                )
            }
            activity.setContentView(playerView)
            playerView.player = player
            viewRef.set(playerView)
            playerRef.set(player)
            player.setMediaItem(MediaItem.fromUri(fixture.toURI().toString()), START_POSITION_MS)
            player.prepare()
            player.play()
        }

        try {
            assertTrue("Media3 never received MPV's first rendered frame", firstFrame.await(25, TimeUnit.SECONDS))
            val visibleVideo = (1..8).any {
                Thread.sleep(300L)
                captureHasVisibleVideo(instrumentation, viewRef.get())
            }
            assertTrue("MPV surface remained black after audio/video playback started", visibleVideo)
        } finally {
            scenario.onActivity {
                viewRef.getAndSet(null)?.player = null
                playerRef.getAndSet(null)?.release()
            }
            scenario.close()
        }
    }

    @Test
    fun mediaSessionEngineSwitchRebindsTheExistingControllerSurfaceToMpv() {
        assumeTrue("PixelCopy requires API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app external files directory", fixture.isFile)

        val controllerFuture = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        val controller = controllerFuture.get(10, TimeUnit.SECONDS)
        val firstFrame = CountDownLatch(1)
        val viewRef = AtomicReference<PlayerView>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync {
            controller.addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrame.countDown()
                    }
                },
            )
        }

        try {
            scenario.onActivity { activity ->
                val playerView = PlayerView(activity).apply {
                    useController = false
                    setBackgroundColor(Color.BLACK)
                }
                activity.setContentView(playerView)
                bindPlayerViewToEngine(playerView, controller, PlaybackEngine.MEDIA3)
                assertTrue(
                    PlaybackService.setPlaybackEngineNow(
                        PlaybackEngine.MPV,
                        PlaybackEngineReason.USER_SELECTION,
                        preserveCurrentMedia = false,
                    ),
                )
                bindPlayerViewToEngine(playerView, controller, PlaybackEngine.MPV)
                viewRef.set(playerView)
                controller.setMediaItem(MediaItem.fromUri(fixture.toURI().toString()), START_POSITION_MS)
                controller.prepare()
                controller.play()
            }

            assertTrue(
                "MPV never rendered after the MediaSession player swap",
                firstFrame.await(25, TimeUnit.SECONDS),
            )
            val visibleVideo = (1..8).any {
                Thread.sleep(300L)
                captureHasVisibleVideo(instrumentation, viewRef.get())
            }
            assertTrue("The controller surface stayed attached to the old Media3 player", visibleVideo)
        } finally {
            scenario.onActivity {
                viewRef.getAndSet(null)?.player = null
                controller.stop()
                controller.clearMediaItems()
                PlaybackService.setPlaybackEngineNow(
                    PlaybackEngine.MEDIA3,
                    PlaybackEngineReason.USER_SELECTION,
                    preserveCurrentMedia = false,
                )
                controller.release()
            }
            scenario.close()
        }
    }

    private fun captureHasVisibleVideo(
        instrumentation: android.app.Instrumentation,
        playerView: PlayerView,
    ): Boolean {
        val surface = playerView.videoSurfaceView as? SurfaceView ?: return false
        if (surface.width <= 0 || surface.height <= 0) return false
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        instrumentation.runOnMainSync {
            PixelCopy.request(
                surface,
                bitmap,
                { copyResult ->
                    result = copyResult
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        if (!copied.await(3, TimeUnit.SECONDS) || result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return false
        }

        var visible = 0
        var sampled = 0
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                sampled++
                if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > MIN_VISIBLE_RGB_SUM) {
                    visible++
                }
            }
        }
        bitmap.recycle()
        return sampled > 0 && visible.toFloat() / sampled >= MIN_VISIBLE_SAMPLE_RATIO
    }

    private companion object {
        const val FIXTURE_NAME = "vlc-fixture.mkv"
        const val START_POSITION_MS = 5_000L
        const val MIN_VISIBLE_RGB_SUM = 72
        const val MIN_VISIBLE_SAMPLE_RATIO = 0.08f
    }
}
