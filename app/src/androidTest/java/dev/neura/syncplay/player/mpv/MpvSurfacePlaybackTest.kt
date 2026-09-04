package dev.neura.syncplay.player.mpv

import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neura.syncplay.ui.MpvVideoOutputSurface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end guard for the audio-only regression: MPV must put non-black pixels in the UI output.
 *
 * The fixture is provisioned in the target app's files directory because committing a binary
 * video to the test APK would
 * make the instrumentation artifact unnecessarily large. When it is not present, this test is
 * intentionally skipped; the remaining MPV fixture tests still cover demux/decode state.
 */
@RunWith(AndroidJUnit4::class)
class MpvSurfacePlaybackTest {
    @Test
    fun libMpvRendersVisiblePixelsIntoComposeExternalSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app files directory", fixture.isFile)

        val firstFrame = CountDownLatch(1)
        val engineRef = AtomicReference<LibMpvEngine>()
        val scenario = ActivityScenario.launch(MpvTestActivity::class.java)
        scenario.onActivity { activity ->
            val engine = LibMpvEngine(activity).apply {
                setListener { event ->
                    if (event.firstFrameRendered) firstFrame.countDown()
                }
                setMedia(Uri.fromFile(fixture), startPositionMs = START_POSITION_MS)
                prepare()
                play()
            }
            engineRef.set(engine)
            activity.setContent {
                MpvVideoOutputSurface(
                    onAttachVideoOutput = engine::setVideoOutput,
                    onClearVideoOutput = engine::clearVideoOutput,
                )
            }
        }

        try {
            // PLAYBACK_RESTART is a useful hint but pixels are the user-visible contract. Some
            // Android 6 virtual GPUs can present before forwarding that event, so never let the
            // event hide the stronger screenshot assertion.
            firstFrame.await(FIRST_FRAME_HINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val visibleVideo = (1..FRAME_CAPTURE_ATTEMPTS).any {
                Thread.sleep(FRAME_CAPTURE_RETRY_DELAY_MS)
                captureHasVisibleVideo(instrumentation)
            }
            assertTrue("MPV surface remained black after audio/video playback started", visibleVideo)
        } finally {
            scenario.onActivity { engineRef.getAndSet(null)?.close() }
            scenario.close()
        }
    }

    @Test
    fun snapshotCarriesSurfaceAndVideoGeometry() {
        val snapshot = MpvPlaybackSnapshot(
            videoSize = MpvVideoSize(1920, 1080),
            surfaceSize = MpvSurfaceSize(1280, 720),
        )
        assertNotNull(snapshot.videoSize)
        assertEquals(1280, snapshot.surfaceSize?.width)
        assertEquals(720, snapshot.surfaceSize?.height)
    }

    private fun captureHasVisibleVideo(
        instrumentation: android.app.Instrumentation,
    ): Boolean {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false

        var visible = 0
        var sampled = 0
        val startX = bitmap.width / 10
        val endX = bitmap.width - startX
        val startY = bitmap.height / 6
        val endY = bitmap.height - startY
        val stepX = ((endX - startX) / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        val stepY = ((endY - startY) / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
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
        const val FIXTURE_NAME = "mpv-fixture.mkv"
        const val START_POSITION_MS = 5_000L
        const val FIRST_FRAME_HINT_TIMEOUT_SECONDS = 8L
        const val FRAME_CAPTURE_ATTEMPTS = 30
        const val FRAME_CAPTURE_RETRY_DELAY_MS = 500L
        const val SAMPLE_GRID_SIZE = 24
        const val MIN_VISIBLE_RGB_SUM = 72
        const val MIN_VISIBLE_SAMPLE_RATIO = 0.08f
    }
}
