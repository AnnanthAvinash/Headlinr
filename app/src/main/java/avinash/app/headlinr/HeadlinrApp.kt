package avinash.app.headlinr

import android.app.Application
import com.google.android.gms.ads.MobileAds
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class HeadlinrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        MobileAds.initialize(this) { status ->
            Timber.tag("AdMob").d(
                "SDK init complete — adapters: %s",
                status.adapterStatusMap.entries.joinToString { (k, v) ->
                    "$k=${v.initializationState.name}(latency=${v.latency}ms)"
                }
            )
        }
    }
}
