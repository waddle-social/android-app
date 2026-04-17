package social.waddle.android.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Compose wrapper around WebRTC's [SurfaceViewRenderer]. Handles `init` /
 * `release` on the view's lifecycle and swaps the video-track sink whenever
 * [track] changes. Keep the [eglBaseContext] stable for the lifetime of the
 * renderer — it must come from a single [org.webrtc.EglBase] (the one owned
 * by [social.waddle.android.call.CallManager]).
 *
 * @param track the video track to render (null blanks the surface)
 * @param mirror mirror horizontally — used for front-facing local preview
 * @param zOrderOnTop keep this surface above others — used for the local PIP
 *                    so it paints above the full-bleed remote feed
 */
@Composable
fun WebRtcRenderer(
    track: VideoTrack?,
    eglBaseContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    zOrderOnTop: Boolean = false,
) {
    val holder = remember { RendererHolder() }

    DisposableEffect(holder) {
        onDispose {
            val r = holder.renderer ?: return@onDispose
            holder.attached?.let { runCatching { it.removeSink(r) } }
            holder.attached = null
            runCatching { r.release() }
            holder.renderer = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (zOrderOnTop) setZOrderMediaOverlay(true)
                holder.renderer = this
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            if (holder.attached !== track) {
                holder.attached?.let { runCatching { it.removeSink(renderer) } }
                track?.addSink(renderer)
                holder.attached = track
            }
        },
    )
}

private class RendererHolder {
    var renderer: SurfaceViewRenderer? = null
    var attached: VideoTrack? = null
}
