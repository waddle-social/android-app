package social.waddle.android.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.waddle.android.xmpp.XmppConnectionState
import java.time.Instant

class MessageNotificationGateTest {
    @Test
    fun suppressesMessagesWhileAppIsForeground() {
        var now = 100_000L
        val gate = MessageNotificationGate { now }

        assertTrue(gate.shouldSuppress(instant(now), appForeground = true))
    }

    @Test
    fun suppressesMessagesDuringReconnectSettleWindow() {
        var now = 100_000L
        val gate = MessageNotificationGate { now }

        gate.onConnectionState(XmppConnectionState.Reconnecting(attempt = 1, nextDelaySeconds = 1, cause = "network"))
        now += 5_000L

        assertTrue(gate.shouldSuppress(instant(now), appForeground = false))
    }

    @Test
    fun suppressesMessagesStampedBeforeConnectedBoundary() {
        var now = 100_000L
        val gate = MessageNotificationGate { now }

        gate.onConnectionState(XmppConnectionState.Connected("me@example.test/mobile"))
        now += 25_000L

        assertTrue(gate.shouldSuppress(instant(99_500L), appForeground = false))
    }

    @Test
    fun allowsFreshBackgroundMessagesAfterSettleWindow() {
        var now = 100_000L
        val gate = MessageNotificationGate { now }

        gate.onConnectionState(XmppConnectionState.Connected("me@example.test/mobile"))
        now += 25_000L

        assertFalse(gate.shouldSuppress(instant(now), appForeground = false))
    }

    private fun instant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
}
