package dev.neura.syncplay.ui.subtitle

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleExportTest {
    @Test
    fun shiftsSrtTimestampsAndClampsTheBeginning() {
        val source = """
            1
            00:00:00,250 --> 00:00:01,000
            First

            2
            00:00:02,000 --> 00:00:03,500
            Second
        """.trimIndent()

        val exported = SubtitleExport.shifted("episode.srt", source, -500L)

        assertEquals("episode-sincronizado.srt", exported.suggestedFileName)
        assertTrue(exported.text.contains("00:00:00,000 --> 00:00:00,500"))
        assertTrue(exported.text.contains("00:00:01,500 --> 00:00:03,000"))
    }

    @Test
    fun convertsAssDialogueToPortableShiftedSrt() {
        val source = """
            [Script Info]
            Title: sample
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.20,0:00:02.50,Default,,0,0,0,,{\i1}Hello\Nworld
        """.trimIndent()

        val exported = SubtitleExport.shifted("sample.ass", source, 300L)

        assertEquals("sample-sincronizado.srt", exported.suggestedFileName)
        assertTrue(exported.text.contains("00:00:01,500 --> 00:00:02,800"))
        assertTrue(exported.text.contains("Hello\nworld"))
    }

    @Test
    fun decodesUtf16LittleEndianBom() {
        val payload = "hello".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload
        assertEquals("hello", SubtitleExport.decode(bytes))
    }

    @Test
    fun exportsFirstSupportedSubtitleFromZipLikeNoir() {
        val subtitle = """
            1
            00:00:01,000 --> 00:00:02,000
            From archive
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("notes/readme.txt"))
                zip.write("ignored".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("subs/episode.srt"))
                zip.write(subtitle.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val exported = SubtitleExport.shiftedBytes("bundle.zip", bytes, 500L)

        assertEquals("episode-sincronizado.srt", exported.suggestedFileName)
        assertTrue(exported.text.contains("00:00:01,500 --> 00:00:02,500"))
        assertTrue(exported.text.contains("From archive"))
    }
}
