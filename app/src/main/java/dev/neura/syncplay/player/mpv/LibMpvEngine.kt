package dev.neura.syncplay.player.mpv

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
import java.util.zip.ZipInputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/** External subtitle metadata passed directly to libmpv. */
data class MpvExternalSubtitle(
    val id: String?,
    val uri: Uri,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)

/**
 * A Compose-managed Android surface plus its current layout size.
 *
 * [androidx.compose.foundation.AndroidExternalSurface] owns the platform surface lifecycle. This
 * handle lets MPV receive geometry changes without tearing down and recreating its OpenGL output.
 */
class MpvSurfaceOutput(
    val surface: Surface,
    width: Int,
    height: Int,
) {
    @Volatile var width: Int = width
        private set
    @Volatile var height: Int = height
        private set

    fun updateSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }
}

/** The narrow native-engine surface consumed by [MpvPlaybackSession]. */
interface MpvPlayerEngine : Closeable {
    fun setListener(listener: ((MpvEngineEvent) -> Unit)?)
    fun setMedia(
        uri: Uri,
        externalSubtitles: List<MpvExternalSubtitle> = emptyList(),
        startPositionMs: Long = 0L,
    )
    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun clearMedia() = Unit
    fun seekTo(positionMs: Long, exact: Boolean = false)
    fun setRate(rate: Float)
    fun getRate(): Float
    fun setVolume(volume: Float)
    fun getVolume(): Float
    fun replaceExternalSubtitles(subtitles: List<MpvExternalSubtitle>) = Unit
    fun applySubtitleAppearance(properties: Map<String, Any>) = Unit
    fun setSubtitleDelay(delayMs: Long) = Unit
    fun alignSubtitleCue(skip: Int) = Unit
    fun setSubtitleVisibility(visible: Boolean) = Unit
    fun setVideoOutput(output: Any?)
    fun clearVideoOutput(output: Any?)
    fun selectAudioTrack(id: Int) = Unit
    fun selectVideoTrack(id: Int) = Unit
    fun selectSubtitleTrack(id: Int)
    fun currentSnapshot(): MpvTrackSnapshot
    fun currentPositionMs(): Long
    fun currentDurationMs(): Long
    fun isSeekable(): Boolean
    override fun close()
}

/**
 * libmpv implementation used for demanding Matroska/HEVC playback.
 *
 * libmpv owns demuxing, MediaCodec/software decoding, GPU presentation and libass subtitle
 * rendering. Every blocking native command runs on [commandThread], while events are marshalled
 * to the supplied event handler.
 */
class LibMpvEngine(
    context: Context,
    private val eventHandler: Handler = Handler(Looper.getMainLooper()),
    private val onInitializationStage: (String) -> Unit = {},
) : MpvPlayerEngine, MPV.EventObserver {
    private val appContext = context.applicationContext
    private val commandThread = HandlerThread("Syncplay-libmpv").apply { start() }
    private val commandHandler = Handler(commandThread.looper)
    private val subtitleExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Syncplay-subtitle-io").apply { isDaemon = true }
    }
    private val mediaGeneration = AtomicLong(0L)
    private val subtitleLoad = AtomicReference<SubtitleLoadJob?>()

    @Volatile private var listener: ((MpvEngineEvent) -> Unit)? = null
    @Volatile private var initializationError: Throwable? = null
    @Volatile private var snapshot = MpvTrackSnapshot()
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = -1L
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
    private var configuredSubtitles: List<MpvExternalSubtitle> = emptyList()
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

    override fun setListener(listener: ((MpvEngineEvent) -> Unit)?) {
        this.listener = listener
        initializationError?.let { error ->
            if (listener != null) emitPlaybackError(error)
        }
    }

    override fun setMedia(
        uri: Uri,
        externalSubtitles: List<MpvExternalSubtitle>,
        startPositionMs: Long,
    ) {
        val generation = mediaGeneration.incrementAndGet()
        cancelSubtitleLoad()
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = -1L
        seekable = false
        snapshot = MpvTrackSnapshot()
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
                emit(MpvEngineEvent(MpvEngineEvent.Kind.MEDIA_CHANGED, positionMs = positionMs))
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
            emit(MpvEngineEvent(MpvEngineEvent.Kind.OPENING, positionMs = positionMs))
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
            emit(MpvEngineEvent(MpvEngineEvent.Kind.STOPPED, positionMs = positionMs))
        }
    }

    override fun clearMedia() {
        mediaGeneration.incrementAndGet()
        cancelSubtitleLoad()
        dispatch("clear media") { unloadCurrentMedia(it) }
    }

    override fun seekTo(positionMs: Long, exact: Boolean) {
        val target = positionMs.coerceAtLeast(0L)
        this.positionMs = target
        dispatch("seek") { core ->
            if (fileLoaded) {
                core.command("seek", seconds(target).toString(), if (exact) "absolute+exact" else "absolute")
            } else {
                pendingStartPositionMs = target
            }
            emit(MpvEngineEvent(MpvEngineEvent.Kind.POSITION_CHANGED, positionMs = target))
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

    override fun replaceExternalSubtitles(subtitles: List<MpvExternalSubtitle>) {
        configuredSubtitles = subtitles.toList()
        cancelSubtitleLoad()
        val generation = mediaGeneration.get()
        dispatch("replace subtitles") { core ->
            // Remove only tracks that this session added; embedded streams remain available.
            snapshot.tracks.filter { it.type == MpvTrackType.SUBTITLE && it.externalId != null }
                .forEach { track -> runCatching { core.command("sub-remove", track.id.toString()) } }
            activeSubtitles.forEach(OpenedMpvSubtitle::close)
            activeSubtitles = emptyList()
            pendingSubtitles.forEach(OpenedMpvSubtitle::close)
            pendingSubtitles = emptyList()
            if (fileLoaded) refreshTracks()
        }
        scheduleSubtitleLoad(generation, subtitles)
    }

    override fun applySubtitleAppearance(properties: Map<String, Any>) {
        if (properties.isEmpty()) return
        dispatch("subtitle appearance") { core ->
            properties.forEach { (name, value) ->
                when (value) {
                    is Boolean -> core.setPropertyBoolean(name, value)
                    is Number -> core.setPropertyDouble(name, value.toDouble())
                    is String -> core.setPropertyString(name, value)
                }
            }
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        dispatch("subtitle delay") { it.setPropertyDouble("sub-delay", delayMs / 1_000.0) }
    }

    override fun alignSubtitleCue(skip: Int) {
        require(skip == -1 || skip == 1) { "Subtitle cue direction must be -1 or 1" }
        dispatch("subtitle cue alignment") { core ->
            core.command("sub-step", skip.toString(), "primary")
            core.getPropertyDouble("sub-delay")?.takeIf(Double::isFinite)?.let { seconds ->
                emit(
                    MpvEngineEvent(
                        MpvEngineEvent.Kind.SUBTITLE_DELAY_CHANGED,
                        subtitleDelayMs = (seconds * 1_000.0).roundToLong(),
                    ),
                )
            }
        }
    }

    override fun setSubtitleVisibility(visible: Boolean) {
        dispatch("subtitle visibility") { it.setPropertyBoolean("sub-visibility", visible) }
    }

    override fun setVideoOutput(output: Any?) {
        if (released) return
        if (this.output === output) {
            if (output is MpvSurfaceOutput) {
                attachSurfaceAsync(output.surface, output.width, output.height)
            }
            return
        }
        removeOutputCallbacks()
        detachSurfaceAsync()
        this.output = output
        when (output) {
            is MpvSurfaceOutput -> if (output.surface.isValid) {
                attachSurfaceAsync(output.surface, output.width, output.height)
            }
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

    override fun currentSnapshot(): MpvTrackSnapshot = snapshot
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

    override fun eventProperty(property: String) {
        if (property == "sub-text/ass") {
            postNativeEvent("property sub-text/ass") {
                if (isCurrentNativeLoad()) emit(
                    MpvEngineEvent(MpvEngineEvent.Kind.SUBTITLE_TEXT_CHANGED, subtitleText = null),
                )
            }
        }
    }

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
        when (property) {
            "sid", "aid", "vid" -> postNativeEvent("property $property") {
                if (isCurrentNativeLoad()) refreshTracks()
            }
            "sub-text/ass" -> postNativeEvent("property sub-text/ass") {
                if (isCurrentNativeLoad()) emit(
                    MpvEngineEvent(MpvEngineEvent.Kind.SUBTITLE_TEXT_CHANGED, subtitleText = value),
                )
            }
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
            val subtitleFontsDirectory = ensureSubtitleFontsDirectory()
            check(core.setOptionString("config-dir", appContext.filesDir.absolutePath) >= 0) {
                "Unsupported mpv option: config-dir"
            }
            check(core.setOptionString("sub-fonts-dir", subtitleFontsDirectory.absolutePath) >= 0) {
                "Unsupported mpv option: sub-fonts-dir"
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
                emit(MpvEngineEvent(MpvEngineEvent.Kind.BUFFERING, positionMs = positionMs))
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
        emit(MpvEngineEvent(MpvEngineEvent.Kind.OPENING, positionMs = positionMs))
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
            "eof" -> emit(MpvEngineEvent(MpvEngineEvent.Kind.END_REACHED, positionMs = positionMs))
            "error" -> emitPlaybackError(IOException("libmpv could not decode this media"))
            else -> emit(MpvEngineEvent(MpvEngineEvent.Kind.STOPPED, positionMs = positionMs))
        }
    }

    private fun handleDoubleProperty(property: String, value: Double) {
        if (!value.isFinite()) return
        when (property) {
            "time-pos" -> {
                positionMs = secondsToMs(value)
                emit(MpvEngineEvent(MpvEngineEvent.Kind.TIME_CHANGED, positionMs = positionMs))
            }
            "duration" -> {
                durationMs = secondsToMs(value)
                emit(MpvEngineEvent(MpvEngineEvent.Kind.LENGTH_CHANGED, durationMs = durationMs))
            }
            "cache-buffering-state" -> if (value < 100.0) {
                emit(MpvEngineEvent(MpvEngineEvent.Kind.BUFFERING, bufferingPercent = value.toFloat()))
            }
        }
    }

    private fun handleLongProperty(property: String, value: Long) {
        if (property == "cache-buffering-state" && value < 100L) {
            emit(MpvEngineEvent(MpvEngineEvent.Kind.BUFFERING, bufferingPercent = value.toFloat()))
        }
    }

    private fun handleBooleanProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> if (fileLoaded && mpv?.getPropertyBoolean("paused-for-cache") != true) {
                emit(
                    MpvEngineEvent(
                        if (value) MpvEngineEvent.Kind.PAUSED else MpvEngineEvent.Kind.PLAYING,
                        positionMs = positionMs,
                    ),
                )
            }
            "paused-for-cache", "seeking" -> if (value) {
                emit(MpvEngineEvent(MpvEngineEvent.Kind.BUFFERING, positionMs = positionMs))
            } else if (fileLoaded) {
                publishPlaybackPhase()
            }
            "seekable" -> {
                seekable = value
                emit(MpvEngineEvent(MpvEngineEvent.Kind.SEEKABLE_CHANGED, seekable = value))
            }
            "eof-reached" -> if (value) {
                emit(MpvEngineEvent(MpvEngineEvent.Kind.END_REACHED, positionMs = positionMs))
            }
        }
    }

    private fun publishPlaybackPhase(firstFrameRendered: Boolean = false) {
        val core = mpv ?: return
        val kind = when {
            core.getPropertyBoolean("paused-for-cache") == true ||
                core.getPropertyBoolean("seeking") == true -> MpvEngineEvent.Kind.BUFFERING
            core.getPropertyBoolean("pause") == true -> MpvEngineEvent.Kind.PAUSED
            else -> MpvEngineEvent.Kind.PLAYING
        }
        emit(
            MpvEngineEvent(
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
            val addMode = mpvSubtitleAddMode(subtitle.metadata.isDefault)
            val title = subtitle.metadata.label?.takeIf(String::isNotBlank)
                ?: subtitle.metadata.id?.takeIf(String::isNotBlank)
                ?: subtitle.metadata.uri.lastPathSegment.orEmpty()
            if (addMode == "select") {
                // `sid` and subtitle visibility are separate MPV properties. A user-selected
                // sidecar must recover from either an embedded track or a previous "off" choice.
                // Text tracks use the Compose overlay so Noir's layout controls remain exact;
                // bitmap tracks contain already-rendered pixels and must stay native.
                core.setPropertyBoolean("sub-visibility", subtitle.isBitmap())
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
                "audio" -> MpvTrackType.AUDIO
                "video" -> MpvTrackType.VIDEO
                "sub" -> MpvTrackType.SUBTITLE
                else -> MpvTrackType.UNKNOWN
            }
            if (type == MpvTrackType.UNKNOWN) return@mapNotNull null
            val title = map.string("title")
            val externalFilename = map.string("external-filename")
            val externalId = configuredSubtitles.firstOrNull { subtitle ->
                externalTrackMatches(subtitle, title, externalFilename)
            }?.id
            MpvTrackInfo(
                id = id,
                type = type,
                codec = map.string("codec"),
                originalCodec = map.string("codec-desc"),
                bitrate = map.long("demux-bitrate")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                language = map.string("lang"),
                description = map.string("codec-desc"),
                label = title,
                isDefault = map.boolean("default") == true,
                isForced = map.boolean("forced") == true,
                isHearingImpaired = map.boolean("hearing-impaired") == true,
                isVisualImpaired = map.boolean("visual-impaired") == true,
                isCommentary = map.boolean("commentary") == true,
                externalId = externalId,
                width = map.long("demux-w")?.toInt(),
                height = map.long("demux-h")?.toInt(),
                sampleRate = map.long("demux-samplerate")?.toInt(),
                channelCount = map.long("demux-channel-count")?.toInt()
                    ?: parseChannelCount(map.string("audio-channels")),
                frameRate = map.double("demux-fps")?.toFloat(),
                pixelWidthHeightRatio = map.double("demux-par")?.toFloat()?.takeIf { it > 0f } ?: 1f,
                rotationDegrees = map.long("demux-rotation")?.toInt() ?: 0,
            )
        }
        snapshot = MpvTrackSnapshot(
            tracks = mapped,
            selectedAudioId = selectedTrackId(core, "aid"),
            selectedVideoId = selectedTrackId(core, "vid"),
            selectedSubtitleId = selectedTrackId(core, "sid"),
        )
        emit(MpvEngineEvent(MpvEngineEvent.Kind.TRACKS_CHANGED, tracks = snapshot))
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
            MpvEngineEvent(
                MpvEngineEvent.Kind.VOUT,
                videoSize = MpvVideoSize(width, height, ratio, map.long("rotate")?.toInt() ?: 0),
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
                    MpvEngineEvent(
                        MpvEngineEvent.Kind.SURFACE_SIZE_CHANGED,
                        surfaceSize = MpvSurfaceSize(width, height),
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
        snapshot = MpvTrackSnapshot()
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
        if (SmbUri.isSmbUri(uri)) {
            // The SMB adapter owns the random-access handle/proxy and must outlive native demuxing.
            val smbSource = SmbPlaybackEnvironment.openMpvSource(uri)
            return OpenedMpvSource(location = smbSource.location, lease = smbSource)
        }
        return openMpvLocation(appContext.contentResolver, uri)
    }

    private fun openSubtitle(
        subtitle: MpvExternalSubtitle,
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
        subtitles: List<MpvExternalSubtitle>,
    ) {
        if (subtitles.isEmpty() || released || generation != mediaGeneration.get()) return
        val job = SubtitleLoadJob()
        subtitleLoad.getAndSet(job)?.cancel()
        val future = subtitleExecutor.submit {
            val opened = mutableListOf<OpenedMpvSubtitle>()
            var firstFailure: Throwable? = null
            var transferred = false
            try {
                for (subtitle in subtitles) {
                    if (job.isCancelled() || generation != mediaGeneration.get()) break
                    // A stale or inaccessible sidecar is non-fatal to the movie itself.
                    runCatching { openSubtitle(subtitle, job) }
                        .onSuccess(opened::add)
                        .onFailure { error -> if (firstFailure == null) firstFailure = error }
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
                    if (opened.isEmpty() && subtitles.isNotEmpty() && firstFailure != null) {
                        emit(
                            MpvEngineEvent(
                                MpvEngineEvent.Kind.EXTERNAL_SUBTITLE_LOAD_FAILED,
                                error = IOException("Unable to load the selected subtitle", firstFailure),
                            ),
                        )
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

    private fun emit(event: MpvEngineEvent) {
        val callback = listener ?: return
        eventHandler.post { if (!released && listener === callback) callback(event) }
    }

    private fun emitPlaybackError(error: Throwable) {
        emit(MpvEngineEvent(MpvEngineEvent.Kind.ENCOUNTERED_ERROR, error = error))
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

    private fun ensureSubtitleFontsDirectory(): File =
        File(appContext.filesDir, "mpv/fonts").apply { mkdirs() }

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
            "sub-ass-override" to "force",
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
            // ASS text retains simple bold/italic/underline/color markup. MPV still decodes it
            // while native subtitle visibility is disabled for the Compose caption overlay.
            "sub-text/ass" to MPV.mpvFormat.MPV_FORMAT_STRING,
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
    /** Keeps a provider/SMB bridge alive until the matching native END_FILE. */
    private val lease: Closeable? = null,
) : Closeable {
    override fun close() {
        runCatching { descriptor?.close() }
        runCatching { temporaryFile?.delete() }
        runCatching { lease?.close() }
    }
}

private data class OpenedMpvSubtitle(
    val metadata: MpvExternalSubtitle,
    val source: OpenedMpvSource,
) : Closeable {
    override fun close() = source.close()
}

private fun OpenedMpvSubtitle.isBitmap(): Boolean = subtitleExtension(metadata) in BITMAP_SUBTITLE_EXTENSIONS

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
internal fun openMpvSubtitleLocation(
    context: Context,
    subtitle: MpvExternalSubtitle,
    isCancelled: () -> Boolean = { false },
    onInputOpened: (InputStream) -> Unit = {},
): OpenedMpvSource {
    if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
    val uri = subtitle.uri
    val extension = subtitleExtension(subtitle)
    val mustMaterialize = uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) ||
        SmbUri.isSmbUri(uri) || extension == "zip"
    if (!mustMaterialize) {
        return openMpvLocation(context.contentResolver, uri)
    }
    val destination = File.createTempFile("syncplay-subtitle-", ".$extension", context.cacheDir)
    var extracted: File? = null
    try {
        val input: InputStream = when {
            SmbUri.isSmbUri(uri) -> SmbPlaybackEnvironment.openInputStream(uri)
            uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) ->
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Android document provider returned no subtitle stream")
            uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true) ->
                File(uri.path ?: throw IOException("Subtitle file URI has no path")).inputStream()
            else -> throw IOException("ZIP subtitles must come from an Android, file, or SMB URI")
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
        val materialized = if (extension == "zip") {
            extractFirstTextSubtitle(destination, context.cacheDir, isCancelled).also { extracted = it }
        } else {
            destination
        }
        validateMaterializedSubtitle(materialized)
        if (materialized !== destination) destination.delete()
        return OpenedMpvSource(materialized.absolutePath, temporaryFile = materialized)
    } catch (error: Throwable) {
        destination.delete()
        extracted?.delete()
        if (error is IOException) throw error
        throw IOException("Unable to cache external subtitle", error)
    }
}

private fun extractFirstTextSubtitle(
    archive: File,
    cacheDirectory: File,
    isCancelled: () -> Boolean,
): File {
    ZipInputStream(archive.inputStream().buffered()).use { zip ->
        var entriesScanned = 0
        while (entriesScanned < MAX_ZIP_ENTRIES) {
            if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
            val entry = zip.nextEntry ?: break
            entriesScanned++
            val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (!entry.isDirectory && extension in ARCHIVE_SUBTITLE_EXTENSIONS) {
                val target = File.createTempFile("syncplay-subtitle-zip-", ".$extension", cacheDirectory)
                try {
                    target.outputStream().buffered().use { output ->
                        copySubtitleStream(zip, output, isCancelled)
                    }
                    return target
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
            zip.closeEntry()
        }
    }
    throw IOException("The ZIP archive contains no SRT, VTT, ASS, or SSA subtitle")
}

private fun copySubtitleStream(
    input: InputStream,
    output: java.io.OutputStream,
    isCancelled: () -> Boolean,
) {
    val buffer = ByteArray(SUBTITLE_COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        if (isCancelled()) throw InterruptedIOException("Subtitle loading was cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > MAX_EXTERNAL_SUBTITLE_BYTES) {
            throw IOException("External subtitle is larger than the supported limit")
        }
        output.write(buffer, 0, count)
    }
}

private fun validateMaterializedSubtitle(file: File) {
    if (!file.isFile || file.length() <= 0L) throw IOException("The selected subtitle is empty")
    val extension = file.extension.lowercase(Locale.ROOT)
    if (extension !in TEXT_SUBTITLE_EXTENSIONS) return
    val sample = file.inputStream().buffered().use { input ->
        val output = java.io.ByteArrayOutputStream(minOf(file.length(), SUBTITLE_VALIDATION_BYTES.toLong()).toInt())
        val buffer = ByteArray(16 * 1024)
        var remaining = SUBTITLE_VALIDATION_BYTES
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        output.toString(Charsets.UTF_8.name())
    }
    val valid = when (extension) {
        "srt", "vtt" -> TIMED_SUBTITLE_CUE.containsMatchIn(sample)
        "ass", "ssa" -> ASS_SUBTITLE_CUE.containsMatchIn(sample)
        "ttml", "xml" -> TTML_SUBTITLE_CUE.containsMatchIn(sample)
        else -> true
    }
    if (!valid) throw IOException("The selected subtitle contains no valid timed cues")
}

private fun ensureSeekable(descriptor: ParcelFileDescriptor) {
    try {
        val position = Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        Os.lseek(descriptor.fileDescriptor, position, OsConstants.SEEK_SET)
    } catch (error: ErrnoException) {
        throw IOException("The selected document provider does not support random access", error)
    }
}

internal fun subtitleExtension(subtitle: MpvExternalSubtitle): String {
    val pathExtension = listOfNotNull(subtitle.label, subtitle.uri.lastPathSegment)
        .asSequence()
        .map { Uri.decode(it).substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT) }
        .firstOrNull { it in SUPPORTED_SUBTITLE_EXTENSIONS }
    if (pathExtension != null) return pathExtension
    return when (subtitle.mimeType?.lowercase(Locale.ROOT)) {
        "text/x-ssa", "text/x-ass", "application/x-ass", "application/x-ssa" -> "ass"
        "text/vtt" -> "vtt"
        "application/ttml+xml" -> "ttml"
        "application/pgs" -> "sup"
        "application/x-vobsub" -> "idx"
        "application/zip", "application/x-zip-compressed" -> "zip"
        else -> "srt"
    }
}

/** Pure descriptor MRL construction used by source tests. */
internal fun fdMrlForDescriptor(fd: Int): String {
    require(fd >= 0) { "File descriptor must be non-negative" }
    return "fd://$fd"
}

/** Translate a sidecar's default intent to MPV's explicit add/select semantics. */
internal fun mpvSubtitleAddMode(isDefault: Boolean): String = if (isDefault) "select" else "auto"

internal fun externalTrackMatches(
    subtitle: MpvExternalSubtitle,
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

private fun parseChannelCount(channels: String?): Int? = when (channels?.trim()?.lowercase(Locale.ROOT)) {
    "mono" -> 1
    "stereo" -> 2
    "2.1", "3.0" -> 3
    "quad", "4.0" -> 4
    "5.0" -> 5
    "5.1" -> 6
    "6.1" -> 7
    "7.1" -> 8
    else -> null
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
private const val SUBTITLE_VALIDATION_BYTES = 2 * 1024 * 1024
private const val MAX_ZIP_ENTRIES = 1_024
private val TEXT_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "xml")
private val ARCHIVE_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
private val BITMAP_SUBTITLE_EXTENSIONS = setOf("sup", "pgs", "idx", "sub")
private val SUPPORTED_SUBTITLE_EXTENSIONS =
    TEXT_SUBTITLE_EXTENSIONS + ARCHIVE_SUBTITLE_EXTENSIONS + BITMAP_SUBTITLE_EXTENSIONS + "zip"
private val TIMED_SUBTITLE_CUE = Regex(
    "(?m)^[^\\n]*\\d{1,3}:\\d{2}(?::\\d{2})?[,.]\\d{1,3}\\s*-->\\s*" +
        "\\d{1,3}:\\d{2}(?::\\d{2})?[,.]\\d{1,3}",
)
private val ASS_SUBTITLE_CUE = Regex("(?im)^\\s*Dialogue\\s*:")
private val TTML_SUBTITLE_CUE = Regex("(?is)<(?:tt|p)(?:\\s|>)")
