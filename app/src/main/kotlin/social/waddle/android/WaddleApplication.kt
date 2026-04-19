package social.waddle.android

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import dagger.hilt.android.HiltAndroidApp
import social.waddle.android.notifications.WaddleNotificationChannels
import javax.inject.Inject

@HiltAndroidApp
class WaddleApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        WaddleNotificationChannels.registerAll(this)
    }

    /**
     * Singleton Coil image loader wired with an animated-image decoder so
     * `<img src=...gif>` and inline GIF attachments play rather than showing
     * a static first frame. Uses the platform `ImageDecoder` on API 28+ and
     * falls back to Coil's software GIF decoder on older devices.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.build()
}
