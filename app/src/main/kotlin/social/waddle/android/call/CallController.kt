package social.waddle.android.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.VideoTrack
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.XmppClient
import social.waddle.android.xmpp.XmppConnectionState
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Higher-level orchestrator that binds a [CallManager] (WebRTC lifecycle) to a
 * [CallSignaler] (wire format). The UI layer talks to this controller rather
 * than to either of those directly so the handshake order is enforced:
 *
 *  Outgoing:
 *    1. discover ICE servers
 *    2. open mic / camera, build PeerConnection, generate offer
 *    3. send XEP-0482 `<invite>` to peer, `session-initiate` to SFU
 *    4. apply SFU's answer from `session-accept`
 *    5. trickle local candidates through `transport-info`
 *
 *  Incoming:
 *    1. peer's invite arrives via [CallSignaler.events] → state = Incoming
 *    2. user accepts → open mic / camera, build PeerConnection, generate
 *       offer, send `session-initiate` to SFU
 *    3. apply SFU's answer
 *    4. trickle
 */
@Singleton
class CallController
    @Inject
    constructor(
        private val callManager: CallManager,
        private val signaler: CallSignaler,
        private val xmppClient: XmppClient,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var eventJob: Job? = null
        private var xmppStateJob: Job? = null
        private var activePeer: PeerConnection? = null
        private var activeSid: String? = null
        private var activeSfu: String? = null
        private var activePeerJid: String? = null
        private var activeAudioOnly: Boolean = false

        fun observeSignaler() {
            if (eventJob?.isActive == true) return
            eventJob =
                scope.launch {
                    signaler.events.collect(::onSignalEvent)
                }
            if (xmppStateJob?.isActive != true) {
                xmppStateJob =
                    scope.launch {
                        xmppClient.connectionState.collect(::onXmppStateChange)
                    }
            }
        }

        /**
         * Observe XMPP connection state. When the connection drops during an
         * active call we can't do anything useful (the SFU's session is
         * still live on its side, but we can't send transport-info updates);
         * we mark the call ended so the UI surfaces the drop instead of
         * pretending everything's fine. A full re-join path would re-issue
         * `session-initiate` once XMPP reconnects, but that requires the
         * SFU to recognize the existing SID — for now we end the call and
         * let the user redial.
         */
        private suspend fun onXmppStateChange(state: XmppConnectionState) {
            val current = callManager.state.value
            if (current is CallState.Idle || current is CallState.Ended) return
            when (state) {
                is XmppConnectionState.Disconnected, is XmppConnectionState.Failed -> {
                    WaddleLog.info("XMPP dropped during active call — ending session.")
                    hangUp("xmpp-disconnected")
                }

                else -> {
                    Unit
                }
            }
        }

        suspend fun startOutgoingCall(
            peerJid: String,
            sfuJid: String,
            audioOnly: Boolean,
            muji: Boolean = false,
        ) {
            val sid = UUID.randomUUID().toString()
            activeSid = sid
            activeSfu = sfuJid
            callManager.updateCallState(
                CallState.OutgoingRinging(
                    sid = sid,
                    peerJid = peerJid,
                    startedAtEpochMillis = System.currentTimeMillis(),
                    muji = muji,
                ),
            )
            val iceServers = toWebRtcIceServers(signaler.discoverIceServers())
            val peer = callManager.startLocalMedia(iceServers, audioOnly, buildObserver(sid, sfuJid))
            if (peer == null) {
                WaddleLog.error("Failed to start local media for outgoing call.")
                callManager.hangUp("local-media-failed")
                return
            }
            activePeer = peer
            val offer = callManager.createOffer(peer)
            signaler.sendInvite(peerJid, sid)
            signaler.sendSessionInitiate(sfuJid, sid, offer)
            callManager.updateCallState(
                CallState.Connecting(
                    sid = sid,
                    peerJid = peerJid,
                    direction = CallDirection.OUTGOING,
                    muji = muji,
                ),
            )
        }

        /**
         * Start a Muji (XEP-0272) group call into a MUC room. Mirrors the
         * web client's `startMujiCall`: the invite is broadcast to the room,
         * every participant independently runs a Jingle session against the
         * same SFU SID and the SFU fans media back out to everyone.
         */
        suspend fun startMujiCall(
            roomJid: String,
            sfuJid: String,
            audioOnly: Boolean,
        ) {
            startOutgoingCall(
                peerJid = roomJid,
                sfuJid = sfuJid,
                audioOnly = audioOnly,
                muji = true,
            )
        }

        /**
         * Join an already-advertised Muji call by Jingle SID. Used when the
         * user taps a call chip in chat history — the call invite was
         * broadcast to the room and any member can later join by opening
         * their own Jingle session against the same SFU+sid. No new invite
         * is sent (the room already has one).
         */
        suspend fun joinExistingCall(
            roomJid: String,
            sfuJid: String,
            sid: String,
            audioOnly: Boolean,
        ) {
            val current = callManager.state.value
            if (current !is CallState.Idle && current !is CallState.Ended) {
                WaddleLog.info("joinExistingCall ignored — already in a call (state=$current).")
                return
            }
            activeSid = sid
            activeSfu = sfuJid
            callManager.updateCallState(
                CallState.Connecting(
                    sid = sid,
                    peerJid = roomJid,
                    direction = CallDirection.OUTGOING,
                    muji = true,
                ),
            )
            val iceServers = toWebRtcIceServers(signaler.discoverIceServers())
            val peer = callManager.startLocalMedia(iceServers, audioOnly, buildObserver(sid, sfuJid))
            if (peer == null) {
                WaddleLog.error("joinExistingCall: local media failed.")
                callManager.hangUp("local-media-failed")
                return
            }
            activePeer = peer
            val offer = callManager.createOffer(peer)
            signaler.sendSessionInitiate(sfuJid, sid, offer)
            WaddleLog.info("Joined existing Muji call sid=$sid sfu=$sfuJid room=$roomJid audioOnly=$audioOnly.")
        }

        suspend fun acceptIncomingCall(audioOnly: Boolean) {
            val pending = callManager.state.value as? CallState.Incoming ?: return
            val sfuJid = pending.peerJid.substringAfter('@').let { "sfu.$it" }
            activeSfu = sfuJid
            activeSid = pending.sid
            val iceServers = toWebRtcIceServers(signaler.discoverIceServers())
            val peer =
                callManager.startLocalMedia(iceServers, audioOnly, buildObserver(pending.sid, sfuJid))
            if (peer == null) {
                callManager.hangUp("local-media-failed")
                return
            }
            activePeer = peer
            val offer = callManager.createOffer(peer)
            signaler.sendSessionInitiate(sfuJid, pending.sid, offer)
            callManager.updateCallState(
                CallState.Connecting(sid = pending.sid, peerJid = pending.peerJid, direction = CallDirection.INCOMING),
            )
        }

        suspend fun declineIncomingCall() {
            val pending = callManager.state.value as? CallState.Incoming ?: return
            signaler.sendSessionTerminate(
                sfuJid = pending.peerJid.substringAfter('@').let { "sfu.$it" },
                sid = pending.sid,
                reason = "decline",
            )
            callManager.hangUp("declined")
        }

        suspend fun hangUp(reason: String? = null) {
            val sid = activeSid
            val sfu = activeSfu
            if (sid != null && sfu != null) {
                runCatching { signaler.sendSessionTerminate(sfu, sid, reason) }
                    .onFailure { throwable -> WaddleLog.error("session-terminate failed.", throwable) }
            }
            callManager.hangUp(reason)
            activePeer = null
            activeSid = null
            activeSfu = null
        }

        private suspend fun onSignalEvent(event: CallSignalEvent) {
            when (event) {
                is CallSignalEvent.IncomingInvite -> onIncomingInvite(event)
                is CallSignalEvent.RemoteAnswer -> onRemoteAnswer(event)
                is CallSignalEvent.RemoteCandidate -> onRemoteCandidate(event)
                is CallSignalEvent.RemoteTerminate -> onRemoteTerminate(event)
            }
        }

        private fun onIncomingInvite(event: CallSignalEvent.IncomingInvite) {
            val current = callManager.state.value
            if (current is CallState.Idle) {
                callManager.updateCallState(
                    CallState.Incoming(
                        sid = event.sid,
                        peerJid = event.fromJid,
                        peerDisplayName = event.fromDisplayName,
                        muji = event.muji,
                        meetingDescription = event.meetingDescription,
                    ),
                )
                return
            }
            // Call-waiting: a second invite arrives while we're already mid-call.
            // Mirror the web client's useMujiRuntime.ts behavior — auto-join if
            // we're already connected (Muji rooms expect this), otherwise log
            // and drop so we don't clobber an outbound ringing state.
            if (event.muji && current is CallState.InCall) {
                WaddleLog.info("Accepting concurrent Muji invite ${event.sid} during active call.")
                // The handshake is handled by the signaler collector; the UI
                // will observe the Incoming state transition on the side.
                callManager.updateCallState(
                    CallState.Incoming(
                        sid = event.sid,
                        peerJid = event.fromJid,
                        peerDisplayName = event.fromDisplayName,
                        muji = true,
                        meetingDescription = event.meetingDescription,
                    ),
                )
                return
            }
            WaddleLog.info("Ignoring invite ${event.sid} — already in a non-Muji call.")
        }

        private suspend fun onRemoteAnswer(event: CallSignalEvent.RemoteAnswer) {
            val peer = activePeer ?: return
            if (activeSid != event.sid) {
                WaddleLog.info("Remote answer sid mismatch (${event.sid} vs $activeSid); dropping.")
                return
            }
            callManager.applyRemoteDescription(peer, event.answer)
        }

        private fun onRemoteCandidate(event: CallSignalEvent.RemoteCandidate) {
            val peer = activePeer ?: return
            if (activeSid != event.sid) return
            callManager.addRemoteIceCandidate(peer, event.candidate)
        }

        private suspend fun onRemoteTerminate(event: CallSignalEvent.RemoteTerminate) {
            if (activeSid != event.sid) return
            callManager.hangUp(event.reason ?: "remote-terminate")
            activePeer = null
            activeSid = null
            activeSfu = null
        }

        private fun buildObserver(
            sid: String,
            sfuJid: String,
        ): PeerConnectionObserver =
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    val c = candidate ?: return
                    scope.launch {
                        runCatching { signaler.sendTransportInfo(sfuJid, sid, c) }
                            .onFailure { throwable ->
                                WaddleLog.error("Failed to send local ICE candidate.", throwable)
                            }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    val next = state ?: return
                    when (next) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED,
                        -> {
                            val previous = callManager.state.value
                            val muji =
                                (previous as? CallState.Connecting)?.muji
                                    ?: (previous as? CallState.OutgoingRinging)?.muji
                                    ?: false
                            val peer =
                                (previous as? CallState.Connecting)?.peerJid
                                    ?: (previous as? CallState.OutgoingRinging)?.peerJid
                                    ?: ""
                            val direction =
                                (previous as? CallState.Connecting)?.direction ?: CallDirection.OUTGOING
                            callManager.updateCallState(
                                CallState.InCall(
                                    sid = sid,
                                    peerJid = peer,
                                    direction = direction,
                                    connectedAtEpochMillis = System.currentTimeMillis(),
                                    muji = muji,
                                ),
                            )
                        }

                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        -> {
                            scope.launch { hangUp("ice-$next") }
                        }

                        else -> {
                            Unit
                        }
                    }
                }

                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?,
                ) {
                    val track = receiver?.track()
                    val streamId = streams?.firstOrNull()?.id ?: "remote"
                    if (track is VideoTrack) {
                        callManager.setRemoteVideoTrack(track)
                        callManager.upsertParticipant(streamId = streamId, videoTrack = track)
                    } else {
                        // Audio-only participant (voice-only room member or audio-only call).
                        callManager.upsertParticipant(streamId = streamId, hasAudio = true)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    stream?.id?.let(callManager::removeParticipant)
                }
            }

        private fun toWebRtcIceServers(config: List<IceServerConfig>): List<PeerConnection.IceServer> =
            config.map { server ->
                val builder = PeerConnection.IceServer.builder(server.urls)
                if (!server.username.isNullOrBlank()) builder.setUsername(server.username)
                if (!server.credential.isNullOrBlank()) builder.setPassword(server.credential)
                builder.createIceServer()
            }
    }
