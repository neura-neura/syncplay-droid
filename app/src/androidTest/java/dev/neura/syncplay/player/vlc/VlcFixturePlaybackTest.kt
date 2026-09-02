package dev.neura.syncplay.player.vlc

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional device fixture test; skipped when vlc-fixture.mkv was not provisioned externally. */
@RunWith(AndroidJUnit4::class)
class VlcFixturePlaybackTest {
    @Test
    fun libVlcParsesPlaysSeeksAndSelectsExternalSubtitleForRealMatroska() {
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
        val engine = AtomicReference<LibVlcEngine>()
        instrumentation.runOnMainSync {
            engine.set(
                LibVlcEngine(context).apply {
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
                            if (!seekRequested.get() && position >= 4_000L) {
                                initialPositionReached.countDown()
                            }
                            if (seekRequested.get() && position >= 9_000L) {
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
            assertTrue("LibVLC did not expose embedded and external tracks", parsed.await(15, TimeUnit.SECONDS))
            assertTrue("LibVLC did not start playback", playing.await(15, TimeUnit.SECONDS))
            assertTrue(
                "LibVLC ignored the initial synchronized position",
                initialPositionReached.await(8, TimeUnit.SECONDS),
            )
            val snapshot = latestTracks.get()
            assertTrue(snapshot.tracks.any { it.type == VlcTrackType.VIDEO })
            assertTrue(snapshot.tracks.any { it.type == VlcTrackType.AUDIO })
            assertTrue(snapshot.tracks.count { it.type == VlcTrackType.TEXT } >= 3)
            val externalTrack = snapshot.tracks.first { it.externalId == EXTERNAL_SUBTITLE_ID }

            instrumentation.runOnMainSync { engine.get().selectSubtitleTrack(externalTrack.id) }
            assertTrue("LibVLC did not select the external SRT track", externalSelected.await(5, TimeUnit.SECONDS))

            seekRequested.set(true)
            instrumentation.runOnMainSync { engine.get().seekTo(10_000L) }
            assertTrue("LibVLC did not complete the fast seek", seekPositionReached.await(8, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { engine.getAndSet(null)?.close() }
            externalSubtitle.delete()
        }
    }

    private companion object {
        const val FIXTURE_NAME = "vlc-fixture.mkv"
        const val EXTERNAL_SUBTITLE_ID = "syncplay-external-subtitle:fixture-srt"
    }
}
