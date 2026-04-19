package social.waddle.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

/**
 * Per-screen controller for the shared image lightbox. Composables that
 * render images (message attachments, inline GIFs, avatars) read it off the
 * [LocalLightbox] composition local and call [open] instead of launching
 * a browser intent, so the image opens full-screen with pinch-zoom / pan
 * and can be dismissed without leaving the app.
 */
class LightboxController {
    var currentUrl: String? by mutableStateOf(null)
        private set

    fun open(url: String) {
        currentUrl = url
    }

    fun close() {
        currentUrl = null
    }
}

val LocalLightbox = staticCompositionLocalOf<LightboxController?> { null }

@Composable
fun rememberLightboxController(): LightboxController = remember { LightboxController() }

@Composable
fun LightboxOverlay(controller: LightboxController) {
    val url = controller.currentUrl ?: return
    Dialog(
        onDismissRequest = controller::close,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable(onClick = controller::close),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "Full-size image",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .pointerInput(url) {
                            detectTapGestures(
                                onDoubleTap = {
                                    // Double-tap toggles 1x ↔ 2.5x zoom.
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = LIGHTBOX_DOUBLE_TAP_SCALE
                                    }
                                },
                                onTap = { controller.close() },
                            )
                        }.pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, LIGHTBOX_MAX_SCALE)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
            )
        }
    }
}

private const val LIGHTBOX_DOUBLE_TAP_SCALE = 2.5f
private const val LIGHTBOX_MAX_SCALE = 5f
