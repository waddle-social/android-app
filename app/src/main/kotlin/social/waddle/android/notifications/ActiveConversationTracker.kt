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
 * Bridges UI state and notifications: the UI publishes the conversation the
 * user is currently viewing so the notification poster can suppress alerts for
 * that room or peer (Slack-style foreground suppression).
 */
@Singleton
class ActiveConversationTracker
    @Inject
    constructor() {
        private val mutableActive = MutableStateFlow<ActiveConversation?>(null)
        val active = mutableActive.asStateFlow()

        fun setActive(conversation: ActiveConversation?) {
            mutableActive.value = conversation
        }

        fun isActive(conversation: ActiveConversation): Boolean = mutableActive.value == conversation
    }
