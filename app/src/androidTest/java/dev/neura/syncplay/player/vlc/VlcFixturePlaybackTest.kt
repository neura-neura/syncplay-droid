package dev.neura.syncplay.player.vlc

import android.net.Uri
import androidx.media3.common.MimeTypes
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
class VlcFixturePlaybackTest {
    @Test
    fun libMpvParsesPlaysSeeksAndSelectsExternalSubtitleForRealMatroska() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app external files directory", fixture.isFile)
        val externalSubtitle = File(context.cacheDir, "vlc-fixture-external.srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:30,000\nSyncplay external subtitle\n")
        }

        val parsed = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val initialPositionReached = CountDownLatch(1)
        val seekPositionReached = CountDownLatch(1)
        val seekRequested = AtomicBoolean(false)
        val externalSelected = CountDownLatch(1)
        val latestTracks = AtomicReference(VlcTrackSnapshot())
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
                            if (externalTrack != null && tracks.selectedTextId == externalTrack.id) {
                                externalSelected.countDown()
                            }
                            if (tracks.tracks.any { it.type == VlcTrackType.VIDEO } &&
                                tracks.tracks.any { it.type == VlcTrackType.AUDIO } &&
                                tracks.tracks.count { it.type == VlcTrackType.TEXT } >= 3 &&
                                externalTrack != null
                            ) {
                                parsed.countDown()
                            }
                        }
                        if (event.kind == VlcEngineEvent.Kind.PLAYING) playing.countDown()
                        if (event.kind == VlcEngineEvent.Kind.TIME_CHANGED) {
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
                            VlcExternalSubtitle(
                                id = EXTERNAL_SUBTITLE_ID,
                                uri = Uri.fromFile(externalSubtitle),
                                label = externalSubtitle.name,
                                mimeType = MimeTypes.APPLICATION_SUBRIP,
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
            assertTrue(snapshot.tracks.any { it.type == VlcTrackType.VIDEO })
            assertTrue(snapshot.tracks.any { it.type == VlcTrackType.AUDIO })
            assertTrue(snapshot.tracks.count { it.type == VlcTrackType.TEXT } >= 3)
            val externalTrack = snapshot.tracks.first { it.externalId == EXTERNAL_SUBTITLE_ID }

            instrumentation.runOnMainSync { engine.get().selectSubtitleTrack(externalTrack.id) }
            assertTrue("MPV did not select the external SRT track", externalSelected.await(5, TimeUnit.SECONDS))

            seekRequested.set(true)
            instrumentation.runOnMainSync { engine.get().seekTo(10_000L) }
            assertTrue("MPV did not complete the fast seek", seekPositionReached.await(8, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { engine.getAndSet(null)?.close() }
            externalSubtitle.delete()
        }
    }

    @Test
    fun rapidReplacementIgnoresEndEventsFromOlderPlaylistEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Provision $FIXTURE_NAME in the app external files directory", fixture.isFile)

        val newestPositionReached = CountDownLatch(1)
        val stoppedEvents = AtomicInteger(0)
        val engine = AtomicReference<LibMpvEngine>()
        instrumentation.runOnMainSync {
            engine.set(
                LibMpvEngine(context).apply {
                    setListener { event ->
                        if (event.kind == VlcEngineEvent.Kind.STOPPED) stoppedEvents.incrementAndGet()
                        if (
                            event.kind == VlcEngineEvent.Kind.TIME_CHANGED &&
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
        const val FIXTURE_NAME = "vlc-fixture.mkv"
        const val EXTERNAL_SUBTITLE_ID = "syncplay-external-subtitle:fixture-srt"
        const val RAPID_REPLACEMENT_POSITION_MS = 12_000L
    }
}
