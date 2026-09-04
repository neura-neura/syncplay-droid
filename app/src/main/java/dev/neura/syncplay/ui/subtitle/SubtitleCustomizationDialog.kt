package dev.neura.syncplay.ui.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Material 3 editor for every Noir subtitle appearance and synchronization control.
 *
 * Remote CSS is intentionally UI-only: this component reports the URL and load intent to the
 * host, which can apply its own networking and trust policy. No remote content is fetched here.
 */
@Composable
fun SubtitleCustomizationDialog(
    appearance: SubtitleAppearance,
    sync: SubtitleSyncSettings,
    onAppearanceChange: (SubtitleAppearance) -> Unit,
    onSyncChange: (SubtitleSyncSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    installedFonts: List<String> = emptyList(),
    remoteFonts: List<String> = emptyList(),
    remoteCssUrl: String = "",
    isRemoteFontLoading: Boolean = false,
    onRemoteCssUrlChange: (String) -> Unit = {},
    onLoadRemoteCss: (String) -> Unit = {},
    canAlignToCue: Boolean = false,
    onAlignPreviousCue: () -> Unit = {},
    onAlignNextCue: () -> Unit = {},
    canExportSubtitle: Boolean = false,
    isExportingSubtitle: Boolean = false,
    onExportSubtitle: () -> Unit = {},
    onResetAppearance: () -> Unit = { onAppearanceChange(SubtitleAppearance.DEFAULT) },
) {
    val style = appearance.normalized()
    val normalizedSync = sync.normalized()
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var fontQuery by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val fontScrollState = rememberScrollState()
    val fontOptions = remember(installedFonts, remoteFonts) {
        listOf(SubtitleAppearance.DEFAULT_FONT_FAMILY) + installedFonts + remoteFonts
    }.map(String::trim).filter(String::isNotEmpty).distinct()
    val filteredFonts = fontOptions.filter { it.contains(fontQuery.trim(), ignoreCase = true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 820.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Apariencia de subtítulos", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Personaliza cómo se ven y sincronizan en este dispositivo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Cerrar apariencia de subtítulos"
                        },
                    ) {
                        Text("×", style = MaterialTheme.typography.headlineSmall)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Vista previa", style = MaterialTheme.typography.titleMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(164.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black),
                    ) {
                        SubtitleTextOverlay(
                            text = "Una noche tranquila en la ciudad.",
                            appearance = style,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SubtitleSectionTitle("Fuente")
                    OutlinedTextField(
                        value = fontQuery,
                        onValueChange = { fontQuery = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text("Buscar fuentes instaladas") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 196.dp)
                            .verticalScroll(fontScrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (filteredFonts.isEmpty()) {
                            Text(
                                "No hay fuentes que coincidan",
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            filteredFonts.forEach { family ->
                                FilterChip(
                                    selected = style.fontFamily == family,
                                    onClick = {
                                        onAppearanceChange(style.copy(fontFamily = family))
                                    },
                                    label = {
                                        Text(
                                            family,
                                            fontFamily = SubtitleFontRegistry.resolve(family),
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "Predeterminada: Noir/Sans (familia compatible del sistema).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = remoteCssUrl,
                        onValueChange = onRemoteCssUrlChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text("URL CSS remota (opcional)") },
                        supportingText = { Text("Compatible con hojas @font-face de Noir Player.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    OutlinedButton(
                        onClick = { onLoadRemoteCss(remoteCssUrl.trim()) },
                        enabled = remoteCssUrl.isNotBlank() && !isRemoteFontLoading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (isRemoteFontLoading) "Cargando…" else "Cargar CSS remoto")
                    }

                    SubtitleSectionTitle("Colores")
                    SubtitleColorField(
                        label = "Color del texto",
                        value = style.textColor,
                        onValueChange = { onAppearanceChange(style.copy(textColor = it)) },
                    )
                    SubtitleColorField(
                        label = "Color del fondo",
                        value = style.backgroundColor,
                        onValueChange = { onAppearanceChange(style.copy(backgroundColor = it)) },
                    )
                    SubtitleNumberControl(
                        label = "Opacidad del fondo",
                        value = style.backgroundOpacity * 100f,
                        range = SubtitleAppearanceRanges.opacitySlider,
                        typedRange = SubtitleAppearanceRanges.opacityTyped,
                        unit = "%",
                        onValueChange = {
                            onAppearanceChange(style.copy(backgroundOpacity = it / 100f))
                        },
                    )

                    SubtitleSectionTitle("Diseño")
                    SubtitleNumberControl(
                        label = "Tamaño de fuente",
                        value = style.fontSize,
                        range = SubtitleAppearanceRanges.fontSizeSlider,
                        typedRange = SubtitleAppearanceRanges.fontSizeTyped,
                        unit = "px",
                        onValueChange = { onAppearanceChange(style.copy(fontSize = it)) },
                    )
                    SubtitleNumberControl(
                        label = "Posición desde abajo",
                        value = style.bottomOffset,
                        range = SubtitleAppearanceRanges.positionSlider,
                        typedRange = SubtitleAppearanceRanges.positionTyped,
                        unit = "%",
                        onValueChange = { onAppearanceChange(style.copy(bottomOffset = it)) },
                    )
                    SubtitleSwitchRow(
                        label = "Usar ancho máximo personalizado",
                        checked = style.useCustomMaxWidth,
                        onCheckedChange = {
                            onAppearanceChange(style.copy(useCustomMaxWidth = it))
                        },
                    )
                    SubtitleNumberControl(
                        label = "Ancho máximo",
                        value = style.maxWidth,
                        range = SubtitleAppearanceRanges.widthSlider,
                        typedRange = SubtitleAppearanceRanges.widthTyped,
                        unit = "%",
                        enabled = style.useCustomMaxWidth,
                        onValueChange = { onAppearanceChange(style.copy(maxWidth = it)) },
                    )
                    SubtitleNumberControl(
                        label = "Relleno horizontal",
                        value = style.paddingX,
                        range = SubtitleAppearanceRanges.paddingXSlider,
                        typedRange = SubtitleAppearanceRanges.paddingXTyped,
                        unit = "px",
                        onValueChange = { onAppearanceChange(style.copy(paddingX = it)) },
                    )
                    SubtitleNumberControl(
                        label = "Relleno vertical",
                        value = style.paddingY,
                        range = SubtitleAppearanceRanges.paddingYSlider,
                        typedRange = SubtitleAppearanceRanges.paddingYTyped,
                        unit = "px",
                        onValueChange = { onAppearanceChange(style.copy(paddingY = it)) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Avanzado", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Peso, interlineado, espaciado, radio y sombra",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { advancedOpen = !advancedOpen },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(if (advancedOpen) "Ocultar" else "Mostrar")
                        }
                    }
                    if (advancedOpen) {
                        SubtitleNumberControl(
                            label = "Peso de fuente",
                            value = style.fontWeight.toFloat(),
                            range = SubtitleAppearanceRanges.weightSlider,
                            typedRange = SubtitleAppearanceRanges.weightTyped,
                            unit = "",
                            onValueChange = {
                                onAppearanceChange(style.copy(fontWeight = it.roundToInt()))
                            },
                        )
                        SubtitleNumberControl(
                            label = "Altura de línea",
                            value = style.lineHeight,
                            range = SubtitleAppearanceRanges.lineHeightSlider,
                            typedRange = SubtitleAppearanceRanges.lineHeightTyped,
                            unit = "×",
                            onValueChange = { onAppearanceChange(style.copy(lineHeight = it)) },
                        )
                        SubtitleNumberControl(
                            label = "Espaciado de letras",
                            value = style.letterSpacing,
                            range = SubtitleAppearanceRanges.letterSpacingSlider,
                            typedRange = SubtitleAppearanceRanges.letterSpacingTyped,
                            unit = "px",
                            onValueChange = { onAppearanceChange(style.copy(letterSpacing = it)) },
                        )
                        SubtitleNumberControl(
                            label = "Radio de las esquinas",
                            value = style.borderRadius,
                            range = SubtitleAppearanceRanges.radiusSlider,
                            typedRange = SubtitleAppearanceRanges.radiusTyped,
                            unit = "px",
                            onValueChange = { onAppearanceChange(style.copy(borderRadius = it)) },
                        )
                        SubtitleSwitchRow(
                            label = "Sombra del texto",
                            checked = style.textShadow,
                            onCheckedChange = { onAppearanceChange(style.copy(textShadow = it)) },
                        )
                    }

                    SubtitleSectionTitle("Sincronización")
                    SubtitleNumberControl(
                        label = "Desfase de subtítulos",
                        value = normalizedSync.offsetMs / 1_000f,
                        range = SubtitleAppearanceRanges.syncOffsetSlider,
                        typedRange = SubtitleAppearanceRanges.syncOffsetTyped,
                        unit = "s",
                        onValueChange = {
                            onSyncChange(
                                normalizedSync.copy(offsetMs = (it * 1_000f).roundToInt().toLong()),
                            )
                        },
                    )
                    SubtitleSwitchRow(
                        label = "Recordar desfase",
                        checked = normalizedSync.rememberOffset,
                        onCheckedChange = {
                            onSyncChange(normalizedSync.copy(rememberOffset = it))
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                onSyncChange(normalizedSync.copy(offsetMs = 0L))
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Restablecer (0.0)")
                        }
                        TextButton(
                            onClick = {
                                onSyncChange(
                                    normalizedSync.copy(
                                        offsetMs = (normalizedSync.offsetMs - 500L)
                                            .coerceIn(SubtitleSyncSettings.MIN_OFFSET_MS, SubtitleSyncSettings.MAX_OFFSET_MS),
                                    ),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Mover -0.5 s")
                        }
                        TextButton(
                            onClick = {
                                onSyncChange(
                                    normalizedSync.copy(
                                        offsetMs = (normalizedSync.offsetMs - 100L)
                                            .coerceIn(SubtitleSyncSettings.MIN_OFFSET_MS, SubtitleSyncSettings.MAX_OFFSET_MS),
                                    ),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Mover -0.1 s")
                        }
                        TextButton(
                            onClick = {
                                onSyncChange(
                                    normalizedSync.copy(
                                        offsetMs = (normalizedSync.offsetMs + 100L)
                                            .coerceIn(SubtitleSyncSettings.MIN_OFFSET_MS, SubtitleSyncSettings.MAX_OFFSET_MS),
                                    ),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Mover +0.1 s")
                        }
                        TextButton(
                            onClick = {
                                onSyncChange(
                                    normalizedSync.copy(
                                        offsetMs = (normalizedSync.offsetMs + 500L)
                                            .coerceIn(SubtitleSyncSettings.MIN_OFFSET_MS, SubtitleSyncSettings.MAX_OFFSET_MS),
                                    ),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Mover +0.5 s")
                        }
                        TextButton(
                            onClick = onAlignPreviousCue,
                            enabled = canAlignToCue,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Anterior aquí")
                        }
                        TextButton(
                            onClick = onAlignNextCue,
                            enabled = canAlignToCue,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Siguiente aquí")
                        }
                        TextButton(
                            onClick = onExportSubtitle,
                            enabled = canExportSubtitle && !isExportingSubtitle,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(if (isExportingSubtitle) "Exportando…" else "Descargar ajustados")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onResetAppearance()
                            advancedOpen = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Restaurar estilo predeterminado")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Listo")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun SubtitleSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Switch
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun SubtitleColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    fun commit() {
        val normalized = normalizeSubtitleHexColor(draft, value)
        draft = normalized
        onValueChange(normalized)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).onFocusChanged {
                if (!it.isFocused) commit()
            },
            label = { Text(label) },
            supportingText = { Text("Hexadecimal #RRGGBB") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(subtitleComposeColor(value, Color.White))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics { contentDescription = "$label swatch" },
        )
    }
}

@Composable
private fun SubtitleNumberControl(
    label: String,
    value: Float,
    range: SubtitleNumericRange,
    typedRange: SubtitleNumericRange,
    unit: String,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    var draft by remember(value) { mutableStateOf(formatSubtitleNumber(value)) }
    fun commit() {
        val typed = draft.trim().replace(',', '.').toFloatOrNull()
        val normalized = typed
            ?.takeIf(Float::isFinite)
            ?.coerceIn(typedRange.min, typedRange.max)
            ?: value.coerceIn(typedRange.min, typedRange.max)
        draft = formatSubtitleNumber(normalized)
        onValueChange(normalized)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                formatSubtitleNumber(value) + unit.let { if (it.isBlank()) "" else " $it" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.min, range.max),
            onValueChange = onValueChange,
            valueRange = range.min..range.max,
            steps = ((range.max - range.min) / range.step).roundToInt() - 1,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = label },
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .onFocusChanged { if (!it.isFocused) commit() },
            label = { Text("Valor de $label") },
            suffix = { if (unit.isNotBlank()) Text(unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
        )
    }
}

private fun formatSubtitleNumber(value: Float): String {
    val rounded = value.roundToInt().toFloat()
    if (kotlin.math.abs(value - rounded) < 0.0001f) return rounded.toInt().toString()
    return String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')
}

private fun subtitleComposeColor(value: String, fallback: Color): Color {
    val normalized = normalizeSubtitleHexColor(value, fallback.toHexRgb()).removePrefix("#")
    val red = normalized.substring(0, 2).toIntOrNull(16) ?: fallback.redByte()
    val green = normalized.substring(2, 4).toIntOrNull(16) ?: fallback.greenByte()
    val blue = normalized.substring(4, 6).toIntOrNull(16) ?: fallback.blueByte()
    return Color(red / 255f, green / 255f, blue / 255f, 1f)
}

private fun Color.toHexRgb(): String = "#%02X%02X%02X".format(
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
)

private fun Color.redByte(): Int = (red * 255f).roundToInt().coerceIn(0, 255)

private fun Color.greenByte(): Int = (green * 255f).roundToInt().coerceIn(0, 255)

private fun Color.blueByte(): Int = (blue * 255f).roundToInt().coerceIn(0, 255)
