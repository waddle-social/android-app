package social.waddle.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val MUTED_KEY = stringSetPreferencesKey("muted_conversations")

/**
 * Tracks which conversations the user has muted. Keys are `"room:<roomJid>"` or
 * `"dm:<peerJid>"` (mirror the notification poster's ConversationKey.storeKey).
 * Backed by DataStore so it survives process death and is inspectable offline.
 */
@Singleton
class MuteRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val muted: Flow<Set<String>> = dataStore.data.map { it[MUTED_KEY] ?: emptySet() }

        fun isMuted(key: String): Flow<Boolean> = muted.map { key in it }

        suspend fun setMuted(
            key: String,
            muted: Boolean,
        ) {
            dataStore.edit { prefs ->
                val current = prefs[MUTED_KEY] ?: emptySet()
                prefs[MUTED_KEY] =
                    if (muted) current + key else current - key
            }
        }

        suspend fun snapshot(): Set<String> = dataStore.data.first()[MUTED_KEY] ?: emptySet()

        companion object {
            fun roomKey(roomJid: String): String = "room:$roomJid"

            fun directKey(peerJid: String): String = "dm:$peerJid"
        }
    }
