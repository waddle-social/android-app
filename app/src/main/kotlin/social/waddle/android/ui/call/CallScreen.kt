package social.waddle.android.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import social.waddle.android.call.CallState

/**
 * Full-bleed in-call screen. Layout:
 * - remote video fills the screen
 * - local preview sits in the bottom-right corner as a rounded PIP
 * - state banner at the top (connecting / ringing / call duration)
 * - bottom control row with mute, camera, flip, speaker, hang-up
 *
 * The screen is state-driven: when [CallViewModel.state] resolves to
 * [CallState.Idle] or [CallState.Ended] the caller (WaddleApp) is
 * responsible for routing away from this screen — this composable assumes an
 * active call.
 */
@Composable
fun CallScreen(
    onCallFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val micEnabled by viewModel.micEnabled.collectAsStateWithLifecycle()
    val cameraEnabled by viewModel.cameraEnabled.collectAsStateWithLifecycle()
    val speakerphoneEnabled by viewModel.speakerphoneEnabled.collectAsStateWithLifecycle()
    val frontCamera by viewModel.frontCamera.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is CallState.Ended || state is CallState.Idle) {
            onCallFinished()
        }
    }

    if (collapsed) {
        CollapsedCallBar(
            state = state,
            onExpand = { collapsed = false },
            onHangUp = { viewModel.hangUp("user-hangup") },
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    ExpandedCallScreen(
        state = state,
        localTrack = localTrack,
        remoteTrack = remoteTrack,
        participants = participants,
        micEnabled = micEnabled,
        cameraEnabled = cameraEnabled,
        speakerphoneEnabled = speakerphoneEnabled,
        frontCamera = frontCamera,
        eglBaseContext = viewModel.eglBaseContext,
        onCollapse = { collapsed = true },
        onToggleMic = { viewModel.setMicEnabled(!micEnabled) },
        onToggleCamera = { viewModel.setCameraEnabled(!cameraEnabled) },
        onFlipCamera = viewModel::toggleCameraFacing,
        onToggleSpeaker = { viewModel.setSpeakerphoneEnabled(!speakerphoneEnabled) },
        onHangUp = { viewModel.hangUp("user-hangup") },
        modifier = modifier,
    )
}

/**
 * Full-bleed variant of [CallScreen]. Split out so the entry composable
 * stays within the detekt `LongMethod` threshold and each concern has its
 * own focused lambda.
 */
@Suppress("LongParameterList")
@Composable
private fun ExpandedCallScreen(
    state: CallState,
    localTrack: org.webrtc.VideoTrack?,
    remoteTrack: org.webrtc.VideoTrack?,
    participants: List<social.waddle.android.call.CallParticipant>,
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    speakerphoneEnabled: Boolean,
    frontCamera: Boolean,
    eglBaseContext: org.webrtc.EglBase.Context,
    onCollapse: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        RemoteArea(
            participants = participants,
            remoteTrack = remoteTrack,
            state = state,
            eglBaseContext = eglBaseContext,
        )
        LocalPictureInPicture(
            localTrack = localTrack,
            cameraEnabled = cameraEnabled,
            frontCamera = frontCamera,
            eglBaseContext = eglBaseContext,
        )
        CallStatusOverlay(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
        )
        androidx.compose.material3.IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = "Minimize call",
                tint = Color.White,
            )
        }
        CallControlsRow(
            micEnabled = micEnabled,
            cameraEnabled = cameraEnabled,
            speakerphoneEnabled = speakerphoneEnabled,
            onToggleMic = onToggleMic,
            onToggleCamera = onToggleCamera,
            onFlipCamera = onFlipCamera,
            onToggleSpeaker = onToggleSpeaker,
            onHangUp = onHangUp,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .fillMaxWidth(),
        )
    }
}

@Composable
private fun BoxScope.RemoteArea(
    participants: List<social.waddle.android.call.CallParticipant>,
    remoteTrack: org.webrtc.VideoTrack?,
    state: CallState,
    eglBaseContext: org.webrtc.EglBase.Context,
) {
    if (participants.isNotEmpty()) {
        ParticipantGrid(
            participants = participants,
            eglBaseContext = eglBaseContext,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // 1:1 calls may not issue a participant-map; fall back to the
        // single-remote renderer so those cases still display video.
        WebRtcRenderer(
            track = remoteTrack,
            eglBaseContext = eglBaseContext,
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (remoteTrack == null && participants.isEmpty()) {
        RemotePlaceholder(state = state, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun BoxScope.LocalPictureInPicture(
    localTrack: org.webrtc.VideoTrack?,
    cameraEnabled: Boolean,
    frontCamera: Boolean,
    eglBaseContext: org.webrtc.EglBase.Context,
) {
    if (localTrack == null || !cameraEnabled) return
    WebRtcRenderer(
        track = localTrack,
        eglBaseContext = eglBaseContext,
        mirror = frontCamera,
        zOrderOnTop = true,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(width = 120.dp, height = 180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.DarkGray),
    )
}

@Composable
private fun RemotePlaceholder(
    state: CallState,
    modifier: Modifier = Modifier,
) {
    val label =
        when (state) {
            is CallState.OutgoingRinging -> "Calling…"
            is CallState.Connecting -> "Connecting…"
            is CallState.InCall -> "Waiting for video"
            is CallState.Incoming -> "Incoming call"
            is CallState.Ended -> "Call ended"
            CallState.Idle -> ""
        }
    Text(
        text = label,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
private fun CallStatusOverlay(
    state: CallState,
    modifier: Modifier = Modifier,
) {
    val peerLabel = peerLabelFor(state)
    val statusLine = statusLineFor(state)

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (peerLabel.isNotEmpty()) {
            Text(
                text = peerLabel,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (statusLine.isNotEmpty()) {
            Text(
                text = statusLine,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
        }
    }
}

private fun peerLabelFor(state: CallState): String =
    when (state) {
        is CallState.OutgoingRinging -> state.peerJid.substringBefore('@')
        is CallState.Connecting -> state.peerJid.substringBefore('@')
        is CallState.InCall -> state.peerJid.substringBefore('@').ifEmpty { "in call" }
        is CallState.Incoming -> state.peerDisplayName ?: state.peerJid.substringBefore('@')
        else -> ""
    }

@Composable
private fun statusLineFor(state: CallState): String =
    when (state) {
        is CallState.OutgoingRinging -> "Ringing…"
        is CallState.Connecting -> "Connecting…"
        is CallState.InCall -> callDurationTimer(state.connectedAtEpochMillis)
        is CallState.Incoming -> "Wants to call you"
        is CallState.Ended -> "Call ended"
        CallState.Idle -> ""
    }

/**
 * Returns a MM:SS string that updates once per second while the call is
 * connected. Anchored to [connectedAtEpochMillis] rather than a relative
 * counter so the timer survives recomposition.
 */
@Composable
private fun callDurationTimer(connectedAtEpochMillis: Long): String {
    var now by remember(connectedAtEpochMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAtEpochMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - connectedAtEpochMillis) / 1_000).coerceAtLeast(0L)
    val mm = seconds / 60
    val ss = seconds % 60
    return "%02d:%02d".format(mm, ss)
}

@Composable
private fun CallControlsRow(
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    speakerphoneEnabled: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlToggle(
            checked = micEnabled,
            onCheckedChange = { onToggleMic() },
            onIcon = Icons.Filled.Mic,
            offIcon = Icons.Filled.MicOff,
            description = if (micEnabled) "Mute microphone" else "Unmute microphone",
        )
        ControlToggle(
            checked = cameraEnabled,
            onCheckedChange = { onToggleCamera() },
            onIcon = Icons.Filled.Videocam,
            offIcon = Icons.Filled.VideocamOff,
            description = if (cameraEnabled) "Turn camera off" else "Turn camera on",
        )
        ControlButton(
            icon = Icons.Filled.Cameraswitch,
            description = "Flip camera",
            onClick = onFlipCamera,
        )
        ControlToggle(
            checked = speakerphoneEnabled,
            onCheckedChange = { onToggleSpeaker() },
            onIcon = Icons.AutoMirrored.Filled.VolumeUp,
            offIcon = Icons.AutoMirrored.Filled.VolumeOff,
            description = if (speakerphoneEnabled) "Switch to earpiece" else "Switch to speaker",
        )
        HangUpButton(onHangUp = onHangUp)
    }
}

@Composable
private fun ControlToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onIcon: androidx.compose.ui.graphics.vector.ImageVector,
    offIcon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.size(56.dp),
        colors =
            IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White,
                checkedContainerColor = Color.White,
                checkedContentColor = Color.Black,
            ),
    ) {
        Icon(
            imageVector = if (checked) onIcon else offIcon,
            contentDescription = description,
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White,
            ),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

@Composable
private fun HangUpButton(onHangUp: () -> Unit) {
    FilledIconButton(
        onClick = onHangUp,
        modifier = Modifier.size(64.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
    ) {
        Icon(imageVector = Icons.Filled.CallEnd, contentDescription = "Hang up")
    }
}

/**
 * Minimized call pill — shows while the user has collapsed the full call
 * screen but remains connected. Tap to expand, tap the end icon to hang up.
 * Mirrors the web client's collapsed-overlay mode.
 */
@Composable
private fun CollapsedCallBar(
    state: CallState,
    onExpand: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onExpand),
            ) {
                Icon(imageVector = Icons.Filled.Call, contentDescription = null)
                val label =
                    when (state) {
                        is CallState.InCall -> {
                            "In call with ${state.peerJid.substringBefore('@').ifEmpty { "peer" }}"
                        }

                        is CallState.Connecting -> {
                            "Connecting…"
                        }

                        is CallState.OutgoingRinging -> {
                            "Ringing…"
                        }

                        is CallState.Incoming -> {
                            "Incoming call"
                        }

                        else -> {
                            "Call"
                        }
                    }
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
            FilledIconButton(
                onClick = onHangUp,
                modifier = Modifier.size(40.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Icon(imageVector = Icons.Filled.CallEnd, contentDescription = "Hang up")
            }
        }
    }
}

/**
 * Outgoing-only helper: small accept/decline row used on the incoming-call
 * banner, not on the in-call screen. Placed here so the icon + theme setup
 * stays with its peers.
 */
@Composable
internal fun AcceptCallButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        Icon(imageVector = Icons.Filled.Call, contentDescription = "Accept call")
    }
}
