package social.waddle.android.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import social.waddle.android.data.model.ChatMessageDraft
import social.waddle.android.data.model.CreateChannelRequest
import social.waddle.android.data.model.CreateWaddleRequest
import social.waddle.android.data.model.UpdateWaddleRequest
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.xmpp.XmppConnectionState
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WaddleServerE2eTest {
    @Test
    fun healthWellKnownSessionAndProductionClientConnectThroughContainer() =
        runBlocking {
            val http = HttpClient(CIO)
            try {
                val health = http.get("${server.httpBaseUrl}/health")
                assertEquals(HttpStatusCode.OK, health.status)
                assertTrue(health.bodyAsText().contains("healthy"))

                val hostMeta = http.get("${server.httpBaseUrl}/.well-known/host-meta.json")
                assertEquals(HttpStatusCode.OK, hostMeta.status)
                assertTrue(hostMeta.bodyAsText().contains("xmpp-websocket"))
            } finally {
                http.close()
            }

            server.connectedGateway().use { handle ->
                val session = handle.gateway.fetchSession(WaddleEnvironment.Dev, handle.session.sessionId)
                assertEquals(E2E_BARE_JID, session.jid)
                assertEquals(E2E_USERNAME, session.username)
                assertEquals(
                    XmppConnectionState.Connected(E2E_BARE_JID),
                    handle.gateway.connectionState.value,
                )
            }
        }

    @Test
    fun restFacadeRoundTripsWaddlesChannelsMembersAndUsers() =
        runBlocking {
            server.connectedGateway().use { handle ->
                val gateway = handle.gateway
                val session = handle.session
                val token = shortId()

                val created =
                    gateway.createWaddle(
                        environment = WaddleEnvironment.Dev,
                        sessionId = session.sessionId,
                        input =
                            CreateWaddleRequest(
                                name = "Android E2E $token",
                                description = "Created from Android e2e",
                                isPublic = true,
                            ),
                    )
                assertEquals("Android E2E $token", created.name)
                assertEquals(E2E_BARE_JID, created.ownerUserId)

                val listed =
                    eventually("created waddle in public list") {
                        gateway
                            .publicWaddles(WaddleEnvironment.Dev, session.sessionId, token)
                            .firstOrNull { it.id == created.id }
                    }
                assertEquals(created.id, listed.id)

                val updated =
                    gateway.updateWaddle(
                        environment = WaddleEnvironment.Dev,
                        sessionId = session.sessionId,
                        waddleId = created.id,
                        input = UpdateWaddleRequest(name = "Android E2E updated $token"),
                    )
                assertEquals("Android E2E updated $token", updated.name)

                val channel =
                    gateway.createChannel(
                        environment = WaddleEnvironment.Dev,
                        sessionId = session.sessionId,
                        waddleId = created.id,
                        input = CreateChannelRequest(name = "android-$token", description = "Round trip"),
                    )
                assertEquals("android-$token", channel.name)

                val members = gateway.listMembers(WaddleEnvironment.Dev, session.sessionId, created.id)
                assertTrue(members.any { it.userId == E2E_BARE_JID && it.role == "owner" })

                val searchUsername = "search-$token"
                val searchJid = server.seedUser(searchUsername)
                val users = gateway.searchUsers(WaddleEnvironment.Dev, session.sessionId, searchUsername)
                assertTrue(users.any { it.username == searchUsername && it.jid == searchJid })

                gateway.deleteChannel(WaddleEnvironment.Dev, session.sessionId, created.id, channel.id)
                gateway.deleteWaddle(WaddleEnvironment.Dev, session.sessionId, created.id)
            }
        }

    @Test
    fun productionXmppClientGroupMessagesRoundTripThroughMamAndSearch() =
        runBlocking {
            server.connectedGateway().use { handle ->
                val gateway = handle.gateway
                val roomJid = roomJid("android-room")
                val token = "needle-${shortId()}"
                val body = "Android group message $token"
                val stanzaId = "android-e2e-${UUID.randomUUID()}"

                val sent =
                    gateway.sendGroupMessage(
                        ChatMessageDraft(
                            channelId = "android-room",
                            roomJid = roomJid,
                            body = body,
                            stanzaId = stanzaId,
                        ),
                    )
                assertEquals(stanzaId, sent)

                val archived =
                    eventually("room message in MAM") {
                        gateway
                            .loadMessageHistory(roomJid)
                            .messages
                            .firstOrNull { it.originStanzaId == stanzaId || it.body == body }
                    }
                assertEquals(body, archived.body)

                val searchHit =
                    eventually("room full-text search hit") {
                        gateway
                            .searchMessageHistory(roomJid, token)
                            .firstOrNull { it.originStanzaId == stanzaId || it.body == body }
                    }
                assertEquals(stanzaId, searchHit.originStanzaId)
            }
        }

    @Test
    fun productionXmppClientRichGroupPayloadsAndUpdatesRoundTripThroughMapper() =
        runBlocking {
            server.connectedGateway().use { handle ->
                val gateway = handle.gateway
                val roomJid = roomJid("android-rich")
                val token = shortId()
                val baseBody = "Base message $token"
                val baseId =
                    gateway.sendGroupMessage(
                        ChatMessageDraft(
                            channelId = "android-rich",
                            roomJid = roomJid,
                            body = baseBody,
                            stanzaId = "android-e2e-${UUID.randomUUID()}",
                        ),
                    )
                eventually("base room message in MAM") {
                    gateway.loadMessageHistory(roomJid).messages.firstOrNull { it.originStanzaId == baseId }
                }

                val richBody = "Rich payload @admin $token"
                val richId =
                    gateway.sendGroupMessage(
                        ChatMessageDraft(
                            channelId = "android-rich",
                            roomJid = roomJid,
                            body = richBody,
                            stanzaId = "android-e2e-${UUID.randomUUID()}",
                            replyToMessageId = baseId,
                            replyToSenderJid = "$roomJid/$E2E_USERNAME",
                            replyToFallbackBody = baseBody,
                            mentions = listOf(E2E_BARE_JID),
                            stickerPack = "pack-$token",
                            threadId = "thread-$token",
                            forumTopicTitle = "Topic $token",
                        ),
                    )

                val rich =
                    eventually("rich room message in MAM") {
                        gateway.loadMessageHistory(roomJid).messages.firstOrNull { it.originStanzaId == richId }
                    }
                assertEquals(richBody, rich.body)
                assertEquals(baseId, rich.replyToMessageId)
                assertTrue(rich.mentions.contains(E2E_BARE_JID))
                assertTrue(rich.isSticker)
                assertEquals("thread-$token", rich.threadId)
                assertEquals("Topic $token", rich.forumTopicTitle)

                gateway.correctMessage(roomJid, baseId, "Edited message $token")
                assertEquals(
                    baseId,
                    eventually("room correction marker") {
                        gateway.loadMessageHistory(roomJid).messages.firstOrNull { it.replacesId == baseId }
                    }.replacesId,
                )

                gateway.react(roomJid, baseId, listOf("ok"))
                gateway.markDisplayed(roomJid, baseId)
                gateway.retractMessage(roomJid, baseId)
            }
        }

    @Test
    fun productionXmppClientGroupRepliesRoundTripAcrossTwoAndroidUsers() =
        runBlocking {
            val aliceUsername = "alice${shortId()}"
            val bobUsername = "bob${shortId()}"
            server.connectedGateway(aliceUsername).use { alice ->
                server.connectedGateway(bobUsername).use { bob ->
                    val roomJid = roomJid("android-reply")
                    val token = shortId()
                    val rootBody = "Alice root $token"
                    val rootId =
                        alice.gateway.sendGroupMessage(
                            ChatMessageDraft(
                                channelId = "android-reply",
                                roomJid = roomJid,
                                body = rootBody,
                                stanzaId = "android-e2e-${UUID.randomUUID()}",
                            ),
                        )

                    val rootForBob =
                        eventually("root room message visible to second user") {
                            bob.gateway.loadMessageHistory(roomJid).messages.firstOrNull {
                                it.originStanzaId == rootId || it.body == rootBody
                            }
                        }
                    assertEquals(rootBody, rootForBob.body)
                    assertEquals("$roomJid/$aliceUsername", rootForBob.senderId)

                    val replyBody = "Bob replies $token"
                    val replyId =
                        bob.gateway.sendGroupMessage(
                            ChatMessageDraft(
                                channelId = "android-reply",
                                roomJid = roomJid,
                                body = replyBody,
                                stanzaId = "android-e2e-${UUID.randomUUID()}",
                                replyToMessageId = rootId,
                                replyToSenderJid = requireNotNull(rootForBob.senderId),
                                replyToFallbackBody = rootForBob.body,
                            ),
                        )

                    val replyForAlice =
                        eventually("reply room message visible to original author") {
                            alice.gateway.loadMessageHistory(roomJid).messages.firstOrNull {
                                it.originStanzaId == replyId || it.body == replyBody
                            }
                        }
                    assertEquals(replyBody, replyForAlice.body)
                    assertEquals(rootId, replyForAlice.replyToMessageId)
                    assertEquals("$roomJid/$bobUsername", replyForAlice.senderId)
                    assertEquals(bobUsername, replyForAlice.senderName)
                }
            }
        }

    @Test
    fun productionXmppClientDirectMessagesAndRepliesRoundTripAcrossTwoAndroidUsers() =
        runBlocking {
            val receiverUsername = "receiver${shortId()}"
            val senderUsername = "sender${shortId()}"
            server.connectedGateway(receiverUsername).use { receiver ->
                server.connectedGateway(senderUsername).use { sender ->
                    val token = "dm-${shortId()}"
                    val body = "Android direct message $token"
                    val stanzaId = "android-e2e-${UUID.randomUUID()}"

                    val sent = sender.gateway.sendDirectMessage(receiver.session.jid, body, stanzaId)
                    assertEquals(stanzaId, sent)

                    val archived =
                        eventually("direct message in MAM") {
                            receiver
                                .gateway
                                .loadDirectMessageHistory(receiver.session.jid, sender.session.jid)
                                .messages
                                .firstOrNull { it.originStanzaId == stanzaId || it.body == body }
                        }
                    assertEquals(body, archived.body)
                    assertEquals(sender.session.jid, archived.fromJid)

                    val searchHit =
                        eventually("direct full-text search hit") {
                            receiver
                                .gateway
                                .searchDirectMessageHistory(receiver.session.jid, sender.session.jid, token)
                                .firstOrNull { it.originStanzaId == stanzaId || it.body == body }
                        }
                    assertEquals(stanzaId, searchHit.originStanzaId)

                    val replyBody = "Direct reply $token"
                    val replyId =
                        receiver.gateway.sendDirectMessage(
                            peerJid = sender.session.jid,
                            body = replyBody,
                            stanzaId = "android-e2e-${UUID.randomUUID()}",
                            replyToMessageId = stanzaId,
                            replyToSenderJid = sender.session.jid,
                            replyToFallbackBody = archived.body,
                        )

                    val reply =
                        eventually("direct reply in MAM") {
                            sender
                                .gateway
                                .loadDirectMessageHistory(sender.session.jid, receiver.session.jid)
                                .messages
                                .firstOrNull { it.originStanzaId == replyId || it.body == replyBody }
                        }
                    assertEquals(replyBody, reply.body)
                    assertEquals(stanzaId, reply.replyToMessageId)
                    assertEquals(receiver.session.jid, reply.fromJid)

                    sender.gateway.reactDirect(receiver.session.jid, stanzaId, listOf("ok"))
                    sender.gateway.retractDirectMessage(receiver.session.jid, stanzaId)
                }
            }
        }

    @Test
    fun productionFacadeUploadSlotUploadsAndDownloadsThroughAndroidApiWrapper() =
        runBlocking {
            server.connectedGateway().use { handle ->
                val http = HttpClient(CIO)
                try {
                    val bytes = "Android upload payload ${shortId()}".encodeToByteArray()
                    val slot =
                        requireNotNull(
                            handle.gateway.uploadSlot(
                                filename = "android-e2e.txt",
                                contentType = "text/plain",
                                sizeBytes = bytes.size.toLong(),
                            ),
                        )

                    handle.gateway.uploadToSlot(
                        putUrl = server.toHostHttpUrl(slot.putUrl),
                        bytes = bytes,
                        contentType = "text/plain",
                        uploadHeaders = slot.headers,
                    )

                    val downloaded = http.get(server.toHostHttpUrl(slot.getUrl)).bodyAsBytes()
                    assertArrayEquals(bytes, downloaded)
                } finally {
                    http.close()
                }
            }
        }

    private val server: WaddleServerContainer
        get() = WaddleE2eServer.get()

    private suspend fun <T> eventually(
        label: String,
        block: suspend () -> T?,
    ): T {
        repeat(EVENTUALLY_ATTEMPTS) {
            block()?.let { return it }
            Thread.sleep(EVENTUALLY_SLEEP_MILLIS)
        }
        error("Timed out waiting for $label")
    }

    private companion object {
        private const val EVENTUALLY_ATTEMPTS = 20
        private const val EVENTUALLY_SLEEP_MILLIS = 250L

        @JvmStatic
        @AfterClass
        fun stopServer() {
            WaddleE2eServer.stop()
        }
    }
}
