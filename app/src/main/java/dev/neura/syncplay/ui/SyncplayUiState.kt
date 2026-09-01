package dev.neura.syncplay.ui

import androidx.media3.common.Player
import dev.neura.syncplay.protocol.ChatEntry
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.protocol.RoomUser

data class PlaybackUiState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = true,
    val isReady: Boolean = false,
    val speed: Float = 1f,
    val syncOffsetMs: Long? = null,
    val syncAction: String = "Esperando estado de la sala",
    val error: String? = null,
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
    val isMediaLoading: Boolean = false,
    val isSubtitleLoading: Boolean = false,
    val subtitleName: String? = null,
    val localReady: Boolean = true,
    val player: Player? = null,
    val playback: PlaybackUiState = PlaybackUiState(),
)

enum class RoomPanel {
    PEOPLE,
    CHAT,
    PLAYLIST,
}
