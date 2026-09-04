package dev.neura.syncplay.player.mpv

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional device fixture test; skipped when the Matroska fixture was not provisioned externally. */
@RunWith(AndroidJUnit4::class)
class MpvFixturePlaybackTest {
    @Test
    fun libMpvParsesPlaysSeeksAndSelectsExternalSubtitleForRealMatroska() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app files directory", fixture.isFile)
        val externalSubtitle = File(context.cacheDir, "mpv-fixture-external.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:30,000\nSyncplay external subtitle\n")
        }

        val parsed = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val initialPositionReached = CountDownLatch(1)
        val seekPositionReached = CountDownLatch(1)
        val seekRequested = AtomicBoolean(false)
        val externalSelected = CountDownLatch(1)
        val latestTracks = AtomicReference(MpvTrackSnapshot())
        val engine = AtomicReference<LibMpvEngine>()
        instrumentation.runOnMainSync {
            engine.set(
                LibMpvEngine(context).apply {
                    setListener { event ->
                        event.tracks?.let { tracks ->
                            latestTracks.set(tracks)
                            val externalTrack = tracks.tracks.firstOrNull {
                                it.externalId == EXTERNAL_SUBTITLE_ID
                            }
                            if (externalTrack != null && tracks.selectedSubtitleId == externalTrack.id) {
                                externalSelected.countDown()
                            }
                            if (tracks.tracks.any { it.type == MpvTrackType.VIDEO } &&
                                tracks.tracks.any { it.type == MpvTrackType.AUDIO } &&
                                tracks.tracks.count { it.type == MpvTrackType.SUBTITLE } >= 3 &&
                                externalTrack != null
                            ) {
                                parsed.countDown()
                            }
                        }
                        if (event.kind == MpvEngineEvent.Kind.PLAYING) playing.countDown()
                        if (event.kind == MpvEngineEvent.Kind.TIME_CHANGED) {
                            val position = event.positionMs ?: 0L
                            if (!seekRequested.get() && position >= 4_750L) {
                                initialPositionReached.countDown()
                            }
                            if (seekRequested.get() && position >= 9_750L) {
                                seekPositionReached.countDown()
                            }
                        }
                    }
                    setMedia(
                        Uri.fromFile(fixture),
                        listOf(
                            MpvExternalSubtitle(
                                id = EXTERNAL_SUBTITLE_ID,
                                uri = Uri.fromFile(externalSubtitle),
                                label = externalSubtitle.name,
                                mimeType = "application/x-subrip",
                                isDefault = true,
                            ),
                        ),
                        startPositionMs = 5_000L,
                    )
                    prepare()
                    play()
                },
            )
        }

        try {
            assertTrue("MPV did not expose embedded and external tracks", parsed.await(20, TimeUnit.SECONDS))
            assertTrue("MPV did not start playback", playing.await(20, TimeUnit.SECONDS))
            assertTrue(
                "MPV ignored the initial synchronized position",
                initialPositionReached.await(8, TimeUnit.SECONDS),
            )
            val snapshot = latestTracks.get()
            assertTrue(snapshot.tracks.any { it.type == MpvTrackType.VIDEO })
            assertTrue(snapshot.tracks.any { it.type == MpvTrackType.AUDIO })
            assertTrue(snapshot.tracks.count { it.type == MpvTrackType.SUBTITLE } >= 3)
            val externalTrack = snapshot.tracks.first { it.externalId == EXTERNAL_SUBTITLE_ID }

            assertTrue(
                "MPV did not automatically select the user-selected external SRT track",
                externalSelected.await(5, TimeUnit.SECONDS),
            )

            seekRequested.set(true)
            instrumentation.runOnMainSync { engine.get().seekTo(10_000L) }
            assertTrue("MPV did not complete the fast seek", seekPositionReached.await(8, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { engine.getAndSet(null)?.close() }
            externalSubtitle.delete()
        }
    }

    @Test
    fun libMpvAutomaticallySelectsExternalAssForRealMatroska() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app files directory", fixture.isFile)
        val externalSubtitle = File(context.cacheDir, "mpv-fixture-external.ass").apply {
            writeText(
                """
                [Script Info]
                ScriptType: v4.00+
                PlayResX: 640
                PlayResY: 360

                [V4+ Styles]
                Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                Style: Default,sans-serif,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.00,0:00:30.00,Default,,0,0,0,,Syncplay external ASS subtitle
                """.trimIndent(),
            )
        }

        val externalSelected = CountDownLatch(1)
        val latestTracks = AtomicReference(MpvTrackSnapshot())
        val engine = AtomicReference<LibMpvEngine>()
        instrumentation.runOnMainSync {
            engine.set(
                LibMpvEngine(context).apply {
                    setListener { event ->
                        event.tracks?.let { tracks ->
                            latestTracks.set(tracks)
                            val externalTrack = tracks.tracks.firstOrNull {
                                it.externalId == EXTERNAL_ASS_SUBTITLE_ID
                            }
                            if (externalTrack != null && tracks.selectedSubtitleId == externalTrack.id) {
                                externalSelected.countDown()
                            }
                        }
                    }
                    setMedia(
                        Uri.fromFile(fixture),
                        listOf(
                            MpvExternalSubtitle(
                                id = EXTERNAL_ASS_SUBTITLE_ID,
                                uri = Uri.fromFile(externalSubtitle),
                                label = externalSubtitle.name,
                                mimeType = "text/x-ssa",
                                isDefault = true,
                            ),
                        ),
                        startPositionMs = 5_000L,
                    )
                    prepare()
                    play()
                },
            )
        }

        try {
            assertTrue(
                "MPV did not automatically select the user-selected external ASS track",
                externalSelected.await(20, TimeUnit.SECONDS),
            )
            val externalTrack = latestTracks.get().tracks.first {
                it.externalId == EXTERNAL_ASS_SUBTITLE_ID
            }
            assertTrue(
                "MPV did not parse the external file as ASS/SSA",
                externalTrack.codec.orEmpty().contains("ass", ignoreCase = true) ||
                    externalTrack.codec.orEmpty().contains("ssa", ignoreCase = true),
            )
        } finally {
            instrumentation.runOnMainSync { engine.getAndSet(null)?.close() }
            externalSubtitle.delete()
        }
    }

    @Test
    fun rapidReplacementIgnoresEndEventsFromOlderPlaylistEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app files directory", fixture.isFile)

        val newestPositionReached = CountDownLatch(1)
        val stoppedEvents = AtomicInteger(0)
        val engine = AtomicReference<LibMpvEngine>()
        instrumentation.runOnMainSync {
            engine.set(
                LibMpvEngine(context).apply {
                    setListener { event ->
                        if (event.kind == MpvEngineEvent.Kind.STOPPED) stoppedEvents.incrementAndGet()
                        if (
                            event.kind == MpvEngineEvent.Kind.TIME_CHANGED &&
                            (event.positionMs ?: 0L) >= RAPID_REPLACEMENT_POSITION_MS - 250L
                        ) {
                            newestPositionReached.countDown()
                        }
                    }
                    listOf(1_000L, 5_000L, RAPID_REPLACEMENT_POSITION_MS).forEach { position ->
                        setMedia(Uri.fromFile(fixture), startPositionMs = position)
                        prepare()
                    }
                    play()
                },
            )
        }

        try {
            assertTrue(
                "The newest MPV load was replaced by an obsolete END_FILE event",
                newestPositionReached.await(20, TimeUnit.SECONDS),
            )
            Thread.sleep(1_000L)
            assertTrue("An older playlist entry stopped the newest load", stoppedEvents.get() == 0)
        } finally {
            instrumentation.runOnMainSync { engine.getAndSet(null)?.close() }
        }
    }

    private companion object {
        const val FIXTURE_NAME = "mpv-fixture.mkv"
        const val EXTERNAL_SUBTITLE_ID = "syncplay-external-subtitle:fixture-srt"
        const val EXTERNAL_ASS_SUBTITLE_ID = "syncplay-external-subtitle:fixture-ass"
        const val RAPID_REPLACEMENT_POSITION_MS = 12_000L
    }
}
