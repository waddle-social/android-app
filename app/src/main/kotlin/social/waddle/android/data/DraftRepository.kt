package social.waddle.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists composer drafts keyed by channelId (rooms) or peerJid (DMs) so the
 * text the user was typing is restored after navigation / process death.
 */
@Singleton
class DraftRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        fun observeChannelDraft(channelId: String): Flow<String> = dataStore.data.map { it[channelKey(channelId)].orEmpty() }

        fun observeDirectDraft(peerJid: String): Flow<String> = dataStore.data.map { it[directKey(peerJid)].orEmpty() }

        suspend fun setChannelDraft(
            channelId: String,
            text: String,
        ) {
            dataStore.edit { prefs ->
                if (text.isBlank()) {
                    prefs.remove(channelKey(channelId))
                } else {
                    prefs[channelKey(channelId)] = text
                }
            }
        }

        suspend fun setDirectDraft(
            peerJid: String,
            text: String,
        ) {
            dataStore.edit { prefs ->
                if (text.isBlank()) {
                    prefs.remove(directKey(peerJid))
                } else {
                    prefs[directKey(peerJid)] = text
                }
            }
        }

        private fun channelKey(channelId: String) = stringPreferencesKey("draft.channel.$channelId")

        private fun directKey(peerJid: String) = stringPreferencesKey("draft.direct.$peerJid")
    }
