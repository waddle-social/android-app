package social.waddle.android.util

import android.util.Log

object WaddleLog {
    private const val TAG = "Waddle"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}
