package dev.neura.syncplay.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.syncplaySecrets by preferencesDataStore(
    name = "syncplay_secrets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Stores the server password encrypted with an app-private Android Keystore key. */
internal class SecurePasswordStore(
    context: Context,
    private val cipher: PasswordCipher = AndroidKeystorePasswordCipher(),
) {
    private val dataStore = context.applicationContext.syncplaySecrets

    val credential: Flow<StoredPassword?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val serverAddress = preferences[SERVER_ADDRESS].orEmpty()
            val encrypted = preferences[ENCRYPTED_PASSWORD].orEmpty()
            if (serverAddress.isEmpty() || encrypted.isEmpty()) {
                null
            } else {
                val plaintext = withContext(Dispatchers.IO) {
                    try {
                        cipher.decrypt(encrypted)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        ""
                    }
                }
                plaintext.takeIf { it.isNotEmpty() }?.let { StoredPassword(serverAddress, it) }
            }
        }


    suspend fun save(serverAddress: String, password: String) {
        if (password.isEmpty()) {
            dataStore.edit {
                it.remove(SERVER_ADDRESS)
                it.remove(ENCRYPTED_PASSWORD)
            }
            return
        }

        val encrypted = withContext(Dispatchers.IO) {
            cipher.encrypt(password)
        }
        dataStore.edit {
            it[SERVER_ADDRESS] = serverAddress
            it[ENCRYPTED_PASSWORD] = encrypted
        }
    }

    private companion object {
        val SERVER_ADDRESS = stringPreferencesKey("server_address")
        val ENCRYPTED_PASSWORD = stringPreferencesKey("server_password_aes_gcm")
    }
}

internal data class StoredPassword(
    val serverAddress: String,
    val password: String,
)

internal interface PasswordCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(payload: String): String
}

/** AES-GCM cipher whose non-exportable key lives in AndroidKeyStore. */
internal class AndroidKeystorePasswordCipher : PasswordCipher {
    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return EncryptedPasswordPayload.encode(cipher.iv, ciphertext)
    }

    override fun decrypt(payload: String): String {
        if (payload.isEmpty()) return ""
        val decoded = requireNotNull(EncryptedPasswordPayload.decode(payload)) {
            "Formato de contraseña cifrada inválido"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, decoded.iv),
        )
        cipher.updateAAD(ASSOCIATED_DATA)
        return String(cipher.doFinal(decoded.ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            requireNotNull(existing) { "La clave AndroidKeyStore no está disponible" }
            return@synchronized existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.neura.syncplay.server-password.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val ASSOCIATED_DATA = "syncplay-droid/server-password/v1".toByteArray(StandardCharsets.UTF_8)
        val KEY_LOCK = Any()
    }
}

internal data class EncryptedPasswordPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    companion object {
        private const val VERSION = "v1"
        private const val IV_BYTES = 12
        private const val MIN_CIPHERTEXT_BYTES = 16

        fun encode(iv: ByteArray, ciphertext: ByteArray): String {
            require(iv.size == IV_BYTES) { "IV AES-GCM inválido" }
            require(ciphertext.size >= MIN_CIPHERTEXT_BYTES) { "Ciphertext AES-GCM inválido" }
            return "$VERSION:${iv.toHex()}:${ciphertext.toHex()}"
        }

        fun decode(value: String): EncryptedPasswordPayload? {
            val parts = value.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != VERSION) return null
            val iv = parts[1].hexToBytesOrNull() ?: return null
            val ciphertext = parts[2].hexToBytesOrNull() ?: return null
            if (iv.size != IV_BYTES || ciphertext.size < MIN_CIPHERTEXT_BYTES) return null
            return EncryptedPasswordPayload(iv, ciphertext)
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()

private fun ByteArray.toHex(): String = CharArray(size * 2).also { output ->
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = HEX[value ushr 4]
        output[index * 2 + 1] = HEX[value and 0x0f]
    }
}.concatToString()

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2).also { output ->
        for (index in output.indices) {
            val high = Character.digit(this[index * 2], 16)
            val low = Character.digit(this[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            output[index] = ((high shl 4) or low).toByte()
        }
    }
}
