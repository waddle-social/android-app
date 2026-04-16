package social.waddle.android.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot channel between [android.content.Intent] extras and the UI layer.
 * MainActivity parses the intent on create / new-intent and writes a
 * [DeepLink] here; WaddleApp consumes it via `LaunchedEffect` and then clears.
 */
@Singleton
class PendingDeepLink
    @Inject
    constructor() {
        private val mutableLink = MutableStateFlow<DeepLink?>(null)
        val link = mutableLink.asStateFlow()

        fun push(link: DeepLink) {
            mutableLink.value = link
        }

        fun consume(): DeepLink? {
            val value = mutableLink.value ?: return null
            mutableLink.value = null
            return value
        }
    }

sealed interface DeepLink {
    data class OpenDirectMessage(
        val peerJid: String,
    ) : DeepLink

    data class OpenRoom(
        val roomJid: String,
    ) : DeepLink
}
