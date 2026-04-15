package social.waddle.android.xmpp

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.jxmpp.jid.impl.JidCreate

class XmppMessageMapperTest {
    private val mapper =
        XmppMessageMapper(
            accountUserId = null,
            accountUsername = null,
            mucNickname = null,
        )

    @Test
    fun ignoresSubjectOnlyRoomMessages() {
        val message =
            StanzaBuilder
                .buildMessage("subject-1")
                .from(JidCreate.entityBareFrom(ROOM_JID))
                .ofType(Message.Type.groupchat)
                .setSubject("Welcome to $ROOM_JID!")
                .build()

        assertNull(mapper.liveRoomMessage(message, ROOM_JID))
    }

    @Test
    fun keepsBodyWhenRoomMessageAlsoHasSubject() {
        val message =
            StanzaBuilder
                .buildMessage("message-1")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .setSubject("Room topic")
                .setBody("real message")
                .build()

        assertEquals("real message", mapper.liveRoomMessage(message, ROOM_JID)?.body)
    }

    private companion object {
        const val ROOM_JID = "room@muc.waddle.social"
    }
}
