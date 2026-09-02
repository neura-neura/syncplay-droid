package dev.neura.syncplay.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.neura.syncplay.smb.SmbConnectionProfile
import dev.neura.syncplay.smb.SmbDirectoryEntry
import dev.neura.syncplay.smb.SmbLocation
import dev.neura.syncplay.smb.SmbPlaybackEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class SmbPickKind {
    MEDIA,
    SUBTITLE,
}

internal data class SmbPickedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * In-app SMB2/SMB3 browser used when a DocumentsProvider only exposes a sequential pipe.
 *
 * Credentials are registered in [SmbPlaybackEnvironment] for this process only. They are not
 * placed in the playback URI, Compose save state, logs, DataStore, or Android backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmbBrowserDialog(
    kind: SmbPickKind,
    initialHost: String = "",
    onPicked: (SmbPickedFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val profileId = rememberSaveable { "browse-${UUID.randomUUID()}" }
    var host by rememberSaveable { mutableStateOf(initialHost) }
    var port by rememberSaveable { mutableStateOf("445") }
    var share by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords must never be written into a SavedState Bundle.
    var password by remember { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var currentPath by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SmbDirectoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retainProfile by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val registry = SmbPlaybackEnvironment.registry
    val repository = SmbPlaybackEnvironment.directoryRepository
    // DisposableEffect is intentionally keyed only by the stable profile id. Keep the cleanup
    // callback in sync with the latest selection so choosing a file cannot be undone by the
    // dialog leaving composition with the callback that captured the initial `false` value.
    val retainProfileState = rememberUpdatedState(retainProfile)

    DisposableEffect(profileId) {
        onDispose {
            if (!retainProfileState.value) registry.unregister(profileId)
        }
    }

    fun browse(path: String) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.list(profileId, share.trim(), path)
                }
                currentPath = path
                entries = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "No se pudo leer esta carpeta. Comprueba el recurso y la conexión."
            } finally {
                loading = false
            }
        }
    }

    fun connect() {
        if (loading) return
        val normalizedHost = host.trim()
        val normalizedShare = share.trim()
        val parsedPort = port.toIntOrNull()
        error = when {
            normalizedHost.isEmpty() -> "Escribe la dirección del equipo SMB."
            parsedPort == null || parsedPort !in 1..65535 -> "El puerto debe estar entre 1 y 65535."
            normalizedShare.isEmpty() -> "Escribe el recurso compartido, por ejemplo D o Movies."
            normalizedShare.any { it == '/' || it == '\\' } ->
                "El recurso compartido es solo su nombre; navega las carpetas después."
            else -> null
        }
        if (error != null) return

        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val profile = SmbConnectionProfile(
                        id = profileId,
                        host = normalizedHost,
                        port = parsedPort!!,
                        username = username.trim(),
                        password = password,
                        domain = domain.trim().takeIf(String::isNotEmpty),
                        knownShares = listOf(normalizedShare),
                    )
                    repository.connect(profile)
                    repository.list(profileId, normalizedShare)
                }
                host = normalizedHost
                share = normalizedShare
                currentPath = ""
                entries = result
                connected = true
                password = ""
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                registry.unregister(profileId)
                error = "No se pudo conectar. Revisa servidor, recurso, usuario y contraseña."
            } finally {
                loading = false
            }
        }
    }

    fun goBack() {
        if (loading) return
        if (currentPath.isEmpty()) {
            connected = false
            entries = emptyList()
            registry.unregister(profileId)
            return
        }
        browse(currentPath.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    // Restore the visible directory after an Activity/window recreation. Credentials remain only
    // in the process registry; if Android killed the process, the user returns to the login form.
    LaunchedEffect(connected, profileId) {
        if (connected && entries.isEmpty() && registry.contains(profileId) && !loading) {
            browse(currentPath)
        } else if (connected && !registry.contains(profileId)) {
            connected = false
            error = "Vuelve a escribir las credenciales SMB."
        }
    }

    Dialog(
        onDismissRequest = { if (!loading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !loading,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (LocalConfiguration.current.screenWidthDp >= 700) 0.72f else 0.96f)
                .fillMaxHeight(0.92f)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(if (kind == SmbPickKind.MEDIA) "Abrir video por SMB" else "Abrir subtítulos por SMB")
                                if (connected) {
                                    Text(
                                        text = buildSmbBreadcrumb(host, share, currentPath),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (connected) {
                                IconButton(onClick = ::goBack, enabled = !loading) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Carpeta anterior")
                                }
                            } else {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = 18.dp).size(24.dp),
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = onDismiss, enabled = !loading) { Text("Cerrar") }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (connected) {
                        SmbDirectoryContents(
                            kind = kind,
                            entries = entries,
                            loading = loading,
                            error = error,
                            onOpenDirectory = { browse(it.path) },
                            onPickFile = { entry ->
                                retainProfile = true
                                onPicked(
                                    SmbPickedFile(
                                        uri = SmbLocation(profileId, share, entry.path).asUri(),
                                        displayName = entry.name,
                                        sizeBytes = entry.sizeBytes,
                                    ),
                                )
                            },
                            onRetry = { browse(currentPath) },
                        )
                    } else {
                        SmbConnectionForm(
                            host = host,
                            onHostChange = { host = it; error = null },
                            port = port,
                            onPortChange = { port = it.filter(Char::isDigit).take(5); error = null },
                            share = share,
                            onShareChange = { share = it; error = null },
                            username = username,
                            onUsernameChange = { username = it; error = null },
                            password = password,
                            onPasswordChange = { password = it; error = null },
                            domain = domain,
                            onDomainChange = { domain = it; error = null },
                            passwordVisible = passwordVisible,
                            onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                            loading = loading,
                            error = error,
                            onConnect = ::connect,
                        )
                    }
                    if (loading) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 20.dp, vertical = 16.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text(if (connected) "Abriendo carpeta…" else "Conectando por SMB…")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmbConnectionForm(
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    share: String,
    onShareChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    domain: String,
    onDomainChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    loading: Boolean,
    error: String?,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Conexión directa con acceso aleatorio. Úsala para MKV grandes cuando un explorador externo se atore al adelantar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Servidor") },
                supportingText = { Text("IP o nombre del equipo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !loading,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Puerto") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !loading,
                modifier = Modifier.width(112.dp),
            )
        }
        OutlinedTextField(
            value = share,
            onValueChange = onShareChange,
            label = { Text("Recurso compartido") },
            supportingText = { Text("Solo el nombre, por ejemplo D o Movies") },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Usuario") },
            supportingText = { Text("Déjalo vacío si el recurso acepta invitados") },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Contraseña") },
            supportingText = { Text("Se conserva solo mientras la app está abierta") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityChange) {
                    Icon(
                        if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = domain,
            onValueChange = onDomainChange,
            label = { Text("Dominio (opcional)") },
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(
            onClick = onConnect,
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text("Conectar y explorar")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SmbDirectoryContents(
    kind: SmbPickKind,
    entries: List<SmbDirectoryEntry>,
    loading: Boolean,
    error: String?,
    onOpenDirectory: (SmbDirectoryEntry) -> Unit,
    onPickFile: (SmbDirectoryEntry) -> Unit,
    onRetry: () -> Unit,
) {
    val visible = remember(entries, kind) {
        entries.filter { entry -> entry.isDirectory || entry.isSupportedFor(kind) }
    }
    val hiddenCount = entries.count { !it.isDirectory && !it.isSupportedFor(kind) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (kind == SmbPickKind.SUBTITLE) {
            Text(
                "Se muestran SRT, ASS, SSA, VTT y TTML.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry, enabled = !loading) { Text("Reintentar") }
                }
            }
        }
        if (visible.isEmpty() && !loading && error == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (kind == SmbPickKind.SUBTITLE) {
                        "No hay subtítulos compatibles en esta carpeta."
                    } else {
                        "Esta carpeta está vacía."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(visible, key = { "${it.isDirectory}:${it.path}" }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(enabled = !loading) {
                                if (entry.isDirectory) onOpenDirectory(entry) else onPickFile(entry)
                            }
                            .semantics { role = Role.Button }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when {
                                entry.isDirectory -> Icons.Rounded.Folder
                                kind == SmbPickKind.SUBTITLE -> Icons.Rounded.Description
                                else -> Icons.Rounded.Movie
                            },
                            contentDescription = null,
                            tint = if (entry.isDirectory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!entry.isDirectory) {
                                Text(
                                    formatFileSize(entry.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (hiddenCount > 0) {
            Text(
                "$hiddenCount ${if (hiddenCount == 1) "archivo oculto" else "archivos ocultos"} por formato.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

private fun SmbDirectoryEntry.isSupportedFor(kind: SmbPickKind): Boolean {
    if (isDirectory) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (kind) {
        SmbPickKind.SUBTITLE -> extension in setOf("srt", "ass", "ssa", "vtt", "ttml", "xml")
        SmbPickKind.MEDIA -> extension in setOf(
            "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "mpg",
            "mpeg", "3gp", "flv", "wmv", "mp3", "flac", "m4a", "aac", "ogg", "opus",
        )
    }
}

private fun buildSmbBreadcrumb(host: String, share: String, path: String): String = buildString {
    append(host)
    append(" / ")
    append(share)
    if (path.isNotEmpty()) {
        append(" / ")
        append(path.replace("/", " › "))
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
