package io.luzh.cordova.plugin.helpers

import android.R
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

            if (overlap) {
                showOverlap()
            } else {
                showPush()
            }

            callbackContext.success()
        }
    }

    private fun showOverlap() {
        // always add to the top-level content frame, not WebView's direct parent
        val contentFrame = cordova.activity.findViewById<ViewGroup>(android.R.id.content)
            ?: cordovaWebView.view.parent as? ViewGroup
            ?: cordovaWebView as ViewGroup

        val gravity = when (bannerPosition) {
            BANNER_POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            BANNER_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            BANNER_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val containerLp = FrameLayout.LayoutParams(containerW, containerH)
        containerLp.gravity = gravity

        contentFrame.addView(bannerContainerLayout, containerLp)
        bannerContainerLayout?.bringToFront()
    }

    private fun showPush() {
        val view = cordovaWebView.view
        val contentFrame = view.parent as? ViewGroup ?: return
        log("+++ showPush: contentFrame=${contentFrame.javaClass.simpleName} childCount=${contentFrame.childCount}")

        // Remove WebView from ContentFrameLayout
        contentFrame.removeView(view)

        // Create LinearLayout and add to ContentFrameLayout (not via setContentView)
        val linearLayout = LinearLayout(cordova.activity)
        linearLayout.setBackgroundColor(0xFF000000.toInt())

        if (isHorizontal) {
            linearLayout.orientation = LinearLayout.HORIZONTAL
            val webViewParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            val bannerParams = LinearLayout.LayoutParams(containerW, LinearLayout.LayoutParams.MATCH_PARENT)
            bannerParams.gravity = Gravity.CENTER_VERTICAL
            if (bannerPosition == BANNER_POSITION_LEFT) {
                linearLayout.addView(bannerContainerLayout, bannerParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(bannerContainerLayout, bannerParams)
            }
        } else {
            linearLayout.orientation = LinearLayout.VERTICAL
            val webViewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            val bannerParams = LinearLayout.LayoutParams(containerW, containerH)
            bannerParams.gravity = Gravity.CENTER_HORIZONTAL
            if (bannerPosition == BANNER_POSITION_TOP) {
                linearLayout.addView(bannerContainerLayout, bannerParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(bannerContainerLayout, bannerParams)
            }
        }

        // Add linearLayout directly to ContentFrameLayout with MATCH_PARENT
        contentFrame.addView(linearLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

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
                    if (bannerShown) {
                        cordova.activity.runOnUiThread {
                            bannerContainerLayout?.removeAllViews()
                            val adLayoutParams = RelativeLayout.LayoutParams(containerW, containerH)
                            adLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
                            bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)
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

        log("+++ hideBannerView: overlap=$overlap bannerContainer.parent=${bannerContainerLayout?.parent?.javaClass?.simpleName}")
        (bannerContainerLayout?.parent as? ViewGroup)?.removeView(bannerContainerLayout)

        if (!overlap) {
            val view = cordovaWebView.view
            val linearLayout = view.parent as? LinearLayout
            log("+++ hideBannerView: webView.parent=${view.parent?.javaClass?.simpleName}")
            if (linearLayout != null) {
                val contentFrame = linearLayout.parent as? ViewGroup
                log("+++ hideBannerView: linearLayout.parent=${contentFrame?.javaClass?.simpleName} childCount=${contentFrame?.childCount}")
                linearLayout.removeView(view)
                contentFrame?.removeView(linearLayout)
                contentFrame?.addView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                contentFrame?.requestLayout()
                view.requestLayout()
                log("+++ hideBannerView after: contentFrame childCount=${contentFrame?.childCount}")
            }
        }

        bannerContainerLayout?.removeView(mBannerAdView)
        bannerContainerLayout = null

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
