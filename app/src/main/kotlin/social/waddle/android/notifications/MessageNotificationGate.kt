package social.waddle.android.notifications

import social.waddle.android.xmpp.XmppConnectionState
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides whether an incoming user-message should become a system notification.
 *
 * The important edge case is reconnect: servers can redeliver missed stanzas,
 * MUC backlog, or stream-resumption overlap immediately after the socket comes
 * back. We treat every connect/reconnect boundary as a replay boundary and
 * keep a short settle window for stanzas that arrive without a reliable delay
 * stamp.
 */
internal class MessageNotificationGate(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val replayCutoffMillis = AtomicLong(0L)
    private val quietUntilMillis = AtomicLong(0L)

    fun resetForStart() {
        markConnectionBoundary()
    }

    fun onConnectionState(state: XmppConnectionState) {
        when (state) {
            XmppConnectionState.Connecting,
            is XmppConnectionState.Reconnecting,
            is XmppConnectionState.Connected,
            -> markConnectionBoundary()

            XmppConnectionState.Disconnected,
            is XmppConnectionState.Failed,
            -> Unit
        }
    }

    fun shouldSuppress(
        createdAt: String,
        appForeground: Boolean,
    ): Boolean {
        if (appForeground) return true

        val now = nowMillis()
        if (now < quietUntilMillis.get()) return true

        val createdAtMillis = timestampMillis(createdAt) ?: return false
        if (createdAtMillis <= replayCutoffMillis.get() + SERVER_TIMESTAMP_SKEW_MILLIS) {
            return true
        }
        return now - createdAtMillis > FRESH_WINDOW_MILLIS
    }

    private fun markConnectionBoundary() {
        val now = nowMillis()
        replayCutoffMillis.set(now)
        quietUntilMillis.set(now + CONNECTION_SETTLE_MILLIS)
    }

    companion object {
        private const val CONNECTION_SETTLE_MILLIS = 20_000L
        private const val SERVER_TIMESTAMP_SKEW_MILLIS = 1_000L
        private const val FRESH_WINDOW_MILLIS = 2 * 60 * 1000L

        fun timestampMillis(createdAt: String): Long? =
            runCatching { Instant.parse(createdAt).toEpochMilli() }
                .getOrNull()
    }
}
