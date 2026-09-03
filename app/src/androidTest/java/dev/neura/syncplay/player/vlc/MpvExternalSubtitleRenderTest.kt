package dev.neura.syncplay.player.vlc

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.neura.syncplay.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies decoded SRT/ASS glyphs on the native surface, not only track metadata. */
@RunWith(AndroidJUnit4::class)
class MpvExternalSubtitleRenderTest {
    @Test
    fun externalSrtAndAssChangeTheRenderedVideoFrame() {
        assumeTrue("PixelCopy requires API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(instrumentation.targetContext.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app external files directory", fixture.isFile)

        subtitleCases.forEach { subtitleCase ->
            assertSubtitleChangesFrame(instrumentation, fixture, subtitleCase)
        }
    }

    private fun assertSubtitleChangesFrame(
        instrumentation: android.app.Instrumentation,
        fixture: File,
        subtitleCase: SubtitleCase,
    ) {
        val context = instrumentation.targetContext
        val sidecar = File(context.cacheDir, "mpv-render-${subtitleCase.extension}.${subtitleCase.extension}")
            .apply { writeText(subtitleCase.contents) }
        val playerRef = AtomicReference<VlcPlayer>()
        val viewRef = AtomicReference<PlayerView>()
        val nextFrame = AtomicReference(CountDownLatch(1))
        val externalAvailableAndOff = CountDownLatch(1)
        val externalSelected = CountDownLatch(1)
        val selectionRequested = AtomicBoolean(false)
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
                            nextFrame.get().countDown()
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            val reference = findTrack(tracks, subtitleCase.id) ?: return
                            if (reference.group.isTrackSelected(reference.index)) {
                                if (selectionRequested.get()) externalSelected.countDown()
                            } else {
                                externalAvailableAndOff.countDown()
                            }
                        }
                    },
                )
            }
            activity.setContentView(playerView)
            playerView.player = player
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(sidecar))
                .setId(subtitleCase.id)
                .setLabel(sidecar.name)
                .setMimeType(subtitleCase.mimeType)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val item = MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(fixture))
                .setSubtitleConfigurations(listOf(subtitle))
                .build()
            playerRef.set(player)
            viewRef.set(playerView)
            player.setMediaItem(item, TARGET_POSITION_MS)
            player.prepare()
            player.play()
        }

        var baseline: Bitmap? = null
        var withSubtitle: Bitmap? = null
        try {
            assertTrue(
                "${subtitleCase.extension}: MPV never exposed the external track in the off state",
                externalAvailableAndOff.await(25, TimeUnit.SECONDS),
            )
            assertTrue(
                "${subtitleCase.extension}: MPV never rendered the fixture's first frame",
                nextFrame.get().await(25, TimeUnit.SECONDS),
            )

            awaitExactPausedFrame(instrumentation, playerRef.get(), nextFrame)
            val baselineFrame = captureSurface(instrumentation, viewRef.get())
            baseline = baselineFrame

            instrumentation.runOnMainSync {
                val player = playerRef.get()
                val reference = findTrack(player.currentTracks, subtitleCase.id)
                    ?: error("External ${subtitleCase.extension} track disappeared")
                selectionRequested.set(true)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(
                        TrackSelectionOverride(reference.group.mediaTrackGroup, listOf(reference.index)),
                    )
                    .build()
            }
            assertTrue(
                "${subtitleCase.extension}: the external track was not selected",
                externalSelected.await(10, TimeUnit.SECONDS),
            )
            awaitExactPausedFrame(instrumentation, playerRef.get(), nextFrame)
            val subtitleFrame = captureSurface(instrumentation, viewRef.get())
            withSubtitle = subtitleFrame

            val changed = countChangedPixels(baselineFrame, subtitleFrame)
            val minimum = (baselineFrame.width * baselineFrame.height * MIN_CHANGED_RATIO).toInt()
                .coerceAtLeast(MIN_CHANGED_PIXELS)
            assertTrue(
                "${subtitleCase.extension}: selected track changed only $changed pixels; subtitle was not rendered",
                changed >= minimum,
            )
        } finally {
            baseline?.recycle()
            withSubtitle?.recycle()
            scenario.onActivity {
                viewRef.getAndSet(null)?.player = null
                playerRef.getAndSet(null)?.release()
            }
            scenario.close()
            sidecar.delete()
        }
    }

    private fun awaitExactPausedFrame(
        instrumentation: android.app.Instrumentation,
        player: VlcPlayer,
        nextFrame: AtomicReference<CountDownLatch>,
    ) {
        val rendered = CountDownLatch(1)
        nextFrame.set(rendered)
        instrumentation.runOnMainSync {
            player.pause()
            player.seekTo(TARGET_POSITION_MS)
        }
        assertTrue("MPV did not redraw the exact paused frame", rendered.await(10, TimeUnit.SECONDS))
        Thread.sleep(250L)
    }

    private fun captureSurface(
        instrumentation: android.app.Instrumentation,
        playerView: PlayerView,
    ): Bitmap {
        val surface = playerView.videoSurfaceView as? SurfaceView
            ?: error("MPV is not attached to a SurfaceView")
        check(surface.width > 0 && surface.height > 0) { "MPV surface has no size" }
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
        check(copied.await(5, TimeUnit.SECONDS)) { "PixelCopy timed out" }
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed with result $result" }
        return bitmap
    }

    private fun countChangedPixels(before: Bitmap, after: Bitmap): Int {
        check(before.width == after.width && before.height == after.height)
        var changed = 0
        for (y in 0 until before.height step PIXEL_STEP) {
            for (x in 0 until before.width step PIXEL_STEP) {
                val first = before.getPixel(x, y)
                val second = after.getPixel(x, y)
                val distance = abs(Color.red(first) - Color.red(second)) +
                    abs(Color.green(first) - Color.green(second)) +
                    abs(Color.blue(first) - Color.blue(second))
                if (distance >= MIN_RGB_DISTANCE) changed++
            }
        }
        return changed
    }

    private fun findTrack(tracks: Tracks, formatId: String): TrackReference? {
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            repeat(group.length) { index ->
                if (group.getTrackFormat(index).id == formatId) return TrackReference(group, index)
            }
        }
        return null
    }

    private data class TrackReference(val group: Tracks.Group, val index: Int)

    private data class SubtitleCase(
        val extension: String,
        val mimeType: String,
        val id: String,
        val contents: String,
    )

    private companion object {
        const val FIXTURE_NAME = "vlc-fixture.mkv"
        const val TARGET_POSITION_MS = 5_000L
        const val PIXEL_STEP = 2
        const val MIN_RGB_DISTANCE = 72
        const val MIN_CHANGED_RATIO = 0.0005f
        const val MIN_CHANGED_PIXELS = 80

        val subtitleCases = listOf(
            SubtitleCase(
                extension = "srt",
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                id = "syncplay-external-subtitle:pixel-srt",
                contents = """
                    1
                    00:00:00,000 --> 00:00:30,000
                    SYNCPLAY EXTERNAL SRT VISIBLE VISIBLE VISIBLE VISIBLE
                    SYNCPLAY EXTERNAL SRT VISIBLE VISIBLE VISIBLE VISIBLE
                    SYNCPLAY EXTERNAL SRT VISIBLE VISIBLE VISIBLE VISIBLE
                    SYNCPLAY EXTERNAL SRT VISIBLE VISIBLE VISIBLE VISIBLE
                """.trimIndent(),
            ),
            SubtitleCase(
                extension = "ass",
                mimeType = MimeTypes.TEXT_SSA,
                id = "syncplay-external-subtitle:pixel-ass",
                contents = """
                    [Script Info]
                    ScriptType: v4.00+
                    PlayResX: 640
                    PlayResY: 360

                    [V4+ Styles]
                    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                    Style: Default,sans-serif,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

                    [Events]
                    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                    Dialogue: 0,0:00:00.00,0:00:30.00,Default,,0,0,0,,{\an7\pos(0,0)\bord0\shad0\1c&H00FF00&\alpha&H00&\p1}m 0 0 l 640 0 l 640 120 l 0 120{\p0}
                """.trimIndent(),
            ),
        )
    }
}
