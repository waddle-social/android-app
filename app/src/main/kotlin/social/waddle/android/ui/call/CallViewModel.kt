package social.waddle.android.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import social.waddle.android.call.CallController
import social.waddle.android.call.CallManager
import social.waddle.android.call.CallMediaControls
import social.waddle.android.call.CallState
import javax.inject.Inject

/**
 * Thin wrapper that exposes [CallManager]'s StateFlows to Compose and hands
 * user actions (accept / decline / hang-up / toggle mic etc.) off to
 * [CallController] / [CallManager]. Intentionally free of call *business*
 * logic — the controller owns the handshake order, the manager owns the
 * WebRTC lifecycle.
 */
@HiltViewModel
class CallViewModel
    @Inject
    constructor(
        val callManager: CallManager,
        private val callController: CallController,
    ) : ViewModel(),
        CallMediaControls by callManager {
        val state: StateFlow<CallState> = callManager.state

        val eglBaseContext get() = callManager.eglBaseContext

        val localVideoTrack = callManager.localVideoTrack
        val remoteVideoTrack = callManager.remoteVideoTrack
        val participants = callManager.participants

        /**
         * Idempotently wire up the signaler → state pipeline. Safe to call
         * multiple times — [CallController.observeSignaler] no-ops if already
         * observing.
         */
        fun observeSignaler() {
            callController.observeSignaler()
        }

        fun accept(audioOnly: Boolean) {
            viewModelScope.launch { callController.acceptIncomingCall(audioOnly) }
        }

        fun decline() {
            viewModelScope.launch { callController.declineIncomingCall() }
        }

        fun hangUp(reason: String? = null) {
            viewModelScope.launch { callController.hangUp(reason) }
        }

        fun startOutgoing(
            peerJid: String,
            sfuJid: String,
            audioOnly: Boolean,
        ) {
            viewModelScope.launch { callController.startOutgoingCall(peerJid, sfuJid, audioOnly) }
        }

        fun startMujiCall(
            roomJid: String,
            sfuJid: String,
            audioOnly: Boolean,
        ) {
            viewModelScope.launch { callController.startMujiCall(roomJid, sfuJid, audioOnly) }
        }

        fun joinExistingCall(
            roomJid: String,
            sfuJid: String,
            sid: String,
            audioOnly: Boolean,
        ) {
            viewModelScope.launch {
                callController.joinExistingCall(roomJid, sfuJid, sid, audioOnly)
            }
        }
    }
