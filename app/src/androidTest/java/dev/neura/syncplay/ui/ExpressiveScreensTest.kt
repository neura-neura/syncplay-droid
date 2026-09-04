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
    fun playbackDiagnosticsIdentifyMpvAsTheSingleBackend() {
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

        composeRule.onNodeWithContentDescription("Más opciones").performClick()
        composeRule.onNodeWithText("Diagnóstico de reproducción").performClick()
        composeRule.onNodeWithText("MPV · único motor de reproducción").assertIsDisplayed()
    }

    @Test
    fun subtitleCustomizationExposesNoirAppearanceAndTimingControls() {
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
                                id = "subtitle:1:0",
                                label = "episode.ass",
                                isSelected = true,
                                isExternal = true,
                                isText = true,
                            ),
                        ),
                        installedSubtitleFonts = listOf("sans-serif", "serif"),
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

        composeRule.onNodeWithContentDescription("Más opciones").performClick()
        composeRule.onNodeWithText("Personalizar subtítulos").performClick()
        composeRule.onNodeWithText("Apariencia de subtítulos").assertIsDisplayed()
        composeRule.onNodeWithText("Buscar fuentes instaladas").assertIsDisplayed()
        composeRule.onNodeWithText("Color del texto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tamaño de fuente").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Avanzado").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Mostrar").performClick()
        // The editor is a regular scrollable Column (not lazy), so every option must remain in
        // the semantics tree even when it is below the current viewport.
        composeRule.onAllNodesWithText("Peso de fuente").assertCountEquals(1)
        composeRule.onAllNodesWithText("Espaciado de letras").assertCountEquals(1)
        composeRule.onAllNodesWithText("Desfase de subtítulos").assertCountEquals(1)
        composeRule.onAllNodesWithText("Recordar desfase").assertCountEquals(1)
        composeRule.onAllNodesWithText("Anterior aquí").assertCountEquals(1)
        composeRule.onAllNodesWithText("Siguiente aquí").assertCountEquals(1)
        composeRule.onAllNodesWithText("Descargar ajustados").assertCountEquals(1)
    }
}
