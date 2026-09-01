package dev.neura.syncplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.neura.syncplay.protocol.ChatEntry
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.RoomUser
import dev.neura.syncplay.protocol.supportsSyncplayFeature
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerRoomScreen(
    state: SyncplayUiState,
    onOpenFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSubtitle: () -> Unit,
    onToggleReady: () -> Unit,
    onSendChat: (String) -> Boolean,
    onChangeRoom: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDismissPlaybackError: () -> Unit,
) {
    var panel by rememberSaveable { mutableStateOf(RoomPanel.PEOPLE) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showRoomDialog by rememberSaveable { mutableStateOf(false) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreenActive = isFullscreen && state.player != null && state.media != null
    val roomUsers = state.users.filter { it.room == state.effectiveRoom }
    val companionCount = roomUsers.count { it.username != state.effectiveUsername }
    val companionSummary = when (companionCount) {
        0 -> "Solo tú por ahora"
        1 -> "1 compañero"
        else -> "$companionCount compañeros"
    }

    LaunchedEffect(state.player, state.media) {
        if (state.player == null || state.media == null) isFullscreen = false
    }
    FullscreenWindowEffect(fullscreenActive)
    BackHandler(enabled = fullscreenActive) { isFullscreen = false }

    if (fullscreenActive) {
        FullscreenPlayerScreen(
            player = state.player,
            isMediaLoading = state.isMediaLoading,
            onFullscreenChange = { isFullscreen = it },
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 780.dp || maxWidth > maxHeight * 1.35f
        val compactActions = maxHeight < 520.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                RoomTopAppBar(
                    state = state,
                    companionSummary = companionSummary,
                    onChangeRoom = { showRoomDialog = true },
                    onDisconnect = onDisconnect,
                )
            },
            bottomBar = {
                if (!wide) {
                    CompactRoomNavigationBar(
                        panel = panel,
                        onPanelChange = { panel = it },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                ConnectionBanner(state.connectionStatus)
                state.playback.error?.let { error ->
                    ErrorBanner(error, onDismissPlaybackError)
                }
                if (wide) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.65f)
                                .fillMaxHeight(),
                        ) {
                            PlayerPane(
                                state = state,
                                onOpenFile = onOpenFile,
                                onOpenUrl = { showUrlDialog = true },
                                onOpenSubtitle = onOpenSubtitle,
                                onFullscreenChange = { isFullscreen = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            MediaActionBar(
                                state = state,
                                onOpenFile = onOpenFile,
                                onOpenUrl = { showUrlDialog = true },
                                onOpenSubtitle = onOpenSubtitle,
                                onToggleReady = onToggleReady,
                                compact = compactActions,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp),
                        )
                        RoomPanelPane(
                            state = state,
                            panel = panel,
                            onPanelChange = { panel = it },
                            onSendChat = onSendChat,
                            showTabs = true,
                            modifier = Modifier
                                .widthIn(min = 320.dp, max = 430.dp)
                                .fillMaxHeight(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        if (state.media == null) {
                            PlayerPane(
                                state = state,
                                onOpenFile = onOpenFile,
                                onOpenUrl = { showUrlDialog = true },
                                onOpenSubtitle = onOpenSubtitle,
                                onFullscreenChange = { isFullscreen = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 310.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        } else {
                            PlayerPane(
                                state = state,
                                onOpenFile = onOpenFile,
                                onOpenUrl = { showUrlDialog = true },
                                onOpenSubtitle = onOpenSubtitle,
                                onFullscreenChange = { isFullscreen = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                        MediaActionBar(
                            state = state,
                            onOpenFile = onOpenFile,
                            onOpenUrl = { showUrlDialog = true },
                            onOpenSubtitle = onOpenSubtitle,
                            onToggleReady = onToggleReady,
                            compact = compactActions,
                        )
                        RoomPanelPane(
                            state = state,
                            panel = panel,
                            onPanelChange = { panel = it },
                            onSendChat = onSendChat,
                            showTabs = false,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        TextInputDialog(
            title = "Abrir una URL",
            label = "https://…/video.m3u8",
            confirmText = "Abrir",
            keyboardType = KeyboardType.Uri,
            validate = { value ->
                when {
                    value.isBlank() -> "Escribe una URL"
                    validateHttpMediaUrl(value) == null -> "Usa una URL http:// o https:// válida"
                    else -> null
                }
            },
            onDismiss = { showUrlDialog = false },
            onConfirm = {
                showUrlDialog = false
                onOpenUrl(it)
            },
        )
    }
    if (showRoomDialog) {
        TextInputDialog(
            title = "Cambiar de sala",
            label = "Nombre de la sala",
            initialValue = state.effectiveRoom,
            confirmText = "Entrar",
            onDismiss = { showRoomDialog = false },
            onConfirm = {
                showRoomDialog = false
                onChangeRoom(it)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTopAppBar(
    state: SyncplayUiState,
    companionSummary: String,
    onChangeRoom: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state.connectionStatus as? ConnectionStatus.Connected
    val secure = connected?.secure == true
    TopAppBar(
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.effectiveRoom.ifBlank { "Syncplay" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SyncStatusPill(state.playback)
                    Text(
                        text = "${state.effectiveUsername.ifBlank { "Invitado" }} · $companionSummary",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            Surface(
                modifier = Modifier.padding(start = 12.dp).size(34.dp),
                shape = CircleShape,
                color = if (secure) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (secure) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (secure) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = if (secure) {
                            "Conexión cifrada"
                        } else {
                            "Conexión sin cifrar o pendiente"
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        actions = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text("Cambiar de sala") } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onChangeRoom) {
                    Icon(Icons.Rounded.MeetingRoom, contentDescription = "Cambiar de sala")
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text("Desconectar") } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Desconectar")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SyncStatusPill(playback: PlaybackUiState) {
    val absolute = playback.syncOffsetMs?.let(::abs)
    val synchronized = absolute != null && absolute <= 150
    val adjusting = absolute != null && absolute <= 1_000 && !synchronized
    val statusText = when {
        synchronized -> "Sincronizado"
        adjusting -> "Ajustando"
        absolute != null -> "Desfase ${absolute} ms"
        else -> playback.syncAction
    }
    val containerColor = when {
        synchronized -> MaterialTheme.colorScheme.tertiaryContainer
        adjusting -> MaterialTheme.colorScheme.primaryContainer
        absolute != null -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        synchronized -> MaterialTheme.colorScheme.onTertiaryContainer
        adjusting -> MaterialTheme.colorScheme.onPrimaryContainer
        absolute != null -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .heightIn(min = 28.dp)
            .widthIn(max = 176.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = statusText
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = when {
                    synchronized -> Icons.Rounded.CheckCircle
                    adjusting -> Icons.Rounded.Refresh
                    absolute != null -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.HourglassEmpty
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactRoomNavigationBar(
    panel: RoomPanel,
    onPanelChange: (RoomPanel) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        RoomPanel.entries.forEach { item ->
            val label = when (item) {
                RoomPanel.PEOPLE -> "Sala"
                RoomPanel.CHAT -> "Chat"
                RoomPanel.PLAYLIST -> "Lista"
            }
            NavigationBarItem(
                selected = panel == item,
                onClick = { onPanelChange(item) },
                icon = {
                    Icon(
                        imageVector = when (item) {
                            RoomPanel.PEOPLE -> Icons.Rounded.Groups
                            RoomPanel.CHAT -> Icons.AutoMirrored.Rounded.Chat
                            RoomPanel.PLAYLIST -> Icons.Rounded.QueuePlayNext
                        },
                        contentDescription = null,
                    )
                },
                label = { Text(label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun PlayerPane(
    state: SyncplayUiState,
    onOpenFile: () -> Unit,
    onOpenUrl: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasMedia = state.media != null
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (hasMedia) {
                    Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val tightEmptyState = !hasMedia && maxHeight < 280.dp
        val player = state.player
        if (player != null && state.media != null) {
            Media3PlayerView(
                player = player,
                isFullscreen = false,
                controlsEnabled = !state.isMediaLoading,
                onFullscreenChange = onFullscreenChange,
            )
        }
        if (state.media == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (tightEmptyState) 6.dp else 12.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (tightEmptyState) 12.dp else 24.dp,
                        vertical = if (tightEmptyState) 8.dp else 22.dp,
                    ),
            ) {
                if (!tightEmptyState) {
                    Surface(
                        modifier = Modifier.size(78.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
                Text(
                    "Ningún video abierto",
                    style = if (tightEmptyState) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (tightEmptyState) {
                        "Abre un archivo o una URL para reproducir en sincronía."
                    } else {
                        "Abre tu copia local o pega una URL para comenzar a reproducir en sincronía. Syncplay no transfiere el video."
                    },
                    style = if (tightEmptyState) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = if (tightEmptyState) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
                FlowRow(
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onOpenFile,
                        modifier = Modifier
                            .widthIn(min = if (tightEmptyState) 128.dp else 160.dp)
                            .heightIn(min = if (tightEmptyState) 48.dp else 52.dp),
                    ) {
                        Icon(Icons.Rounded.VideoFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (tightEmptyState) "Archivo" else "Abrir archivo",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenUrl,
                        modifier = Modifier
                            .widthIn(min = if (tightEmptyState) 128.dp else 160.dp)
                            .heightIn(min = if (tightEmptyState) 48.dp else 52.dp),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (tightEmptyState) "URL" else "Abrir URL", maxLines = 1)
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = state.media.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 360.dp),
                )
            }
            if (state.subtitleName != null) {
                AssistChip(
                    onClick = onOpenSubtitle,
                    enabled = !state.isSubtitleLoading && !state.isMediaLoading,
                    label = {
                        Text(
                            state.subtitleName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).widthIn(max = 230.dp),
                )
            }
        }
        if (state.isMediaLoading) {
            MediaLoadingOverlay(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Media3PlayerView(
    player: Player,
    isFullscreen: Boolean,
    controlsEnabled: Boolean = true,
    onFullscreenChange: (Boolean) -> Unit,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                setBackgroundColor(AndroidColor.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                useController = controlsEnabled
                controllerAutoShow = true
                controllerShowTimeoutMs = 3_000
                setShowSubtitleButton(true)
                setShowShuffleButton(false)
                setShowNextButton(false)
                setShowPreviousButton(false)
                keepScreenOn = true
                this.player = player
                setFullscreenButtonClickListener(onFullscreenChange)
                setFullscreenButtonState(isFullscreen)
            }
        },
        update = {
            it.player = player
            it.useController = controlsEnabled
            it.setFullscreenButtonClickListener(onFullscreenChange)
            it.setFullscreenButtonState(isFullscreen)
        },
        onRelease = {
            it.setFullscreenButtonClickListener(null)
            it.player = null
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun FullscreenPlayerScreen(
    player: Player?,
    isMediaLoading: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null) {
            Media3PlayerView(
                player = player,
                isFullscreen = true,
                controlsEnabled = !isMediaLoading,
                onFullscreenChange = onFullscreenChange,
            )
        }
        if (isMediaLoading) {
            MediaLoadingOverlay(modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun MediaLoadingOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
            )
            Text("Abriendo video…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FullscreenWindowEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (!enabled || activity == null) return@DisposableEffect onDispose { }

        val previousOrientation = activity.requestedOrientation
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previousInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val previousBehavior = insetsController.systemBarsBehavior
        val statusBarsWereVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationBarsWereVisible =
            previousInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val previousLightStatusBars = insetsController.isAppearanceLightStatusBars
        val previousLightNavigationBars = insetsController.isAppearanceLightNavigationBars
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity.requestedOrientation = previousOrientation
            insetsController.systemBarsBehavior = previousBehavior
            insetsController.isAppearanceLightStatusBars = previousLightStatusBars
            insetsController.isAppearanceLightNavigationBars = previousLightNavigationBars
            if (statusBarsWereVisible) {
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (navigationBarsWereVisible) {
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun MediaActionBar(
    state: SyncplayUiState,
    onOpenFile: () -> Unit,
    onOpenUrl: () -> Unit,
    onOpenSubtitle: () -> Unit,
    onToggleReady: () -> Unit,
    compact: Boolean,
) {
    val connected = state.connectionStatus as? ConnectionStatus.Connected
    val readinessAvailable = connected != null && supportsSyncplayFeature(
        features = state.serverFeatures,
        serverVersion = connected.serverVersion,
        feature = "readiness",
        minimumVersion = "1.3.0",
    )
    val actions: @Composable () -> Unit = {
        if (state.media != null) {
            FilledTonalButton(
                onClick = onOpenFile,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Rounded.VideoFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Cambiar")
            }
            OutlinedButton(
                onClick = onOpenUrl,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("URL")
            }
            OutlinedButton(
                onClick = onOpenSubtitle,
                enabled = !state.isMediaLoading && !state.isSubtitleLoading,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (state.isSubtitleLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.ClosedCaption, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (state.isSubtitleLoading) "Cargando…" else "Subtítulos")
            }
        }
        FilterChip(
            selected = state.localReady,
            onClick = onToggleReady,
            enabled = readinessAvailable,
            leadingIcon = {
                Icon(
                    if (state.localReady) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                    contentDescription = null,
                )
            },
            label = { Text(if (state.localReady) "Listo" else "No listo") },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        SyncHealthChip(state.playback.syncOffsetMs, state.playback.syncAction)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        tonalElevation = 0.dp,
    ) {
        if (compact) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun SyncHealthChip(offsetMs: Long?, action: String) {
    val absolute = offsetMs?.let(::abs)
    val color = when {
        absolute == null -> MaterialTheme.colorScheme.onSurfaceVariant
        absolute <= 150 -> MaterialTheme.colorScheme.secondary
        absolute <= 1_000 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val label = if (offsetMs == null) action else "$action · ${abs(offsetMs)} ms"
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .widthIn(max = 300.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = label
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (absolute != null && absolute > 1_000) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RoomPanelPane(
    state: SyncplayUiState,
    panel: RoomPanel,
    onPanelChange: (RoomPanel) -> Unit,
    onSendChat: (String) -> Boolean,
    showTabs: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (showTabs) {
            PrimaryTabRow(
                selectedTabIndex = panel.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                RoomPanel.entries.forEach { item ->
                    val label = when (item) {
                        RoomPanel.PEOPLE -> "Sala"
                        RoomPanel.CHAT -> "Chat"
                        RoomPanel.PLAYLIST -> "Lista"
                    }
                    Tab(
                        selected = panel == item,
                        onClick = { onPanelChange(item) },
                        icon = {
                            Icon(
                                when (item) {
                                    RoomPanel.PEOPLE -> Icons.Rounded.Groups
                                    RoomPanel.CHAT -> Icons.AutoMirrored.Rounded.Chat
                                    RoomPanel.PLAYLIST -> Icons.Rounded.QueuePlayNext
                                },
                                contentDescription = label,
                            )
                        },
                        text = { Text(label) },
                    )
                }
            }
        }
        when (panel) {
            RoomPanel.PEOPLE -> PeoplePanel(state, Modifier.weight(1f))
            RoomPanel.CHAT -> {
                val connected = state.connectionStatus as? ConnectionStatus.Connected
                val canSendChat = connected != null && supportsSyncplayFeature(
                    features = state.serverFeatures,
                    serverVersion = connected.serverVersion,
                    feature = "chat",
                    minimumVersion = "1.5.0",
                )
                ChatPanel(
                    chat = state.chat,
                    canSend = canSendChat,
                    reconnecting = state.connectionStatus is ConnectionStatus.Reconnecting,
                    onSendChat = onSendChat,
                    modifier = Modifier.weight(1f),
                )
            }
            RoomPanel.PLAYLIST -> PlaylistPanel(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PeoplePanel(state: SyncplayUiState, modifier: Modifier = Modifier) {
    val users = state.users.filter { it.room == state.effectiveRoom }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Participantes", style = MaterialTheme.typography.titleMedium)
            Text(
                users.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (users.isEmpty()) {
            EmptyPanel(
                icon = Icons.Rounded.Groups,
                title = "Esperando compañeros",
                body = "Comparte el servidor y el nombre exacto de la sala.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(users, key = { it.username }) { user ->
                    UserCard(user, state)
                }
            }
        }
    }
}

@Composable
private fun UserCard(user: RoomUser, state: SyncplayUiState) {
    val fileMatches = fileMatches(user, state)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (user.username == state.effectiveUsername) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (user.username == state.effectiveUsername) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        user.username.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (user.username == state.effectiveUsername) "${user.username} · tú" else user.username,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (user.isController) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Key,
                            "Controlador",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = user.file?.name ?: "Sin archivo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (fileMatches) {
                        true -> MaterialTheme.colorScheme.tertiary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        fileMatches?.let { matches ->
                            stateDescription = if (matches) "El archivo coincide" else "El archivo no coincide"
                        }
                    },
                )
                when (fileMatches) {
                    true -> Text(
                        "Mismo archivo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    false -> Text(
                        "El archivo no coincide",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Unit
                }
            }
            Icon(
                imageVector = if (user.isReady == true) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                contentDescription = if (user.isReady == true) "Listo" else "No listo",
                tint = if (user.isReady == true) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun fileMatches(user: RoomUser, state: SyncplayUiState): Boolean? {
    val local = state.media ?: return null
    val remote = user.file ?: return null
    if (user.username == state.effectiveUsername) return true
    val sameName = local.name.equals(remote.name, ignoreCase = true)
    val sameSize = local.sizeBytes == 0L || remote.sizeBytes == 0L || local.sizeBytes == remote.sizeBytes
    val sameDuration = local.durationSeconds == 0.0 || remote.durationSeconds == 0.0 ||
        abs(local.durationSeconds - remote.durationSeconds) <= 2.5
    return sameName && sameSize && sameDuration
}

@Composable
private fun ChatPanel(
    chat: List<ChatEntry>,
    canSend: Boolean,
    reconnecting: Boolean,
    onSendChat: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.lastOrNull()?.id) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (chat.isNotEmpty() && (lastVisible == -1 || lastVisible >= chat.lastIndex - 2)) {
            listState.scrollToItem(chat.lastIndex)
        }
    }
    Column(modifier = modifier) {
        if (chat.isEmpty()) {
            EmptyPanel(
                icon = Icons.AutoMirrored.Rounded.Chat,
                title = "Chat de la sala",
                body = "Los mensajes no están cifrados si la conexión indica un candado abierto.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chat, key = { it.id }) { entry -> ChatBubble(entry) }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("Mensaje") },
                enabled = canSend,
                supportingText = if (!canSend) {
                    {
                        Text(
                            if (reconnecting) "Se conserva mientras reconectas" else "Este servidor no admite chat",
                        )
                    }
                } else {
                    null
                },
                minLines = 1,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) {
                        if (onSendChat(input)) input = ""
                    }
                }),
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        if (onSendChat(input)) input = ""
                    }
                },
                enabled = canSend && input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Enviar")
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    if (entry.isSystem) {
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    entry.username.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(entry.message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PlaylistPanel(state: SyncplayUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Lista compartida", style = MaterialTheme.typography.titleMedium)
            Text(
                state.sharedPlaylist.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.sharedPlaylist.isEmpty()) {
            EmptyPanel(
                icon = Icons.Rounded.QueuePlayNext,
                title = "Lista compartida vacía",
                body = "La lista sincroniza nombres y URLs, no transfiere archivos. Ábrelo manualmente en Android cuando tus compañeros cambien de elemento.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sharedPlaylist.withIndex().toList(), key = { it.index }) { item ->
                    val selected = item.index == state.sharedPlaylistIndex
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (item.index + 1).toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.value,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Elemento actual",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPanel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

@Composable
private fun ConnectionBanner(status: ConnectionStatus) {
    when (status) {
        // Reconnecting is a recoverable transport state, not a playback error.  Keep the room
        // visible and communicate progress without exposing a raw socket exception as a red
        // failure banner; terminal connection errors are still handled separately.
        is ConnectionStatus.Reconnecting -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Reconectando con el servidor… · intento ${status.attempt}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    conciseConnectionReason(status.reason)?.let { reason ->
                        Text(
                            "Motivo: $reason",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        is ConnectionStatus.Connected -> if (!status.secure) Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Conexión sin TLS: la contraseña/hash y los datos de la sala pueden observarse en tránsito.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        else -> Unit
    }
}

private fun conciseConnectionReason(reason: String): String? {
    val normalized = reason.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return null
    val friendly = when {
        normalized.contains("failed to connect", ignoreCase = true) -> "No se pudo conectar al servidor"
        normalized.contains("connection refused", ignoreCase = true) -> "El servidor rechazó la conexión"
        normalized.contains("timed out", ignoreCase = true) ||
            normalized.contains("timeout", ignoreCase = true) -> "El servidor no respondió a tiempo"
        normalized.contains("connection reset", ignoreCase = true) -> "El servidor cerró la conexión"
        normalized.contains("broken pipe", ignoreCase = true) -> "Se interrumpió la conexión"
        else -> normalized
    }
    return friendly.take(72).let { value ->
        if (friendly.length > value.length) "$value…" else value
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Assertive
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar error") }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    confirmText: String,
    initialValue: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> String? = { value -> if (value.isBlank()) "Este campo es obligatorio" else null },
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var interacted by rememberSaveable(initialValue) { mutableStateOf(false) }
    val validationError = validate(value)
    val visibleError = validationError.takeIf { interacted || value.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    interacted = true
                },
                label = { Text(label) },
                singleLine = true,
                isError = visibleError != null,
                supportingText = visibleError?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    interacted = true
                    if (validationError == null) onConfirm(value)
                }),
                modifier = Modifier
                    .fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    interacted = true
                    if (validationError == null) onConfirm(value)
                },
                enabled = validationError == null,
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
