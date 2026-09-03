package dev.neura.syncplay.player.vlc

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import dev.neura.syncplay.smb.SmbPlaybackEnvironment
import dev.neura.syncplay.smb.SmbUri
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** External subtitle information passed from Media3's MediaItem. */
data class VlcExternalSubtitle(
    val id: String?,
    val uri: Uri,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val selectionFlags: Int = 0,
)

/** The narrow native-engine surface consumed by [VlcPlayer]. */
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
    fun clearMedia() = Unit
    fun seekTo(positionMs: Long)
    fun setRate(rate: Float)
    fun getRate(): Float
    fun setVolume(volume: Float)
    fun getVolume(): Float
    fun setVideoOutput(output: Any?)
    fun clearVideoOutput(output: Any?)
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
 * libmpv implementation used for demanding Matroska/HEVC playback.
 *
 * AndroidX Media3 remains the public Player and MediaSession contract. libmpv owns demuxing,
 * MediaCodec/software decoding, GPU presentation and libass subtitle rendering. Every blocking
 * native command runs on [commandThread], while events return to the Media3 player's looper.
 */
class LibMpvEngine(
    context: Context,
    private val eventHandler: Handler = Handler(Looper.getMainLooper()),
    private val onInitializationStage: (String) -> Unit = {},
) : VlcPlayerEngine, MPV.EventObserver {
    private val appContext = context.applicationContext
    private val commandThread = HandlerThread("Syncplay-libmpv").apply { start() }
    private val commandHandler = Handler(commandThread.looper)
    private val subtitleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Syncplay-subtitle-io").apply { isDaemon = true }
    }
    private val mediaGeneration = AtomicLong(0L)
    private val subtitleLoad = AtomicReference<SubtitleLoadJob?>()

    @Volatile private var listener: ((VlcEngineEvent) -> Unit)? = null
    @Volatile private var initializationError: Throwable? = null
    @Volatile private var snapshot = VlcTrackSnapshot()
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = C.TIME_UNSET
    @Volatile private var seekable = false
    @Volatile private var rate = 1f
    @Volatile private var volume = 1f
    @Volatile private var released = false

    /** Owned and touched only by [commandThread]. */
    private var mpv: MPV? = null
    private var pendingSource: OpenedMpvSource? = null
    private var pendingSourceGeneration = 0L
    private var activeSource: OpenedMpvSource? = null
    private var activeSourceGeneration = 0L
    private var pendingSubtitles: List<OpenedMpvSubtitle> = emptyList()
    private var activeSubtitles: List<OpenedMpvSubtitle> = emptyList()
    private var configuredSubtitles: List<VlcExternalSubtitle> = emptyList()
    private var activeLease: MpvSourceLease? = null
    /** Load commands and END_FILE events are correlated by mpv's lifetime-unique playlist id. */
    private val awaitingStartLeases = java.util.ArrayDeque<MpvSourceLease>()
    private val leasesByEntryId = mutableMapOf<Long, MpvSourceLease>()
    private var activeEntryId: Long? = null
    private var nativeLease: MpvSourceLease? = null
    private var nativeEntryId: Long? = null
    private var pendingStartPositionMs = 0L
    private var fileLoaded = false
    private var attachedSurface: Surface? = null

    /** View ownership and callbacks stay on Android's main thread. */
    private var output: Any? = null
    private var ownedSurfaceTexture: SurfaceTexture? = null
    private var ownedTextureSurface: Surface? = null

    private fun owns(holder: SurfaceHolder): Boolean = when (val current = output) {
        is SurfaceView -> current.holder === holder
        is SurfaceHolder -> current === holder
        else -> false
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!owns(holder)) return
            val (width, height) = (output as? SurfaceView)?.let { it.width to it.height }
                ?: (holder.surfaceFrame.width() to holder.surfaceFrame.height())
            attachSurfaceAsync(holder.surface, width, height)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (owns(holder)) attachSurfaceAsync(holder.surface, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (owns(holder)) detachSurfaceAsync(expectedSurface = holder.surface)
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if ((output as? TextureView)?.surfaceTexture !== surface) return
            val owned = Surface(surface)
            ownedSurfaceTexture = surface
            ownedTextureSurface = owned
            attachSurfaceAsync(owned, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            if (ownedSurfaceTexture !== surface) return
            val owned = ownedTextureSurface ?: return
            attachSurfaceAsync(owned, width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            if (ownedSurfaceTexture !== surface) return true
            val owned = ownedTextureSurface
            ownedSurfaceTexture = null
            ownedTextureSurface = null
            detachSurfaceAsync(expectedSurface = owned, releaseAfterDetach = owned)
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    init {
        commandHandler.post(::initializeNativePlayer)
    }

    override fun setListener(listener: ((VlcEngineEvent) -> Unit)?) {
        this.listener = listener
        initializationError?.let { error ->
            if (listener != null) emitPlaybackError(error)
        }
    }

    override fun setMedia(
        uri: Uri,
        externalSubtitles: List<VlcExternalSubtitle>,
        startPositionMs: Long,
    ) {
        val generation = mediaGeneration.incrementAndGet()
        cancelSubtitleLoad()
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = C.TIME_UNSET
        seekable = false
        snapshot = VlcTrackSnapshot()
        dispatch("open source") { core ->
            if (generation != mediaGeneration.get()) return@dispatch
            unloadCurrentMedia(core)
            try {
                configuredSubtitles = externalSubtitles
                pendingSource = openSource(uri)
                pendingSourceGeneration = generation
                pendingSubtitles = emptyList()
                pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
                fileLoaded = false
                emit(VlcEngineEvent(VlcEngineEvent.Kind.MEDIA_CHANGED, positionMs = positionMs))
                scheduleSubtitleLoad(generation, externalSubtitles)
            } catch (error: Throwable) {
                emitPlaybackError(error)
            }
        }
    }

    override fun prepare() {
        dispatch("prepare") { core ->
            val source = pendingSource ?: activeSource ?: return@dispatch
            if (source === activeSource && activeLease?.ended == false) return@dispatch
            emit(VlcEngineEvent(VlcEngineEvent.Kind.OPENING, positionMs = positionMs))
            core.setPropertyBoolean("pause", true)
            if (source === pendingSource) {
                activeSource = source
                activeSourceGeneration = pendingSourceGeneration
                pendingSource = null
                pendingSourceGeneration = 0L
                activeSubtitles = pendingSubtitles
                pendingSubtitles = emptyList()
            }
            val lease = activeLease?.takeIf { it.source === source } ?: MpvSourceLease(
                source = source,
                subtitles = activeSubtitles,
            ).also { activeLease = it }
            lease.ended = false
            val loadResult = core.commandNode("loadfile", source.location, "replace")
            val entryId = loadResult?.get("playlist_entry_id")?.asInt()
                ?: core.getPropertyLong("playlist/0/id")
            activeEntryId = entryId
            if (entryId != null) {
                leasesByEntryId[entryId] = lease
            } else {
                // Older mpv builds did not return the playlist id from loadfile. START_FILE is
                // still ordered and can bind the lease in the normal single-load case.
                awaitingStartLeases.addLast(lease)
            }
        }
    }

    override fun play() {
        dispatch("play") { it.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        dispatch("pause") { it.setPropertyBoolean("pause", true) }
    }

    override fun stop() {
        dispatch("stop") { core ->
            core.command("stop")
            fileLoaded = false
            emit(VlcEngineEvent(VlcEngineEvent.Kind.STOPPED, positionMs = positionMs))
        }
    }

    override fun clearMedia() {
        mediaGeneration.incrementAndGet()
        cancelSubtitleLoad()
        dispatch("clear media") { unloadCurrentMedia(it) }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        this.positionMs = target
        dispatch("seek") { core ->
            if (fileLoaded) {
                core.command("seek", seconds(target).toString(), "absolute+exact")
            } else {
                pendingStartPositionMs = target
            }
            emit(VlcEngineEvent(VlcEngineEvent.Kind.POSITION_CHANGED, positionMs = target))
        }
    }

    override fun setRate(rate: Float) {
        if (!rate.isFinite()) return
        this.rate = rate.coerceIn(0.25f, 4f)
        dispatch("speed") { it.setPropertyDouble("speed", this.rate.toDouble()) }
    }

    override fun getRate(): Float = rate

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        dispatch("volume") { it.setPropertyDouble("volume", this.volume * 100.0) }
    }

    override fun getVolume(): Float = volume

    override fun setVideoOutput(output: Any?) {
        if (released || this.output === output) return
        removeOutputCallbacks()
        detachSurfaceAsync()
        this.output = output
        when (output) {
            is SurfaceView -> {
                output.holder.addCallback(surfaceCallback)
                output.holder.surface.takeIf(Surface::isValid)?.let { surface ->
                    attachSurfaceAsync(surface, output.width, output.height)
                }
            }
            is SurfaceHolder -> {
                output.addCallback(surfaceCallback)
                output.surface.takeIf(Surface::isValid)?.let { surface ->
                    val frame = output.surfaceFrame
                    attachSurfaceAsync(surface, frame.width(), frame.height())
                }
            }
            is TextureView -> {
                output.surfaceTextureListener = textureListener
                output.surfaceTexture?.takeIf { output.isAvailable }?.let { texture ->
                    Surface(texture).also { surface ->
                        ownedSurfaceTexture = texture
                        ownedTextureSurface = surface
                        attachSurfaceAsync(surface, output.width, output.height)
                    }
                }
            }
            is Surface -> if (output.isValid) attachSurfaceAsync(output, 0, 0)
        }
    }

    override fun clearVideoOutput(output: Any?) {
        if (output != null && this.output !== output) return
        removeOutputCallbacks()
        detachSurfaceAsync()
        this.output = null
    }

    override fun selectAudioTrack(id: Int) = selectTrack("aid", id)

    override fun selectVideoTrack(id: Int) = selectTrack("vid", id)

    override fun selectSubtitleTrack(id: Int) = selectTrack("sid", id)

    override fun currentSnapshot(): VlcTrackSnapshot = snapshot
    override fun currentPositionMs(): Long = positionMs
    override fun currentDurationMs(): Long = durationMs
    override fun isSeekable(): Boolean = seekable

    override fun close() {
        if (released) return
        when (val current = output) {
            is SurfaceView -> current.holder.removeCallback(surfaceCallback)
            is SurfaceHolder -> current.removeCallback(surfaceCallback)
            is TextureView -> if (current.surfaceTextureListener === textureListener) {
                current.surfaceTextureListener = null
            }
        }
        output = null
        ownedSurfaceTexture = null
        val textureSurface = ownedTextureSurface
        ownedTextureSurface = null
        released = true
        mediaGeneration.incrementAndGet()
        cancelSubtitleLoad()
        subtitleExecutor.shutdownNow()
        listener = null
        commandHandler.post {
            val core = mpv
            if (core != null) {
                runCatching { core.setPropertyString("vo", "null") }
                runCatching { if (attachedSurface != null) core.detachSurface() }
                attachedSurface = null
                textureSurface?.release()
                runCatching { core.removeObserver(this) }
                runCatching { core.destroy() }
                mpv = null
            }
            // mpv_destroy joins the native event thread. Descriptors are therefore closed only
            // after every demux/decode reader has stopped using them.
            closeSources()
            commandThread.quitSafely()
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        postNativeEvent("property $property") {
            if (isCurrentNativeLoad()) handleLongProperty(property, value)
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        postNativeEvent("property $property") {
            if (isCurrentNativeLoad()) handleBooleanProperty(property, value)
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sid" || property == "aid" || property == "vid") {
            postNativeEvent("property $property") { if (isCurrentNativeLoad()) refreshTracks() }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        postNativeEvent("property $property") {
            if (isCurrentNativeLoad()) handleDoubleProperty(property, value)
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        postNativeEvent("property $property") {
            if (!isCurrentNativeLoad()) return@postNativeEvent
            when (property) {
                // A node callback can have been captured just before a concurrent sub-add.
                // Query the current list on our serialized thread so a stale two-track snapshot
                // cannot overwrite the newly attached external subtitle.
                "track-list" -> refreshTracks()
                "video-out-params", "video-params" -> publishVideoSize(value)
            }
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        postNativeEvent("event $eventId") { handleEvent(eventId, data) }
    }

    private fun initializeNativePlayer() {
        try {
            stage("native context")
            val core = MPV()
            core.create(appContext)
            mpv = core
            stage("decoder options")
            DEFAULT_OPTIONS.forEach { (name, value) ->
                check(core.setOptionString(name, value) >= 0) { "Unsupported mpv option: $name" }
            }
            stage("subtitle fonts")
            ensureSubtitleFont()
            check(core.setOptionString("config-dir", appContext.filesDir.absolutePath) >= 0) {
                "Unsupported mpv option: config-dir"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ContextCompat.getDisplayOrDefault(appContext).mode.refreshRate
                    .takeIf { it.isFinite() && it > 0f }
                    ?.let { refreshRate ->
                        check(core.setOptionString("display-fps-override", refreshRate.toString()) >= 0)
                    }
            }
            val certificateBundle = ensureCertificateBundle()
            check(core.setOptionString("tls-verify", "yes") >= 0)
            check(core.setOptionString("tls-ca-file", certificateBundle.absolutePath) >= 0)
            core.addObserver(this)
            stage("native initialization")
            core.init()
            OBSERVED_PROPERTIES.forEach { (name, format) -> core.observeProperty(name, format) }
            core.setPropertyString("vo", "null")
            core.setPropertyString("force-window", "no")
            stage("ready")
        } catch (error: Throwable) {
            initializationError = error
            mpv?.let { core ->
                runCatching { core.removeObserver(this) }
                runCatching { if (core.isInitialized) core.destroy() }
            }
            mpv = null
            emitPlaybackError(error)
        }
    }

    private fun handleEvent(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPV.mpvEvent.MPV_EVENT_START_FILE -> onStartFile(data)
            MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> if (isCurrentNativeLoad()) onFileLoaded()
            MPV.mpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                if (isCurrentNativeLoad()) {
                    refreshVideoSize()
                    refreshTracks()
                }
            }
            MPV.mpvEvent.MPV_EVENT_SEEK -> if (isCurrentNativeLoad()) {
                emit(VlcEngineEvent(VlcEngineEvent.Kind.BUFFERING, positionMs = positionMs))
            }
            MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> if (isCurrentNativeLoad()) {
                publishPlaybackPhase(firstFrameRendered = true)
            }
            MPV.mpvEvent.MPV_EVENT_END_FILE -> onEndFile(data)
        }
    }

    private fun onStartFile(data: MPVNode) {
        val entryId = data["playlist_entry_id"]?.asInt()
        val lease = if (entryId != null) {
            leasesByEntryId[entryId]
        } else {
            awaitingStartLeases.pollFirst()
        }
        val resolvedEntryId = entryId ?: activeEntryId?.takeIf { lease === activeLease }
        nativeLease = lease
        nativeEntryId = resolvedEntryId
        if (lease != null && resolvedEntryId != null) leasesByEntryId[resolvedEntryId] = lease
        if (lease !== activeLease || (activeEntryId != null && resolvedEntryId != activeEntryId)) return
        fileLoaded = false
        emit(VlcEngineEvent(VlcEngineEvent.Kind.OPENING, positionMs = positionMs))
    }

    private fun onFileLoaded() {
        val core = mpv ?: return
        fileLoaded = true
        addExternalSubtitles(core)
        pendingStartPositionMs.takeIf { it > 0L }?.let { target ->
            core.command("seek", seconds(target).toString(), "absolute+exact")
        }
        pendingStartPositionMs = 0L
        durationMs = core.getPropertyDouble("duration")?.let(::secondsToMs) ?: durationMs
        seekable = core.getPropertyBoolean("seekable") ?: false
        positionMs = core.getPropertyDouble("time-pos")?.let(::secondsToMs) ?: positionMs
        refreshTracks()
        refreshVideoSize()
        publishPlaybackPhase()
    }

    private fun onEndFile(data: MPVNode) {
        val entryId = data["playlist_entry_id"]?.asInt()
        // The bundled mpv exposes this lifetime-unique ID. If an older build omits it, retaining
        // the lease until destroy is safer than closing or stopping an unrelated current load.
        if (entryId == null) return
        val endedLease = leasesByEntryId.remove(entryId)
        if (nativeEntryId == entryId) {
            nativeEntryId = null
            nativeLease = null
        }
        if (endedLease !== activeLease) {
            endedLease?.close()
            return
        }
        endedLease?.ended = true
        activeEntryId = null
        fileLoaded = false
        when (data["reason"]?.asString()) {
            "eof" -> emit(VlcEngineEvent(VlcEngineEvent.Kind.END_REACHED, positionMs = positionMs))
            "error" -> emitPlaybackError(IOException("libmpv could not decode this media"))
            else -> emit(VlcEngineEvent(VlcEngineEvent.Kind.STOPPED, positionMs = positionMs))
        }
    }

    private fun handleDoubleProperty(property: String, value: Double) {
        if (!value.isFinite()) return
        when (property) {
            "time-pos" -> {
                positionMs = secondsToMs(value)
                emit(VlcEngineEvent(VlcEngineEvent.Kind.TIME_CHANGED, positionMs = positionMs))
            }
            "duration" -> {
                durationMs = secondsToMs(value)
                emit(VlcEngineEvent(VlcEngineEvent.Kind.LENGTH_CHANGED, durationMs = durationMs))
            }
            "cache-buffering-state" -> if (value < 100.0) {
                emit(VlcEngineEvent(VlcEngineEvent.Kind.BUFFERING, bufferingPercent = value.toFloat()))
            }
        }
    }

    private fun handleLongProperty(property: String, value: Long) {
        if (property == "cache-buffering-state" && value < 100L) {
            emit(VlcEngineEvent(VlcEngineEvent.Kind.BUFFERING, bufferingPercent = value.toFloat()))
        }
    }

    private fun handleBooleanProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> if (fileLoaded && mpv?.getPropertyBoolean("paused-for-cache") != true) {
                emit(
                    VlcEngineEvent(
                        if (value) VlcEngineEvent.Kind.PAUSED else VlcEngineEvent.Kind.PLAYING,
                        positionMs = positionMs,
                    ),
                )
            }
            "paused-for-cache", "seeking" -> if (value) {
                emit(VlcEngineEvent(VlcEngineEvent.Kind.BUFFERING, positionMs = positionMs))
            } else if (fileLoaded) {
                publishPlaybackPhase()
            }
            "seekable" -> {
                seekable = value
                emit(VlcEngineEvent(VlcEngineEvent.Kind.SEEKABLE_CHANGED, seekable = value))
            }
            "eof-reached" -> if (value) {
                emit(VlcEngineEvent(VlcEngineEvent.Kind.END_REACHED, positionMs = positionMs))
            }
        }
    }

    private fun publishPlaybackPhase(firstFrameRendered: Boolean = false) {
        val core = mpv ?: return
        val kind = when {
            core.getPropertyBoolean("paused-for-cache") == true ||
                core.getPropertyBoolean("seeking") == true -> VlcEngineEvent.Kind.BUFFERING
            core.getPropertyBoolean("pause") == true -> VlcEngineEvent.Kind.PAUSED
            else -> VlcEngineEvent.Kind.PLAYING
        }
        emit(
            VlcEngineEvent(
                kind = kind,
                // PLAYBACK_RESTART is also emitted for the first paused frame after prepare/seek.
                firstFrameRendered = firstFrameRendered,
                positionMs = positionMs,
                durationMs = durationMs,
                seekable = seekable,
            ),
        )
    }

    private fun isCurrentNativeLoad(): Boolean =
        nativeLease != null &&
            nativeLease === activeLease &&
            (activeEntryId == null || nativeEntryId == activeEntryId)

    private fun addExternalSubtitles(
        core: MPV,
        subtitles: List<OpenedMpvSubtitle> = activeSubtitles,
    ) {
        subtitles.forEach { subtitle ->
            val addMode = mpvSubtitleAddMode(subtitle.metadata.selectionFlags)
            val title = subtitle.metadata.id?.takeIf(String::isNotBlank)
                ?: subtitle.metadata.label?.takeIf(String::isNotBlank)
                ?: subtitle.metadata.uri.lastPathSegment.orEmpty()
            if (addMode == "select") {
                // `sid` and subtitle visibility are separate MPV properties. A user-selected
                // sidecar must recover from either an embedded track or a previous "off" choice.
                core.setPropertyBoolean("sub-visibility", true)
            }
            core.command(
                "sub-add",
                subtitle.source.location,
                addMode,
                title,
                subtitle.metadata.language.orEmpty(),
            )
        }
    }

    private fun refreshTracks() {
        mpv?.getPropertyNode("track-list")?.let(::publishTrackList)
    }

    private fun publishTrackList(node: MPVNode) {
        val core = mpv ?: return
        val mapped = node.asArray().orEmpty().mapNotNull { entry ->
            val map = entry.asMap() ?: return@mapNotNull null
            val id = map.long("id")?.toInt() ?: return@mapNotNull null
            val type = when (map.string("type")) {
                "audio" -> VlcTrackType.AUDIO
                "video" -> VlcTrackType.VIDEO
                "sub" -> VlcTrackType.TEXT
                else -> VlcTrackType.UNKNOWN
            }
            if (type == VlcTrackType.UNKNOWN) return@mapNotNull null
            val title = map.string("title")
            val externalFilename = map.string("external-filename")
            val externalId = configuredSubtitles.firstOrNull { subtitle ->
                externalTrackMatches(subtitle, title, externalFilename)
            }?.id
            VlcTrackInfo(
                id = id,
                type = type,
                codec = map.string("codec"),
                originalCodec = map.string("codec-desc"),
                bitrate = map.long("demux-bitrate")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                    ?: Format.NO_VALUE,
                language = map.string("lang"),
                description = map.string("codec-desc"),
                label = title,
                selectionFlags = (if (map.boolean("default") == true) C.SELECTION_FLAG_DEFAULT else 0) or
                    (if (map.boolean("forced") == true) C.SELECTION_FLAG_FORCED else 0),
                roleFlags = (if (map.boolean("hearing-impaired") == true) C.ROLE_FLAG_CAPTION else 0) or
                    (if (map.boolean("visual-impaired") == true) C.ROLE_FLAG_DESCRIBES_VIDEO else 0) or
                    (if (map.boolean("commentary") == true) C.ROLE_FLAG_COMMENTARY else 0),
                externalId = externalId,
                width = map.long("demux-w")?.toInt() ?: Format.NO_VALUE,
                height = map.long("demux-h")?.toInt() ?: Format.NO_VALUE,
                sampleRate = map.long("demux-samplerate")?.toInt() ?: Format.NO_VALUE,
                channelCount = map.long("demux-channel-count")?.toInt()
                    ?: parseChannelCount(map.string("audio-channels")),
                frameRate = map.double("demux-fps")?.toFloat() ?: Format.NO_VALUE.toFloat(),
                pixelWidthHeightRatio = map.double("demux-par")?.toFloat()?.takeIf { it > 0f } ?: 1f,
                rotationDegrees = map.long("demux-rotation")?.toInt() ?: 0,
            )
        }
        snapshot = VlcTrackSnapshot(
            tracks = mapped,
            selectedAudioId = selectedTrackId(core, "aid"),
            selectedVideoId = selectedTrackId(core, "vid"),
            selectedTextId = selectedTrackId(core, "sid"),
        )
        emit(VlcEngineEvent(VlcEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot))
    }

    private fun refreshVideoSize() {
        val core = mpv ?: return
        val node = core.getPropertyNode("video-out-params")
            ?: core.getPropertyNode("video-params")
            ?: return
        publishVideoSize(node)
    }

    private fun publishVideoSize(node: MPVNode) {
        val map = node.asMap() ?: return
        val width = map.long("dw")?.toInt() ?: map.long("w")?.toInt() ?: return
        val height = map.long("dh")?.toInt() ?: map.long("h")?.toInt() ?: return
        if (width <= 0 || height <= 0) return
        val ratio = map.double("aspect")
            ?.takeIf { it > 0.0 }
            ?.let { (it / (width.toDouble() / height)).toFloat() }
            ?: 1f
        emit(
            VlcEngineEvent(
                VlcEngineEvent.Kind.VOUT,
                videoSize = VlcVideoSize(width, height, ratio, map.long("rotate")?.toInt() ?: 0),
            ),
        )
    }

    private fun selectedTrackId(core: MPV, property: String): Int =
        core.getPropertyString(property)?.toIntOrNull() ?: -1

    private fun selectTrack(property: String, id: Int) {
        dispatch("select track") { core ->
            if (selectedTrackId(core, property) == id) return@dispatch
            if (id < 0) core.setPropertyString(property, "no") else core.setPropertyInt(property, id)
            refreshTracks()
        }
    }

    private fun attachSurfaceAsync(surface: Surface, width: Int, height: Int) {
        if (!surface.isValid) return
        dispatch("attach surface") { core ->
            if (!surface.isValid) return@dispatch
            if (attachedSurface !== surface) {
                detachSurface(core)
                core.attachSurface(surface)
                attachedSurface = surface
            }
            if (width > 0 && height > 0) {
                core.setPropertyString("android-surface-size", "${width}x$height")
                emit(
                    VlcEngineEvent(
                        VlcEngineEvent.Kind.SURFACE_SIZE_CHANGED,
                        surfaceSize = VlcSurfaceSize(width, height),
                    ),
                )
            }
            core.setPropertyString("vo", VIDEO_OUTPUT)
            core.setPropertyString("force-window", "yes")
        }
    }

    private fun detachSurfaceAsync(
        expectedSurface: Surface? = null,
        releaseAfterDetach: Surface? = null,
    ) {
        dispatch("detach surface") { core ->
            if (expectedSurface == null || attachedSurface === expectedSurface) detachSurface(core)
            releaseAfterDetach?.release()
        }
    }

    private fun detachSurface(core: MPV) {
        if (attachedSurface == null) return
        // Match mpv-android: deinitialize the VO before dropping the Java Surface reference.
        core.setPropertyString("vo", "null")
        core.setPropertyString("force-window", "no")
        core.detachSurface()
        attachedSurface = null
    }

    private fun removeOutputCallbacks() {
        when (val current = output) {
            is SurfaceView -> current.holder.removeCallback(surfaceCallback)
            is SurfaceHolder -> current.removeCallback(surfaceCallback)
            is TextureView -> if (current.surfaceTextureListener === textureListener) {
                current.surfaceTextureListener = null
            }
        }
        val owned = ownedTextureSurface
        ownedSurfaceTexture = null
        ownedTextureSurface = null
        if (owned != null) {
            detachSurfaceAsync(expectedSurface = owned, releaseAfterDetach = owned)
        }
    }

    private fun unloadCurrentMedia(core: MPV) {
        runCatching { core.command("stop") }
        fileLoaded = false
        pendingSource?.close()
        pendingSubtitles.forEach(OpenedMpvSubtitle::close)
        // A source which already produced END_FILE has no native readers and can close now.
        // Otherwise its lease is retained until the matching playlist_entry_id ends.
        activeLease?.takeIf { it.ended }?.close()
        pendingSource = null
        pendingSourceGeneration = 0L
        activeSource = null
        activeSourceGeneration = 0L
        pendingSubtitles = emptyList()
        activeSubtitles = emptyList()
        activeLease = null
        activeEntryId = null
        configuredSubtitles = emptyList()
        snapshot = VlcTrackSnapshot()
    }

    private fun closeSources() {
        pendingSource?.close()
        pendingSubtitles.forEach(OpenedMpvSubtitle::close)
        activeLease?.close()
        awaitingStartLeases.forEach(MpvSourceLease::close)
        leasesByEntryId.values.forEach(MpvSourceLease::close)
        nativeLease?.close()
        awaitingStartLeases.clear()
        leasesByEntryId.clear()
        activeEntryId = null
        nativeLease = null
        nativeEntryId = null
        pendingSource = null
        pendingSourceGeneration = 0L
        activeSource = null
        activeSourceGeneration = 0L
        pendingSubtitles = emptyList()
        activeSubtitles = emptyList()
        activeLease = null
    }

    private fun openSource(uri: Uri): OpenedMpvSource {
        if (uri.scheme.equals("syncplaysmb", ignoreCase = true)) {
            throw IOException("Direct SMB is handled by AndroidX Media3; use a system SMB document provider for MPV")
        }
        return openMpvLocation(appContext.contentResolver, uri)
    }

    private fun openSubtitle(
        subtitle: VlcExternalSubtitle,
        job: SubtitleLoadJob,
    ): OpenedMpvSubtitle = OpenedMpvSubtitle(
        subtitle,
        openMpvSubtitleLocation(
            context = appContext,
            subtitle = subtitle,
            isCancelled = job::isCancelled,
            onInputOpened = job::attachInput,
        ),
    )

    private fun scheduleSubtitleLoad(
        generation: Long,
        subtitles: List<VlcExternalSubtitle>,
    ) {
        if (subtitles.isEmpty() || released || generation != mediaGeneration.get()) return
        val job = SubtitleLoadJob()
        subtitleLoad.getAndSet(job)?.cancel()
        val future = subtitleExecutor.submit {
            val opened = mutableListOf<OpenedMpvSubtitle>()
            var transferred = false
            try {
                for (subtitle in subtitles) {
                    if (job.isCancelled() || generation != mediaGeneration.get()) break
                    // A stale or inaccessible sidecar is non-fatal to the movie itself.
                    runCatching { openSubtitle(subtitle, job) }.getOrNull()?.let(opened::add)
                }
                if (job.isCancelled() || generation != mediaGeneration.get()) return@submit
                transferred = commandHandler.post {
                    if (released || generation != mediaGeneration.get()) {
                        opened.forEach(OpenedMpvSubtitle::close)
                        return@post
                    }
                    when {
                        pendingSource != null && pendingSourceGeneration == generation -> {
                            pendingSubtitles.forEach(OpenedMpvSubtitle::close)
                            pendingSubtitles = opened
                        }
                        activeSource != null && activeSourceGeneration == generation -> {
                            activeSubtitles = opened
                            activeLease?.addSubtitles(opened)
                            val core = mpv
                            if (core != null && fileLoaded && isCurrentNativeLoad()) {
                                addExternalSubtitles(core, opened)
                                refreshTracks()
                            }
                        }
                        else -> opened.forEach(OpenedMpvSubtitle::close)
                    }
                }
            } finally {
                subtitleLoad.compareAndSet(job, null)
                if (!transferred) opened.forEach(OpenedMpvSubtitle::close)
            }
        }
        job.attachFuture(future)
    }

    private fun cancelSubtitleLoad() {
        subtitleLoad.getAndSet(null)?.cancel()
    }

    private fun dispatch(operation: String, block: (MPV) -> Unit) {
        if (released) return
        commandHandler.post {
            if (released) return@post
            val core = mpv
            if (core == null) {
                initializationError?.let(::emitPlaybackError)
                return@post
            }
            runCatching { block(core) }.onFailure { error ->
                emitPlaybackError(IOException("libmpv failed during $operation", error))
            }
        }
    }

    /** Keep every JNI callback failure contained so the native command looper remains alive. */
    private fun postNativeEvent(operation: String, block: () -> Unit) {
        if (released) return
        commandHandler.post {
            if (released) return@post
            runCatching(block).onFailure { error ->
                emitPlaybackError(IOException("libmpv failed while handling $operation", error))
            }
        }
    }

    private fun emit(event: VlcEngineEvent) {
        val callback = listener ?: return
        eventHandler.post { if (!released && listener === callback) callback(event) }
    }

    private fun emitPlaybackError(error: Throwable) {
        emit(VlcEngineEvent(VlcEngineEvent.Kind.ENCOUNTERED_ERROR, error = error))
    }

    private fun stage(name: String) {
        eventHandler.post { if (!released) onInitializationStage(name) }
    }

    private fun ensureCertificateBundle(): File {
        val directory = File(appContext.filesDir, "mpv").apply { mkdirs() }
        val destination = File(directory, "cacert.pem")
        if (!destination.isFile || destination.length() == 0L) {
            appContext.assets.open("cacert.pem").use { source ->
                destination.outputStream().buffered().use { target -> source.copyTo(target) }
            }
        }
        return destination
    }

    /**
     * The libmpv AAR ships the same broad fallback font used by mpv-android, but a library
     * consumer must materialize that asset itself. Without it this build has no Android system
     * font provider: text tracks decode and report a selected `sid`, yet libass renders no glyphs.
     */
    private fun ensureSubtitleFont(): File {
        val destination = File(appContext.filesDir, "subfont.ttf")
        if (!destination.isFile || destination.length() == 0L) {
            appContext.assets.open("subfont.ttf").use { source ->
                destination.outputStream().buffered().use { target -> source.copyTo(target) }
            }
        }
        return destination
    }

    private fun secondsToMs(value: Double): Long = (value * 1_000.0).toLong().coerceAtLeast(0L)
    private fun seconds(valueMs: Long): Double = valueMs / 1_000.0

    companion object {
        private const val VIDEO_OUTPUT = "gpu"

        /**
         * `mediacodec` attempts zero-copy GPU interop first; copy-back is the compatible hardware
         * fallback and mpv falls back to software when a vendor decoder rejects HEVC Main10.
         * `mediacodec_embed` is intentionally absent because it cannot render ASS/PGS/OSD.
         */
        val DEFAULT_OPTIONS: Map<String, String> = linkedMapOf(
            // The AAR's fallback `subfont.ttf` is resolved through this private config directory.
            // No user-supplied config is exposed; the directory belongs exclusively to the app.
            "config" to "yes",
            "profile" to "fast",
            "vo" to VIDEO_OUTPUT,
            "gpu-context" to "android",
            "gpu-api" to "opengl",
            "opengl-es" to "yes",
            "hwdec" to "mediacodec,mediacodec-copy",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
            "ao" to "audiotrack,opensles",
            "interpolation" to "no",
            "video-sync" to "audio",
            "hr-seek" to "yes",
            "hr-seek-framedrop" to "yes",
            "vd-lavc-threads" to "0",
            "cache" to "yes",
            "cache-pause" to "yes",
            "cache-pause-initial" to "yes",
            "demuxer-readahead-secs" to "30",
            "demuxer-max-bytes" to "134217728",
            "demuxer-max-back-bytes" to "33554432",
            "sub-auto" to "no",
            "sub-ass" to "yes",
            "embeddedfonts" to "yes",
            "osc" to "no",
            "osd-level" to "0",
            "input-default-bindings" to "no",
            "idle" to "yes",
            "keep-open" to "no",
            "audio-pitch-correction" to "yes",
        )

        private val OBSERVED_PROPERTIES = mapOf(
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seekable" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "sid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "aid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "vid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "video-out-params" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-params" to MPV.mpvFormat.MPV_FORMAT_NODE,
        )
    }
}

/** Source retained for as long as mpv may read its descriptor. */
data class OpenedMpvSource(
    val location: String,
    private val descriptor: ParcelFileDescriptor? = null,
    private val temporaryFile: File? = null,
) : Closeable {
    override fun close() {
        descriptor?.close()
        temporaryFile?.delete()
    }
}

private data class OpenedMpvSubtitle(
    val metadata: VlcExternalSubtitle,
    val source: OpenedMpvSource,
) : Closeable {
    override fun close() = source.close()
}

/** Descriptor ownership for one loadfile request; close is intentionally idempotent. */
private class MpvSourceLease(
    val source: OpenedMpvSource,
    subtitles: List<OpenedMpvSubtitle>,
) : Closeable {
    var ended: Boolean = false
    private val subtitles = subtitles.toMutableList()
    private var closed = false

    fun addSubtitles(additional: List<OpenedMpvSubtitle>) {
        if (closed) {
            additional.forEach(OpenedMpvSubtitle::close)
        } else {
            subtitles.addAll(additional)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching(source::close)
        subtitles.forEach { runCatching(it::close) }
    }
}

/** Cancellation closes the active provider stream; interruption alone is unreliable for SMB. */
private class SubtitleLoadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var input: InputStream? = null
    @Volatile private var future: Future<*>? = null

    fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

    fun attachInput(input: InputStream) {
        this.input = input
        if (cancelled.get()) runCatching(input::close)
    }

    fun attachFuture(future: Future<*>) {
        this.future = future
        if (cancelled.get()) future.cancel(true)
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { input?.close() }
        future?.cancel(true)
    }
}

/** Resolve Android provider URIs without copying a multi-gigabyte movie into app storage. */
internal fun openMpvLocation(contentResolver: ContentResolver, uri: Uri): OpenedMpvSource =
    when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_CONTENT -> openMpvContentLocation(contentResolver, uri)
        ContentResolver.SCHEME_FILE -> OpenedMpvSource(
            uri.path?.takeIf(String::isNotBlank) ?: throw IOException("File URI has no path"),
        )
        "http", "https", "rtmp", "rtmps", "rtsp", "ftp", "data" -> OpenedMpvSource(uri.toString())
        null -> OpenedMpvSource(uri.toString())
        else -> throw IOException("Unsupported media URI scheme")
    }

/** Prefer an asset descriptor for slice offsets, then support providers exposing only a PFD. */
private fun openMpvContentLocation(
    contentResolver: ContentResolver,
    uri: Uri,
): OpenedMpvSource {
    var assetFailure: Throwable? = null
    val asset = runCatching { contentResolver.openAssetFileDescriptor(uri, "r") }
        .onFailure { assetFailure = it }
        .getOrNull()
    if (asset != null) {
        try {
            val duplicate = ParcelFileDescriptor.dup(asset.fileDescriptor)
            try {
                ensureSeekable(duplicate)
                val fdLocation = fdMrlForDescriptor(duplicate.fd)
                val length = asset.length
                val location = when {
                    asset.startOffset > 0L && length != AssetFileDescriptor.UNKNOWN_LENGTH ->
                        "slice://${asset.startOffset}-${asset.startOffset + length}@$fdLocation"
                    asset.startOffset > 0L -> "slice://${asset.startOffset}@$fdLocation"
                    length != AssetFileDescriptor.UNKNOWN_LENGTH -> "slice://0-$length@$fdLocation"
                    else -> fdLocation
                }
                return OpenedMpvSource(location, duplicate)
            } catch (error: Throwable) {
                duplicate.close()
                assetFailure = error
            }
        } finally {
            asset.close()
        }
    }

    val descriptor = try {
        contentResolver.openFileDescriptor(uri, "r")
    } catch (error: Exception) {
        throw IOException("Unable to open Android document provider", error).also { wrapped ->
            assetFailure?.let(wrapped::addSuppressed)
        }
    } ?: throw IOException("Android document provider returned no file descriptor").also { wrapped ->
        assetFailure?.let(wrapped::addSuppressed)
    }
    try {
        ensureSeekable(descriptor)
        return OpenedMpvSource(fdMrlForDescriptor(descriptor.fd), descriptor)
    } catch (error: Throwable) {
        descriptor.close()
        throw IOException("Unable to retain a seekable Android document descriptor", error).also { wrapped ->
            assetFailure?.let(wrapped::addSuppressed)
        }
    }
}

/**
 * Materialize a small provider/SMB subtitle with its real extension. Passing `fd://42` to
 * `sub-add` discards the name, which can make ASS/SRT probing unreliable. Movies never take this
 * path, so a multi-gigabyte video is not copied into app storage.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun openMpvSubtitleLocation(
    context: Context,
    subtitle: VlcExternalSubtitle,
    isCancelled: () -> Boolean = { false },
    onInputOpened: (InputStream) -> Unit = {},
): OpenedMpvSource {
    if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
    val uri = subtitle.uri
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) && !SmbUri.isSmbUri(uri)) {
        return openMpvLocation(context.contentResolver, uri)
    }
    val extension = subtitleExtension(subtitle)
    val destination = File.createTempFile("syncplay-subtitle-", ".$extension", context.cacheDir)
    try {
        val input: InputStream = if (SmbUri.isSmbUri(uri)) {
            DataSourceInputStream(
                SmbPlaybackEnvironment.dataSourceFactory(context).createDataSource(),
                DataSpec(uri),
            ).apply { open() }
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Android document provider returned no subtitle stream")
        }
        onInputOpened(input)
        if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
        input.use { source ->
            destination.outputStream().buffered().use { target ->
                val buffer = ByteArray(SUBTITLE_COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > MAX_EXTERNAL_SUBTITLE_BYTES) {
                        throw IOException("External subtitle is larger than the supported limit")
                    }
                    target.write(buffer, 0, count)
                }
            }
        }
        if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
        return OpenedMpvSource(destination.absolutePath, temporaryFile = destination)
    } catch (error: Throwable) {
        destination.delete()
        if (error is IOException) throw error
        throw IOException("Unable to cache external subtitle", error)
    }
}

private fun ensureSeekable(descriptor: ParcelFileDescriptor) {
    try {
        val position = Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        Os.lseek(descriptor.fileDescriptor, position, OsConstants.SEEK_SET)
    } catch (error: ErrnoException) {
        throw IOException("The selected document provider does not support random access", error)
    }
}

private fun subtitleExtension(subtitle: VlcExternalSubtitle): String {
    val pathExtension = listOfNotNull(subtitle.label, subtitle.uri.lastPathSegment)
        .asSequence()
        .map { Uri.decode(it).substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT) }
        .firstOrNull { it in SUPPORTED_SUBTITLE_EXTENSIONS }
    if (pathExtension != null) return pathExtension
    return when (subtitle.mimeType?.lowercase(Locale.ROOT)) {
        "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa" -> "ass"
        "text/vtt" -> "vtt"
        "application/ttml+xml" -> "ttml"
        else -> "srt"
    }
}

/** Pure descriptor MRL construction used by source tests. */
internal fun fdMrlForDescriptor(fd: Int): String {
    require(fd >= 0) { "File descriptor must be non-negative" }
    return "fd://$fd"
}

/** Translate Media3's default sidecar intent to MPV's explicit add/select semantics. */
internal fun mpvSubtitleAddMode(selectionFlags: Int): String =
    if (selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) "select" else "auto"

internal fun externalTrackMatches(
    subtitle: VlcExternalSubtitle,
    title: String?,
    externalFilename: String?,
): Boolean {
    return subtitleMetadataMatches(
        title = title,
        externalFilename = externalFilename,
        id = subtitle.id,
        label = subtitle.label,
        path = subtitle.uri.lastPathSegment,
    )
}

internal fun subtitleMetadataMatches(
    title: String?,
    externalFilename: String?,
    id: String?,
    label: String?,
    path: String?,
): Boolean {
    val stableId = id?.takeIf(String::isNotBlank)
    if (stableId != null && title == stableId) return true
    val candidates = listOfNotNull(label, path)
        .map(::subtitleMatchKey)
        .filter(String::isNotEmpty)
    return listOfNotNull(title, externalFilename)
        .map(::subtitleMatchKey)
        .any { actual -> candidates.any { expected -> actual.contains(expected) || expected.contains(actual) } }
}

private fun subtitleMatchKey(value: String): String = decodeUriComponent(value)
    .substringAfterLast('/')
    .lowercase(Locale.ROOT)
    .substringBeforeLast('.')
    .filter(Char::isLetterOrDigit)

/** Percent decoder kept Android-free so matching behavior is covered by local JVM tests. */
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

private fun parseChannelCount(channels: String?): Int = when (channels?.trim()?.lowercase(Locale.ROOT)) {
    "mono" -> 1
    "stereo" -> 2
    "2.1", "3.0" -> 3
    "quad", "4.0" -> 4
    "5.0" -> 5
    "5.1" -> 6
    "6.1" -> 7
    "7.1" -> 8
    else -> Format.NO_VALUE
}

private fun Map<String, MPVNode>.string(key: String): String? = get(key)?.asString()
private fun Map<String, MPVNode>.long(key: String): Long? = get(key)?.asInt()
private fun Map<String, MPVNode>.double(key: String): Double? = when (val node = get(key)) {
    is MPVNode.DoubleNode -> node.value
    is MPVNode.IntNode -> node.value.toDouble()
    else -> null
}
private fun Map<String, MPVNode>.boolean(key: String): Boolean? = get(key)?.asBoolean()

private const val SUBTITLE_COPY_BUFFER_BYTES = 64 * 1024
private const val MAX_EXTERNAL_SUBTITLE_BYTES = 64L * 1024L * 1024L
private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml")
