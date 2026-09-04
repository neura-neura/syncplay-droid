package dev.neura.syncplay.ui

import dev.neura.syncplay.player.PlaybackDiagnostics
import dev.neura.syncplay.player.SourceAccessClassification
import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import dev.neura.syncplay.protocol.ChatEntry
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.protocol.RoomUser
import dev.neura.syncplay.ui.subtitle.SubtitlePreferences

data class PlaybackUiState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = true,
    val isReady: Boolean = false,
    val isSeekable: Boolean = false,
    val speed: Float = 1f,
    val phase: MpvPlaybackPhase = MpvPlaybackPhase.IDLE,
    val syncOffsetMs: Long? = null,
    val syncAction: String = "Esperando estado de la sala",
    val error: String? = null,
)

data class SubtitleTrackUi(
    val id: String,
    val label: String,
    val detail: String? = null,
    val isSelected: Boolean = false,
    val isSupported: Boolean = true,
    val isExternal: Boolean = false,
    val isText: Boolean = true,
)

data class SyncplayUiState(
    val form: ConnectionConfig = ConnectionConfig(),
    val settingsLoaded: Boolean = false,
    val formError: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isInRoom: Boolean = false,
    val effectiveUsername: String = "",
    val effectiveRoom: String = "",
    val serverFeatures: Map<String, Any?> = emptyMap(),
    val motd: String? = null,
    val users: List<RoomUser> = emptyList(),
    val chat: List<ChatEntry> = emptyList(),
    val sharedPlaylist: List<String> = emptyList(),
    val sharedPlaylistIndex: Int? = null,
    val media: MediaDescriptor? = null,
    val mediaUri: String? = null,
    val mediaSourceAccess: SourceAccessClassification = SourceAccessClassification.UNKNOWN,
    val isMediaLoading: Boolean = false,
    val isSubtitleLoading: Boolean = false,
    val isSubtitleExporting: Boolean = false,
    val subtitleName: String? = null,
    val subtitleTracks: List<SubtitleTrackUi> = emptyList(),
    val localReady: Boolean = true,
    val playerAvailable: Boolean = false,
    val subtitleText: String? = null,
    val subtitlePreferences: SubtitlePreferences = SubtitlePreferences.DEFAULT,
    val installedSubtitleFonts: List<String> = listOf(
        "Noir/Sans fallback",
        "sans-serif",
        "serif",
        "monospace",
        "cursive",
        "sans-serif-condensed",
    ),
    val remoteSubtitleFonts: List<String> = emptyList(),
    val remoteFontCssUrl: String = "",
    val isRemoteFontLoading: Boolean = false,
    val playbackDiagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
    val playback: PlaybackUiState = PlaybackUiState(),
)

enum class RoomPanel {
    PEOPLE,
    CHAT,
    PLAYLIST,
}
