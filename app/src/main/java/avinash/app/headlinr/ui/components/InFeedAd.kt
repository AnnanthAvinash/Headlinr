package avinash.app.headlinr.ui.components

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

private const val AD_UNIT_ID = "ca-app-pub-2136184247126956/3823812744"
private const val TAG = "AdMob"

private val totalRequested = AtomicInteger(0)
private val totalLoaded = AtomicInteger(0)
private val totalFailed = AtomicInteger(0)
private val totalDestroyed = AtomicInteger(0)

private fun logStats(event: String) {
    Timber.tag(TAG).d(
        "%s | requested=%d loaded=%d failed=%d destroyed=%d pending=%d",
        event,
        totalRequested.get(),
        totalLoaded.get(),
        totalFailed.get(),
        totalDestroyed.get(),
        totalRequested.get() - totalLoaded.get() - totalFailed.get()
    )
}

private data class AdColors(
    val card: Int,
    val primary: Int,
    val onPrimary: Int,
    val text: Int,
    val subtext: Int
)

@Composable
fun InFeedAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isLoaded by remember { mutableStateOf(false) }
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }

    val colors = AdColors(
        card = MaterialTheme.colorScheme.surface.toArgb(),
        primary = MaterialTheme.colorScheme.primary.toArgb(),
        onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb(),
        text = MaterialTheme.colorScheme.onSurface.toArgb(),
        subtext = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    )

    DisposableEffect(Unit) {
        val adId = totalRequested.incrementAndGet()
        var isActive = true

        Timber.tag(TAG).d("Ad #%d — requesting native (unit=%s)", adId, AD_UNIT_ID)
        logStats("REQUESTED #$adId")

        AdLoader.Builder(context, AD_UNIT_ID)
            .forNativeAd { ad ->
                if (isActive) {
                    nativeAd = ad
                    isLoaded = true
                    totalLoaded.incrementAndGet()
                    Timber.tag(TAG).d("Ad #%d — LOADED native (headline=%s)", adId, ad.headline)
                    logStats("LOADED #$adId")
                } else {
                    ad.destroy()
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    totalFailed.incrementAndGet()
                    Timber.tag(TAG).e(
                        "Ad #%d — FAILED code=%d msg=%s domain=%s cause=%s",
                        adId, error.code, error.message, error.domain,
                        error.cause?.toString() ?: "none"
                    )
                    logStats("FAILED #$adId")
                }

                override fun onAdImpression() {
                    Timber.tag(TAG).d("Ad #%d — IMPRESSION recorded", adId)
                }

                override fun onAdClicked() {
                    Timber.tag(TAG).d("Ad #%d — CLICKED", adId)
                }

                override fun onAdOpened() {
                    Timber.tag(TAG).d("Ad #%d — OPENED", adId)
                }

                override fun onAdClosed() {
                    Timber.tag(TAG).d("Ad #%d — CLOSED", adId)
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                    .build()
            )
            .build()
            .loadAd(AdRequest.Builder().build())

        onDispose {
            isActive = false
            nativeAd?.destroy()
            totalDestroyed.incrementAndGet()
            Timber.tag(TAG).d("Ad #%d — DESTROYED", adId)
            logStats("DESTROYED #$adId")
        }
    }

    AnimatedVisibility(
        visible = isLoaded && nativeAd != null,
        enter = fadeIn() + expandVertically()
    ) {
        nativeAd?.let { ad ->
            AndroidView(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                factory = { ctx -> buildNativeAdView(ctx, ad, colors) }
            )
        }
    }
}

private fun buildNativeAdView(
    context: Context,
    ad: NativeAd,
    colors: AdColors
): NativeAdView {
    val dp = context.resources.displayMetrics.density
    fun dpPx(value: Int) = (value * dp).toInt()

    val adView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        background = GradientDrawable().apply {
            setColor(colors.card)
            cornerRadius = 14f * dp
        }
        elevation = 2f * dp
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 14f * dp)
            }
        }
        clipToOutline = true
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // -- Media --
    val mediaView = MediaView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpPx(160)
        )
        setImageScaleType(ImageView.ScaleType.CENTER_CROP)
    }
    root.addView(mediaView)
    adView.mediaView = mediaView

    // -- Content padding container --
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpPx(12), dpPx(10), dpPx(12), dpPx(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // "Ad" badge
    content.addView(TextView(context).apply {
        text = "Ad"
        setTextColor(colors.onPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dpPx(8), dpPx(2), dpPx(8), dpPx(2))
        background = GradientDrawable().apply {
            setColor(colors.primary)
            cornerRadius = 6f * dp
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpPx(4) }
    })

    // Headline
    val headline = TextView(context).apply {
        text = ad.headline
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    content.addView(headline)
    adView.headlineView = headline

    // Body
    ad.body?.let { bodyText ->
        val body = TextView(context).apply {
            text = bodyText
            setTextColor(colors.subtext)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpPx(3) }
        }
        content.addView(body)
        adView.bodyView = body
    }

    // Spacer
    content.addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpPx(8)
        )
    })

    // -- Bottom row: icon + advertiser + CTA --
    val bottomRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    ad.icon?.let { icon ->
        val iconView = ImageView(context).apply {
            setImageDrawable(icon.drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dpPx(20), dpPx(20)).apply {
                marginEnd = dpPx(6)
            }
        }
        bottomRow.addView(iconView)
        adView.iconView = iconView
    }

    val advertiser = TextView(context).apply {
        text = ad.advertiser ?: ad.store ?: ""
        setTextColor(colors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    bottomRow.addView(advertiser)
    adView.advertiserView = advertiser

    ad.callToAction?.let { ctaText ->
        val cta = TextView(context).apply {
            text = ctaText
            setTextColor(colors.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpPx(16), dpPx(6), dpPx(16), dpPx(6))
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = 8f * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpPx(8) }
        }
        bottomRow.addView(cta)
        adView.callToActionView = cta
    }

    content.addView(bottomRow)
    root.addView(content)
    adView.addView(root)
    adView.setNativeAd(ad)

    return adView
}
