package dev.neura.syncplay.ui

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

/** User-facing recovery text; private paths and native error details stay out of the UI. */
internal fun friendlyPlaybackError(error: Throwable?): String = when (error) {
    is FileNotFoundException, is SecurityException ->
        "Ya no se puede leer el archivo. Vuelve a seleccionarlo en tu explorador."
    is SocketTimeoutException ->
        "La conexión tardó demasiado. Comprueba la red y vuelve a abrir el video."
    is IOException ->
        "Se interrumpió la lectura del video. Comprueba el archivo o la conexión y vuelve a intentarlo."
    else -> "MPV no pudo reproducir este contenido. Vuelve a abrirlo o prueba otro archivo."
}
