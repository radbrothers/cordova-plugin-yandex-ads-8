package io.luzh.cordova.plugin.helpers

import android.R
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.view.contains
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import io.luzh.cordova.plugin.utils.Constants.BANNER_POSITION_BOTTOM
import io.luzh.cordova.plugin.utils.Constants.BANNER_POSITION_LEFT
import io.luzh.cordova.plugin.utils.Constants.BANNER_POSITION_RIGHT
import io.luzh.cordova.plugin.utils.Constants.BANNER_POSITION_TOP
import io.luzh.cordova.plugin.utils.ConstantsEvents
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONObject


internal class BannerAdsHelper(
    cordovaPlugin: CordovaPlugin,
    cordovaWebView: CordovaWebView,
    blockId: String,
    val bannerPosition: String,
    val bannerSize: JSONObject?
) : BaseAdsHelper<Unit>(cordovaPlugin, cordovaWebView, blockId) {
    private var bannerContainerLayout: RelativeLayout? = null
    private var bannerParrentLayout: RelativeLayout? = null
    private var bannerLoaded: Boolean = false
    private var bannerShown: Boolean = false
    private var mBannerAdView: BannerAdView? = null

    private val isHorizontal get() = bannerPosition == BANNER_POSITION_LEFT || bannerPosition == BANNER_POSITION_RIGHT

    override fun getLoader() = null

    override fun show(callbackContext: CallbackContext) {
        cordova.activity.runOnUiThread {
            // cant show or already shown
            if (mBannerAdView == null || !bannerLoaded || bannerShown) {
                callbackContext.success()
                return@runOnUiThread
            }

            bannerShown = true

            if (bannerSize == null) {
                // sticky banner — overlay via RelativeLayout
                val alignRule = when (bannerPosition) {
                    BANNER_POSITION_TOP -> RelativeLayout.ALIGN_PARENT_TOP
                    BANNER_POSITION_LEFT -> RelativeLayout.ALIGN_PARENT_LEFT
                    BANNER_POSITION_RIGHT -> RelativeLayout.ALIGN_PARENT_RIGHT
                    else -> RelativeLayout.ALIGN_PARENT_BOTTOM
                }

                val bannerLayoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply { addRule(alignRule) }

                bannerParrentLayout = RelativeLayout(cordova.activity)
                val bannerParrentLayoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                )

                try {
                    (cordovaWebView.view.parent as? ViewGroup)?.addView(bannerParrentLayout, bannerParrentLayoutParams)
                } catch (e: java.lang.Exception) {
                    (cordovaWebView as ViewGroup).addView(bannerParrentLayout, bannerParrentLayoutParams)
                }

                bannerParrentLayout?.addView(mBannerAdView, bannerLayoutParams)
                bannerParrentLayout?.bringToFront()
            } else {
                // inline banner — setContentView with LinearLayout to bypass ContentFrameLayout
                val view = cordovaWebView.view
                val wvParentView = view.parent as? ViewGroup

                if (wvParentView != null) {
                    wvParentView.removeView(view)

                    val density = cordova.activity.resources.displayMetrics.density
                    val containerW = (bannerSize.optInt("width") * density).toInt()
                    val containerH = (bannerSize.optInt("height") * density).toInt()

                    bannerContainerLayout = RelativeLayout(cordova.activity)
                    bannerContainerLayout?.setBackgroundColor(0xFF000000.toInt())
                    bannerContainerLayout?.minimumHeight = containerH
                    val adLayoutParams = RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT
                    )
                    adLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
                    bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)

                    val linearLayout = LinearLayout(cordova.activity)
                    linearLayout.setBackgroundColor(0xFF000000.toInt())

                    if (isHorizontal) {
                        // left/right — horizontal LinearLayout
                        linearLayout.orientation = LinearLayout.HORIZONTAL

                        val webViewParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
                        val bannerParams = LinearLayout.LayoutParams(containerW, LinearLayout.LayoutParams.MATCH_PARENT)
                        bannerParams.gravity = android.view.Gravity.CENTER_VERTICAL

                        if (bannerPosition == BANNER_POSITION_LEFT) {
                            linearLayout.addView(bannerContainerLayout, bannerParams)
                            linearLayout.addView(view, webViewParams)
                        } else {
                            linearLayout.addView(view, webViewParams)
                            linearLayout.addView(bannerContainerLayout, bannerParams)
                        }
                    } else {
                        // top/bottom — vertical LinearLayout
                        linearLayout.orientation = LinearLayout.VERTICAL

                        val webViewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
                        val bannerParams = LinearLayout.LayoutParams(containerW, containerH)
                        bannerParams.gravity = android.view.Gravity.CENTER_HORIZONTAL

                        if (bannerPosition == BANNER_POSITION_TOP) {
                            linearLayout.addView(bannerContainerLayout, bannerParams)
                            linearLayout.addView(view, webViewParams)
                        } else {
                            linearLayout.addView(view, webViewParams)
                            linearLayout.addView(bannerContainerLayout, bannerParams)
                        }
                    }

                    cordova.activity.setContentView(linearLayout)

                    // trigger WebView viewport recalculation via instant fullscreen toggle
                    linearLayout.post {
                        cordovaWebView.loadUrl(
                            "javascript:setTimeout(function(){" +
                            "var el = document.documentElement;" +
                            "var req = el.requestFullscreen || el.webkitRequestFullscreen;" +
                            "var exit = document.exitFullscreen || document.webkitExitFullscreen;" +
                            "if(req && exit){" +
                            "req.call(el).then(function(){ exit.call(document); })" +
                            ".catch(function(e){ console.log('fs error: ' + e); });" +
                            "}" +
                            "}, 300);"
                        )
                    }
                }
            }

            callbackContext.success()
        }
    }

    override fun load(callbackContext: CallbackContext) {
        cordova.getActivity().runOnUiThread(Runnable {
            hideBannerView()
            mBannerAdView = BannerAdView(cordova.activity)

            val adSize =
                if (bannerSize != null && bannerSize.has("width") && bannerSize.has("height")) {
                    BannerAdSize.inline( // FIXME_SDK8: Auto-generated during migration, please review.
                        cordova.context,
                        bannerSize.optInt("width"),
                        bannerSize.optInt("height")
                    )
                } else {
                    val adWidth = cordovaWebView.view.width
                    BannerAdSize.sticky(cordova.context, adWidth) // FIXME_SDK8: Auto-generated during migration, please review.
                }

            mBannerAdView?.setAdSize(adSize)
            bannerShown = false

            val adRequest: AdRequest = AdRequest.Builder(blockId).build() // FIXME_SDK8: Auto-generated during migration, please review.

            mBannerAdView?.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    bannerLoaded = true
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_DID_LOAD)
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_FAILED_TO_LOAD, error.description)
                }

                override fun onAdClicked() {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_DID_CLICK)
                }

                override fun onImpression(impressionData: ImpressionData?) {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_IMPRESSION)
                }
            })

            mBannerAdView?.loadAd(adRequest)
            callbackContext.success()
        })
    }

    /**
     * Destroys Yandex Ads Banner and removes it from the container
     */
    fun hide(callbackContext: CallbackContext) {
        cordova.getActivity().runOnUiThread(Runnable {
            hideBannerView()
            callbackContext.success()
        })
    }

    fun reload(callbackContext: CallbackContext) {
        cordova.activity.runOnUiThread {
            if (mBannerAdView != null && bannerShown) {
                if (getParentLayout() != null && bannerContainerLayout != null) {
                    (mBannerAdView?.parent as? ViewGroup)?.removeView(mBannerAdView)

                    if (bannerParrentLayout != null) {
                        mBannerAdView?.let {
                            if (bannerParrentLayout?.contains(it) == true) {
                                bannerParrentLayout?.removeView(it)
                            }
                        }
                    }
                }
                destroyBanner()
            }

            mBannerAdView = BannerAdView(cordovaPlugin.cordova.activity)

            val adSize =
                if (bannerSize != null && bannerSize.has("width") && bannerSize.has("height")) {
                    BannerAdSize.inline( // FIXME_SDK8: Auto-generated during migration, please review.
                        cordovaPlugin.cordova.context,
                        bannerSize.optInt("width"),
                        bannerSize.optInt("height")
                    )
                } else {
                    val adWidth = cordovaWebView.view.width
                    BannerAdSize.sticky(cordovaPlugin.cordova.context, adWidth) // FIXME_SDK8: Auto-generated during migration, please review.
                }

            mBannerAdView?.setAdSize(adSize)
            bannerShown = false

            val adRequest: AdRequest = AdRequest.Builder(blockId).build() // FIXME_SDK8: Auto-generated during migration, please review.

            mBannerAdView?.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    bannerLoaded = true
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_DID_LOAD)

                    if (bannerSize == null) {
                        val alignRule = when (bannerPosition) {
                            BANNER_POSITION_TOP -> RelativeLayout.ALIGN_PARENT_TOP
                            BANNER_POSITION_LEFT -> RelativeLayout.ALIGN_PARENT_LEFT
                            BANNER_POSITION_RIGHT -> RelativeLayout.ALIGN_PARENT_RIGHT
                            else -> RelativeLayout.ALIGN_PARENT_BOTTOM
                        }

                        val bannerParrentParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                        ).apply { addRule(alignRule) }

                        bannerParrentLayout?.addView(mBannerAdView, bannerParrentParams)
                        bannerParrentLayout?.bringToFront()
                    } else {
                        val layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
                        bannerContainerLayout?.addView(mBannerAdView, layoutParams)
                        mBannerAdView?.layoutParams = layoutParams
                    }

                    bannerShown = true
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_FAILED_TO_LOAD, error.description)
                }

                override fun onAdClicked() {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_DID_CLICK)
                }

                override fun onImpression(impressionData: ImpressionData?) {
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_IMPRESSION)
                }
            })

            mBannerAdView?.loadAd(adRequest)
            callbackContext.success()
        }
    }

    private fun hideBannerView() {
        cordova.getActivity().runOnUiThread(Runnable {
            val view = cordovaWebView.view
            val linearLayout = view.parent as? LinearLayout

            if (linearLayout != null) {
                linearLayout.removeView(view)
                cordova.activity.setContentView(view)
            }

            bannerContainerLayout?.removeView(mBannerAdView)
            bannerContainerLayout = null

            destroyBanner()
        })
    }

    /**
     * Destroy Banner
     */
    private fun destroyBanner() {
        mBannerAdView?.let {
            try {
                it.destroy()
            } catch (e: Exception) {
                log("Exception while destroying banner, seems too many requests")
            }
        }
        mBannerAdView = null
    }

    private fun getParentLayout() = cordovaWebView.view?.parent as? ViewGroup
}
