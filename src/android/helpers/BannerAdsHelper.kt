package io.luzh.cordova.plugin.helpers

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
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
import io.luzh.cordova.plugin.utils.Constants.KEY_BANNER_OVERLAP
import io.luzh.cordova.plugin.utils.Constants.KEY_BANNER_POSITION
import io.luzh.cordova.plugin.utils.Constants.KEY_BANNER_SIZE
import io.luzh.cordova.plugin.utils.ConstantsEvents
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONArray
import org.json.JSONObject


internal class BannerAdsHelper(
    cordovaPlugin: CordovaPlugin,
    cordovaWebView: CordovaWebView,
    blockId: String
) : BaseAdsHelper<Unit>(cordovaPlugin, cordovaWebView, blockId) {
    private var bannerContainerLayout: RelativeLayout? = null
    private var spacerView: android.view.View? = null  // push mode only — holds space in LinearLayout
    private var bannerLoaded: Boolean = false
    private var bannerShown: Boolean = false
    private var firstShow: Boolean = true
    private var mBannerAdView: BannerAdView? = null

    // set during load()
    private var bannerPosition: String = BANNER_POSITION_BOTTOM
    private var bannerSize: JSONObject = JSONObject()
    private var overlap: Boolean = false

    // cached px dimensions
    private var containerW: Int = 0
    private var containerH: Int = 0

    private val isHorizontal get() = bannerPosition == BANNER_POSITION_LEFT || bannerPosition == BANNER_POSITION_RIGHT

    override fun getLoader() = null

    override fun show(callbackContext: CallbackContext) {
        cordova.activity.runOnUiThread {
            if (mBannerAdView == null || !bannerLoaded || bannerShown) {
                callbackContext.success()
                return@runOnUiThread
            }

            bannerShown = true

            val adLayoutParams = RelativeLayout.LayoutParams(containerW, containerH)
            adLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
            bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)
            bannerContainerLayout?.visibility = android.view.View.VISIBLE
            bannerContainerLayout?.bringToFront()

            if (firstShow) {
                firstShow = false
                cordovaWebView.view.post {
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

            callbackContext.success()
        }
    }

    /**
     * Create fixed spacer in LinearLayout to push WebView.
     * Called once on first show in push mode.
     */
    private fun createSpacer() {
        val view = cordovaWebView.view
        val wvParentView = view.parent as? ViewGroup ?: return

        wvParentView.removeView(view)

        val spacer = android.view.View(cordova.activity)
        spacer.setBackgroundColor(0xFF000000.toInt())
        spacerView = spacer

        val linearLayout = LinearLayout(cordova.activity)
        linearLayout.setBackgroundColor(0xFF000000.toInt())

        if (isHorizontal) {
            linearLayout.orientation = LinearLayout.HORIZONTAL
            val webViewParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            val spacerParams = LinearLayout.LayoutParams(containerW, LinearLayout.LayoutParams.MATCH_PARENT)
            if (bannerPosition == BANNER_POSITION_LEFT) {
                linearLayout.addView(spacer, spacerParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(spacer, spacerParams)
            }
        } else {
            linearLayout.orientation = LinearLayout.VERTICAL
            val webViewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            val spacerParams = LinearLayout.LayoutParams(containerW, containerH)
            spacerParams.gravity = Gravity.CENTER_HORIZONTAL
            if (bannerPosition == BANNER_POSITION_TOP) {
                linearLayout.addView(spacer, spacerParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(spacer, spacerParams)
            }
        }

        wvParentView.addView(linearLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    override fun load(callbackContext: CallbackContext) {
        load(null, callbackContext)
    }

    fun load(args: JSONArray?, callbackContext: CallbackContext) {
        args?.optJSONObject(0)?.let { options ->
            bannerPosition = options.optString(KEY_BANNER_POSITION, BANNER_POSITION_BOTTOM)
            bannerSize = options.optJSONObject(KEY_BANNER_SIZE) ?: JSONObject()
            overlap = options.optBoolean(KEY_BANNER_OVERLAP, false)
        }

        val density = cordova.activity.resources.displayMetrics.density
        containerW = (bannerSize.optInt("width") * density).toInt()
        containerH = (bannerSize.optInt("height") * density).toInt()

        cordova.getActivity().runOnUiThread(Runnable {
            destroyBanner()

            // create spacer on first push-mode load (only once)
            if (!overlap && spacerView == null) {
                createSpacer()
            }

            // create bannerContainerLayout and add to DecorView as GONE
            // this prevents layout recalculation when it becomes visible in show()
            if (bannerContainerLayout == null) {
                bannerContainerLayout = RelativeLayout(cordova.activity)
                val gravity = when (bannerPosition) {
                    BANNER_POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    BANNER_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                    BANNER_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                val containerLp = FrameLayout.LayoutParams(containerW, containerH)
                containerLp.gravity = gravity
                val decorView = cordova.activity.window.decorView as? ViewGroup
                decorView?.addView(bannerContainerLayout, containerLp)
                bannerContainerLayout?.visibility = android.view.View.GONE
            }

            mBannerAdView = BannerAdView(cordova.activity)

            mBannerAdView?.isFocusable = false
            mBannerAdView?.isFocusableInTouchMode = false
            mBannerAdView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            val adSize = BannerAdSize.inline(
                cordova.context,
                bannerSize.optInt("width"),
                bannerSize.optInt("height")
            )

            mBannerAdView?.setAdSize(adSize)

            val adRequest: AdRequest = AdRequest.Builder(blockId).build()

            mBannerAdView?.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    bannerLoaded = true
                    emitWindowEvent(ConstantsEvents.EVENT_BANNER_DID_LOAD)
                    log("+++ onAdLoaded: spacer top=${spacerView?.top} bottom=${spacerView?.bottom} visibility=${spacerView?.visibility}")
                    log("+++ onAdLoaded: bannerContainer top=${bannerContainerLayout?.top} visibility=${bannerContainerLayout?.visibility}")
                    if (bannerShown) {
                        cordovaWebView.view.post {
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

    fun hide(callbackContext: CallbackContext) {
        cordova.getActivity().runOnUiThread(Runnable {
            hideBannerView()
            callbackContext.success()
        })
    }

    private fun hideBannerView() {
        bannerShown = false
        bannerLoaded = false
        firstShow = true

        // hide banner overlay (keep in DecorView as GONE)
        bannerContainerLayout?.removeAllViews()
        bannerContainerLayout?.visibility = android.view.View.GONE

        // remove spacer from LinearLayout (WebView expands automatically via weight)
        (spacerView?.parent as? ViewGroup)?.removeView(spacerView)
        spacerView = null

        destroyBanner()
    }

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
