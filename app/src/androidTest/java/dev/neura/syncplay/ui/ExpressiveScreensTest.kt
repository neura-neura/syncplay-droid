package dev.neura.syncplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
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
        composeRule.onNodeWithText("Sala").assertIsDisplayed()
        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Lista").assertIsDisplayed()
    }
}
