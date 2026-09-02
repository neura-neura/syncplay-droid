package dev.neura.syncplay.player.vlc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.MainThread
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import dev.neura.syncplay.smb.SmbConnectionProfile
import dev.neura.syncplay.smb.SmbConnectionProfileRegistry
import dev.neura.syncplay.smb.SmbPlaybackEnvironment
import dev.neura.syncplay.smb.SmbLocation
import dev.neura.syncplay.smb.SmbUri

/** External subtitle information passed from Media3's MediaItem. */
data class VlcExternalSubtitle(
    val id: String?,
    val uri: Uri,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val selectionFlags: Int = 0,
)

/** The narrow engine surface consumed by [VlcPlayer]. */
interface VlcPlayerEngine : Closeable {
    fun setListener(listener: ((VlcEngineEvent) -> Unit)?)
    fun setMedia(
        uri: Uri,
        externalSubtitles: List<VlcExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0L,
    )
    fun prepare()
    fun play()
    fun pause()
    fun stop()
    /** Drop the current source and any provider descriptor while retaining the engine instance. */
    fun clearMedia() = Unit
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun getRate(): Float
    fun setVolume(volume: Float)
    fun getVolume(): Float
    fun setVideoOutput(output: Any?)
    fun clearVideoOutput(output: Any?)
    /** Optional stream selectors; lightweight test engines may leave these as no-ops. */
    fun selectAudioTrack(id: Int) = Unit
    fun selectVideoTrack(id: Int) = Unit
    fun selectSubtitleTrack(id: Int)
    fun currentSnapshot(): VlcTrackSnapshot
    fun currentPositionMs(): Long
    fun currentDurationMs(): Long
    fun isSeekable(): Boolean
    override fun close()
}

/**
 * LibVLC 3.7.5 implementation. This class only uses the public LGPL Java binding; no VLC source
 * or GPL application code is bundled in Syncplay Droid.
 *
 * LibVLC has no separate prepare call. [prepare] parses the media asynchronously; actual decode
 * starts when [play] is called. This preserves Media3's prepare/play contract for controllers.
 */
@MainThread
class LibVlcEngine(
    context: Context,
    options: List<String> = DEFAULT_OPTIONS,
    private val eventHandler: Handler = Handler(Looper.getMainLooper()),
    /** Process-local SMB credential registry. No password is read unless a syncplaysmb URI is used. */
    private val smbProfiles: SmbConnectionProfileRegistry = SmbPlaybackEnvironment.registry,
    private val onInitializationStage: (String) -> Unit = {},
) : VlcPlayerEngine {
    private val appContext = context.applicationContext
    private val libVlc = run {
        onInitializationStage("LibVLC")
        // LibVLC appends its device-specific audio/video defaults to the supplied list. Kotlin's
        // listOf is read-only and would make its Java constructor throw UnsupportedOperationException.
        LibVLC(appContext, options.toMutableList())
    }
    private val mediaPlayer = run {
        onInitializationStage("MediaPlayer")
        MediaPlayer(libVlc)
    }
    private var currentMedia: IMedia? = null
    private var externalSubtitles: List<VlcExternalSubtitle> = emptyList()
    private var listener: ((VlcEngineEvent) -> Unit)? = null
    private var snapshot = VlcTrackSnapshot()
    private var positionMs = 0L
    /** Initial or user-requested position retried once the native input becomes seekable. */
    private var pendingSeekPositionMs: Long? = null
    private var durationMs = Long.MIN_VALUE
    private var seekable = false
    private var parseRequested = false
    private var released = false
    private var output: Any? = null
    /** A provider-owned descriptor must stay open for the lifetime of the LibVLC media object. */
    private var sourceDescriptor: Closeable? = null
    /** Descriptors backing `fd://` side-loaded subtitles; closed with the current media item. */
    private val subtitleDescriptors = mutableListOf<ParcelFileDescriptor>()
    private var mediaGeneration = 0L
    private val layoutListener = object : IVLCVout.OnNewVideoLayoutListener {
        override fun onNewVideoLayout(
            vout: IVLCVout,
            width: Int,
            height: Int,
            visibleWidth: Int,
            visibleHeight: Int,
            sarNum: Int,
            sarDen: Int,
        ) {
            if (width <= 0 || height <= 0) return
            val ratio = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
            emit(
                VlcEngineEvent(
                    kind = VlcEngineEvent.Kind.VOUT,
                    videoSize = VlcVideoSize(width, height, ratio),
                ),
            )
        }
    }

    init {
        onInitializationStage("event listener")
    }

    override fun setListener(listener: ((VlcEngineEvent) -> Unit)?) {
        this.listener = listener
    }

    override fun setMedia(
        uri: Uri,
        externalSubtitles: List<VlcExternalSubtitle>,
        startPositionMs: Long,
    ) {
        check(!released) { "LibVlcEngine is released" }
        stopAndReleaseMedia()
        this.externalSubtitles = externalSubtitles
        snapshot = VlcTrackSnapshot()
        positionMs = 0L
        pendingSeekPositionMs = startPositionMs.takeIf { it > 0L }
        durationMs = Long.MIN_VALUE
        seekable = false
        parseRequested = false

        val smbProfile = uri.takeIf(SmbUri::isSmbUri)?.let { smbProfiles.require(SmbUri.parse(it).profileId) }
        val opened = openMedia(uri, smbProfile)
        val media = opened.media
        try {
            // setDefaultMediaPlayerOptions adds the Android-safe baseline options in LibVLC 3.x.
            media.setDefaultMediaPlayerOptions()
            startPositionMs.takeIf { it > 0L }?.let { position ->
                // Give the native input an early start hint. pendingSeekPositionMs also retries
                // after Seekable/Playing because some provider and Matroska inputs ignore the
                // option until playback has actually opened.
                media.addOption(
                    ":start-time=" + String.format(Locale.ROOT, "%.3f", position / 1_000.0),
                )
                positionMs = position
            }
            smbProfile?.applyCredentials(media)
            val generation = ++mediaGeneration
            installMediaPlayerListener(generation)
            mediaPlayer.setMedia(media)
            // MediaPlayer retains the media after setMedia. Keep the constructor's reference in
            // currentMedia until the next replacement, then release it independently of the
            // MediaPlayer-owned reference.
            currentMedia = media
            sourceDescriptor = opened.descriptor
            media.setEventListener(IMedia.EventListener { event ->
                if (event.type == IMedia.Event.ParsedChanged || event.type == IMedia.Event.DurationChanged) {
                    eventHandler.post {
                        // LibVLC can deliver a late parse event after a new item was installed.
                        // Ignore it rather than publishing stale tracks/duration to Media3.
                        if (released || currentMedia !== media || mediaGeneration != generation) return@post
                        // A metadata parse may time out on a large remote Matroska even while the
                        // playback input is valid and already decoding. Publish whatever streams
                        // LibVLC knows; only MediaPlayer.EncounteredError is a playback failure.
                        refreshTracks()
                        emit(
                            VlcEngineEvent(
                                kind = VlcEngineEvent.Kind.TRACKS_CHANGED,
                                durationMs = media.getDuration().takeIf { it >= 0L },
                                tracks = snapshot,
                            ),
                        )
                    }
                }
            })
        } catch (error: Throwable) {
            mediaGeneration++
            runCatching { mediaPlayer.setEventListener(null) }
            runCatching { media.setEventListener(null) }
            runCatching { media.release() }
            // setMedia retains its argument before returning. If configuration fails after that
            // call, clear the player immediately so the native reference cannot linger until the
            // next item or engine release.
            runCatching { mediaPlayer.setMedia(null) }
            runCatching { opened.descriptor?.close() }
            if (sourceDescriptor === opened.descriptor) sourceDescriptor = null
            currentMedia = null
            throw error
        }

        // Side-loaded subtitles are LibVLC slaves. They are attached before prepare/play so they
        // appear alongside embedded streams when ESAdded is emitted.
        externalSubtitles.forEach { subtitle ->
            var openedSubtitle: OpenedSubtitle? = null
            runCatching {
                val subtitleProfile = subtitle.uri.takeIf(SmbUri::isSmbUri)
                    ?.let { smbProfiles.require(SmbUri.parse(it).profileId) }
                val opened = openSubtitle(subtitle.uri, subtitleProfile)
                openedSubtitle = opened
                // LibVLC's SMB access module reads authentication from media options rather than
                // URI user-info. A media descriptor has one global option set, so only apply the
                // subtitle profile when it is the same profile as the main source (or when the
                // main source is not SMB); otherwise a second profile would overwrite the main
                // stream's credentials. Cross-profile slaves remain URI-safe but may require a
                // separate playback item/provider in a future adapter revision.
                if (subtitleProfile != null &&
                    (smbProfile == null || subtitleProfile.id == smbProfile.id)
                ) {
                    subtitleProfile.applyCredentials(media)
                }
                media.addSlave(
                    IMedia.Slave(
                        IMedia.Slave.Type.Subtitle,
                        /* user supplied slave priority */ 4,
                        opened.vlcUri.toString(),
                    ),
                )
                opened.descriptor?.let(subtitleDescriptors::add)
                openedSubtitle = null
            }.onFailure {
                // A missing subtitle grant must not tear down otherwise valid video playback.
                runCatching { openedSubtitle?.descriptor?.close() }
            }
        }
        emit(VlcEngineEvent(VlcEngineEvent.Kind.MEDIA_CHANGED))
    }

    override fun prepare() {
        val media = currentMedia ?: return
        if (media.isReleased) return
        if (!parseRequested && !media.isParsed) {
            val accepted = runCatching {
                // ParseNetwork handles both content:// providers and HTTP/SMB streams. The call is
                // asynchronous and does not start playback.
                media.parseAsync(IMedia.Parse.ParseNetwork)
            }.getOrDefault(false)
            // parseAsync is advisory: some content providers reject pre-parsing but their native
            // playback input still opens successfully. Do not show a false fatal error here.
            parseRequested = accepted
        }
        refreshTracks()
        emit(VlcEngineEvent(VlcEngineEvent.Kind.OPENING, tracks = snapshot))
    }

    override fun play() {
        if (released || currentMedia == null) return
        runCatching { mediaPlayer.play() }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun pause() {
        if (released) return
        runCatching { mediaPlayer.pause() }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun stop() {
        if (released) return
        runCatching { mediaPlayer.stop() }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun clearMedia() {
        if (released) return
        stopAndReleaseMedia()
        externalSubtitles = emptyList()
        snapshot = VlcTrackSnapshot()
        positionMs = 0L
        durationMs = Long.MIN_VALUE
        seekable = false
        parseRequested = false
    }

    override fun seekTo(positionMs: Long) {
        if (released || currentMedia == null) return
        this.positionMs = positionMs.coerceAtLeast(0L)
        pendingSeekPositionMs = this.positionMs
        applyPendingSeek(force = false)
    }

    override fun setRate(rate: Float) {
        if (released) return
        val safeRate = rate.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        runCatching { mediaPlayer.setRate(safeRate) }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun getRate(): Float = runCatching { mediaPlayer.getRate() }.getOrDefault(1f)

    override fun setVolume(volume: Float) {
        if (released) return
        val percent = (volume.coerceIn(0f, 1f) * 100f).toInt()
        runCatching { mediaPlayer.setVolume(percent) }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun getVolume(): Float = runCatching {
        (mediaPlayer.getVolume() / 100f).coerceIn(0f, 1f)
    }.getOrDefault(1f)

    override fun setVideoOutput(output: Any?) {
        if (released) return
        clearVideoOutput(this.output)
        this.output = output
        val vout = mediaPlayer.getVLCVout()
        runCatching {
            when (output) {
                is SurfaceHolder -> vout.setVideoSurface(output.surface, output)
                is Surface -> vout.setVideoSurface(output, null)
                is SurfaceView -> vout.setVideoView(output)
                is TextureView -> vout.setVideoView(output)
                null -> return@runCatching
                else -> return@runCatching
            }
            vout.attachViews(layoutListener)
        }.onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
    }

    override fun clearVideoOutput(output: Any?) {
        if (released) return
        if (output != null && this.output !== output) return
        runCatching { mediaPlayer.getVLCVout().detachViews() }
        this.output = null
    }

    override fun selectAudioTrack(id: Int) {
        if (released || id < -1) return
        if (snapshot.selectedAudioId == id) return
        val selected = runCatching { mediaPlayer.setAudioTrack(id) }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
            .getOrDefault(false)
        if (!selected) return
        refreshTracks()
        emit(VlcEngineEvent(VlcEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot))
    }

    override fun selectVideoTrack(id: Int) {
        if (released) return
        if (id >= 0 && snapshot.selectedVideoId == id) return
        if (id < 0 && snapshot.selectedVideoId < 0) return
        val selected = runCatching {
            if (id < 0) {
                mediaPlayer.setVideoTrackEnabled(false)
                true
            } else {
                mediaPlayer.setVideoTrackEnabled(true)
                mediaPlayer.setVideoTrack(id)
            }
        }.onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
            .getOrDefault(false)
        if (!selected) return
        refreshTracks()
        emit(VlcEngineEvent(VlcEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot))
    }

    override fun selectSubtitleTrack(id: Int) {
        if (released) return
        if (snapshot.selectedTextId == id) return
        val selected = runCatching { mediaPlayer.setSpuTrack(id) }
            .onFailure { emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = it)) }
            .getOrDefault(false)
        if (!selected) return
        refreshTracks()
        emit(VlcEngineEvent(VlcEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot))
    }

    override fun currentSnapshot(): VlcTrackSnapshot = snapshot

    override fun currentPositionMs(): Long = positionMs

    override fun currentDurationMs(): Long = durationMs

    override fun isSeekable(): Boolean = seekable

    override fun close() {
        if (released) return
        released = true
        listener = null
        runCatching { mediaPlayer.setEventListener(null) }
        runCatching { mediaPlayer.getVLCVout().detachViews() }
        runCatching { mediaPlayer.stop() }
        stopAndReleaseMedia()
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun handlePlayerEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.MediaChanged -> emit(VlcEngineEvent(VlcEngineEvent.Kind.MEDIA_CHANGED))
            MediaPlayer.Event.Opening -> emit(VlcEngineEvent(VlcEngineEvent.Kind.OPENING))
            MediaPlayer.Event.Buffering -> {
                val percent = event.getBuffering().coerceIn(0f, 100f)
                val estimate = durationMs.takeIf { it >= 0L }?.let { (it * percent / 100f).toLong() }
                emit(VlcEngineEvent(VlcEngineEvent.Kind.BUFFERING, bufferingPercent = percent, positionMs = positionMs))
                if (estimate != null) durationMs = durationMs.coerceAtLeast(estimate)
            }
            MediaPlayer.Event.Playing -> {
                applyPendingSeek(force = true)
                emit(VlcEngineEvent(VlcEngineEvent.Kind.PLAYING))
            }
            MediaPlayer.Event.Paused -> emit(VlcEngineEvent(VlcEngineEvent.Kind.PAUSED))
            MediaPlayer.Event.Stopped -> emit(VlcEngineEvent(VlcEngineEvent.Kind.STOPPED))
            MediaPlayer.Event.EndReached -> emit(VlcEngineEvent(VlcEngineEvent.Kind.END_REACHED))
            MediaPlayer.Event.EncounteredError -> emit(
                VlcEngineEvent(
                    kind = VlcEngineEvent.Kind.ENCOUNTERED_ERROR,
                    error = IllegalStateException("LibVLC playback error"),
                ),
            )
            MediaPlayer.Event.TimeChanged -> {
                positionMs = event.getTimeChanged().coerceAtLeast(0L)
                pendingSeekPositionMs?.let { requested ->
                    if (kotlin.math.abs(positionMs - requested) <= SEEK_CONFIRMATION_TOLERANCE_MS) {
                        pendingSeekPositionMs = null
                    }
                }
                emit(VlcEngineEvent(VlcEngineEvent.Kind.TIME_CHANGED, positionMs = positionMs))
            }
            MediaPlayer.Event.PositionChanged -> {
                val position = event.getPositionChanged().takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                val mapped = if (position != null && durationMs >= 0L) (durationMs * position).toLong() else positionMs
                positionMs = mapped.coerceAtLeast(0L)
                emit(VlcEngineEvent(VlcEngineEvent.Kind.POSITION_CHANGED, positionMs = positionMs))
            }
            MediaPlayer.Event.LengthChanged -> {
                durationMs = event.getLengthChanged().takeIf { it >= 0L } ?: Long.MIN_VALUE
                emit(VlcEngineEvent(VlcEngineEvent.Kind.LENGTH_CHANGED, durationMs = durationMs))
            }
            MediaPlayer.Event.SeekableChanged -> {
                seekable = event.getSeekable()
                if (seekable) applyPendingSeek(force = false)
                emit(VlcEngineEvent(VlcEngineEvent.Kind.SEEKABLE_CHANGED, seekable = seekable))
            }
            MediaPlayer.Event.Vout -> {
                refreshTracks()
                emit(VlcEngineEvent(VlcEngineEvent.Kind.VOUT, tracks = snapshot, videoSize = currentVideoSize()))
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected,
            -> {
                refreshTracks()
                emit(VlcEngineEvent(VlcEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot, videoSize = currentVideoSize()))
            }
        }
    }

    private fun refreshTracks() {
        if (released) return
        val descriptions = runCatching { mediaPlayer.getSpuTracks() }.getOrNull().orEmpty()
        val audioDescriptions = runCatching { mediaPlayer.getAudioTracks() }.getOrNull().orEmpty()
        val videoDescriptions = runCatching { mediaPlayer.getVideoTracks() }.getOrNull().orEmpty()
        val mediaTracks = buildList {
            val media = currentMedia ?: return@buildList
            repeat(media.getTrackCount()) { index -> add(runCatching { media.getTrack(index) }.getOrNull()) }
        }.filterNotNull()

        val usedExternalIndices = mutableSetOf<Int>()
        val textTracks = descriptions.filter { it.id >= 0 }.map { description ->
            val mediaTrack = mediaTracks.firstOrNull { it.id == description.id }
            val matchingExternal = externalSubtitles.indices
                .asSequence()
                .filterNot(usedExternalIndices::contains)
                .mapNotNull { index ->
                    externalSubtitles[index]
                        .takeIf { descriptionMatchesSubtitle(description.name, it) }
                        ?.let { index to it }
                }
                .firstOrNull()
            // content:// slaves are deliberately passed as fd:// so LibVLC can read them, but
            // that strips the original filename from the native track description. Streams that
            // do not belong to the parsed container are sidecars, so map those deterministically
            // in attachment order when name matching is impossible.
            val externalPair = matchingExternal ?: if (mediaTrack == null) {
                externalSubtitles.indices
                    .firstOrNull { it !in usedExternalIndices }
                    ?.let { it to externalSubtitles[it] }
            } else {
                null
            }
            val external = externalPair
                ?.also { usedExternalIndices += it.first }
                ?.second
            VlcTrackInfo(
                id = description.id,
                type = VlcTrackType.TEXT,
                // Sidecar streams may not appear in IMedia tracks. Preserve their declared MIME
                // so the Media3 facade advertises ASS/SRT as supported rather than "unknown".
                codec = mediaTrack?.codec ?: mediaTrack?.originalCodec ?: external?.mimeType,
                language = mediaTrack?.language ?: external?.language,
                description = description.name,
                label = external?.label ?: description.name,
                externalId = external?.id,
                selectionFlags = external?.selectionFlags ?: 0,
                roleFlags = if (external != null) 0 else androidx.media3.common.C.ROLE_FLAG_SUBTITLE,
            )
        }
        val audioTracks = audioDescriptions.filter { it.id >= 0 }.map { description ->
            val mediaTrack = mediaTracks.firstOrNull { it.id == description.id } as? IMedia.AudioTrack
            VlcTrackInfo(
                id = description.id,
                type = VlcTrackType.AUDIO,
                codec = mediaTrack?.codec ?: mediaTrack?.originalCodec,
                bitrate = mediaTrack?.bitrate ?: -1,
                language = mediaTrack?.language,
                description = description.name,
                label = description.name,
                channelCount = mediaTrack?.channels ?: -1,
                sampleRate = mediaTrack?.rate ?: -1,
            )
        }
        val videoTracks = videoDescriptions.filter { it.id >= 0 }.map { description ->
            val mediaTrack = mediaTracks.firstOrNull { it.id == description.id } as? IMedia.VideoTrack
            VlcTrackInfo(
                id = description.id,
                type = VlcTrackType.VIDEO,
                codec = mediaTrack?.codec ?: mediaTrack?.originalCodec,
                bitrate = mediaTrack?.bitrate ?: -1,
                language = mediaTrack?.language,
                description = description.name,
                label = description.name,
                width = mediaTrack?.width ?: -1,
                height = mediaTrack?.height ?: -1,
                frameRate = if (mediaTrack != null && mediaTrack.frameRateDen > 0) {
                    mediaTrack.frameRateNum.toFloat() / mediaTrack.frameRateDen
                } else -1f,
                pixelWidthHeightRatio = if (mediaTrack != null && mediaTrack.sarDen > 0) {
                    mediaTrack.sarNum.toFloat() / mediaTrack.sarDen
                } else 1f,
                rotationDegrees = orientationToRotation(mediaTrack?.orientation ?: 0),
            )
        }
        snapshot = VlcTrackSnapshot(
            tracks = videoTracks + audioTracks + textTracks,
            selectedAudioId = runCatching { mediaPlayer.getAudioTrack() }.getOrDefault(-1),
            selectedVideoId = runCatching { mediaPlayer.getVideoTrack() }.getOrDefault(-1),
            selectedTextId = runCatching { mediaPlayer.getSpuTrack() }.getOrDefault(-1),
        )
        durationMs = runCatching { currentMedia?.getDuration() ?: mediaPlayer.getLength() }
            .getOrDefault(durationMs)
            .takeIf { it >= 0L }
            ?: durationMs
        seekable = runCatching { mediaPlayer.isSeekable() }.getOrDefault(seekable)
    }

    private fun currentVideoSize(): VlcVideoSize? = snapshot.tracks
        .firstOrNull { it.type == VlcTrackType.VIDEO && it.id == snapshot.selectedVideoId }
        ?.let { VlcVideoSize(it.width, it.height, it.pixelWidthHeightRatio, it.rotationDegrees) }

    private fun emit(event: VlcEngineEvent) {
        if (!released) listener?.invoke(event)
    }

    private fun installMediaPlayerListener(generation: Long) {
        mediaPlayer.setEventListener(MediaPlayer.EventListener { event ->
            // Posts already queued for the prior item retain its captured generation. This keeps
            // a late Stopped/TimeChanged/Error callback from changing the newly installed item.
            eventHandler.post {
                if (!released && mediaGeneration == generation) handlePlayerEvent(event)
            }
        })
    }

    private fun applyPendingSeek(force: Boolean) {
        val target = pendingSeekPositionMs ?: return
        if (!force && !runCatching { mediaPlayer.isSeekable() }.getOrDefault(false)) return
        // Fast seeking lands on the closest usable point and avoids decoding a long HEVC GOP just
        // to service a scrub/synchronization correction. Small residual drift is handled by the
        // normal Syncplay rate-correction path.
        runCatching { mediaPlayer.setTime(target, true) }
            .onSuccess { appliedPosition ->
                if (appliedPosition >= 0L &&
                    kotlin.math.abs(appliedPosition - target) <= SEEK_CONFIRMATION_TOLERANCE_MS
                ) {
                    positionMs = appliedPosition
                    pendingSeekPositionMs = null
                }
            }
            .onFailure { error ->
                emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = error))
            }
    }

    private fun stopAndReleaseMedia() {
        // Replacing an item while LibVLC is still decoding can leave native events queued for the
        // old media. Stop first, invalidate its generation, detach MediaPlayer's reference, then
        // drop our Media reference and provider descriptors.
        val media = currentMedia
        runCatching { mediaPlayer.setEventListener(null) }
        if (media != null) runCatching { mediaPlayer.stop() }
        mediaGeneration++
        pendingSeekPositionMs = null
        currentMedia = null
        media?.let {
            runCatching { it.setEventListener(null) }
            // MediaPlayer retains the IMedia supplied to setMedia. Detach it before releasing
            // our reference and provider descriptors so clearMedia does not keep a stale native
            // item alive until another video happens to be installed.
            runCatching { mediaPlayer.setMedia(null) }
            runCatching { it.release() }
        }
        runCatching { sourceDescriptor?.close() }
        sourceDescriptor = null
        subtitleDescriptors.toList().forEach { descriptor -> runCatching { descriptor.close() } }
        subtitleDescriptors.clear()
    }

    private data class OpenedMedia(
        val media: Media,
        val descriptor: Closeable?,
    )

    private data class OpenedSubtitle(
        val vlcUri: Uri,
        val descriptor: ParcelFileDescriptor?,
    )

    /**
     * LibVLC's Uri constructor forwards `content://` literally to the native core, which cannot
     * ask a DocumentsProvider for bytes. Prefer the public descriptor constructors for provider
     * URIs and retain the descriptor until the media is released. Other URI schemes (including
     * the already-normalised SMB URI) continue through LibVLC's normal location constructor.
     */
    private fun openMedia(uri: Uri, profile: SmbConnectionProfile?): OpenedMedia {
        if (profile != null) {
            return OpenedMedia(Media(libVlc, uri.toLibVlcUri(profile)), descriptor = null)
        }
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return OpenedMedia(Media(libVlc, uri), descriptor = null)
        }

        val resolver = appContext.contentResolver
        var assetError: Throwable? = null
        val asset = try {
            resolver.openAssetFileDescriptor(uri, READ_MODE)
        } catch (error: Throwable) {
            assetError = error
            null
        }
        if (asset != null) {
            return try {
                OpenedMedia(Media(libVlc, asset), descriptor = asset)
            } catch (error: Throwable) {
                runCatching { asset.close() }
                throw error
            }
        }

        val descriptor = try {
            resolver.openFileDescriptor(uri, READ_MODE)
        } catch (error: Throwable) {
            val wrapped = IOException("Unable to open content URI for LibVLC", error)
            assetError?.let(wrapped::addSuppressed)
            throw wrapped
        } ?: run {
            val wrapped = IOException("Content provider returned no file descriptor")
            assetError?.let(wrapped::addSuppressed)
            throw wrapped
        }
        return try {
            OpenedMedia(Media(libVlc, descriptor.fileDescriptor), descriptor = descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    /**
     * Resolve provider-backed subtitle streams to an `fd://` MRL. LibVLC's slave API only accepts
     * URI strings, so passing the SAF URI itself would make the native core try to open
     * `content://` without an Android ContentResolver. A duplicated ParcelFileDescriptor keeps
     * the descriptor alive until the slave is removed/released. Asset descriptors with a non-zero
     * offset cannot be represented by `fd://`; reopen those through openFileDescriptor instead.
     */
    private fun openSubtitle(uri: Uri, profile: SmbConnectionProfile?): OpenedSubtitle {
        if (profile != null) {
            return OpenedSubtitle(uri.toLibVlcUri(profile), descriptor = null)
        }
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            return OpenedSubtitle(uri, descriptor = null)
        }

        val resolver = appContext.contentResolver
        val asset = runCatching { resolver.openAssetFileDescriptor(uri, READ_MODE) }.getOrNull()
        if (asset != null) {
            val startOffset = asset.startOffset
            if (startOffset == 0L) {
                val duplicate = runCatching { asset.parcelFileDescriptor.dup() }.getOrNull()
                runCatching { asset.close() }
                if (duplicate != null) return OpenedSubtitle(fdUri(duplicate), duplicate)
            } else {
                // The fd:// form has no offset/length fields. Closing this AFD before reopening
                // avoids leaking the provider's original descriptor on providers that expose a
                // ZIP/asset subrange.
                runCatching { asset.close() }
            }
        }

        val descriptor = resolver.openFileDescriptor(uri, READ_MODE)
            ?: throw IOException("Content provider returned no subtitle descriptor")
        return try {
            OpenedSubtitle(fdUri(descriptor), descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private fun fdUri(descriptor: ParcelFileDescriptor): Uri = fdUriForDescriptor(descriptor.fd)

    private fun orientationToRotation(orientation: Int): Int = when (orientation) {
        5, 6 -> 90
        2, 3 -> 180
        7, 4 -> 270
        else -> 0
    }

    companion object {
        private const val READ_MODE = "r"
        private const val SEEK_CONFIRMATION_TOLERANCE_MS = 1_500L

        /** Enough read-ahead for Wi-Fi/SMB jitter without turning every seek into a long stall. */
        val DEFAULT_OPTIONS: List<String> = listOf(
            "--no-video-title-show",
            "--avcodec-hw=any",
            "--network-caching=2000",
            "--file-caching=1000",
        )
    }
}

/**
 * Convert the credential-free app URI into LibVLC's regular SMB URI. This function deliberately
 * keeps user-info out of the authority; credentials are supplied as media options instead.
 */
internal fun Uri.toLibVlcUri(profile: SmbConnectionProfile?): Uri {
    if (profile == null) return this
    val location = SmbUri.parse(this)
    return Uri.parse(smbLocationToLibVlcMrl(location, profile))
}

/** Pure MRL construction kept separate from Android [Uri] calls for deterministic unit tests. */
internal fun smbLocationToLibVlcMrl(
    location: SmbLocation,
    profile: SmbConnectionProfile,
): String {
    val host = profile.host
    require(host == host.trim() && host.isNotEmpty()) { "SMB host must not contain surrounding whitespace" }
    require(host.none { it.isWhitespace() || it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' }) {
        "SMB host contains an unsupported character"
    }
    val authorityHost = if (host.contains(':') && !host.startsWith('[') && !host.endsWith(']')) {
        "[$host]"
    } else {
        host
    }
    val pathSegments = listOf(location.shareName) +
        SmbUriPathSegments.from(location.path)
    val encodedPath = pathSegments.joinToString(
        separator = "/",
        prefix = "/",
    ) { encodeSmbSegment(it) }
    return "smb://$authorityHost:${profile.port}$encodedPath"
}

private object SmbUriPathSegments {
    fun from(path: String): List<String> = if (path.isEmpty()) emptyList() else path.split('/')
}

private const val SMB_SEGMENT_ALLOWED = "-_.~!$&'()*+,;=:@"

private fun encodeSmbSegment(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val result = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val code = byte.toInt() and 0xff
        val character = code.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            SMB_SEGMENT_ALLOWED.indexOf(character) >= 0
        ) {
            result.append(character)
        } else {
            result.append('%')
            result.append(HEX_DIGITS[code ushr 4])
            result.append(HEX_DIGITS[code and 0x0f])
        }
    }
    return result.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** Pure `fd://` MRL construction kept visible for descriptor/SAF tests. */
internal fun fdUriForDescriptor(fd: Int): Uri {
    return Uri.parse(fdMrlForDescriptor(fd))
}

internal fun fdMrlForDescriptor(fd: Int): String {
    require(fd >= 0) { "File descriptor must be non-negative" }
    return "fd://$fd"
}

/** Match LibVLC's slave display name to Media3 subtitle metadata without exposing a URI. */
internal fun descriptionMatchesSubtitle(
    description: String?,
    subtitle: VlcExternalSubtitle,
): Boolean = subtitleDescriptionMatches(
    description = description,
    label = subtitle.label,
    path = subtitle.uri.lastPathSegment,
)

/** Pure subtitle-name matcher used by the LibVLC adapter and JVM tests. */
internal fun subtitleDescriptionMatches(
    description: String?,
    label: String?,
    path: String?,
): Boolean {
    val descriptionText = description?.trim().orEmpty()
    if (descriptionText.isEmpty()) return false
    val descriptionKey = subtitleMatchKey(descriptionText)
    return listOfNotNull(
        label,
        path,
    ).any { candidate ->
        val candidateText = decodeUriComponent(candidate).substringAfterLast('/').trim()
        if (candidateText.isEmpty()) return@any false
        val candidateKey = subtitleMatchKey(candidateText)
        candidateText.equals(descriptionText, ignoreCase = true) ||
            descriptionText.contains(candidateText, ignoreCase = true) ||
            candidateText.contains(descriptionText, ignoreCase = true) ||
            (descriptionKey.isNotEmpty() && candidateKey.isNotEmpty() && descriptionKey.contains(candidateKey))
    }
}

private fun subtitleMatchKey(value: String): String = value
    .lowercase(Locale.ROOT)
    .substringBeforeLast('.')
    .filter(Char::isLetterOrDigit)

private fun decodeUriComponent(value: String): String {
    if ('%' !in value) return value
    val bytes = java.io.ByteArrayOutputStream(value.length)
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                bytes.write((high shl 4) or low)
                index += 3
                continue
            }
        }
        if (bytes.size() > 0) {
            result.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
        result.append(value[index])
        index++
    }
    if (bytes.size() > 0) result.append(String(bytes.toByteArray(), Charsets.UTF_8))
    return result.toString()
}

/** Add credentials as LibVLC media options, never as URI user-info or diagnostic metadata. */
private fun SmbConnectionProfile.applyCredentials(media: Media) {
    if (username.isNotEmpty()) media.addOption(":smb-user=$username")
    val password = passwordCopy()
    try {
        if (password.isNotEmpty()) media.addOption(":smb-pwd=${String(password)}")
    } finally {
        password.fill('\u0000')
    }
    domain?.takeIf { it.isNotEmpty() }?.let { media.addOption(":smb-domain=$it") }
}
