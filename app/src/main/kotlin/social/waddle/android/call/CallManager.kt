package social.waddle.android.call

import android.content.Context
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import social.waddle.android.util.WaddleLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central WebRTC lifecycle owner. Handles PeerConnectionFactory initialization,
 * camera/mic capture, [PeerConnection] creation, and SDP / ICE-candidate
 * plumbing — everything that is common to outgoing and incoming calls.
 *
 * The SDP ↔ Jingle translation (XEP-0166 content elements, XEP-0167 RTP
 * descriptions, XEP-0176 ICE-UDP transport, XEP-0320 DTLS fingerprints) is the
 * responsibility of [JingleSdpBridge]. This class does not touch XMPP directly
 * — all wire-format traffic is bounced through a [CallSignaler].
 *
 * Thread model: all WebRTC calls are serialised on [lifecycleMutex] and run on
 * [Dispatchers.IO]. Public control API is safe to call from any dispatcher.
 */
@Singleton
class CallManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val audioRouter: CallAudioRouter,
    ) : CallMediaControls {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lifecycleMutex = Mutex()

        private val eglBase: EglBase by lazy { EglBase.create() }
        private val factory: PeerConnectionFactory by lazy { buildFactory() }

        private var audioSource: AudioSource? = null
        private var audioTrack: AudioTrack? = null
        private var videoSource: VideoSource? = null
        private var videoTrack: VideoTrack? = null
        private var capturer: VideoCapturer? = null
        private var surfaceHelper: SurfaceTextureHelper? = null
        private var peerConnection: PeerConnection? = null

        private val mutableState = MutableStateFlow<CallState>(CallState.Idle)
        val state: StateFlow<CallState> = mutableState.asStateFlow()

        private val mutableRemoteTrack = MutableStateFlow<VideoTrack?>(null)
        val remoteVideoTrack: StateFlow<VideoTrack?> = mutableRemoteTrack.asStateFlow()

        private val mutableParticipants = MutableStateFlow<List<CallParticipant>>(emptyList())
        val participants: StateFlow<List<CallParticipant>> = mutableParticipants.asStateFlow()

        private val mutableLocalTrack = MutableStateFlow<VideoTrack?>(null)
        val localVideoTrack: StateFlow<VideoTrack?> = mutableLocalTrack.asStateFlow()

        private val mutableMicEnabled = MutableStateFlow(true)
        override val micEnabled: StateFlow<Boolean> = mutableMicEnabled.asStateFlow()

        private val mutableCameraEnabled = MutableStateFlow(true)
        override val cameraEnabled: StateFlow<Boolean> = mutableCameraEnabled.asStateFlow()

        private val mutableSpeakerphoneEnabled = MutableStateFlow(false)
        override val speakerphoneEnabled: StateFlow<Boolean> = mutableSpeakerphoneEnabled.asStateFlow()

        private val mutableFrontCamera = MutableStateFlow(true)
        override val frontCamera: StateFlow<Boolean> = mutableFrontCamera.asStateFlow()

        val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

        /**
         * Attach a Compose-created [SurfaceViewRenderer] to the renderer slot
         * matching [target]. The caller must invoke [SurfaceViewRenderer.init]
         * with [eglBaseContext] before passing it in, and [SurfaceViewRenderer.release]
         * when the composable leaves the tree.
         */
        fun attachRenderer(
            target: RendererSlot,
            renderer: SurfaceViewRenderer,
        ) {
            val track =
                when (target) {
                    RendererSlot.LOCAL -> mutableLocalTrack.value
                    RendererSlot.REMOTE -> mutableRemoteTrack.value
                }
            track?.addSink(renderer)
        }

        fun detachRenderer(
            target: RendererSlot,
            renderer: SurfaceViewRenderer,
        ) {
            val track =
                when (target) {
                    RendererSlot.LOCAL -> mutableLocalTrack.value
                    RendererSlot.REMOTE -> mutableRemoteTrack.value
                }
            runCatching { track?.removeSink(renderer) }
        }

        /**
         * Create the local audio + video tracks, build the PeerConnection,
         * and produce an SDP offer ready to hand to the signaler.
         *
         * Returns null if permissions haven't been granted — callers should
         * check [CAMERA]/[RECORD_AUDIO] before invoking.
         */
        suspend fun startLocalMedia(
            iceServers: List<PeerConnection.IceServer>,
            audioOnly: Boolean,
            observer: PeerConnectionObserver,
        ): PeerConnection? =
            lifecycleMutex.withLock {
                val existing = peerConnection
                if (existing != null) {
                    WaddleLog.info("startLocalMedia called while a call is already active; reusing.")
                    return@withLock existing
                }
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                val config =
                    PeerConnection.RTCConfiguration(iceServers).apply {
                        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                        continualGatheringPolicy =
                            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                        keyType = PeerConnection.KeyType.ECDSA
                    }
                val peer =
                    factory.createPeerConnection(config, observer)
                        ?: run {
                            WaddleLog.error("createPeerConnection returned null; aborting call setup.")
                            return@withLock null
                        }

                val audioConstraints = MediaConstraints()
                val aSource = factory.createAudioSource(audioConstraints)
                val aTrack = factory.createAudioTrack("waddle-audio-0", aSource)
                aTrack.setEnabled(true)
                peer.addTrack(aTrack, listOf(LOCAL_STREAM_ID))

                if (!audioOnly) {
                    attachVideo(peer)
                }

                audioSource = aSource
                audioTrack = aTrack
                peerConnection = peer
                audioRouter.onCallStart(audioOnly = audioOnly)
                peer
            }

        private fun attachVideo(peer: PeerConnection) {
            val enumerator = Camera2Enumerator(context)
            val deviceName =
                enumerator.deviceNames
                    .firstOrNull { enumerator.isFrontFacing(it) == mutableFrontCamera.value }
                    ?: enumerator.deviceNames.firstOrNull()
                    ?: run {
                        WaddleLog.info("No camera available; continuing audio-only.")
                        return
                    }
            val helper = SurfaceTextureHelper.create("Waddle-CaptureThread", eglBase.eglBaseContext)
            val cap = enumerator.createCapturer(deviceName, null) ?: return
            val vSource = factory.createVideoSource(cap.isScreencast)
            cap.initialize(helper, context, vSource.capturerObserver)
            cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            val vTrack = factory.createVideoTrack("waddle-video-0", vSource)
            vTrack.setEnabled(true)
            peer.addTrack(vTrack, listOf(LOCAL_STREAM_ID))
            surfaceHelper = helper
            capturer = cap
            videoSource = vSource
            videoTrack = vTrack
            mutableLocalTrack.value = vTrack
        }

        suspend fun createOffer(peer: PeerConnection): SessionDescription =
            suspendCancellableSdp { observer -> peer.createOffer(observer, offerAnswerConstraints()) }
                .also { sdp -> suspendSdpSet { o -> peer.setLocalDescription(o, sdp) } }

        suspend fun createAnswer(peer: PeerConnection): SessionDescription =
            suspendCancellableSdp { observer -> peer.createAnswer(observer, offerAnswerConstraints()) }
                .also { sdp -> suspendSdpSet { o -> peer.setLocalDescription(o, sdp) } }

        suspend fun applyRemoteDescription(
            peer: PeerConnection,
            sdp: SessionDescription,
        ) {
            WaddleLog.info("Remote answer SDP full:\n${sdp.description}")
            suspendSdpSet { o -> peer.setRemoteDescription(o, sdp) }
        }

        fun addRemoteIceCandidate(
            peer: PeerConnection,
            candidate: IceCandidate,
        ) {
            peer.addIceCandidate(candidate)
        }

        suspend fun hangUp(reason: String? = null) {
            lifecycleMutex.withLock {
                val prevSid =
                    (mutableState.value as? CallState.InCall)?.sid
                        ?: (mutableState.value as? CallState.Connecting)?.sid
                        ?: (mutableState.value as? CallState.OutgoingRinging)?.sid
                        ?: (mutableState.value as? CallState.Incoming)?.sid
                        ?: ""
                teardownLocked()
                mutableState.value = CallState.Ended(sid = prevSid, reason = reason)
            }
        }

        fun updateCallState(next: CallState) {
            mutableState.value = next
        }

        fun setRemoteVideoTrack(track: VideoTrack?) {
            mutableRemoteTrack.value = track
        }

        /**
         * Add or update a participant from an `onAddTrack` callback. [streamId]
         * is the first stream in `MediaStream[]` (WebRTC groups tracks that
         * share an msid). Re-calling with the same streamId updates the video
         * track rather than adding a duplicate row.
         */
        fun upsertParticipant(
            streamId: String,
            videoTrack: VideoTrack? = null,
            hasAudio: Boolean = true,
        ) {
            val current = mutableParticipants.value
            val existing = current.firstOrNull { it.streamId == streamId }
            val next =
                existing?.copy(
                    videoTrack = videoTrack ?: existing.videoTrack,
                    hasAudio = hasAudio,
                ) ?: CallParticipant(
                    streamId = streamId,
                    videoTrack = videoTrack,
                    hasAudio = hasAudio,
                )
            mutableParticipants.value =
                if (existing == null) current + next else current.map { if (it.streamId == streamId) next else it }
        }

        /** Remove a participant when their stream ends. */
        fun removeParticipant(streamId: String) {
            mutableParticipants.value = mutableParticipants.value.filterNot { it.streamId == streamId }
        }

        /**
         * Apply a `<participant-map>` payload from the SFU: map stream ids to
         * JIDs so the UI can label tiles.
         */
        fun applyParticipantMap(mapping: Map<String, String>) {
            if (mapping.isEmpty()) return
            mutableParticipants.value =
                mutableParticipants.value.map { p ->
                    val jid = mapping[p.streamId]
                    if (jid != null && jid != p.peerJid) {
                        p.copy(peerJid = jid, displayName = jid.substringBefore('@'))
                    } else {
                        p
                    }
                }
        }

        override fun setMicEnabled(enabled: Boolean) {
            mutableMicEnabled.value = enabled
            audioTrack?.setEnabled(enabled)
        }

        override fun setCameraEnabled(enabled: Boolean) {
            mutableCameraEnabled.value = enabled
            videoTrack?.setEnabled(enabled)
        }

        override fun toggleCameraFacing() {
            val cameraCapturer = capturer as? CameraVideoCapturer ?: return
            cameraCapturer.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                        mutableFrontCamera.value = isFrontFacing
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {
                        WaddleLog.error("Camera switch failed: $errorDescription")
                    }
                },
            )
        }

        override fun setSpeakerphoneEnabled(enabled: Boolean) {
            mutableSpeakerphoneEnabled.value = enabled
            audioRouter.setSpeakerphone(enabled)
        }

        private fun teardownLocked() {
            runCatching { capturer?.stopCapture() }
            capturer?.dispose()
            capturer = null
            surfaceHelper?.dispose()
            surfaceHelper = null
            videoSource?.dispose()
            videoSource = null
            videoTrack = null
            audioSource?.dispose()
            audioSource = null
            audioTrack = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
            mutableLocalTrack.value = null
            mutableRemoteTrack.value = null
            mutableParticipants.value = emptyList()
            mutableMicEnabled.value = true
            mutableCameraEnabled.value = true
            mutableSpeakerphoneEnabled.value = false
            runCatching { audioRouter.onCallEnd() }
        }

        private fun buildFactory(): PeerConnectionFactory {
            val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            return PeerConnectionFactory
                .builder()
                .setVideoEncoderFactory(encoder)
                .setVideoDecoderFactory(decoder)
                .createPeerConnectionFactory()
        }

        private fun offerAnswerConstraints(): MediaConstraints =
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

        enum class RendererSlot { LOCAL, REMOTE }

        private companion object {
            const val LOCAL_STREAM_ID = "waddle-stream-0"
            const val CAPTURE_WIDTH = 1280
            const val CAPTURE_HEIGHT = 720
            const val CAPTURE_FPS = 30
        }
    }

private suspend fun suspendCancellableSdp(request: (SdpObserver) -> Unit): SessionDescription =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val observer =
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    cont.resume(sdp) { _, _, _ -> }
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String?) {
                    cont.resumeWith(Result.failure(IllegalStateException("createSdp failed: $error")))
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWith(Result.failure(IllegalStateException("setSdp failed: $error")))
                }
            }
        request(observer)
    }

private suspend fun suspendSdpSet(request: (SdpObserver) -> Unit) {
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val observer =
            object : SdpObserver {
                override fun onCreateSuccess(newSdp: SessionDescription) = Unit

                override fun onSetSuccess() {
                    cont.resume(Unit) { _, _, _ -> }
                }

                override fun onCreateFailure(error: String?) {
                    cont.resumeWith(Result.failure(IllegalStateException("setSdp createFailure: $error")))
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWith(Result.failure(IllegalStateException("setSdp failed: $error")))
                }
            }
        request(observer)
    }
}

/**
 * Minimal convenience base for [PeerConnection.Observer] — routes into lambdas
 * so a single call-controller object can own all the callbacks without the
 * typical 12-method override boilerplate per call.
 */
interface PeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

    override fun onAddStream(stream: MediaStream?) = Unit

    override fun onRemoveStream(stream: MediaStream?) = Unit

    override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

    override fun onRenegotiationNeeded() = Unit

    override fun onAddTrack(
        receiver: RtpReceiver?,
        streams: Array<out MediaStream>?,
    ) = Unit

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
}
