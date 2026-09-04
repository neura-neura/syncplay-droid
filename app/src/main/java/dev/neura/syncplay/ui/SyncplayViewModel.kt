package dev.neura.syncplay.ui

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.neura.syncplay.data.SettingsRepository
import dev.neura.syncplay.data.SubtitlePreferencesRepository
import dev.neura.syncplay.player.LocalPlaybackChangeReason
import dev.neura.syncplay.player.LocalPlaybackEvent
import dev.neura.syncplay.player.MediaInfoResolver
import dev.neura.syncplay.player.PlaybackDiagnosticsStore
import dev.neura.syncplay.player.PlaybackService
import dev.neura.syncplay.player.PlaybackSynchronizer
import dev.neura.syncplay.player.RemoteApplyResult
import dev.neura.syncplay.player.ResolvedMediaInfo
import dev.neura.syncplay.player.SourceAccessClassification
import dev.neura.syncplay.player.isEffectivelyPaused
import dev.neura.syncplay.player.mpv.MpvEventOrigin
import dev.neura.syncplay.player.mpv.MpvExternalSubtitle
import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import dev.neura.syncplay.player.mpv.MpvPlaybackSession
import dev.neura.syncplay.player.mpv.MpvPlaybackSnapshot
import dev.neura.syncplay.player.mpv.MpvTrackInfo
import dev.neura.syncplay.player.mpv.MpvTrackMapper
import dev.neura.syncplay.player.mpv.MpvTrackType
import dev.neura.syncplay.player.mpv.isBitmapSubtitle
import dev.neura.syncplay.protocol.ChatEntry
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.protocol.ProtocolEvent
import dev.neura.syncplay.protocol.RoomUser
import dev.neura.syncplay.protocol.SyncplayConnection
import dev.neura.syncplay.protocol.parseServerEndpoint
import dev.neura.syncplay.smb.SmbPlaybackEnvironment
import dev.neura.syncplay.smb.SmbUri
import dev.neura.syncplay.ui.subtitle.SubtitleAppearance
import dev.neura.syncplay.ui.subtitle.SubtitlePreferences
import dev.neura.syncplay.ui.subtitle.SubtitleExport
import dev.neura.syncplay.ui.subtitle.SubtitleFontRegistry
import dev.neura.syncplay.ui.subtitle.SubtitleSyncSettings
import dev.neura.syncplay.ui.subtitle.SystemSubtitleFontCatalog
import dev.neura.syncplay.ui.subtitle.toMpvProperties
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen state holder and protocol coordinator for the single MPV playback session. */
class SyncplayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val settings = SettingsRepository(app)
    private val subtitleSettings = SubtitlePreferencesRepository(app)
    private val connection = SyncplayConnection()
    private val synchronizer = PlaybackSynchronizer(scope = viewModelScope)
    private val _uiState = MutableStateFlow(SyncplayUiState())
    val uiState: StateFlow<SyncplayUiState> = _uiState.asStateFlow()

    private var player: MpvPlaybackSession? = null
    private var playerStateJob: Job? = null
    private var protocolEventsJob: Job? = null
    private var mediaLoadJob: Job? = null
    private var subtitleLoadJob: Job? = null
    private var remoteFontLoadJob: Job? = null
    private var subtitleTimeoutJob: Job? = null
    private var currentMediaInfo: ResolvedMediaInfo? = null
    private var currentMediaUri: Uri? = null
    private var currentSubtitleUri: Uri? = null
    private var currentSubtitleDisplayName: String? = null
    private var pendingMedia: ResolvedMediaInfo? = null
    private var pendingMediaGrantUri: Uri? = null
    private var pendingSubtitleGrantUri: Uri? = null
    private var desiredExternalSubtitleId: String? = null
    private var mediaLoadGeneration = 0L
    private val subtitleSelectionGate = SubtitleSelectionGate()
    private val remoteFontLoadGate = RemoteSubtitleFontLoadGate()
    private var lastSentDescriptor: MediaDescriptor? = null
    private var chatSequence = 0L
    private var passwordServerHost: String? = null
    private var serviceBound = false
    private var appliedNativeSubtitleVisibility: Boolean? = null
    private var subtitlePreferencesLoaded = false
    private var remoteFontPreferencesRestored = false

    private val persistedSelectionUris = runCatching {
        app.contentResolver.persistedUriPermissions.asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .toCollection(linkedSetOf())
    }.getOrDefault(linkedSetOf())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val session = (binder as? PlaybackService.LocalBinder)?.playbackSession ?: return
            serviceBound = true
            attachPlayer(session)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            detachPlayer()
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceBound = false
            detachPlayer()
            bindPlaybackService()
        }
    }

    init {
        connection.setLocalStateProvider { synchronizer.localState.value }
        connection.setRemoteStateApplier { remote ->
            withContext(Dispatchers.Main.immediate) {
                synchronizer.applyRemoteState(remote)
                synchronizer.snapshot()
            }
        }
        synchronizer.onLocalPlaybackEvent = ::handleLocalPlaybackEvent
        synchronizer.onRemoteApplied = ::handleRemoteApplied

        viewModelScope.launch {
            val saved = runCatching { settings.connectionConfig.first() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    ConnectionConfig()
                }
            passwordServerHost = saved.password.takeIf(String::isNotBlank)
                ?.let { parsedServerHost(saved.serverAddress) }
            _uiState.update { it.copy(form = saved, settingsLoaded = true) }
        }
        viewModelScope.launch {
            subtitleSettings.preferences.collect { stored ->
                val currentSync = _uiState.value.subtitlePreferences.sync
                val effectiveSync = when {
                    stored.sync.rememberOffset -> stored.sync
                    subtitlePreferencesLoaded && !currentSync.rememberOffset -> currentSync
                    else -> stored.sync.copy(offsetMs = 0L)
                }
                val effective = stored.copy(sync = effectiveSync)
                subtitlePreferencesLoaded = true
                _uiState.update { it.copy(subtitlePreferences = effective) }
                applySubtitlePreferences(effective)
                if (!remoteFontPreferencesRestored) {
                    remoteFontPreferencesRestored = true
                    val savedUrl = RemoteSubtitleFontLoader.savedCssUrl(app)
                    val shouldRestore = SubtitleFontRegistry.isGothamPro(effective.appearance.fontFamily) ||
                        !savedUrl.equals(RemoteSubtitleFontLoader.DEFAULT_CSS_URL, ignoreCase = true)
                    if (shouldRestore && savedUrl.isNotBlank()) {
                        loadRemoteSubtitleFont(
                            savedUrl,
                            reportFailure = false,
                            preferCache = true,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            connection.status.collect { status ->
                _uiState.update { current ->
                    val left = status is ConnectionStatus.Disconnected || status is ConnectionStatus.Error
                    current.copy(
                        connectionStatus = status,
                        isInRoom = if (left) false else current.isInRoom,
                        users = if (left) emptyList() else current.users,
                    )
                }
            }
        }
        viewModelScope.launch {
            PlaybackDiagnosticsStore.state.collect { diagnostics ->
                _uiState.update { it.copy(playbackDiagnostics = diagnostics) }
            }
        }
        PlaybackService.ensureStarted(app)
        bindPlaybackService()
        viewModelScope.launch {
            val fonts = withContext(Dispatchers.IO) { SystemSubtitleFontCatalog.load() }
            _uiState.update { it.copy(installedSubtitleFonts = fonts) }
        }
    }

    private fun bindPlaybackService() {
        if (serviceBound) return
        runCatching {
            app.bindService(Intent(app, PlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        }.onSuccess { serviceBound = it }
    }

    private fun attachPlayer(session: MpvPlaybackSession) {
        if (player === session) return
        detachPlayer()
        player = session
        synchronizer.attach(session)
        _uiState.update { it.copy(playerAvailable = true) }
        applySubtitlePreferences(_uiState.value.subtitlePreferences)
        restoreSessionState(session.snapshot.value)
        playerStateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.snapshot.collect(::handlePlayerSnapshot)
        }
        pendingMedia?.let { info ->
            pendingMedia = null
            installMediaOnPlayer(info)
        }
    }

    private fun detachPlayer() {
        playerStateJob?.cancel()
        playerStateJob = null
        synchronizer.detach()
        player = null
        _uiState.update { it.copy(playerAvailable = false) }
    }

    fun updateConnectionForm(config: ConnectionConfig) {
        _uiState.update { current ->
            val passwordEdited = config.password != current.form.password
            if (passwordEdited) {
                passwordServerHost = config.password.takeIf(String::isNotBlank)
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
        runCatching { connection.connect(config) }.onFailure { error ->
            protocolEventsJob?.cancel()
            protocolEventsJob = null
            _uiState.update { it.copy(formError = error.message) }
        }
    }

    fun disconnect() {
        protocolEventsJob?.cancel()
        protocolEventsJob = null
        connection.disconnect()
        player?.pause(MpvEventOrigin.SYSTEM)
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
        val value = !_uiState.value.localReady
        if (connection.sendReady(value)) _uiState.update { it.copy(localReady = value) }
    }

    fun sendChat(text: String): Boolean {
        val maximum = (_uiState.value.serverFeatures["maxChatMessageLength"] as? Number)
            ?.toInt()?.coerceAtLeast(1) ?: 500
        val message = text.trim().take(maximum)
        return message.isNotEmpty() && connection.sendChat(message)
    }

    fun changeRoom(room: String) {
        val normalized = room.trim()
        if (normalized.isEmpty() || normalized == _uiState.value.effectiveRoom) return
        connection.changeRoom(normalized)
        val publicRoom = roomWithoutControllerPassword(normalized)
        _uiState.update {
            it.copy(
                effectiveRoom = publicRoom,
                users = emptyList(),
                chat = emptyList(),
                sharedPlaylist = emptyList(),
                sharedPlaylistIndex = null,
                form = it.form.copy(room = publicRoom),
            )
        }
        persistConnection(_uiState.value.form)
    }

    fun openMedia(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val generation = beginMediaSelection()
        pendingMediaGrantUri = uri
        if (!takeReadPermission(uri, grantFlags)) PlaybackService.retainReadGrant(app, uri)
        mediaLoadJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { MediaInfoResolver.resolve(app, uri) }
                ensureActive()
                if (isCurrentMediaLoad(generation, mediaLoadGeneration)) installMedia(info)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentMediaLoad(generation, mediaLoadGeneration)) {
                    _uiState.update {
                        it.copy(
                            isMediaLoading = false,
                            playback = it.playback.copy(
                                error = "No se pudo abrir el video: " +
                                    (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName),
                            ),
                        )
                    }
                }
            } finally {
                if (isCurrentMediaLoad(generation, mediaLoadGeneration) && pendingMediaGrantUri == uri) {
                    pendingMediaGrantUri = null
                }
                releasePersistedGrantIfUnused(uri)
            }
        }
    }

    fun openUrl(rawUrl: String) {
        val value = validateHttpMediaUrl(rawUrl)
        if (value == null) {
            setPlaybackError("La URL debe comenzar con http:// o https:// e incluir un host")
            return
        }
        val uri = value.toUri()
        beginMediaSelection()
        val name = Uri.decode(
            uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
                ?.takeIf(String::isNotBlank) ?: uri.host ?: "stream",
        )
        installMedia(ResolvedMediaInfo(uri, name, 0L, 0.0, mimeType = inferStreamMime(uri)))
    }

    internal fun openSmbMedia(picked: SmbPickedFile) {
        beginMediaSelection()
        installMedia(
            ResolvedMediaInfo(
                uri = picked.uri,
                displayName = picked.displayName,
                sizeBytes = picked.sizeBytes.coerceAtLeast(0L),
                durationSeconds = 0.0,
                mimeType = inferStreamMime(picked.uri),
                sourceAccess = SourceAccessClassification.SEEKABLE,
            ),
        )
    }

    fun addSubtitle(uri: Uri, grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val media = currentMediaInfo ?: run {
            setPlaybackError("Abre primero un video")
            return
        }
        subtitleLoadJob?.cancel()
        subtitleTimeoutJob?.cancel()
        val request = subtitleSelectionGate.begin(media.uri.toString())
        pendingSubtitleGrantUri = uri
        _uiState.update { it.copy(isSubtitleLoading = true, playback = it.playback.copy(error = null)) }
        if (!takeReadPermission(uri, grantFlags)) PlaybackService.retainReadGrant(app, uri)
        subtitleLoadJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { MediaInfoResolver.resolve(app, uri) }
                ensureActive()
                if (!isCurrentSubtitleRequest(request)) return@launch
                val providerMime = info.mimeType ?: withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.getType(uri) }.getOrNull()
                }
                val type = SubtitleFileTypes.typeFor(info.displayName, providerMime)
                if (type == null) {
                    setPlaybackError("Formato no compatible. Usa SRT, ASS, SSA, VTT, TTML, ZIP, SUP o PGS.")
                    _uiState.update { it.copy(isSubtitleLoading = false) }
                    return@launch
                }
                val externalId = "external:${UUID.randomUUID()}"
                desiredExternalSubtitleId = externalId
                player?.setSubtitleVisibility(!type.isText, MpvEventOrigin.SYSTEM)
                player?.replaceExternalSubtitles(
                    listOf(
                        MpvExternalSubtitle(
                            id = externalId,
                            uri = uri,
                            label = info.displayName,
                            mimeType = type.mimeType,
                            isDefault = true,
                        ),
                    ),
                )
                currentSubtitleUri = uri
                currentSubtitleDisplayName = info.displayName
                releaseObsoletePersistedUriGrants()
                _uiState.update { it.copy(subtitleName = info.displayName) }
                startSubtitleTimeout(externalId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentSubtitleRequest(request)) {
                    desiredExternalSubtitleId = null
                    _uiState.update {
                        it.copy(isSubtitleLoading = false, playback = it.playback.copy(error = friendlyPlaybackError(error)))
                    }
                }
            } finally {
                if (pendingSubtitleGrantUri == uri) pendingSubtitleGrantUri = null
                releasePersistedGrantIfUnused(uri)
            }
        }
    }

    fun selectSubtitleTrack(trackId: String?) {
        val session = player ?: return
        if (trackId == null) {
            session.selectSubtitleTrack(-1)
            desiredExternalSubtitleId = null
            _uiState.update { it.copy(subtitleName = null) }
            return
        }
        val track = session.snapshot.value.tracks.findByStableId(trackId) ?: return
        if (track.type != MpvTrackType.SUBTITLE) return
        session.selectSubtitleTrack(track.id)
        _uiState.update { it.copy(subtitleName = subtitleLabel(track)) }
    }

    fun togglePlayback() {
        val session = player ?: return
        if (session.snapshot.value.playWhenReady) session.pause() else session.play()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs, exact = true)
    }

    fun seekBy(deltaMs: Long) {
        val session = player ?: return
        session.seekTo((session.snapshot.value.positionMs + deltaMs).coerceAtLeast(0L), exact = true)
    }

    fun attachVideoOutput(output: Any?) {
        player?.attachVideoOutput(output)
    }

    fun clearVideoOutput(output: Any?) {
        player?.clearVideoOutput(output)
    }

    fun updateSubtitleAppearance(value: SubtitleAppearance) {
        applySubtitleAppearance(value, cancelRemoteFontLoad = true)
    }

    private fun applySubtitleAppearance(
        value: SubtitleAppearance,
        cancelRemoteFontLoad: Boolean,
    ) {
        val normalized = value.normalized()
        val current = _uiState.value
        if (cancelRemoteFontLoad && normalized.fontFamily != current.subtitlePreferences.appearance.fontFamily) {
            invalidateRemoteFontLoad()
        }
        val switchingAwayFromRemoteFont = normalized.fontFamily != current.subtitlePreferences.appearance.fontFamily &&
            normalized.fontFamily !in current.remoteSubtitleFonts && current.remoteFontCssUrl.isNotBlank()
        _uiState.update {
            it.copy(
                subtitlePreferences = it.subtitlePreferences.copy(appearance = normalized),
                remoteSubtitleFonts = if (switchingAwayFromRemoteFont) emptyList() else it.remoteSubtitleFonts,
                remoteFontCssUrl = if (switchingAwayFromRemoteFont) "" else it.remoteFontCssUrl,
            )
        }
        if (switchingAwayFromRemoteFont) RemoteSubtitleFontLoader.clearSavedCss(app)
        player?.applySubtitleAppearance(normalized.toMpvProperties())
        viewModelScope.launch { subtitleSettings.saveAppearance(normalized) }
    }

    fun resetSubtitleAppearance() {
        invalidateRemoteFontLoad()
        RemoteSubtitleFontLoader.clearSavedCss(app)
        _uiState.update {
            it.copy(
                remoteSubtitleFonts = emptyList(),
                remoteFontCssUrl = "",
                isRemoteFontLoading = false,
            )
        }
        applySubtitleAppearance(SubtitleAppearance.DEFAULT, cancelRemoteFontLoad = false)
        loadRemoteSubtitleFont(
            RemoteSubtitleFontLoader.DEFAULT_CSS_URL,
            reportFailure = true,
            preferCache = true,
        )
    }

    fun updateSubtitleSync(value: SubtitleSyncSettings) {
        val previous = _uiState.value.subtitlePreferences.sync
        val normalized = value.normalized().let { requested ->
            if (previous.rememberOffset && !requested.rememberOffset) {
                requested.copy(offsetMs = 0L)
            } else {
                requested
            }
        }
        _uiState.update { current ->
            current.copy(subtitlePreferences = current.subtitlePreferences.copy(sync = normalized))
        }
        player?.setSubtitleDelay(normalized.offsetMs)
        if (normalized.rememberOffset || previous.rememberOffset != normalized.rememberOffset) {
            viewModelScope.launch { subtitleSettings.saveSync(normalized) }
        }
    }

    fun alignSubtitleCue(skip: Int) {
        if (skip != -1 && skip != 1) return
        player?.alignSubtitleCue(skip)
    }

    fun exportCurrentSubtitle(destinationUri: Uri) {
        val session = player ?: return
        val selected = session.snapshot.value.tracks.tracks.firstOrNull { track ->
            track.type == MpvTrackType.SUBTITLE &&
                track.id == session.snapshot.value.tracks.selectedSubtitleId
        }
        val sourceUri = currentSubtitleUri
        val sourceName = currentSubtitleDisplayName ?: selected?.label ?: _uiState.value.subtitleName
        if (selected?.externalId == null || selected.isBitmapSubtitle() || sourceUri == null || sourceName == null ||
            !SubtitleExport.supports(sourceName)
        ) {
            setPlaybackError("Solo se pueden exportar subtítulos de texto externos SRT, VTT, ASS, SSA o ZIP")
            return
        }
        _uiState.update { it.copy(isSubtitleExporting = true, playback = it.playback.copy(error = null)) }
        viewModelScope.launch {
            try {
                val offsetMs = _uiState.value.subtitlePreferences.sync.offsetMs
                val document = withContext(Dispatchers.IO) {
                    SubtitleExport.shiftedBytes(sourceName, readSubtitleBytes(sourceUri), offsetMs)
                }
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(destinationUri, "wt")
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { it.write(document.text) }
                        ?: throw IOException("Android no permitió escribir el archivo elegido")
                }
                _uiState.update { it.copy(isSubtitleExporting = false) }
                appendSystemMessage("Subtítulos exportados con un desfase de ${offsetMs / 1_000.0} s")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isSubtitleExporting = false,
                        playback = it.playback.copy(error = "No se pudieron exportar los subtítulos: ${error.message.orEmpty()}"),
                    )
                }
            }
        }
    }

    fun updateRemoteFontCssUrl(value: String) {
        val nextUrl = value.take(MAX_CSS_URL_LENGTH)
        if (nextUrl != _uiState.value.remoteFontCssUrl) invalidateRemoteFontLoad()
        _uiState.update { it.copy(remoteFontCssUrl = nextUrl) }
    }

    fun loadRemoteSubtitleFont(rawUrl: String) = loadRemoteSubtitleFont(rawUrl, reportFailure = true)

    private fun loadRemoteSubtitleFont(
        rawUrl: String,
        reportFailure: Boolean,
        preferCache: Boolean = false,
    ) {
        val url = rawUrl.trim()
        invalidateRemoteFontLoad()
        if (url.isEmpty()) {
            RemoteSubtitleFontLoader.clearSavedCss(app)
            _uiState.update {
                it.copy(remoteFontCssUrl = "", remoteSubtitleFonts = emptyList(), isRemoteFontLoading = false)
            }
            return
        }
        val request = remoteFontLoadGate.begin(url)
        _uiState.update { it.copy(isRemoteFontLoading = true, remoteFontCssUrl = url) }
        remoteFontLoadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (preferCache) {
                        RemoteSubtitleFontLoader.loadCached(app, url).recoverCatching {
                            RemoteSubtitleFontLoader.load(app, url).getOrThrow()
                        }
                    } else {
                        RemoteSubtitleFontLoader.load(app, url)
                    }
                }
                ensureActive()
                if (!isCurrentRemoteFontLoad(request)) return@launch
                if (result.isSuccess) {
                    val fontSet = result.getOrThrow()
                    fontSet.register()
                    runCatching { RemoteSubtitleFontLoader.saveCssUrl(app, request.url) }
                    _uiState.update {
                        it.copy(
                            isRemoteFontLoading = false,
                            remoteSubtitleFonts = fontSet.familyNames,
                            playback = it.playback.copy(error = null),
                        )
                    }
                    applySubtitleAppearance(
                        _uiState.value.subtitlePreferences.appearance.copy(fontFamily = fontSet.primaryFamilyName),
                        cancelRemoteFontLoad = false,
                    )
                } else {
                    val error = result.exceptionOrNull()
                    _uiState.update {
                        it.copy(
                            isRemoteFontLoading = false,
                            playback = if (reportFailure) {
                                it.playback.copy(error = "No se pudo cargar esa fuente: ${error?.message.orEmpty()}")
                            } else {
                                it.playback
                            },
                        )
                    }
                }
            } finally {
                if (isCurrentRemoteFontLoad(request)) remoteFontLoadJob = null
            }
        }
    }

    fun clearPlaybackError() {
        _uiState.update { it.copy(playback = it.playback.copy(error = null)) }
    }

    private fun invalidateRemoteFontLoad() {
        remoteFontLoadGate.invalidate()
        remoteFontLoadJob?.cancel()
        remoteFontLoadJob = null
        if (_uiState.value.isRemoteFontLoading) {
            _uiState.update { it.copy(isRemoteFontLoading = false) }
        }
    }

    private fun isCurrentRemoteFontLoad(request: RemoteSubtitleFontLoadGate.Request): Boolean =
        remoteFontLoadGate.isCurrent(request, _uiState.value.remoteFontCssUrl)

    private fun beginMediaSelection(): Long {
        val generation = ++mediaLoadGeneration
        mediaLoadJob?.cancel()
        subtitleLoadJob?.cancel()
        subtitleTimeoutJob?.cancel()
        subtitleSelectionGate.invalidate()
        desiredExternalSubtitleId = null
        pendingMediaGrantUri = null
        pendingSubtitleGrantUri = null
        _uiState.update {
            it.copy(
                isMediaLoading = true,
                isSubtitleLoading = false,
                isSubtitleExporting = false,
                subtitleName = null,
                subtitleTracks = emptyList(),
                subtitleText = null,
                playback = it.playback.copy(error = null),
            )
        }
        return generation
    }

    private fun installMedia(info: ResolvedMediaInfo) {
        currentMediaInfo = info
        currentMediaUri = info.uri
        currentSubtitleUri = null
        currentSubtitleDisplayName = null
        lastSentDescriptor = null
        PlaybackDiagnosticsStore.restart(info.uri)
        _uiState.update {
            it.copy(
                media = info.descriptor,
                mediaUri = info.uri.toString(),
                mediaSourceAccess = info.sourceAccess,
                isMediaLoading = player == null,
                isSubtitleLoading = false,
                isSubtitleExporting = false,
                subtitleName = null,
                subtitleTracks = emptyList(),
                subtitleText = null,
                playback = it.playback.copy(error = null, positionMs = 0L, durationMs = 0L),
            )
        }
        if (player == null) pendingMedia = info else installMediaOnPlayer(info)
        releaseObsoletePersistedUriGrants()
        broadcastFile(info.descriptor)
    }

    private fun installMediaOnPlayer(info: ResolvedMediaInfo) {
        val session = player ?: return
        session.open(
            uri = info.uri,
            mediaIdentity = info.uri.toString(),
            title = info.displayName,
            startPositionMs = 0L,
            playWhenReady = false,
        )
        applySubtitlePreferences(_uiState.value.subtitlePreferences)
    }

    private fun handlePlayerSnapshot(snapshot: MpvPlaybackSnapshot) {
        val expected = currentMediaUri?.toString()
        if (snapshot.mediaIdentity != null && expected != null && snapshot.mediaIdentity != expected) return
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val subtitleFailure = snapshot.subtitleError?.takeIf { desiredExternalSubtitleId != null }
        if (subtitleFailure != null) {
            desiredExternalSubtitleId = null
            subtitleTimeoutJob?.cancel()
            subtitleTimeoutJob = null
            currentSubtitleUri = null
            currentSubtitleDisplayName = null
            releaseObsoletePersistedUriGrants()
        }
        val currentSync = _uiState.value.subtitlePreferences.sync
        if (snapshot.subtitleDelayMs != currentSync.offsetMs) {
            val reportedSync = currentSync.copy(offsetMs = snapshot.subtitleDelayMs).normalized()
            _uiState.update { current ->
                current.copy(
                    subtitlePreferences = current.subtitlePreferences.copy(sync = reportedSync),
                )
            }
            if (reportedSync.rememberOffset) {
                viewModelScope.launch { subtitleSettings.saveSync(reportedSync) }
            }
        }
        val tracks = subtitleTrackUi(snapshot)
        desiredExternalSubtitleId?.let { desired ->
            snapshot.tracks.tracks.firstOrNull { it.externalId == desired }?.let { found ->
                desiredExternalSubtitleId = null
                subtitleTimeoutJob?.cancel()
                subtitleTimeoutJob = null
                if (snapshot.tracks.selectedSubtitleId != found.id) player?.selectSubtitleTrack(found.id)
            }
        }
        val selected = snapshot.tracks.tracks.firstOrNull {
            it.type == MpvTrackType.SUBTITLE && it.id == snapshot.tracks.selectedSubtitleId
        }
        val nativeVisible = selected?.isBitmapSubtitle() == true
        if (appliedNativeSubtitleVisibility != nativeVisible) {
            appliedNativeSubtitleVisibility = nativeVisible
            player?.setSubtitleVisibility(nativeVisible, MpvEventOrigin.SYSTEM)
        }
        val error = snapshot.error?.let(::friendlyPlaybackError)
        _uiState.update { current ->
            current.copy(
                playerAvailable = true,
                isMediaLoading = snapshot.phase == MpvPlaybackPhase.OPENING ||
                    snapshot.phase == MpvPlaybackPhase.BUFFERING,
                isSubtitleLoading = desiredExternalSubtitleId != null,
                subtitleTracks = tracks,
                subtitleName = when {
                    selected != null -> subtitleLabel(selected)
                    desiredExternalSubtitleId != null -> current.subtitleName
                    else -> null
                },
                subtitleText = if (nativeVisible) null else sanitizeSubtitleText(snapshot.subtitleText),
                playback = current.playback.copy(
                    positionMs = snapshot.positionMs,
                    durationMs = duration,
                    paused = isEffectivelyPaused(snapshot.playWhenReady, snapshot.phase, snapshot.error != null),
                    isReady = snapshot.phase == MpvPlaybackPhase.PLAYING || snapshot.phase == MpvPlaybackPhase.PAUSED,
                    isSeekable = snapshot.seekable,
                    speed = snapshot.rate,
                    phase = snapshot.phase,
                    error = subtitleFailure?.let {
                        "No se pudo cargar ese subtítulo: ${it.cause?.message ?: it.message.orEmpty()}"
                    } ?: error ?: current.playback.error,
                ),
            )
        }
        updateDiagnostics(snapshot, selected)
        updateDurationAndBroadcast(duration)
    }

    private fun subtitleTrackUi(snapshot: MpvPlaybackSnapshot): List<SubtitleTrackUi> =
        MpvTrackMapper.sorted(snapshot.tracks)
            .filter { it.type == MpvTrackType.SUBTITLE }
            .map { track ->
                SubtitleTrackUi(
                    id = track.stableId,
                    label = subtitleLabel(track),
                    detail = listOfNotNull(
                        track.language?.uppercase(),
                        track.codec?.uppercase(),
                        if (track.isForced) "FORZADO" else null,
                    ).joinToString(" · ").ifBlank { null },
                    isSelected = track.id == snapshot.tracks.selectedSubtitleId,
                    isSupported = true,
                    isExternal = track.externalId != null,
                    isText = !track.isBitmapSubtitle(),
                )
            }

    private fun subtitleLabel(track: MpvTrackInfo): String = track.label?.takeIf(String::isNotBlank)
        ?: track.description?.takeIf(String::isNotBlank)
        ?: track.language?.uppercase()?.let { "Subtítulos $it" }
        ?: "Subtítulos ${track.id}"

    private fun sanitizeSubtitleText(value: String?): String? = value
        ?.let(::assSubtitleMarkupToHtml)
        ?.replace("\\N", "\n")
        ?.replace("\\n", "\n")
        ?.replace(Regex("\\{[^}]*\\}"), "")
        ?.replace(Regex("<[^>]+>")) { match -> sanitizeSubtitleHtmlTag(match.value) }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun assSubtitleMarkupToHtml(value: String): String {
        var bold = false
        var italic = false
        var underline = false
        var fontColor = false
        val output = StringBuilder(value.length + 32)
        var cursor = 0
        ASS_OVERRIDE_BLOCK.findAll(value).forEach { match ->
            output.append(value, cursor, match.range.first)
            val commands = match.groupValues[1]
            if (ASS_STYLE_RESET.containsMatchIn(commands)) {
                if (fontColor) output.append("</font>")
                if (underline) output.append("</u>")
                if (italic) output.append("</i>")
                if (bold) output.append("</b>")
                bold = false
                italic = false
                underline = false
                fontColor = false
            }
            Regex("\\\\([biu])([01])", RegexOption.IGNORE_CASE).findAll(commands).forEach { command ->
                val enabled = command.groupValues[2] == "1"
                when (command.groupValues[1].lowercase()) {
                    "b" -> if (enabled != bold) {
                        output.append(if (enabled) "<b>" else "</b>")
                        bold = enabled
                    }
                    "i" -> if (enabled != italic) {
                        output.append(if (enabled) "<i>" else "</i>")
                        italic = enabled
                    }
                    "u" -> if (enabled != underline) {
                        output.append(if (enabled) "<u>" else "</u>")
                        underline = enabled
                    }
                }
            }
            ASS_PRIMARY_COLOR.find(commands)?.groupValues?.get(1)?.let { bgr ->
                if (fontColor) output.append("</font>")
                val rgb = bgr.chunked(2).reversed().joinToString("")
                output.append("<font color=\"#").append(rgb).append("\">")
                fontColor = true
            }
            if (ASS_RESET_COLOR.containsMatchIn(commands) && ASS_PRIMARY_COLOR.find(commands) == null && fontColor) {
                output.append("</font>")
                fontColor = false
            }
            cursor = match.range.last + 1
        }
        output.append(value, cursor, value.length)
        if (fontColor) output.append("</font>")
        if (underline) output.append("</u>")
        if (italic) output.append("</i>")
        if (bold) output.append("</b>")
        return output.toString()
            .replace("\\N", "<br>")
            .replace("\\n", "<br>")
            .replace("\\h", " ")
    }

    private fun sanitizeSubtitleHtmlTag(tag: String): String {
        val normalized = tag.trim()
        if (normalized.matches(Regex("</?(?:b|i|u)\\s*>", RegexOption.IGNORE_CASE))) return normalized
        if (normalized.matches(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))) return "<br>"
        if (normalized.matches(Regex("</font\\s*>", RegexOption.IGNORE_CASE))) return "</font>"
        val font = Regex(
            "<font\\s+color\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))\\s*>",
            RegexOption.IGNORE_CASE,
        ).matchEntire(normalized)
        val color = font?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            ?.let(::normalizeSubtitleHtmlColor)
        return color?.let { "<font color=\"$it\">" }.orEmpty()
    }

    private fun normalizeSubtitleHtmlColor(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("#[0-9a-f]{3}(?:[0-9a-f]{3})?", RegexOption.IGNORE_CASE))) {
            return trimmed
        }
        if (trimmed.matches(Regex("[a-z]{3,24}", RegexOption.IGNORE_CASE))) {
            return trimmed.lowercase()
        }
        val rgb = Regex(
            "rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)",
            RegexOption.IGNORE_CASE,
        ).matchEntire(trimmed) ?: return null
        val components = rgb.groupValues.drop(1).mapNotNull(String::toIntOrNull)
        if (components.size != 3 || components.any { it !in 0..255 }) return null
        return components.joinToString(prefix = "#", separator = "") { component ->
            component.toString(16).padStart(2, '0')
        }
    }

    private fun applySubtitlePreferences(preferences: SubtitlePreferences) {
        val session = player ?: return
        session.applySubtitleAppearance(preferences.appearance.toMpvProperties(), MpvEventOrigin.SYSTEM)
        session.setSubtitleDelay(preferences.sync.offsetMs, MpvEventOrigin.SYSTEM)
    }

    /** Rehydrate the visible room after activity recreation while the service keeps MPV alive. */
    private fun restoreSessionState(snapshot: MpvPlaybackSnapshot) {
        if (currentMediaInfo != null) return
        val identity = snapshot.mediaIdentity?.takeIf(String::isNotBlank) ?: return
        val uri = runCatching { identity.toUri() }.getOrNull() ?: return
        val title = snapshot.title?.takeIf(String::isNotBlank)
            ?: Uri.decode(uri.lastPathSegment.orEmpty()).takeIf(String::isNotBlank)
            ?: "video"
        val info = ResolvedMediaInfo(
            uri = uri,
            displayName = title,
            sizeBytes = 0L,
            durationSeconds = snapshot.durationMs.coerceAtLeast(0L) / 1_000.0,
            sourceAccess = if (snapshot.seekable) {
                SourceAccessClassification.SEEKABLE
            } else {
                SourceAccessClassification.UNKNOWN
            },
        )
        currentMediaInfo = info
        currentMediaUri = uri
        _uiState.update {
            it.copy(
                media = info.descriptor,
                mediaUri = identity,
                mediaSourceAccess = info.sourceAccess,
                isMediaLoading = snapshot.phase == MpvPlaybackPhase.OPENING ||
                    snapshot.phase == MpvPlaybackPhase.BUFFERING,
            )
        }
    }

    private fun startSubtitleTimeout(externalId: String) {
        subtitleTimeoutJob?.cancel()
        subtitleTimeoutJob = viewModelScope.launch {
            delay(EXTERNAL_SUBTITLE_TIMEOUT_MS)
            if (desiredExternalSubtitleId != externalId) return@launch
            desiredExternalSubtitleId = null
            _uiState.update {
                it.copy(
                    isSubtitleLoading = false,
                    playback = it.playback.copy(error = "MPV no pudo preparar este archivo de subtítulos"),
                )
            }
        }
    }

    private fun updateDiagnostics(snapshot: MpvPlaybackSnapshot, selectedSubtitle: MpvTrackInfo?) {
        val video = snapshot.tracks.tracks.firstOrNull { it.type == MpvTrackType.VIDEO }
        val audio = snapshot.tracks.tracks.firstOrNull { it.type == MpvTrackType.AUDIO }
        PlaybackDiagnosticsStore.update { current ->
            current.copy(
                videoDecoderName = video?.codec?.let { "MPV · $it" } ?: "MPV",
                audioDecoderName = audio?.codec?.let { "MPV · $it" } ?: "MPV",
                videoFormat = video?.let {
                    dev.neura.syncplay.player.VideoFormatDiagnostics(
                        sampleMimeType = MpvTrackMapper.mimeTypeFor(it),
                        codecs = it.codec,
                        width = it.width,
                        height = it.height,
                        frameRate = it.frameRate,
                        bitrate = it.bitrate,
                        rotationDegrees = it.rotationDegrees,
                    )
                },
                audioFormat = audio?.let {
                    dev.neura.syncplay.player.AudioFormatDiagnostics(
                        sampleMimeType = MpvTrackMapper.mimeTypeFor(it),
                        codecs = it.codec,
                        channelCount = it.channelCount,
                        sampleRate = it.sampleRate,
                        bitrate = it.bitrate,
                        language = it.language,
                    )
                },
                isBuffering = snapshot.phase == MpvPlaybackPhase.BUFFERING,
                lastPlayerError = snapshot.error?.javaClass?.simpleName,
                playerErrorCount = if (snapshot.error != null && current.lastPlayerError == null) {
                    current.playerErrorCount + 1
                } else {
                    current.playerErrorCount
                },
                lastLoadError = selectedSubtitle?.codec?.let { "Subtítulos: $it" },
            )
        }
    }

    private fun handleLocalPlaybackEvent(event: LocalPlaybackEvent) {
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
            it.copy(playback = it.playback.copy(syncOffsetMs = result.driftErrorMs, syncAction = action))
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
                currentMediaInfo?.descriptor?.let { lastSentDescriptor = null; broadcastFile(it) }
                connection.requestUserList()
            }
            is ProtocolEvent.UserList -> _uiState.update {
                it.copy(users = event.users.take(MAX_ROOM_USERS).sortedUsers())
            }
            is ProtocolEvent.UserJoinedOrUpdated -> _uiState.update { current ->
                val old = current.users.firstOrNull { it.username == event.user.username }
                if (old == null && current.users.size >= MAX_ROOM_USERS) return@update current
                val merged = event.user.copy(
                    room = event.user.room.ifBlank { old?.room.orEmpty() },
                    file = if (event.fileProvided) event.user.file else old?.file,
                    isReady = if (event.readinessProvided) event.user.isReady else old?.isReady,
                    isController = if (event.controllerProvided) event.user.isController else old?.isController ?: false,
                    features = if (event.featuresProvided) event.user.features else old?.features.orEmpty(),
                )
                current.copy(users = (current.users.filterNot { it.username == merged.username } + merged).sortedUsers())
            }.also { if (event.joined) appendSystemMessage("${event.user.username} entró a la sala") }
            is ProtocolEvent.UserLeft -> {
                _uiState.update { it.copy(users = it.users.filterNot { user -> user.username == event.username }) }
                appendSystemMessage("${event.username} salió")
            }
            is ProtocolEvent.RoomChanged -> _uiState.update {
                it.copy(effectiveRoom = event.room, form = it.form.copy(room = event.room))
            }
            is ProtocolEvent.ReadyChanged -> _uiState.update { current ->
                current.copy(
                    users = current.users.map { user ->
                        if (user.username == event.username) user.copy(isReady = event.isReady) else user
                    },
                    localReady = if (event.username == current.effectiveUsername) event.isReady else current.localReady,
                )
            }
            is ProtocolEvent.Chat -> appendChat(event.username, event.message)
            is ProtocolEvent.Playback -> synchronizer.applyRemoteState(event.value)
            is ProtocolEvent.PlaylistChanged -> {
                _uiState.update { it.copy(sharedPlaylist = event.files.take(MAX_PLAYLIST_ITEMS)) }
                event.setBy?.let { appendSystemMessage("$it actualizó la lista compartida") }
            }
            is ProtocolEvent.PlaylistIndexChanged -> _uiState.update { it.copy(sharedPlaylistIndex = event.index) }
            is ProtocolEvent.FeaturesChanged -> _uiState.update { current ->
                current.copy(users = current.users.map { user ->
                    if (user.username == event.username) user.copy(features = event.features) else user
                })
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
        val entry = ChatEntry(++chatSequence, username.take(MAX_PROTOCOL_NAME_LENGTH), message.take(MAX_INCOMING_MESSAGE_LENGTH))
        _uiState.update { it.copy(chat = (it.chat + entry).takeLast(MAX_CHAT_HISTORY)) }
    }

    private fun appendSystemMessage(message: String) {
        if (message.isBlank()) return
        val entry = ChatEntry(++chatSequence, null, message.take(MAX_INCOMING_MESSAGE_LENGTH))
        _uiState.update { it.copy(chat = (it.chat + entry).takeLast(MAX_CHAT_HISTORY)) }
    }

    private fun persistConnection(config: ConnectionConfig) {
        viewModelScope.launch {
            try {
                settings.saveConnection(config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val message = "No se pudo recordar la contraseña cifrada en este dispositivo"
                if (_uiState.value.isInRoom) appendSystemMessage(message)
                else _uiState.update { it.copy(formError = message) }
            }
        }
    }

    private fun restartProtocolEventCollection() {
        protocolEventsJob?.cancel()
        protocolEventsJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events.collect(::handleProtocolEvent)
        }
    }

    private fun takeReadPermission(uri: Uri, grantFlags: Int): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        if (grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        val flags = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (flags == 0) return false
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        if (persisted) persistedSelectionUris += uri
        return persisted
    }

    private fun releaseObsoletePersistedUriGrants() {
        val active = setOfNotNull(currentMediaUri, currentSubtitleUri)
        stalePersistedUris(persistedSelectionUris, active).forEach { uri ->
            if (runCatching {
                    app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
            ) persistedSelectionUris.remove(uri)
        }
    }

    private fun releasePersistedGrantIfUnused(uri: Uri) {
        if (uri == currentMediaUri || uri == currentSubtitleUri ||
            uri == pendingMediaGrantUri || uri == pendingSubtitleGrantUri || uri !in persistedSelectionUris
        ) return
        if (runCatching {
                app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
        ) persistedSelectionUris.remove(uri)
    }

    private fun isCurrentSubtitleRequest(request: SubtitleSelectionGate.Request): Boolean =
        subtitleSelectionGate.isCurrent(request, currentMediaInfo?.uri?.toString())

    private fun inferStreamMime(uri: Uri): String? = when {
        uri.lastPathSegment?.substringBefore('?')?.endsWith(".m3u8", true) == true -> "application/x-mpegURL"
        uri.lastPathSegment?.substringBefore('?')?.endsWith(".mpd", true) == true -> "application/dash+xml"
        else -> null
    }

    private fun readSubtitleBytes(uri: Uri): ByteArray {
        val input = if (SmbUri.isSmbUri(uri)) {
            SmbPlaybackEnvironment.openInputStream(uri)
        } else {
            app.contentResolver.openInputStream(uri)
                ?: throw IOException("Android no pudo volver a abrir los subtítulos")
        }
        return input.use { source ->
            val output = ByteArrayOutputStream(64 * 1_024)
            val buffer = ByteArray(64 * 1_024)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_EXPORT_SUBTITLE_BYTES) {
                    throw IOException("El archivo de subtítulos es demasiado grande")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun List<RoomUser>.sortedUsers(): List<RoomUser> = sortedWith(
        compareBy<RoomUser> { it.room != _uiState.value.effectiveRoom }
            .thenByDescending { it.isController }
            .thenBy { it.username.lowercase() },
    )

    private fun setPlaybackError(message: String) {
        _uiState.update { it.copy(playback = it.playback.copy(error = message)) }
    }

    private fun roomWithoutControllerPassword(value: String): String {
        val room = value.trim()
        return if (room.startsWith('+') && room.count { it == ':' } >= 2) room.substringBeforeLast(':') else room
    }

    override fun onCleared() {
        protocolEventsJob?.cancel()
        mediaLoadJob?.cancel()
        subtitleLoadJob?.cancel()
        remoteFontLoadGate.invalidate()
        remoteFontLoadJob?.cancel()
        subtitleTimeoutJob?.cancel()
        subtitleSelectionGate.invalidate()
        detachPlayer()
        if (serviceBound) runCatching { app.unbindService(serviceConnection) }
        serviceBound = false
        synchronizer.close()
        connection.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_CHAT_HISTORY = 250
        const val MAX_INCOMING_MESSAGE_LENGTH = 4_096
        const val MAX_PROTOCOL_NAME_LENGTH = 256
        const val MAX_ROOM_USERS = 500
        const val MAX_PLAYLIST_ITEMS = 1_000
        const val EXTERNAL_SUBTITLE_TIMEOUT_MS = 60_000L
        const val MAX_CSS_URL_LENGTH = 2_048
        const val MAX_EXPORT_SUBTITLE_BYTES = 64L * 1_024L * 1_024L
        val ASS_OVERRIDE_BLOCK = Regex("\\{([^}]*)\\}")
        val ASS_STYLE_RESET = Regex("\\\\r(?:[^}]*)?", RegexOption.IGNORE_CASE)
        val ASS_PRIMARY_COLOR = Regex("\\\\(?:1?c)&H([0-9a-f]{6})&", RegexOption.IGNORE_CASE)
        val ASS_RESET_COLOR = Regex("\\\\(?:1?c)(?!&H)", RegexOption.IGNORE_CASE)
    }
}
