package social.waddle.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists whether the user has opted into a biometric app-lock. Unlock grace
 * is intentionally process-local (not persisted) — a phone reboot or process
 * death should always re-prompt for biometrics.
 */
@Singleton
class AppLockRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val lockEnabled: Flow<Boolean> = dataStore.data.map { it[LOCK_ENABLED_KEY] ?: false }

        suspend fun setLockEnabled(enabled: Boolean) {
            dataStore.edit { prefs -> prefs[LOCK_ENABLED_KEY] = enabled }
        }

        private companion object {
            val LOCK_ENABLED_KEY = booleanPreferencesKey("app_lock_enabled")
        }
    }
