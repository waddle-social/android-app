package social.waddle.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import social.waddle.android.data.model.WaddleEnvironment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnvironmentRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val selectedEnvironment: Flow<WaddleEnvironment> =
            dataStore.data.map { preferences ->
                WaddleEnvironment.fromId(preferences[environmentKey])
            }

        suspend fun setEnvironment(environment: WaddleEnvironment) {
            dataStore.edit { preferences ->
                preferences[environmentKey] = environment.id
            }
        }

        private companion object {
            val environmentKey = stringPreferencesKey("server_environment")
        }
    }
