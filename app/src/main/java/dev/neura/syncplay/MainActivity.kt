package dev.neura.syncplay

import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neura.syncplay.ui.ConnectionScreen
import dev.neura.syncplay.ui.FilePickerAppDialog
import dev.neura.syncplay.ui.PlayerRoomScreen
import dev.neura.syncplay.ui.SyncplayUiState
import dev.neura.syncplay.ui.SyncplayViewModel
import dev.neura.syncplay.ui.theme.SyncplayTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SyncplayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SyncplayTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SyncplayRoot(state, viewModel)
            }
        }
    }
}

@Composable
private fun SyncplayRoot(
    state: SyncplayUiState,
    viewModel: SyncplayViewModel,
) {
    val context = LocalContext.current
    // Keep the in-app chooser visible through rotation/window recreation. The
    // selected external ActivityResult launcher remains owned by composition.
    var pendingPicker by rememberSaveable { mutableStateOf<PendingPicker?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = OpenWithAppContract(),
        onResult = { picked ->
            picked?.let { viewModel.openMedia(it.uri, it.grantFlags) }
        },
    )
    val subtitlePicker = rememberLauncherForActivityResult(
        contract = OpenWithAppContract(),
        onResult = { picked ->
            picked?.let { viewModel.addSubtitle(it.uri, it.grantFlags) }
        },
    )

    when {
        !state.settingsLoaded -> LoadingScreen()
        state.isInRoom -> PlayerRoomScreen(
            state = state,
            onOpenFile = {
                pendingPicker = PendingPicker.MEDIA
            },
            onOpenUrl = viewModel::openUrl,
            onOpenSubtitle = {
                pendingPicker = PendingPicker.SUBTITLE
            },
            onToggleReady = viewModel::toggleReady,
            onSendChat = viewModel::sendChat,
            onChangeRoom = viewModel::changeRoom,
            onDisconnect = viewModel::disconnect,
            onDismissPlaybackError = viewModel::clearPlaybackError,
        )
        else -> ConnectionScreen(
            state = state,
            onFormChange = viewModel::updateConnectionForm,
            onConnect = viewModel::connect,
        )
    }

    pendingPicker?.let { pending ->
        val targets = remember(pending) {
            findFilePickerTargets(context, pending.mimeTypes)
        }
        FilePickerAppDialog(
            title = pending.title,
            targets = targets,
            onSelect = { target ->
                pendingPicker = null
                val request = OpenWithAppRequest(
                    mimeTypes = pending.mimeTypes,
                    chooserTitle = pending.title,
                    action = target.action,
                    component = target.component,
                )
                when (pending) {
                    PendingPicker.MEDIA -> mediaPicker.launch(request)
                    PendingPicker.SUBTITLE -> subtitlePicker.launch(request)
                }
            },
            onDismiss = { pendingPicker = null },
        )
    }
}

private enum class PendingPicker(
    val title: String,
    val mimeTypes: List<String>,
) {
    MEDIA(
        title = "Abrir video con…",
        mimeTypes = listOf("video/*", "audio/*", "application/octet-stream"),
    ),
    SUBTITLE(
        title = "Abrir subtítulos con…",
        // Do not constrain the remote file manager by MIME metadata. Android
        // and SMB providers commonly classify .ass as audio/aac (or as
        // application/octet-stream), which would hide an otherwise valid
        // subtitle before the user can select it. We validate the extension
        // after the picker returns.
        mimeTypes = listOf("*/*"),
    ),
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
