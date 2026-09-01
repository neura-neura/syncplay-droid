package dev.neura.syncplay.ui

import androidx.media3.common.PlaybackException

/** User-facing recovery text; provider URLs and low-level codec details stay out of the UI. */
internal fun friendlyPlaybackError(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "Este dispositivo no pudo decodificar el video. Prueba otra versión o pista del archivo."

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Se interrumpió la carga del video. Comprueba la conexión y vuelve a abrirlo."

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    -> "Ya no se puede leer el archivo. Vuelve a seleccionarlo en tu explorador."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "El archivo o manifiesto está dañado. Prueba otra copia."

    else -> "No se pudo reproducir este contenido. Vuelve a abrirlo o prueba otro archivo."
}
