package dev.neura.syncplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.MediaDescriptor
import dev.neura.syncplay.player.PlaybackEngine
import dev.neura.syncplay.player.PlaybackEnginePreference
import dev.neura.syncplay.player.PlaybackEngineReason
import dev.neura.syncplay.player.PlaybackEngineState
import dev.neura.syncplay.ui.theme.SyncplayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExpressiveScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectionScreenKeepsEssentialActionsAndPreservesHostForQuickPorts() {
        var form by mutableStateOf(
            ConnectionConfig(
                serverAddress = "192.0.2.65:8999",
                username = "android-user",
                room = "movies",
                preferTls = false,
            ),
        )

        composeRule.setContent {
            SyncplayTheme {
                ConnectionScreen(
                    state = SyncplayUiState(
                        form = form,
                        settingsLoaded = true,
                    ),
                    onFormChange = { form = it },
                    onConnect = {},
                )
            }
        }

        composeRule.onNodeWithText("Conéctate a una sala").assertIsDisplayed()
        composeRule.onNodeWithText("Servidor").assertIsDisplayed()
        composeRule.onNodeWithText("Opciones avanzadas").performClick()
        composeRule.onNodeWithContentDescription("Usar puerto 8996")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("192.0.2.65:8996", form.serverAddress)
        }
    }

    @Test
    fun emptyRoomExposesBothMediaActionsAndNavigation() {
        composeRule.setContent {
            SyncplayTheme {
                PlayerRoomScreen(
                    state = SyncplayUiState(
                        connectionStatus = ConnectionStatus.Connected(
                            endpoint = "192.0.2.65:8999",
                            secure = false,
                            serverVersion = "1.7.4",
                        ),
                        isInRoom = true,
                        effectiveUsername = "android-user",
                        effectiveRoom = "movies",
                    ),
                    onOpenFile = {},
                    onOpenUrl = {},
                    onOpenSubtitle = {},
                    onToggleReady = {},
                    onSendChat = { true },
                    onChangeRoom = {},
                    onDisconnect = {},
                    onDismissPlaybackError = {},
                )
            }
        }

        composeRule.onNodeWithText("Ningún video abierto").assertIsDisplayed()
        composeRule.onNodeWithText("Abrir archivo").assertIsDisplayed()
        composeRule.onNodeWithText("Abrir URL").assertIsDisplayed()
        composeRule.onNodeWithText("SMB directo").assertIsDisplayed()
        composeRule.onNodeWithText("Sala").assertIsDisplayed()
        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Lista").assertIsDisplayed()
    }

    @Test
    fun subtitleDialogListsEmbeddedAndExternalTracksAndSelectsOne() {
        var selectedTrack: String? = "unchanged"
        composeRule.setContent {
            SyncplayTheme {
                PlayerRoomScreen(
                    state = SyncplayUiState(
                        connectionStatus = ConnectionStatus.Connected(
                            endpoint = "192.0.2.65:8999",
                            secure = false,
                            serverVersion = "1.7.4",
                        ),
                        isInRoom = true,
                        effectiveUsername = "android-user",
                        effectiveRoom = "movies",
                        media = MediaDescriptor("Movie.mkv", 7_000.0, 8_000_000_000L),
                        subtitleTracks = listOf(
                            SubtitleTrackUi(
                                id = "subtitle:0:0",
                                label = "Japonés",
                                detail = "PGS",
                                isSelected = true,
                            ),
                            SubtitleTrackUi(
                                id = "subtitle:1:0",
                                label = "Inglés",
                                detail = "PGS",
                            ),
                            SubtitleTrackUi(
                                id = "subtitle:2:0",
                                label = "Español externo",
                                detail = "ASS/SSA",
                                isExternal = true,
                            ),
                        ),
                    ),
                    onOpenFile = {},
                    onOpenUrl = {},
                    onOpenSubtitle = {},
                    onSelectSubtitleTrack = { selectedTrack = it },
                    onToggleReady = {},
                    onSendChat = { true },
                    onChangeRoom = {},
                    onDisconnect = {},
                    onDismissPlaybackError = {},
                )
            }
        }

        composeRule.onNodeWithText("Subtítulos").performClick()
        // The selected track is visible both in the player chip and in the open dialog.
        composeRule.onAllNodesWithText("Japonés").assertCountEquals(2)
        composeRule.onNodeWithText("Inglés").assertIsDisplayed()
        composeRule.onNodeWithText("SMB directo").assertIsDisplayed()
        composeRule.onNodeWithText("Español externo").performClick()

        composeRule.runOnIdle {
            assertEquals("subtitle:2:0", selectedTrack)
        }
    }

    @Test
    fun playbackEngineDialogExplainsAutomaticCompatibilityAndAllowsOverride() {
        var selected: PlaybackEnginePreference? = null
        composeRule.setContent {
            SyncplayTheme {
                PlayerRoomScreen(
                    state = SyncplayUiState(
                        connectionStatus = ConnectionStatus.Connected(
                            endpoint = "192.0.2.65:8999",
                            secure = false,
                            serverVersion = "1.7.4",
                        ),
                        isInRoom = true,
                        effectiveUsername = "android-user",
                        effectiveRoom = "movies",
                        playbackEngine = PlaybackEngineState(
                            preference = PlaybackEnginePreference.AUTOMATIC,
                            active = PlaybackEngine.VLC,
                            reason = PlaybackEngineReason.MATROSKA_COMPATIBILITY,
                        ),
                    ),
                    onOpenFile = {},
                    onOpenUrl = {},
                    onOpenSubtitle = {},
                    onToggleReady = {},
                    onSendChat = { true },
                    onChangeRoom = {},
                    onDisconnect = {},
                    onDismissPlaybackError = {},
                    onSetPlaybackEngine = { selected = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Más opciones").performClick()
        composeRule.onNodeWithText("Motor de reproducción").performClick()
        composeRule.onNodeWithText("Motor activo: VLC").assertIsDisplayed()
        composeRule.onNodeWithText("Automático (recomendado)").assertIsDisplayed()
        composeRule.onNodeWithText("AndroidX Media3").performClick()

        composeRule.runOnIdle {
            assertEquals(PlaybackEnginePreference.MEDIA3, selected)
        }
    }
}
