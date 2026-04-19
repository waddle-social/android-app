package social.waddle.android.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ActiveConversation {
    data class Room(
        val roomJid: String,
    ) : ActiveConversation

    data class Direct(
        val peerJid: String,
    ) : ActiveConversation
}

/**
 * Bridges UI state and notifications: the UI publishes foreground state and
 * the currently viewed conversation so the notification poster can suppress
 * alerts while the user is already in the app.
 */
@Singleton
class ActiveConversationTracker
    @Inject
    constructor() {
        private val mutableActive = MutableStateFlow<ActiveConversation?>(null)
        val active = mutableActive.asStateFlow()
        private val mutableAppForeground = MutableStateFlow(false)
        val appForeground = mutableAppForeground.asStateFlow()

        fun setActive(conversation: ActiveConversation?) {
            mutableActive.value = conversation
        }

        fun setAppForeground(foreground: Boolean) {
            mutableAppForeground.value = foreground
        }

        fun isActive(conversation: ActiveConversation): Boolean = mutableActive.value == conversation

        fun isAppForeground(): Boolean = mutableAppForeground.value
    }
