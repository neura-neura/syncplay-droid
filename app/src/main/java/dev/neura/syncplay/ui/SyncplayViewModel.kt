package dev.neura.syncplay.ui

import android.app.Application
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.neura.syncplay.data.SettingsRepository
import dev.neura.syncplay.player.LocalPlaybackChangeReason
import dev.neura.syncplay.player.LocalPlaybackEvent
import dev.neura.syncplay.player.MediaInfoResolver
import dev.neura.syncplay.player.PlaybackService
import dev.neura.syncplay.player.PlaybackDiagnosticsStore
import dev.neura.syncplay.player.PlaybackEngine
import dev.neura.syncplay.player.PlaybackEnginePreference
import dev.neura.syncplay.player.PlaybackEngineReason
import dev.neura.syncplay.player.PlaybackEngineStore
import dev.neura.syncplay.player.PlaybackSynchronizer
import dev.neura.syncplay.player.RemoteApplyResult
import dev.neura.syncplay.player.ResolvedMediaInfo
import dev.neura.syncplay.player.SourceAccessClassification
import dev.neura.syncplay.player.isEffectivelyPaused
import dev.neura.syncplay.player.selectPlaybackEngine
import dev.neura.syncplay.protocol.ChatEntry
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.protocol.ProtocolEvent
import dev.neura.syncplay.protocol.RoomUser
import dev.neura.syncplay.protocol.SyncplayConnection
import dev.neura.syncplay.protocol.parseServerEndpoint
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

class SyncplayViewModel(
    application: Application,
) : AndroidViewModel(application) {
    @SuppressLint("StaticFieldLeak")
    private val app = application.applicationContext
    private val settings = SettingsRepository(app)
    private val connection = SyncplayConnection()
    private val synchronizer = PlaybackSynchronizer(scope = viewModelScope)

    private val _uiState = MutableStateFlow(SyncplayUiState())
    val uiState: StateFlow<SyncplayUiState> = _uiState.asStateFlow()

    private val controllerFutures = ControllerFutureLifecycle(MediaController::releaseFuture)
    private var protocolEventsJob: Job? = null
    private var mediaLoadJob: Job? = null
    private var mediaRehydrationJob: Job? = null
    private var subtitleLoadJob: Job? = null
    private var subtitleSelectionTimeoutJob: Job? = null
    private var controller: MediaController? = null
    private var currentMediaInfo: ResolvedMediaInfo? = null
    private var currentMediaUri: Uri? = null
    private var currentSubtitleUri: Uri? = null
    private var pendingMediaGrantUri: Uri? = null
    private var pendingSubtitleGrantUri: Uri? = null
    private var subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var desiredExternalSubtitleId: String? = null
    private val subtitleSelectionGate = SubtitleSelectionGate()
    private var mediaLoadGeneration = 0L
    // Recover grants acquired by an earlier ViewModel/process instance. They are released only
    // after Media3 has accepted a newer active selection, never merely because the UI recreated.
    private val persistedSelectionUris = runCatching {
        app.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .toCollection(linkedSetOf())
    }.getOrDefault(linkedSetOf())
    private var pendingMedia: ResolvedMediaInfo? = null
    private var lastSentDescriptor: MediaDescriptor? = null
    private var chatSequence = 0L
    private var passwordServerHost: String? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            desiredExternalSubtitleId = null
            subtitleSelectionTimeoutJob?.cancel()
            subtitleSelectionTimeoutJob = null
            _uiState.update {
                it.copy(
                    isSubtitleLoading = false,
                    playback = it.playback.copy(error = friendlyPlaybackError(error.errorCode)),
                )
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            handleSubtitleTracksChanged(tracks)
        }
    }

    init {
        connection.setLocalStateProvider { synchronizer.localState.value }
        connection.setRemoteStateApplier { remote ->
            withContext(Dispatchers.Main.immediate) {
                // MediaController commands run on this looper. Apply the seek directly so the
                // acknowledgement cannot race a queued Service intent or land on a newer item.
                synchronizer.applyRemoteState(remote)
                synchronizer.snapshot()
            }
        }
        synchronizer.onLocalPlaybackEvent = ::handleLocalPlaybackEvent
        synchronizer.onRemoteApplied = ::handleRemoteApplied
        synchronizer.onRemoteSeekRequested = { positionMs ->
            PlaybackService.seekToSynchronizedNow(currentMediaUri?.toString(), positionMs)
        }

        viewModelScope.launch {
            val saved = try {
                settings.connectionConfig.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // SettingsRepository already converts DataStore read failures to an empty
                // configuration. Keep this boundary defensive so a provider/keystore failure
                // can never leave the connection screen's Loading state stuck forever.
                ConnectionConfig()
            }
            passwordServerHost = saved.password.takeIf { it.isNotBlank() }
                ?.let { parsedServerHost(saved.serverAddress) }
            _uiState.update { it.copy(form = saved, settingsLoaded = true) }
        }
        viewModelScope.launch {
            connection.status.collect { status ->
                _uiState.update { current ->
                    val leftSession = status is ConnectionStatus.Disconnected || status is ConnectionStatus.Error
                    current.copy(
                        connectionStatus = status,
                        isInRoom = if (leftSession) false else current.isInRoom,
                        users = if (leftSession) emptyList() else current.users,
                    )
                }
            }
        }
        viewModelScope.launch {
            synchronizer.progress.collect { progress ->
                if (progress == null) return@collect
                if (!isProgressForMedia(progress.mediaIdentity, currentMediaUri?.toString())) return@collect
                val duration = progress.durationMs.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                _uiState.update { current ->
                    current.copy(
                        playback = current.playback.copy(
                            positionMs = progress.positionMs,
                            durationMs = duration,
                            paused = isEffectivelyPaused(
                                playWhenReady = progress.playWhenReady,
                                playbackState = progress.playbackState,
                                hasPlayerError = current.playback.error != null,
                            ),
                            isReady = progress.playbackState == Player.STATE_READY,
                            speed = current.player?.playbackParameters?.speed ?: 1f,
                        ),
                    )
                }
                updateDurationAndBroadcast(duration)
            }
        }
        viewModelScope.launch {
            PlaybackDiagnosticsStore.state.collect { diagnostics ->
                _uiState.update { it.copy(playbackDiagnostics = diagnostics) }
            }
        }
        viewModelScope.launch {
            PlaybackEngineStore.state.collect { engine ->
                _uiState.update { it.copy(playbackEngine = engine) }
            }
        }

        connectMediaController()
    }

    fun updateConnectionForm(config: ConnectionConfig) {
        _uiState.update { current ->
            val passwordEdited = config.password != current.form.password
            if (passwordEdited) {
                passwordServerHost = config.password.takeIf { it.isNotBlank() }
                    ?.let { parsedServerHost(config.serverAddress) }
            } else if (current.form.password.isNotBlank() && passwordServerHost == null) {
                passwordServerHost = parsedServerHost(current.form.serverAddress)
            }
            val clearPassword = !passwordEdited && current.form.password.isNotBlank() &&
                shouldClearPasswordForServerHost(passwordServerHost, config.serverAddress)
            if (clearPassword) passwordServerHost = null
            current.copy(
                form = if (clearPassword) config.copy(password = "") else config,
                formError = null,
                connectionStatus = if (current.connectionStatus is ConnectionStatus.Error) {
                    ConnectionStatus.Disconnected
                } else {
                    current.connectionStatus
                },
            )
        }
    }

    fun connect() {
        val config = _uiState.value.form.copy(
            username = _uiState.value.form.username.trim(),
            room = _uiState.value.form.room.trim(),
            serverAddress = _uiState.value.form.serverAddress.trim(),
        )
        val validationError = runCatching {
            parseServerEndpoint(config.serverAddress)
            require(config.username.isNotBlank()) { "Escribe tu nombre" }
            require(config.room.isNotBlank()) { "Escribe el nombre de la sala" }
        }.exceptionOrNull()?.message
        if (validationError != null) {
            _uiState.update { it.copy(formError = validationError) }
            return
        }
        val savedConfig = config.copy(room = roomWithoutControllerPassword(config.room))
        _uiState.update {
            it.copy(
                form = savedConfig,
                formError = null,
                motd = null,
                users = emptyList(),
                chat = emptyList(),
                sharedPlaylist = emptyList(),
                sharedPlaylistIndex = null,
                playback = it.playback.copy(error = null),
            )
        }
        persistConnection(savedConfig)
        restartProtocolEventCollection()
        runCatching { connection.connect(config) }
            .onFailure { error ->
                protocolEventsJob?.cancel()
                protocolEventsJob = null
                _uiState.update { it.copy(formError = error.message) }
            }
    }

    fun disconnect() {
        protocolEventsJob?.cancel()
        protocolEventsJob = null
        connection.disconnect()
        // Pause locally only after detaching from Syncplay so leaving a room never pauses peers.
        controller?.pause()
        _uiState.update {
            it.copy(
                isInRoom = false,
                effectiveUsername = "",
                effectiveRoom = "",
                users = emptyList(),
                chat = emptyList(),
                sharedPlaylist = emptyList(),
                sharedPlaylistIndex = null,
                playback = it.playback.copy(error = null),
            )
        }
    }

    fun toggleReady() {
        val newValue = !_uiState.value.localReady
        if (connection.sendReady(newValue)) {
            _uiState.update { it.copy(localReady = newValue) }
        }
    }

    fun sendChat(text: String): Boolean {
        val maximum = (_uiState.value.serverFeatures["maxChatMessageLength"] as? Number)
            ?.toInt()
            ?.coerceAtLeast(1)
            ?: 500
        val message = text.trim().take(maximum)
        return message.isNotEmpty() && connection.sendChat(message)
    }

    fun changeRoom(room: String) {
        val normalized = room.trim()
        if (normalized.isEmpty() || normalized == _uiState.value.effectiveRoom) return
        connection.changeRoom(normalized)
        val publicRoomName = roomWithoutControllerPassword(normalized)
        _uiState.update {
            it.copy(
                effectiveRoom = publicRoomName,
                users = emptyList(),
                chat = emptyList(),
                sharedPlaylist = emptyList(),
                sharedPlaylistIndex = null,
                form = it.form.copy(room = publicRoomName),
            )
        }
        persistConnection(_uiState.value.form)
    }

    fun openMedia(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val loadGeneration = beginMediaSelection()
        // Track the current selection even when the provider only grants
        // transient access. This also protects a same-URI replacement from a
        // stale request releasing a previously persisted grant.
        pendingMediaGrantUri = uri
        if (!takeReadPermission(uri, grantFlags)) {
            PlaybackService.retainReadGrant(app, uri)
        }
        mediaLoadJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { MediaInfoResolver.resolve(app, uri) }
                // A provider read may not be interruptible.  Do not let a
                // cancelled, stale load replace a newer selection when it
                // eventually returns.
                ensureActive()
                if (!isCurrentMediaLoad(loadGeneration, mediaLoadGeneration)) return@launch
                installMedia(info)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentMediaLoad(loadGeneration, mediaLoadGeneration)) {
                    _uiState.update {
                        it.copy(
                            isMediaLoading = false,
                            playback = it.playback.copy(
                                error = "No se pudo abrir el video: " +
                                    (error.message?.takeIf { it.isNotBlank() }
                                        ?: error.javaClass.simpleName),
                            ),
                        )
                    }
                }
            } finally {
                // Only the current request may clear the shared pending marker. A stale request
                // for the same URI must not revoke the newer request's grant; its URI will be
                // cleaned on the next successful installation if it is no longer active.
                if (isCurrentMediaLoad(loadGeneration, mediaLoadGeneration)) {
                    if (pendingMediaGrantUri == uri) pendingMediaGrantUri = null
                }
                releasePersistedGrantIfUnused(uri)
            }
        }
    }

    fun openUrl(rawUrl: String) {
        // Validate before cancelling an in-flight SAF selection. A malformed URL
        // must not discard a valid video that is still being resolved.
        val value = validateHttpMediaUrl(rawUrl)
        if (value == null) {
            setPlaybackError("La URL debe comenzar con http:// o https:// e incluir un host")
            return
        }
        val uri = value.toUri()
        beginMediaSelection()
        val name = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
            ?: uri.host
            ?: "stream"
        installMedia(
            ResolvedMediaInfo(
                uri = uri,
                displayName = Uri.decode(name),
                sizeBytes = 0L,
                durationSeconds = 0.0,
                mimeType = inferStreamMime(uri),
            ),
        )
    }

    /** Open a file selected by the in-app SMB2/SMB3 browser. */
    internal fun openSmbMedia(picked: SmbPickedFile) {
        beginMediaSelection()
        val opened = installMedia(
            ResolvedMediaInfo(
                uri = picked.uri,
                displayName = picked.displayName,
                sizeBytes = picked.sizeBytes.coerceAtLeast(0L),
                durationSeconds = 0.0,
                mimeType = inferStreamMime(picked.uri),
                sourceAccess = SourceAccessClassification.SEEKABLE,
            ),
        )
        if (!opened) {
            setPlaybackError("No se pudo abrir el archivo por SMB directo")
        }
    }

    fun addSubtitle(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val selectedMedia = currentMediaInfo
        val selectedMediaUri = selectedMedia?.uri
        if (selectedMedia == null || selectedMediaUri == null) {
            setPlaybackError("Abre primero un video")
            return
        }
        subtitleLoadJob?.cancel()
        val request = subtitleSelectionGate.begin(selectedMediaUri.toString())
        pendingSubtitleGrantUri = null
        _uiState.update {
            it.copy(
                isSubtitleLoading = true,
                playback = it.playback.copy(error = null),
            )
        }
        pendingSubtitleGrantUri = uri
        if (!takeReadPermission(uri, grantFlags)) {
            PlaybackService.retainReadGrant(app, uri)
        }
        subtitleLoadJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { MediaInfoResolver.resolve(app, uri) }
                ensureActive()
                if (!isCurrentSubtitleRequest(request)) return@launch

                val fileType = SubtitleFileTypes.typeForName(info.displayName)
                if (fileType == null) {
                    _uiState.update {
                        it.copy(
                            isSubtitleLoading = false,
                            playback = it.playback.copy(
                                error = "Formato de subtítulos no compatible: ${info.displayName}. " +
                                    "Usa SRT, ASS, SSA, VTT o TTML.",
                            ),
                        )
                    }
                    return@launch
                }
                val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
                    // Always derive the decoder MIME from the extension. Some
                    // SMB/file providers report .ass as audio/aac, which is not
                    // a subtitle MIME and would make Media3 reject the track.
                    .setMimeType(fileType.mediaMimeType)
                    .setLabel(info.displayName)
                    // Media3's MergingMediaSource prefixes this id with its child index. Keeping
                    // our own unique suffix lets onTracksChanged select this exact sidecar rather
                    // than letting the embedded DEFAULT PGS track win a selector tie.
                    // UUID avoids colliding with a sidecar that survived in PlaybackService after
                    // an Activity/process recreation (the request generation restarts at one).
                    .setId("$EXTERNAL_SUBTITLE_ID_PREFIX${UUID.randomUUID()}")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                if (!isCurrentSubtitleRequest(request)) return@launch

                // A subtitle picker represents a replacement. Keeping previous
                // configurations here lets Media3 select the first DEFAULT
                // track, which can leave an older video's subtitle visible.
                desiredExternalSubtitleId = configuration.id
                // Hide the embedded default while the new sidecar group is being prepared. The
                // exact external override is installed as soon as Media3 publishes its tracks.
                resetPlayerSubtitleSelection(disabled = true)
                val replacementConfigurations = subtitleConfigurations.replaceWithLatest(configuration)
                if (reinstallCurrentMediaKeepingPosition(replacementConfigurations)) {
                    subtitleConfigurations = replacementConfigurations
                    currentSubtitleUri = configuration.uri
                    releaseObsoletePersistedUriGrants()
                    startExternalSubtitleSelectionTimeout(configuration.id.orEmpty())
                } else {
                    desiredExternalSubtitleId = null
                    resetPlayerSubtitleSelection(disabled = false)
                }
                _uiState.update {
                    it.copy(
                        isSubtitleLoading = desiredExternalSubtitleId != null,
                        subtitleName = if (desiredExternalSubtitleId != null) {
                            info.displayName
                        } else {
                            it.subtitleName
                        },
                        playback = if (desiredExternalSubtitleId == null) {
                            it.playback.copy(error = "No se pudo aplicar el archivo de subtítulos")
                        } else {
                            it.playback
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentSubtitleRequest(request)) {
                    _uiState.update {
                        it.copy(
                            isSubtitleLoading = false,
                            playback = it.playback.copy(
                                error = "No se pudo abrir los subtítulos: " +
                                    (error.message?.takeIf { it.isNotBlank() }
                                        ?: error.javaClass.simpleName),
                            ),
                        )
                    }
                }
            } finally {
                // A superseded request may have the same URI as the newest request. Only the
                // current token may clear/revoke its shared pending grant marker.
                if (isCurrentSubtitleRequest(request)) {
                    if (pendingSubtitleGrantUri == uri) pendingSubtitleGrantUri = null
                }
                releasePersistedGrantIfUnused(uri)
            }
        }
    }

    fun clearPlaybackError() {
        _uiState.update { it.copy(playback = it.playback.copy(error = null)) }
    }

    fun setPlaybackEnginePreference(preference: PlaybackEnginePreference) {
        PlaybackEngineStore.setPreference(preference)
        val info = currentMediaInfo
        val selection = if (info != null) {
            selectPlaybackEngine(
                preference = preference,
                displayName = info.displayName,
                mimeType = info.mimeType,
                uriPath = info.uri.lastPathSegment ?: info.uri.toString(),
            )
        } else {
            when (preference) {
                PlaybackEnginePreference.VLC -> PlaybackEngine.VLC to PlaybackEngineReason.USER_SELECTION
                PlaybackEnginePreference.MEDIA3,
                PlaybackEnginePreference.AUTOMATIC,
                -> PlaybackEngine.MEDIA3 to if (preference == PlaybackEnginePreference.MEDIA3) {
                    PlaybackEngineReason.USER_SELECTION
                } else {
                    PlaybackEngineReason.DEFAULT
                }
            }
        }
        if (!PlaybackService.setPlaybackEngineNow(selection.first, selection.second)) {
            setPlaybackError("No se pudo cambiar el motor de reproducción")
        }
    }

    /** Select an embedded or side-loaded track from the current prepared Media3 snapshot. */
    fun selectSubtitleTrack(trackId: String?) {
        val player = controller ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            setPlaybackError("Este dispositivo no permite cambiar la pista de subtítulos")
            return
        }

        // A choice made in the selector supersedes a provider read that may still be completing.
        // Cancellation alone is insufficient for SMB-backed DocumentsProviders, so invalidate
        // the request generation as well.
        val abandonedPendingUri = pendingSubtitleGrantUri
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        subtitleSelectionGate.invalidate()
        pendingSubtitleGrantUri = null
        desiredExternalSubtitleId = null
        subtitleSelectionTimeoutJob?.cancel()
        subtitleSelectionTimeoutJob = null
        abandonedPendingUri?.let(::releasePersistedGrantIfUnused)
        if (trackId == null) {
            val disabled = runCatching {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .resetSubtitleSelection(disabled = true)
            }.isSuccess
            if (!disabled) {
                setPlaybackError("No se pudieron desactivar los subtítulos")
                return
            }
            _uiState.update { current ->
                current.copy(
                    isSubtitleLoading = false,
                    subtitleName = null,
                    subtitleTracks = current.subtitleTracks.map { it.copy(isSelected = false) },
                    playback = current.playback.copy(error = null),
                )
            }
            return
        }

        val reference = subtitleTrackReferences(player.currentTracks)
            .firstOrNull { it.ui.id == trackId }
        if (reference == null) {
            handleSubtitleTracksChanged(player.currentTracks)
            setPlaybackError("La pista cambió mientras la seleccionabas. Inténtalo de nuevo.")
            return
        }
        if (!reference.ui.isSupported) {
            setPlaybackError("Esta pista de subtítulos no es compatible con el dispositivo")
            return
        }

        val selected = runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .selectSubtitle(reference)
        }.isSuccess
        if (!selected) {
            setPlaybackError("No se pudo cambiar la pista de subtítulos")
            return
        }
        _uiState.update { current ->
            current.copy(
                isSubtitleLoading = false,
                subtitleName = reference.ui.label,
                subtitleTracks = current.subtitleTracks.map {
                    it.copy(isSelected = it.id == reference.ui.id)
                },
                playback = current.playback.copy(error = null),
            )
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connectMediaController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val generation = controllerFutures.register(future) ?: return
        future.addListener(
            {
                val result = runCatching { future.get() }
                val mediaController = result.getOrNull()
                if (mediaController != null) {
                    controllerFutures.withCurrent(future, generation, mediaController) {
                        controller?.takeUnless { it === mediaController }?.let { previous ->
                            previous.removeListener(playerListener)
                            synchronizer.detach()
                            previous.release()
                        }
                        controller = mediaController
                        mediaController.addListener(playerListener)
                        synchronizer.attach(mediaController)
                        _uiState.update { it.copy(player = mediaController) }
                        handleSubtitleTracksChanged(mediaController.currentTracks)
                        pendingMedia?.let { pending ->
                            if (installMediaOnPlayer(pending, resetSubtitleSelection = true)) {
                                pendingMedia = null
                                releaseObsoletePersistedUriGrants()
                            } else {
                                setPlaybackError("El reproductor no pudo preparar el video seleccionado")
                            }
                        } ?: rehydrateMediaFromController(mediaController)
                    }
                } else {
                    val error = result.exceptionOrNull() ?: return@addListener
                    controllerFutures.withCurrent(future, generation) {
                        setPlaybackError("No se pudo iniciar Media3: ${error.message.orEmpty()}")
                    }
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    private fun installMedia(info: ResolvedMediaInfo): Boolean {
        mediaRehydrationJob?.cancel()
        mediaRehydrationJob = null
        // A subtitle request may have started while this media was still being
        // resolved. It was tied to the previous currentMediaInfo, so make the
        // commit point invalidate it as well as the selection start above.
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        subtitleSelectionTimeoutJob?.cancel()
        subtitleSelectionTimeoutJob = null
        subtitleSelectionGate.invalidate()
        desiredExternalSubtitleId = null
        val activeController = controller
        if (activeController != null && !installMediaOnPlayer(
                info = info,
                resetSubtitleSelection = true,
                subtitleConfigurationsOverride = emptyList(),
            )
        ) {
            _uiState.update {
                it.copy(
                    isMediaLoading = false,
                    playback = it.playback.copy(error = "El reproductor rechazó el video seleccionado"),
                )
            }
            return false
        }

        // Commit identity, grants and UI only after Media3 accepts the replacement. This keeps the
        // previous item coherent if a MediaController disconnects during a slow provider hand-off.
        pendingMediaGrantUri = null
        pendingSubtitleGrantUri = null
        currentMediaInfo = info
        currentMediaUri = info.uri
        currentSubtitleUri = null
        subtitleConfigurations = emptyList()
        lastSentDescriptor = null
        pendingMedia = if (activeController == null) info else null
        _uiState.update {
            it.copy(
                media = info.descriptor,
                mediaUri = info.uri.toString(),
                mediaSourceAccess = info.sourceAccess,
                isMediaLoading = false,
                isSubtitleLoading = false,
                subtitleName = null,
                subtitleTracks = emptyList(),
                playback = it.playback.copy(error = null, positionMs = 0L, durationMs = 0L),
            )
        }
        if (activeController != null) {
            // Only release the previous grant after Media3's controller accepted the replacement.
            releaseObsoletePersistedUriGrants()
        }
        broadcastFile(info.descriptor)
        return true
    }

    /**
     * Start a new media selection and invalidate subtitle work tied to the previous item.
     *
     * The generation check complements coroutine cancellation because some content providers keep
     * reading after cancellation and may resume a stale load later.
     */
    private fun beginMediaSelection(): Long {
        val generation = ++mediaLoadGeneration
        mediaRehydrationJob?.cancel()
        mediaRehydrationJob = null
        mediaLoadJob?.cancel()
        mediaLoadJob = null
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        subtitleSelectionTimeoutJob?.cancel()
        subtitleSelectionTimeoutJob = null
        subtitleSelectionGate.invalidate()
        desiredExternalSubtitleId = null
        pendingMediaGrantUri = null
        pendingSubtitleGrantUri = null
        _uiState.update {
            it.copy(
                isMediaLoading = true,
                isSubtitleLoading = false,
                subtitleTracks = emptyList(),
                playback = it.playback.copy(error = null),
            )
        }
        return generation
    }

    /** Restore UI/file identity when the MediaSession outlives the Activity/ViewModel. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun rehydrateMediaFromController(mediaController: MediaController) {
        if (currentMediaInfo != null || pendingMedia != null) return
        val item = mediaController.currentMediaItem ?: return
        val localConfiguration = item.localConfiguration ?: return
        val uri = localConfiguration.uri
        val identity = item.mediaId.takeIf { it.isNotBlank() } ?: uri.toString()
        val subtitles = localConfiguration.subtitleConfigurations
        val durationMs = mediaController.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val displayName = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: Uri.decode(uri.lastPathSegment?.substringAfterLast('/').orEmpty()).ifBlank { "Video" }
        val retainedSizeBytes = item.mediaMetadata.extras
            ?.getLong(MEDIA_SIZE_BYTES_EXTRA, 0L)
            ?.coerceAtLeast(0L)
            ?: 0L
        val provisional = ResolvedMediaInfo(
            uri = uri,
            displayName = displayName,
            sizeBytes = retainedSizeBytes,
            durationSeconds = durationMs / 1_000.0,
            mimeType = localConfiguration.mimeType,
        )

        currentMediaInfo = provisional
        currentMediaUri = uri
        subtitleConfigurations = subtitles
        currentSubtitleUri = subtitles.singleOrNull()?.uri
        desiredExternalSubtitleId = null
        lastSentDescriptor = null
        _uiState.update {
            it.copy(
                media = provisional.descriptor,
                mediaUri = uri.toString(),
                mediaSourceAccess = provisional.sourceAccess,
                subtitleName = subtitles.singleOrNull()?.label,
                subtitleTracks = subtitleTrackReferences(mediaController.currentTracks).map { it.ui },
                playback = it.playback.copy(
                    positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                    durationMs = durationMs,
                    paused = !mediaController.playWhenReady,
                    isReady = mediaController.playbackState == Player.STATE_READY,
                    error = null,
                ),
            )
        }
        releaseObsoletePersistedUriGrants()
        if (_uiState.value.isInRoom) broadcastFile(provisional.descriptor)

        val generation = mediaLoadGeneration
        mediaRehydrationJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { MediaInfoResolver.resolve(app, uri) }
            if (generation != mediaLoadGeneration || controller !== mediaController ||
                mediaController.currentMediaItem?.let {
                    it.mediaId.takeIf(String::isNotBlank) ?: it.localConfiguration?.uri?.toString()
                } != identity || currentMediaUri != uri
            ) return@launch

            val refined = if (resolved.durationSeconds > 0.0 || durationMs <= 0L) {
                resolved
            } else {
                resolved.copy(durationSeconds = durationMs / 1_000.0)
            }
            currentMediaInfo = refined
            _uiState.update { it.copy(media = refined.descriptor) }
            if (_uiState.value.isInRoom) broadcastFile(refined.descriptor)
        }
    }

    private fun isCurrentSubtitleRequest(request: SubtitleSelectionGate.Request): Boolean =
        subtitleSelectionGate.isCurrent(request, currentMediaInfo?.uri?.toString())

    private fun installMediaOnPlayer(
        info: ResolvedMediaInfo,
        positionMs: Long = 0L,
        play: Boolean = false,
        resetSubtitleSelection: Boolean = false,
        subtitleConfigurationsOverride: List<MediaItem.SubtitleConfiguration> = subtitleConfigurations,
    ): Boolean {
        ensurePlaybackEngine(info)
        val player = controller ?: return false
        val previousItem = runCatching { player.currentMediaItem }.getOrNull()
        val previousPositionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
            .getOrDefault(0L)
        val previousPlayWhenReady = runCatching { player.playWhenReady }.getOrDefault(false)
        val previousTrackParameters = runCatching { player.trackSelectionParameters }.getOrNull()
        var itemWasReplaced = false
        return try {
            if (resetSubtitleSelection) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .resetSubtitleSelection(disabled = false)
            }
            val item = createMediaItem(info, subtitleConfigurationsOverride)
            player.setMediaItem(item, positionMs.coerceAtLeast(0L))
            itemWasReplaced = true
            player.prepare()
            player.playWhenReady = play
            true
        } catch (_: Exception) {
            // MediaController calls are individually asynchronous. If a later command fails after
            // setMediaItem was accepted, restore the previous single-item state so ViewModel,
            // grants and PlaybackService cannot describe different media.
            runCatching {
                previousTrackParameters?.let { player.trackSelectionParameters = it }
                if (itemWasReplaced) {
                    if (previousItem != null) {
                        player.setMediaItem(previousItem, previousPositionMs)
                        player.prepare()
                        player.playWhenReady = previousPlayWhenReady
                    } else {
                        player.clearMediaItems()
                    }
                }
            }
            false
        }
    }

    private fun ensurePlaybackEngine(info: ResolvedMediaInfo) {
        val preference = PlaybackEngineStore.state.value.preference
        val (engine, reason) = selectPlaybackEngine(
            preference = preference,
            displayName = info.displayName,
            mimeType = info.mimeType,
            uriPath = info.uri.lastPathSegment ?: info.uri.toString(),
        )
        PlaybackService.setPlaybackEngineNow(
            engine = engine,
            reason = reason,
            preserveCurrentMedia = false,
        )
    }

    private fun reinstallCurrentMediaKeepingPosition(
        subtitleConfigurationsOverride: List<MediaItem.SubtitleConfiguration> = subtitleConfigurations,
    ): Boolean {
        val player = controller ?: return false
        val info = currentMediaInfo ?: return false
        return installMediaOnPlayer(
            info = info,
            positionMs = player.currentPosition,
            play = player.playWhenReady,
            subtitleConfigurationsOverride = subtitleConfigurationsOverride,
        )
    }

    private fun createMediaItem(
        info: ResolvedMediaInfo,
        subtitleConfigurationsOverride: List<MediaItem.SubtitleConfiguration> = subtitleConfigurations,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(info.uri.toString())
            .setUri(info.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(info.displayName)
                    .setExtras(
                        Bundle().apply {
                            putLong(MEDIA_SIZE_BYTES_EXTRA, info.sizeBytes.coerceAtLeast(0L))
                        },
                    )
                    .build(),
            )
            .setSubtitleConfigurations(subtitleConfigurationsOverride)
        info.mimeType?.takeIf { it.isNotBlank() }?.let(builder::setMimeType)
        return builder.build()
    }

    private fun handleSubtitleTracksChanged(tracks: Tracks) {
        val player = controller ?: return
        val playerMediaIdentity = player.currentMediaItem?.let { item ->
            item.mediaId.takeIf { it.isNotBlank() }
                ?: item.localConfiguration?.uri?.toString()
        }
        if (!isProgressForMedia(playerMediaIdentity, currentMediaUri?.toString())) return
        val references = subtitleTrackReferences(tracks)
        val desiredId = desiredExternalSubtitleId
        if (desiredId != null) {
            val external = findExternalSubtitleTrack(tracks, desiredId)
            if (external != null) {
                desiredExternalSubtitleId = null
                subtitleSelectionTimeoutJob?.cancel()
                subtitleSelectionTimeoutJob = null
                if (!external.ui.isSupported) {
                    resetPlayerSubtitleSelection(disabled = false)
                    _uiState.update { current ->
                        current.copy(
                            isSubtitleLoading = false,
                            subtitleTracks = references.map { it.ui },
                            playback = current.playback.copy(
                                error = "El formato de este archivo de subtítulos no es compatible",
                            ),
                        )
                    }
                    return
                }
                if (player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                    val selected = runCatching {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .selectSubtitle(external)
                    }.isSuccess
                    if (!selected) {
                        resetPlayerSubtitleSelection(disabled = false)
                        _uiState.update { current ->
                            current.copy(
                                isSubtitleLoading = false,
                                subtitleTracks = references.map { it.ui },
                                playback = current.playback.copy(
                                    error = "No se pudo cambiar la pista de subtítulos",
                                ),
                            )
                        }
                        return
                    }
                    _uiState.update { current ->
                        current.copy(
                            isSubtitleLoading = false,
                            subtitleName = external.ui.label,
                            subtitleTracks = references.map { reference ->
                                reference.ui.copy(isSelected = reference.ui.id == external.ui.id)
                            },
                            playback = current.playback.copy(error = null),
                        )
                    }
                    return
                }
                resetPlayerSubtitleSelection(disabled = false)
                _uiState.update { current ->
                    current.copy(
                        isSubtitleLoading = false,
                        subtitleTracks = references.map { it.ui },
                        playback = current.playback.copy(
                            error = "Este dispositivo no permite cambiar la pista de subtítulos",
                        ),
                    )
                }
                return
            }
        }

        val selected = references.firstOrNull { it.ui.isSelected }?.ui
        _uiState.update { current ->
            current.copy(
                subtitleTracks = references.map { it.ui },
                subtitleName = selected?.label,
            )
        }
    }

    private fun resetPlayerSubtitleSelection(disabled: Boolean) {
        val player = controller ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .resetSubtitleSelection(disabled)
        }
    }

    private fun startExternalSubtitleSelectionTimeout(formatId: String) {
        if (formatId.isBlank()) return
        subtitleSelectionTimeoutJob?.cancel()
        subtitleSelectionTimeoutJob = viewModelScope.launch {
            delay(EXTERNAL_SUBTITLE_SELECTION_TIMEOUT_MS)
            if (desiredExternalSubtitleId != formatId) return@launch
            desiredExternalSubtitleId = null
            subtitleSelectionTimeoutJob = null
            resetPlayerSubtitleSelection(disabled = false)
            _uiState.update { current ->
                current.copy(
                    isSubtitleLoading = false,
                    playback = current.playback.copy(
                        error = "El reproductor no pudo preparar este archivo de subtítulos",
                    ),
                )
            }
        }
    }

    private fun handleLocalPlaybackEvent(event: LocalPlaybackEvent) {
        // PlaybackSynchronizer only labels PLAYBACK when Media3's playWhenReady flag changed;
        // remote echoes have already been suppressed there. This also handles the first user
        // press after a remotely-applied initial pause, for which no prior local event exists.
        val shouldSend = event.doSeek || event.reason == LocalPlaybackChangeReason.PLAYBACK
        if (shouldSend && event.reason != LocalPlaybackChangeReason.SPEED) {
            connection.sendPlaybackChange(event.state, event.doSeek)
        }
    }

    private fun handleRemoteApplied(result: RemoteApplyResult) {
        val action = when {
            result.deferredUntilMedia -> "Estado recibido · abre el mismo video"
            result.seekApplied -> "Posición corregida"
            result.correctionSpeed != null && abs(result.correctionSpeed - 1f) > 0.001f ->
                "Ajuste suave ${"%.3f".format(result.correctionSpeed)}×"
            abs(result.driftErrorMs) <= 100L -> "Sincronizado"
            else -> "Corrigiendo deriva"
        }
        _uiState.update {
            it.copy(
                playback = it.playback.copy(
                    syncOffsetMs = result.driftErrorMs,
                    syncAction = action,
                ),
            )
        }
    }

    private fun handleProtocolEvent(event: ProtocolEvent) {
        when (event) {
            is ProtocolEvent.Hello -> {
                val hello = event.value
                _uiState.update {
                    it.copy(
                        isInRoom = true,
                        effectiveUsername = hello.username,
                        effectiveRoom = hello.room,
                        serverFeatures = hello.features,
                        motd = hello.motd,
                        form = it.form.copy(username = hello.username, room = hello.room),
                        formError = null,
                    )
                }
                appendSystemMessage("Conectado como ${hello.username} en «${hello.room}»")
                hello.motd?.let(::appendSystemMessage)
                connection.sendReady(_uiState.value.localReady, manuallyInitiated = false)
                currentMediaInfo?.descriptor?.let {
                    lastSentDescriptor = null
                    broadcastFile(it)
                }
                connection.requestUserList()
            }

            is ProtocolEvent.UserList -> _uiState.update {
                it.copy(users = event.users.take(MAX_ROOM_USERS).sortedUsers())
            }
            is ProtocolEvent.UserJoinedOrUpdated -> {
                _uiState.update { current ->
                    val old = current.users.firstOrNull { it.username == event.user.username }
                    if (old == null && current.users.size >= MAX_ROOM_USERS) return@update current
                    val merged = event.user.copy(
                        room = event.user.room.ifBlank { old?.room.orEmpty() },
                        file = if (event.fileProvided) event.user.file else old?.file,
                        isReady = if (event.readinessProvided) event.user.isReady else old?.isReady,
                        isController = if (event.controllerProvided) {
                            event.user.isController
                        } else {
                            old?.isController ?: false
                        },
                        features = if (event.featuresProvided) {
                            event.user.features
                        } else {
                            old?.features.orEmpty()
                        },
                    )
                    current.copy(users = (current.users.filterNot { it.username == merged.username } + merged).sortedUsers())
                }
                if (event.joined) appendSystemMessage("${event.user.username} entró a la sala")
            }

            is ProtocolEvent.UserLeft -> {
                _uiState.update { it.copy(users = it.users.filterNot { user -> user.username == event.username }) }
                appendSystemMessage("${event.username} salió")
            }

            is ProtocolEvent.RoomChanged -> {
                _uiState.update { it.copy(effectiveRoom = event.room, form = it.form.copy(room = event.room)) }
            }

            is ProtocolEvent.ReadyChanged -> {
                _uiState.update { current ->
                    current.copy(
                        users = current.users.map { user ->
                            if (user.username == event.username) user.copy(isReady = event.isReady) else user
                        },
                        localReady = if (event.username == current.effectiveUsername) event.isReady else current.localReady,
                    )
                }
            }

            is ProtocolEvent.Chat -> appendChat(event.username, event.message)
            is ProtocolEvent.Playback -> synchronizer.applyRemoteState(event.value)
            is ProtocolEvent.PlaylistChanged -> {
                _uiState.update { it.copy(sharedPlaylist = event.files.take(MAX_PLAYLIST_ITEMS)) }
                if (event.setBy != null) appendSystemMessage("${event.setBy} actualizó la lista compartida")
            }

            is ProtocolEvent.PlaylistIndexChanged -> _uiState.update { it.copy(sharedPlaylistIndex = event.index) }
            is ProtocolEvent.FeaturesChanged -> {
                _uiState.update { current ->
                    current.copy(users = current.users.map { user ->
                        if (user.username == event.username) user.copy(features = event.features) else user
                    })
                }
            }

            is ProtocolEvent.Notice -> appendSystemMessage(event.message)
            is ProtocolEvent.Error -> appendSystemMessage("Error del servidor: ${event.message}")
        }
    }

    private fun updateDurationAndBroadcast(durationMs: Long) {
        if (durationMs <= 0L) return
        val info = currentMediaInfo ?: return
        val seconds = durationMs / 1_000.0
        if (abs(info.durationSeconds - seconds) < 0.5) return
        val updated = info.copy(durationSeconds = seconds)
        currentMediaInfo = updated
        _uiState.update { it.copy(media = updated.descriptor) }
        broadcastFile(updated.descriptor)
    }

    private fun broadcastFile(descriptor: MediaDescriptor) {
        if (lastSentDescriptor == descriptor) return
        lastSentDescriptor = descriptor
        connection.sendFile(descriptor)
        connection.requestUserList()
    }

    private fun appendChat(username: String, message: String) {
        val entry = ChatEntry(
            id = ++chatSequence,
            username = username.take(MAX_PROTOCOL_NAME_LENGTH),
            message = message.take(MAX_INCOMING_MESSAGE_LENGTH),
        )
        _uiState.update { it.copy(chat = (it.chat + entry).takeLast(MAX_CHAT_HISTORY)) }
    }

    private fun appendSystemMessage(message: String) {
        if (message.isBlank()) return
        val entry = ChatEntry(
            id = ++chatSequence,
            username = null,
            message = message.take(MAX_INCOMING_MESSAGE_LENGTH),
        )
        _uiState.update { it.copy(chat = (it.chat + entry).takeLast(MAX_CHAT_HISTORY)) }
    }

    private fun persistConnection(config: ConnectionConfig) {
        viewModelScope.launch {
            try {
                settings.saveConnection(config)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = "No se pudo recordar la contraseña cifrada en este dispositivo"
                if (_uiState.value.isInRoom) {
                    appendSystemMessage(message)
                } else {
                    _uiState.update { it.copy(formError = message) }
                }
            }
        }
    }

    private fun takeReadPermission(uri: Uri, grantFlags: Int): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        if (grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        val takeFlags = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (takeFlags == 0) return false
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, takeFlags)
            true
        }.getOrDefault(false)
        if (persisted) persistedSelectionUris += uri
        return persisted
    }

    /**
     * Keep only the current video and subtitle's persisted read grants acquired by this ViewModel.
     * Transient GET_CONTENT grants never enter [persistedSelectionUris] and are therefore
     * untouched. This is intentionally not called from [onCleared], so the active player keeps its
     * grant while the service is still able to read the current file.
     */
    private fun releaseObsoletePersistedUriGrants() {
        val activeUris = setOfNotNull(currentMediaUri, currentSubtitleUri)
        stalePersistedUris(persistedSelectionUris, activeUris).forEach { uri ->
            val released = runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (released) persistedSelectionUris.remove(uri)
        }
    }

    /** Release a grant acquired by a stale/failed selection once it is no longer protected. */
    private fun releasePersistedGrantIfUnused(uri: Uri) {
        if (uri == currentMediaUri || uri == currentSubtitleUri ||
            uri == pendingMediaGrantUri || uri == pendingSubtitleGrantUri
        ) {
            return
        }
        if (uri !in persistedSelectionUris) return
        val released = runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (released) persistedSelectionUris.remove(uri)
    }

    private fun inferStreamMime(uri: Uri): String? = when {
        uri.lastPathSegment?.substringBefore('?')?.endsWith(".m3u8", ignoreCase = true) == true -> MimeTypes.APPLICATION_M3U8
        uri.lastPathSegment?.substringBefore('?')?.endsWith(".mpd", ignoreCase = true) == true -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun List<RoomUser>.sortedUsers(): List<RoomUser> = sortedWith(
        compareBy<RoomUser> { it.room != _uiState.value.effectiveRoom }
            .thenByDescending { it.isController }
            .thenBy { it.username.lowercase() },
    )

    private fun setPlaybackError(message: String) {
        _uiState.update { it.copy(playback = it.playback.copy(error = message)) }
    }

    private fun restartProtocolEventCollection() {
        protocolEventsJob?.cancel()
        protocolEventsJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events.collect(::handleProtocolEvent)
        }
    }

    private fun roomWithoutControllerPassword(value: String): String {
        val room = value.trim()
        return if (room.startsWith('+') && room.count { it == ':' } >= 2) {
            room.substringBeforeLast(':')
        } else {
            room
        }
    }

    override fun onCleared() {
        protocolEventsJob?.cancel()
        protocolEventsJob = null
        mediaLoadJob?.cancel()
        mediaLoadJob = null
        mediaRehydrationJob?.cancel()
        mediaRehydrationJob = null
        subtitleLoadJob?.cancel()
        subtitleLoadJob = null
        subtitleSelectionTimeoutJob?.cancel()
        subtitleSelectionTimeoutJob = null
        subtitleSelectionGate.invalidate()
        // Invalidate/release the future before closing the synchronizer. Its listener runs on
        // another callback turn and must observe the cleared owner before attempting to attach.
        controllerFutures.clear()
        controller?.removeListener(playerListener)
        synchronizer.close()
        connection.close()
        controller = null
    }

    private companion object {
        const val MAX_CHAT_HISTORY = 250
        const val MAX_INCOMING_MESSAGE_LENGTH = 4_096
        const val MAX_PROTOCOL_NAME_LENGTH = 256
        const val MAX_ROOM_USERS = 500
        const val MAX_PLAYLIST_ITEMS = 1_000
        const val EXTERNAL_SUBTITLE_SELECTION_TIMEOUT_MS = 60_000L
        const val MEDIA_SIZE_BYTES_EXTRA = "dev.neura.syncplay.media.SIZE_BYTES"
    }
}
