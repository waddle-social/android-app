package social.waddle.android.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.waddle.android.data.AppLockRepository
import javax.inject.Inject

/**
 * Owns the "is the app currently gated behind biometrics" state. Designed to be
 * driven by the Activity's onStart / onStop so a task-switch or device-lock
 * re-locks the app.
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val appLockRepository: AppLockRepository,
    ) : ViewModel() {
        private val mutableUnlockedAt = MutableStateFlow<Long?>(null)
        val unlockedAt: StateFlow<Long?> = mutableUnlockedAt.asStateFlow()

        val lockEnabled: StateFlow<Boolean> =
            appLockRepository.lockEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /**
         * True when the app content must be covered by the biometric prompt.
         * It resolves to false whenever lock is off OR the user has unlocked
         * within the current foreground session.
         */
        val locked: StateFlow<Boolean> =
            combine(lockEnabled, mutableUnlockedAt) { enabled, unlockedMillis ->
                enabled && unlockedMillis == null
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun setLockEnabled(enabled: Boolean) {
            viewModelScope.launch {
                appLockRepository.setLockEnabled(enabled)
                if (enabled) {
                    mutableUnlockedAt.value = null
                } else {
                    mutableUnlockedAt.value = System.currentTimeMillis()
                }
            }
        }

        fun onUnlocked() {
            mutableUnlockedAt.value = System.currentTimeMillis()
        }

        /**
         * Re-arm the lock when the Activity leaves the foreground — i.e. the
         * user backgrounded the app, switched to another task, or the screen
         * turned off. Next resume will re-prompt (assuming lock still enabled).
         */
        fun onMovedToBackground() {
            if (lockEnabled.value) {
                mutableUnlockedAt.value = null
            }
        }
    }
