package social.waddle.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.waddle.android.util.WaddleLog
import javax.inject.Inject

/**
 * Receives notification-action broadcasts (accept / hang-up) fired by
 * [WaddleCallService]'s CallStyle notification. Runs on a short-lived
 * coroutine because the action handlers call into [CallController] which
 * needs a suspend context.
 */
@AndroidEntryPoint
class WaddleCallControlReceiver : BroadcastReceiver() {
    @Inject
    lateinit var callController: CallController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                when (action) {
                    ACTION_ACCEPT -> callController.acceptIncomingCall(audioOnly = false)
                    ACTION_HANG_UP -> callController.hangUp("notification-hangup")
                    else -> WaddleLog.info("Unknown call control action: $action")
                }
            } catch (cause: Exception) {
                WaddleLog.error("Call control action $action failed.", cause)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "social.waddle.android.CALL_ACCEPT"
        const val ACTION_HANG_UP = "social.waddle.android.CALL_HANG_UP"
    }
}
