package dev.neura.syncplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.neura.syncplay.protocol.ConnectionConfig
import dev.neura.syncplay.protocol.ConnectionStatus
import dev.neura.syncplay.protocol.parseServerEndpoint
import dev.neura.syncplay.protocol.serverAddressWithPort
import dev.neura.syncplay.ui.theme.Cyan

private val QuickPorts = 8995..8999

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: SyncplayUiState,
    onFormChange: (ConnectionConfig) -> Unit,
    onConnect: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val busy = state.connectionStatus is ConnectionStatus.Connecting ||
        state.connectionStatus is ConnectionStatus.Securing ||
        state.connectionStatus is ConnectionStatus.Reconnecting
    val connectionError = (state.connectionStatus as? ConnectionStatus.Error)?.message

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Syncplay",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 32.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = "SYNCPLAY / MEDIA3",
                        style = MaterialTheme.typography.labelLarge,
                        color = Cyan,
                    )
                    Spacer(Modifier.heightIn(min = 10.dp))
                    Text(
                        text = "Conéctate a una sala",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.heightIn(min = 6.dp))
                    Text(
                        text = "Para sincronizar tu reproducción con otros.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.heightIn(min = 24.dp))

                    ConnectionForm(
                        config = state.form,
                        busy = busy,
                        fieldError = state.formError,
                        globalError = connectionError,
                        focusManager = focusManager,
                        onChange = onFormChange,
                        onConnect = onConnect,
                    )
                    SecurityHint(preferTls = state.form.preferTls)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionForm(
    config: ConnectionConfig,
    busy: Boolean,
    fieldError: String?,
    globalError: String?,
    focusManager: FocusManager,
    onChange: (ConnectionConfig) -> Unit,
    onConnect: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val submit = {
        focusManager.clearFocus()
        onConnect()
    }
    val serverError = fieldError?.let { message ->
        message.contains("dirección", ignoreCase = true) ||
            message.contains("puerto", ignoreCase = true) ||
            message.contains("nombre del servidor", ignoreCase = true)
    } == true
    val usernameError = fieldError?.contains("escribe tu nombre", ignoreCase = true) == true
    val roomError = fieldError?.contains("sala", ignoreCase = true) == true
    val inlineErrorShown = serverError || usernameError || roomError
    val bannerError = globalError ?: fieldError.takeUnless { inlineErrorShown }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = config.serverAddress,
            onValueChange = { onChange(config.copy(serverAddress = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Servidor") },
            isError = serverError,
            supportingText = {
                Text(if (serverError) fieldError.orEmpty() else "IP o dominio y puerto")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 520.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = config.username,
                        onValueChange = { onChange(config.copy(username = it)) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        label = { Text("Tu nombre") },
                        isError = usernameError,
                        supportingText = if (usernameError) {
                            { Text(fieldError.orEmpty()) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = config.room,
                        onValueChange = { onChange(config.copy(room = it)) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        label = { Text("Sala") },
                        isError = roomError,
                        supportingText = if (roomError) {
                            { Text(fieldError.orEmpty()) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = config.username,
                        onValueChange = { onChange(config.copy(username = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text("Tu nombre") },
                        isError = usernameError,
                        supportingText = if (usernameError) {
                            { Text(fieldError.orEmpty()) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = config.room,
                        onValueChange = { onChange(config.copy(room = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text("Sala") },
                        isError = roomError,
                        supportingText = if (roomError) {
                            { Text(fieldError.orEmpty()) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
            }
        }

        val expansionRotation by animateFloatAsState(
            targetValue = if (advancedExpanded) 180f else 0f,
            label = "advancedOptionsRotation",
        )
        Card(
            onClick = { advancedExpanded = !advancedExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .semantics {
                    stateDescription = if (advancedExpanded) "Expandido" else "Contraído"
                },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Opciones avanzadas",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Contraseña, TLS y puerto manual",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .rotate(expansionRotation),
                )
            }
        }

        AnimatedVisibility(
            visible = advancedExpanded,
            modifier = Modifier.fillMaxWidth(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Puerto rápido",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val selectedPort = runCatching {
                    parseServerEndpoint(config.serverAddress).port
                }.getOrNull()
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickPorts.forEach { port ->
                        FilterChip(
                            selected = selectedPort == port,
                            onClick = {
                                val updatedAddress = runCatching {
                                    serverAddressWithPort(config.serverAddress, port)
                                }.getOrNull()
                                if (updatedAddress != null) {
                                    onChange(config.copy(serverAddress = updatedAddress))
                                }
                            },
                            enabled = !busy,
                            label = { Text(port.toString()) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Usar puerto $port"
                                },
                        )
                    }
                }

                OutlinedTextField(
                    value = config.password,
                    onValueChange = { onChange(config.copy(password = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Contraseña del servidor (opcional)") },
                    supportingText = { Text("Se recuerda cifrada en este dispositivo") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        val description = if (passwordVisible) {
                            "Ocultar contraseña"
                        } else {
                            "Mostrar contraseña"
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above,
                            ),
                            tooltip = { PlainTooltip { Text(description) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = description,
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Seguridad de la conexión",
                            tint = Cyan,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Exigir TLS", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Desactívalo sólo para un servidor antiguo y una red en la que confíes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = config.preferTls,
                            onCheckedChange = { onChange(config.copy(preferTls = it)) },
                            enabled = !busy,
                            modifier = Modifier.semantics {
                                contentDescription = "Exigir conexión TLS"
                                stateDescription = if (config.preferTls) "Activado" else "Desactivado"
                            },
                        )
                    }
                }
            }
        }

        if (bannerError != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                    },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = "Error",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = bannerError,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            onClick = submit,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Conectando…")
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Entrar y sincronizar")
            }
        }
    }
}

@Composable
private fun SecurityHint(preferTls: Boolean) {
    val title = if (preferTls) {
        "Conexión protegida con TLS"
    } else {
        "La conexión puede no ser segura"
    }
    val message = if (preferTls) {
        "Usa TLS para mantener tus datos privados."
    } else {
        "Activa TLS en opciones avanzadas para mayor privacidad."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Seguridad de la conexión",
            tint = if (preferTls) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
