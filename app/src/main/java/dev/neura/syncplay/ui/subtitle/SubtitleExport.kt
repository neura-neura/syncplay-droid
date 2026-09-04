package dev.neura.syncplay.ui.subtitle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream

data class ShiftedSubtitleDocument(
    val suggestedFileName: String,
    val mimeType: String,
    val text: String,
)

/** Creates the same portable SRT/VTT-style shifted export offered by Noir Player. */
object SubtitleExport {
    fun supports(fileName: String?): Boolean = extension(fileName) in setOf("srt", "vtt", "ass", "ssa", "zip")

    fun suggestedFileName(fileName: String): String {
        val sourceExtension = extension(fileName)
        val outputExtension = if (sourceExtension == "vtt") "vtt" else "srt"
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = leaf.substringBeforeLast('.', leaf).ifBlank { "subtitulos" }
        return "$stem-sincronizado.$outputExtension"
    }

    fun shifted(fileName: String, source: String, offsetMs: Long): ShiftedSubtitleDocument {
        val sourceExtension = extension(fileName)
        val cues = when (sourceExtension) {
            "ass", "ssa" -> parseAss(source)
            "srt", "vtt" -> parseTimedText(source)
            else -> emptyList()
        }
        require(cues.isNotEmpty()) { "No se encontraron eventos de subtítulos exportables" }
        val useVtt = sourceExtension == "vtt"
        val output = buildString {
            if (useVtt) append("WEBVTT\n\n")
            cues.forEachIndexed { index, cue ->
                val start = safeShift(cue.startMs, offsetMs).coerceAtLeast(0L)
                val end = safeShift(cue.endMs, offsetMs).coerceAtLeast(start + 1L)
                if (!useVtt) append(index + 1).append('\n')
                append(formatTimestamp(start, useVtt))
                    .append(" --> ")
                    .append(formatTimestamp(end, useVtt))
                    .append('\n')
                append(cue.text.trim()).append("\n\n")
            }
        }
        return ShiftedSubtitleDocument(
            suggestedFileName = suggestedFileName(fileName),
            mimeType = if (useVtt) "text/vtt" else "application/x-subrip",
            text = output,
        )
    }

    /** Decode either a direct text sidecar or Noir-compatible ZIP's first supported entry. */
    fun shiftedBytes(fileName: String, bytes: ByteArray, offsetMs: Long): ShiftedSubtitleDocument {
        val source = if (extension(fileName) == "zip") {
            firstArchiveSubtitle(bytes)
        } else {
            ArchiveSubtitle(fileName, bytes)
        }
        return shifted(source.fileName, decode(source.bytes), offsetMs)
    }

    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            StandardCharsets.UTF_16LE.decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            StandardCharsets.UTF_16BE.decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        else -> runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { bytes.toString(Charset.forName("windows-1252")) }
    }

    private data class ArchiveSubtitle(val fileName: String, val bytes: ByteArray)

    private fun firstArchiveSubtitle(bytes: ByteArray): ArchiveSubtitle {
        ZipInputStream(ByteArrayInputStream(bytes).buffered()).use { zip ->
            repeat(MAX_ARCHIVE_ENTRIES) {
                val entry = zip.nextEntry ?: throw IOException(
                    "El ZIP no contiene subtítulos SRT, VTT, ASS o SSA",
                )
                val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (!entry.isDirectory && extension(fileName) in ARCHIVE_EXTENSIONS) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1_024)
                    var total = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_ARCHIVE_ENTRY_BYTES) {
                            throw IOException("El subtítulo comprimido es demasiado grande")
                        }
                        output.write(buffer, 0, count)
                    }
                    return ArchiveSubtitle(fileName, output.toByteArray())
                }
                zip.closeEntry()
            }
        }
        throw IOException("El ZIP contiene demasiadas entradas")
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private fun parseTimedText(source: String): List<Cue> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split(Regex("\\n[ \\t]*\\n")).mapNotNull { block ->
            val lines = block.lines()
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex < 0) return@mapNotNull null
            val match = TIMED_TEXT_RANGE.matchEntire(lines[timeLineIndex].trim()) ?: return@mapNotNull null
            val start = parseTimedTextTimestamp(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTimedTextTimestamp(match.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
            text.takeIf(String::isNotEmpty)?.let { Cue(start, end.coerceAtLeast(start + 1L), it) }
        }
    }

    private fun parseAss(source: String): List<Cue> {
        var inEvents = false
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = mutableListOf<Cue>()
        source.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                return@forEach
            }
            if (!inEvents) return@forEach
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                return@forEach
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return@forEach
            val fields = trimmed.substringAfter(':').split(',', limit = format.size)
            val startIndex = format.indexOf("start")
            val endIndex = format.indexOf("end")
            val textIndex = format.indexOf("text")
            if (startIndex !in fields.indices || endIndex !in fields.indices || textIndex !in fields.indices) {
                return@forEach
            }
            val start = parseAssTimestamp(fields[startIndex]) ?: return@forEach
            val end = parseAssTimestamp(fields[endIndex]) ?: return@forEach
            val text = fields[textIndex]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (text.isNotEmpty()) cues += Cue(start, end.coerceAtLeast(start + 1L), text)
        }
        return cues.sortedWith(compareBy(Cue::startMs, Cue::endMs))
    }

    private fun parseTimedTextTimestamp(value: String): Long? {
        val pieces = value.trim().replace(',', '.').split(':')
        if (pieces.size !in 2..3) return null
        val hours = if (pieces.size == 3) pieces[0].toLongOrNull() ?: return null else 0L
        val minutes = pieces[pieces.size - 2].toLongOrNull() ?: return null
        val secondsParts = pieces.last().split('.', limit = 2)
        val seconds = secondsParts[0].toLongOrNull() ?: return null
        val millis = secondsParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        if (minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
        return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + millis
    }

    private fun parseAssTimestamp(value: String): Long? {
        val pieces = value.trim().split(':')
        if (pieces.size != 3) return null
        val hours = pieces[0].toLongOrNull() ?: return null
        val minutes = pieces[1].toLongOrNull() ?: return null
        val secondsParts = pieces[2].split('.', limit = 2)
        val seconds = secondsParts[0].toLongOrNull() ?: return null
        val centiseconds = secondsParts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
        if (minutes !in 0..59 || seconds !in 0..59 || centiseconds !in 0..99) return null
        return ((hours * 60L + minutes) * 60L + seconds) * 1_000L + centiseconds * 10L
    }

    private fun formatTimestamp(valueMs: Long, vtt: Boolean): String {
        val safe = valueMs.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d%c%03d",
            hours,
            minutes,
            seconds,
            if (vtt) '.' else ',',
            millis,
        )
    }

    private fun extension(fileName: String?): String = fileName.orEmpty()
        .substringBefore('?')
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)

    private fun safeShift(value: Long, offset: Long): Long = when {
        offset > 0L && value > Long.MAX_VALUE - offset -> Long.MAX_VALUE
        offset < 0L && value < Long.MIN_VALUE - offset -> Long.MIN_VALUE
        else -> value + offset
    }

    private val TIMED_TEXT_RANGE = Regex(
        "^([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?[,.][0-9]{1,3})\\s*-->\\s*" +
            "([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?[,.][0-9]{1,3})(?:\\s+.*)?$",
    )

    private val ARCHIVE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
    private const val MAX_ARCHIVE_ENTRIES = 1_024
    private const val MAX_ARCHIVE_ENTRY_BYTES = 64 * 1_024 * 1_024
}
