package com.uzaylikafasi.marasulasim.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

const val DEFAULT_SERVER = "https://mithrixa.com/ulasim-api"
const val REPO_URL = "https://github.com/uzaylikafasi/maras-ulasim"
const val API_REPO_URL = "https://github.com/uzaylikafasi/kahramankart-ulasim-api"
const val API_DOCS_URL = "$DEFAULT_SERVER/docs"
const val LEGACY_USB_SERVER = "http://127.0.0.1:8000"

/** One-time upgrade of the old keyless USB pilot. Custom connections stay intact. */
class PilotHttpsMigration : DataMigration<Preferences> {
    private val migrated = booleanPreferencesKey("https-pilot-migrated")
    private val server = stringPreferencesKey("server")
    private val apiKey = stringPreferencesKey("api_key")
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[migrated] != true
    override suspend fun migrate(currentData: Preferences): Preferences {
        val data = currentData.toMutablePreferences()
        val old = data[server]
        if ((old == null || old.trimEnd('/') == LEGACY_USB_SERVER) && data[apiKey].isNullOrBlank()) {
            data[server] = DEFAULT_SERVER
        } else if (old == null) {
            // An old key belongs to its original server, never send it to a new host.
            data[server] = LEGACY_USB_SERVER
        }
        data[migrated] = true
        return data
    }
    override suspend fun cleanUp() = Unit
}
