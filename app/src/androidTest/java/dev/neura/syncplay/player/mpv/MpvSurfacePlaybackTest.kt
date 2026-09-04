package dev.neura.syncplay.player.mpv

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpvSurfacePlaybackTest {
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
}
