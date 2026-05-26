package io.luzh.cordova.plugin.helpers

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private var placeholderLayout: FrameLayout? = null
    private var bannerLoaded: Boolean = false
    private var bannerShown: Boolean = false
    private var mBannerAdView: BannerAdView? = null

    // set during load()
    private var bannerPosition: String = BANNER_POSITION_BOTTOM
    private var bannerSize: JSONObject = JSONObject()
    private var overlap: Boolean = false

    private val isHorizontal get() = bannerPosition == BANNER_POSITION_LEFT || bannerPosition == BANNER_POSITION_RIGHT

    override fun getLoader() = null

    override fun show(callbackContext: CallbackContext) {
        cordova.activity.runOnUiThread {
            if (mBannerAdView == null || !bannerLoaded || bannerShown) {
                callbackContext.success()
                return@runOnUiThread
            }

            bannerShown = true

            val density = cordova.activity.resources.displayMetrics.density
            val containerW = (bannerSize.optInt("width") * density).toInt()
            val containerH = (bannerSize.optInt("height") * density).toInt()

            bannerContainerLayout = RelativeLayout(cordova.activity)
            bannerContainerLayout?.setBackgroundColor(0xFF000000.toInt())
            val adLayoutParams = RelativeLayout.LayoutParams(containerW, containerH)
            adLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
            bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)

            if (overlap) {
                showOverlap(containerW, containerH)
            } else {
                showPush(containerW, containerH)
            }

            callbackContext.success()
        }
    }

    /**
     * Overlay mode — banner floats over WebView using FrameLayout gravity
     */
    private fun showOverlap(containerW: Int, containerH: Int) {
        val wvParent = cordovaWebView.view.parent as? ViewGroup ?: cordovaWebView as ViewGroup

        val gravity = when (bannerPosition) {
            BANNER_POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            BANNER_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            BANNER_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val containerLp = FrameLayout.LayoutParams(containerW, containerH)
        containerLp.gravity = gravity

        wvParent.addView(bannerContainerLayout, containerLp)
        bannerContainerLayout?.bringToFront()
    }

    /**
     * Push mode — fixed placeholder pushes WebView, banner overlays on top of placeholder
     */
    private fun showPush(containerW: Int, containerH: Int) {
        val view = cordovaWebView.view
        val wvParentView = view.parent as? ViewGroup ?: return

        wvParentView.removeView(view)

        // fixed placeholder — never changes size, always pushes WebView
        val placeholder = FrameLayout(cordova.activity)
        placeholder.setBackgroundColor(0xFF000000.toInt())
        placeholderLayout = placeholder

        val linearLayout = LinearLayout(cordova.activity)
        linearLayout.setBackgroundColor(0xFF000000.toInt())

        if (isHorizontal) {
            linearLayout.orientation = LinearLayout.HORIZONTAL

            val webViewParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f)
            val placeholderParams = LinearLayout.LayoutParams(containerW, LinearLayout.LayoutParams.MATCH_PARENT)

            if (bannerPosition == BANNER_POSITION_LEFT) {
                linearLayout.addView(placeholder, placeholderParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(placeholder, placeholderParams)
            }
        } else {
            linearLayout.orientation = LinearLayout.VERTICAL

            val webViewParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            val placeholderParams = LinearLayout.LayoutParams(containerW, containerH)
            placeholderParams.gravity = Gravity.CENTER_HORIZONTAL

            if (bannerPosition == BANNER_POSITION_TOP) {
                linearLayout.addView(placeholder, placeholderParams)
                linearLayout.addView(view, webViewParams)
            } else {
                linearLayout.addView(view, webViewParams)
                linearLayout.addView(placeholder, placeholderParams)
            }
        }

        cordova.activity.setContentView(linearLayout)

        // overlay bannerContainerLayout on top of placeholder
        linearLayout.post {
            val location = IntArray(2)
            placeholder.getLocationInWindow(location)
            val bannerLp = FrameLayout.LayoutParams(containerW, if (isHorizontal) LinearLayout.LayoutParams.MATCH_PARENT else containerH)
            bannerLp.gravity = when (bannerPosition) {
                BANNER_POSITION_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                BANNER_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                BANNER_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            (linearLayout.parent as? ViewGroup)?.let {
                it.addView(bannerContainerLayout, bannerLp)
                bannerContainerLayout?.bringToFront()
            } ?: run {
                linearLayout.addView(bannerContainerLayout, bannerLp)
                bannerContainerLayout?.bringToFront()
            }

            // trigger WebView viewport recalculation via instant fullscreen toggle
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
        // read banner options from args if provided
        args?.optJSONObject(0)?.let { options ->
            bannerPosition = options.optString(KEY_BANNER_POSITION, BANNER_POSITION_BOTTOM)
            bannerSize = options.optJSONObject(KEY_BANNER_SIZE) ?: JSONObject()
            overlap = options.optBoolean(KEY_BANNER_OVERLAP, false)
        }

        cordova.getActivity().runOnUiThread(Runnable {
            hideBannerView()
            mBannerAdView = BannerAdView(cordova.activity)

            // disable D-pad/keyboard focus for TV devices
            mBannerAdView?.isFocusable = false
            mBannerAdView?.isFocusableInTouchMode = false
            mBannerAdView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            val adSize = BannerAdSize.inline( // FIXME_SDK8: Auto-generated during migration, please review.
                cordova.context,
                bannerSize.optInt("width"),
                bannerSize.optInt("height")
            )

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

    private fun hideBannerView() {
        cordova.getActivity().runOnUiThread(Runnable {
            if (overlap) {
                // overlap mode — just remove bannerContainerLayout from parent
                (bannerContainerLayout?.parent as? ViewGroup)?.removeView(bannerContainerLayout)
            } else {
                // push mode — restore WebView back to its original parent (ContentFrameLayout)
                val view = cordovaWebView.view
                val linearLayout = view.parent as? LinearLayout
                if (linearLayout != null) {
                    val originalParent = linearLayout.parent as? ViewGroup
                    linearLayout.removeView(view)
                    originalParent?.removeView(linearLayout)
                    originalParent?.addView(view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
                // remove bannerContainerLayout overlay and placeholder
                (bannerContainerLayout?.parent as? ViewGroup)?.removeView(bannerContainerLayout)
                placeholderLayout = null
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
