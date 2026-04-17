package social.waddle.android.call

import kotlinx.coroutines.flow.StateFlow

/**
 * The in-call user-controllable toggles. Exposed to the UI as StateFlows so
 * Compose recomposes when the underlying track enabled state changes.
 */
interface CallMediaControls {
    val micEnabled: StateFlow<Boolean>
    val cameraEnabled: StateFlow<Boolean>
    val speakerphoneEnabled: StateFlow<Boolean>
    val frontCamera: StateFlow<Boolean>

    fun setMicEnabled(enabled: Boolean)

    fun setCameraEnabled(enabled: Boolean)

    fun toggleCameraFacing()

    fun setSpeakerphoneEnabled(enabled: Boolean)
}
