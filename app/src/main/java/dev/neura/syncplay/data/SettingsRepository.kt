package dev.neura.syncplay.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.neura.syncplay.protocol.ConnectionConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.syncplaySettings by preferencesDataStore(
    name = "syncplay_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.syncplaySettings
    private val passwordStore = SecurePasswordStore(context)
    private val saveMutex = Mutex()

    /**
     * Emits an empty configuration when either DataStore cannot be read. DataStore normally
     * recovers corruption through [ReplaceFileCorruptionHandler]; the flow guard also covers
     * transient I/O failures so an initial collector never remains stuck in Loading.
     */
    val connectionConfig: Flow<ConnectionConfig> = safePreferences(dataStore)
        .combine(passwordStore.credential) { preferences, credential ->
            val serverAddress = preferences[SERVER] ?: "syncplay.pl:8999"
            ConnectionConfig(
                serverAddress = serverAddress,
                username = preferences[USERNAME].orEmpty(),
                room = preferences[ROOM].orEmpty(),
                password = credential
                    ?.takeIf { it.serverAddress == serverAddress }
                    ?.password
                    .orEmpty(),
                preferTls = preferences[PREFER_TLS] ?: true,
            )
        }
        .catch { error ->
            if (error is IOException) emit(ConnectionConfig()) else throw error
        }

    suspend fun saveConnection(config: ConnectionConfig) = saveMutex.withLock {
        // Write the endpoint-bound secret first. If the process stops between stores,
        // the endpoint mismatch fails closed instead of sending a password to another server.
        passwordStore.save(config.serverAddress, config.password)
        dataStore.edit { preferences ->
            preferences[SERVER] = config.serverAddress
            preferences[USERNAME] = config.username
            preferences[ROOM] = config.room
            preferences[PREFER_TLS] = config.preferTls
        }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val USERNAME = stringPreferencesKey("username")
        val ROOM = stringPreferencesKey("room")
        val PREFER_TLS = booleanPreferencesKey("prefer_tls")
    }
}

private fun safePreferences(dataStore: androidx.datastore.core.DataStore<Preferences>): Flow<Preferences> =
    dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
