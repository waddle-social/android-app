package social.waddle.android.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.webrtc.EglBase
import social.waddle.android.call.CallParticipant

/**
 * Adaptive grid of remote [CallParticipant] tiles. Mirrors the web client's
 * `CallOverlay.vue` layout:
 *  - 1 participant → single tile
 *  - 2 participants → side-by-side
 *  - 3 participants → three columns
 *  - 4+           → 2×N grid, cropping tiles to 16:9
 *
 * Audio-only participants render an avatar placeholder + mic icon instead
 * of a video renderer.
 */
@Composable
fun ParticipantGrid(
    participants: List<CallParticipant>,
    eglBaseContext: EglBase.Context,
    modifier: Modifier = Modifier,
) {
    if (participants.isEmpty()) return
    when (participants.size) {
        1 -> SingleTile(participants[0], eglBaseContext, modifier.fillMaxSize())
        2 -> RowOfTiles(participants, eglBaseContext, modifier.fillMaxSize())
        3 -> RowOfTiles(participants, eglBaseContext, modifier.fillMaxSize())
        else -> GridOfTiles(participants, eglBaseContext, modifier.fillMaxSize())
    }
}

@Composable
private fun SingleTile(
    participant: CallParticipant,
    eglBaseContext: EglBase.Context,
    modifier: Modifier,
) {
    ParticipantTile(participant = participant, eglBaseContext = eglBaseContext, modifier = modifier)
}

@Composable
private fun RowOfTiles(
    participants: List<CallParticipant>,
    eglBaseContext: EglBase.Context,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (p in participants) {
            ParticipantTile(
                participant = p,
                eglBaseContext = eglBaseContext,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun GridOfTiles(
    participants: List<CallParticipant>,
    eglBaseContext: EglBase.Context,
    modifier: Modifier,
) {
    val rows = (participants.size + 1) / 2
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (r in 0 until rows) {
            val rowParticipants = participants.drop(r * 2).take(2)
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (p in rowParticipants) {
                    ParticipantTile(
                        participant = p,
                        eglBaseContext = eglBaseContext,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (rowParticipants.size < 2) {
                    androidx.compose.foundation.layout
                        .Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    participant: CallParticipant,
    eglBaseContext: EglBase.Context,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1D21)),
    ) {
        val track = participant.videoTrack
        if (track != null) {
            WebRtcRenderer(
                track = track,
                eglBaseContext = eglBaseContext,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AudioOnlyPlaceholder(
                label = participant.bestLabel,
                hasAudio = participant.hasAudio,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Name chip in the bottom-left corner.
        Text(
            text = participant.bestLabel,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AudioOnlyPlaceholder(
    label: String,
    hasAudio: Boolean,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label.take(1).uppercase(),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                imageVector = if (hasAudio) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
