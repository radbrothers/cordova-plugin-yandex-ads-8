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
    private var spacerLayout: android.view.View? = null  // fixed spacer that pushes WebView
    private var linearLayout: LinearLayout? = null       // holds WebView + spacer
    private var bannerLoaded: Boolean = false
    private var bannerShown: Boolean = false
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

            bannerContainerLayout = RelativeLayout(cordova.activity)
            bannerContainerLayout?.setBackgroundColor(0xFF000000.toInt())
            val adLayoutParams = RelativeLayout.LayoutParams(containerW, containerH)
            adLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
            bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)

            showBannerOverlay()

            callbackContext.success()
        }
    }

    /**
     * Show banner as overlay — works for both overlap and push modes.
     * In push mode the spacer already holds the space, so we just overlay the banner on top.
     */
    private fun showBannerOverlay() {
        val wvParent = if (!overlap && linearLayout != null) {
            // in push mode — add to DecorView to ensure banner is above linearLayout
            cordova.activity.window.decorView as? ViewGroup ?: return
        } else {
            cordovaWebView.view.parent as? ViewGroup ?: cordovaWebView as ViewGroup
        }

        log("+++ showBannerOverlay: wvParent=${wvParent.javaClass.simpleName} childCount=${wvParent.childCount}")

        val gravity = when (bannerPosition) {
            BANNER_POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            BANNER_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            BANNER_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val containerLp = FrameLayout.LayoutParams(
            if (isHorizontal) containerW else containerW,
            if (isHorizontal) ViewGroup.LayoutParams.MATCH_PARENT else containerH
        )
        containerLp.gravity = gravity

        wvParent.addView(bannerContainerLayout, containerLp)
        bannerContainerLayout?.bringToFront()
    }

    /**
     * Create fixed spacer in LinearLayout to push WebView — called once on first load.
     * Only used when overlap = false.
     */
    private fun createSpacerLayout() {
        val view = cordovaWebView.view
        val wvParentView = view.parent as? ViewGroup ?: return

        wvParentView.removeView(view)

        val ll = LinearLayout(cordova.activity)
        ll.setBackgroundColor(0xFF000000.toInt())

        val spacer = android.view.View(cordova.activity)
        spacer.setBackgroundColor(0xFF000000.toInt())
        spacerLayout = spacer

        if (isHorizontal) {
            ll.orientation = LinearLayout.HORIZONTAL
            val webViewParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            val spacerParams = LinearLayout.LayoutParams(containerW, LinearLayout.LayoutParams.MATCH_PARENT)
            if (bannerPosition == BANNER_POSITION_LEFT) {
                ll.addView(spacer, spacerParams)
                ll.addView(view, webViewParams)
            } else {
                ll.addView(view, webViewParams)
                ll.addView(spacer, spacerParams)
            }
        } else {
            ll.orientation = LinearLayout.VERTICAL
            val webViewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            val spacerParams = LinearLayout.LayoutParams(containerW, containerH)
            spacerParams.gravity = Gravity.CENTER_HORIZONTAL
            if (bannerPosition == BANNER_POSITION_TOP) {
                ll.addView(spacer, spacerParams)
                ll.addView(view, webViewParams)
            } else {
                ll.addView(view, webViewParams)
                ll.addView(spacer, spacerParams)
            }
        }

        linearLayout = ll
        cordova.activity.setContentView(ll)

        // trigger WebView viewport recalculation via instant fullscreen toggle
        ll.post {
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

    override fun load(callbackContext: CallbackContext) {
        load(null, callbackContext)
    }

    fun load(args: JSONArray?, callbackContext: CallbackContext) {
        var paramsChanged = false
        args?.optJSONObject(0)?.let { options ->
            val newPosition = options.optString(KEY_BANNER_POSITION, BANNER_POSITION_BOTTOM)
            val newSize = options.optJSONObject(KEY_BANNER_SIZE) ?: JSONObject()
            val newOverlap = options.optBoolean(KEY_BANNER_OVERLAP, false)
            paramsChanged = newPosition != bannerPosition ||
                newSize.optInt("width") != bannerSize.optInt("width") ||
                newSize.optInt("height") != bannerSize.optInt("height") ||
                newOverlap != overlap
            bannerPosition = newPosition
            bannerSize = newSize
            overlap = newOverlap
        }

        val density = cordova.activity.resources.displayMetrics.density
        containerW = (bannerSize.optInt("width") * density).toInt()
        containerH = (bannerSize.optInt("height") * density).toInt()

        cordova.getActivity().runOnUiThread(Runnable {
            // full reset if params changed or first load
            if (paramsChanged || spacerLayout == null && !overlap) {
                hideBannerView()
            } else {
                // just remove old banner overlay, keep spacer intact
                (bannerContainerLayout?.parent as? ViewGroup)?.removeView(bannerContainerLayout)
                bannerContainerLayout?.removeView(mBannerAdView)
                bannerContainerLayout = null
                destroyBanner()
                bannerShown = false
                bannerLoaded = false
            }

            // create spacer on first push-mode load
            if (!overlap && spacerLayout == null) {
                createSpacerLayout()
            }

            mBannerAdView = BannerAdView(cordova.activity)

            // disable D-pad/keyboard focus for TV devices
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

    private fun hideBannerView() {
        bannerShown = false
        bannerLoaded = false

        // remove banner overlay
        (bannerContainerLayout?.parent as? ViewGroup)?.removeView(bannerContainerLayout)
        bannerContainerLayout?.removeView(mBannerAdView)
        bannerContainerLayout = null

        // remove spacer and restore WebView to original parent
        if (!overlap && linearLayout != null) {
            val view = cordovaWebView.view
            linearLayout?.removeView(view)
            cordova.activity.setContentView(view)
            linearLayout = null
            spacerLayout = null
        }

        destroyBanner()
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
