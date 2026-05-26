package io.luzh.cordova.plugin.helpers

import android.R
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.view.contains
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import io.luzh.cordova.plugin.utils.ConstantsEvents
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.json.JSONObject


internal class BannerAdsHelper(
    cordovaPlugin: CordovaPlugin,
    cordovaWebView: CordovaWebView,
    blockId: String,
    val bannerAtTop: Boolean,
    val bannerSize: JSONObject?
) : BaseAdsHelper<Unit>(cordovaPlugin, cordovaWebView, blockId) {
    private var bannerContainerLayout: RelativeLayout? = null
    private var bannerParrentLayout: RelativeLayout? = null
    private var bannerLoaded: Boolean = false
    private var bannerShown: Boolean = false
    private var mBannerAdView: BannerAdView? = null

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
                val bannerLayoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val alignRule = if (bannerAtTop) RelativeLayout.ALIGN_PARENT_TOP
                    else RelativeLayout.ALIGN_PARENT_BOTTOM
                    addRule(alignRule)
                }

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
                val view = cordovaWebView.view
                val wvParentView = view.parent as? ViewGroup

                if (wvParentView != null) {
                    wvParentView.removeView(view)

                    val linearLayout = LinearLayout(cordova.activity)
                    linearLayout.orientation = LinearLayout.VERTICAL

                    val webViewParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f
                    )

                    val bannerParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    bannerContainerLayout = RelativeLayout(cordova.activity)
                    bannerContainerLayout?.setBackgroundColor(0x000000)
                    val adLayoutParams = RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT
                    )
                    adLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    bannerContainerLayout?.addView(mBannerAdView, adLayoutParams)

                    if (bannerAtTop) {
                        linearLayout.addView(bannerContainerLayout, bannerParams)
                        linearLayout.addView(view, webViewParams)
                    } else {
                        linearLayout.addView(view, webViewParams)
                        linearLayout.addView(bannerContainerLayout, bannerParams)
                    }

                    // bypass ContentFrameLayout entirely — set as Activity content directly
                    cordova.activity.setContentView(linearLayout)

                    log("+++ setContentView done")
                    log("+++ linearLayout childCount: ${linearLayout.childCount}")
                    log("+++ child[0]: ${linearLayout.getChildAt(0)?.javaClass?.simpleName}")
                    log("+++ child[1]: ${linearLayout.getChildAt(1)?.javaClass?.simpleName}")
                    log("+++ view.layoutParams type: ${view.layoutParams?.javaClass?.simpleName}")
                    log("+++ view.layoutParams h: ${view.layoutParams?.height}")

                    linearLayout.post {
                        log("+++ post: linearLayout w=${linearLayout.width} h=${linearLayout.height}")
                        log("+++ post: view w=${view.width} h=${view.height}")
                        log("+++ post: bannerContainer w=${bannerContainerLayout?.width} h=${bannerContainerLayout?.height}")
                        val bannerH = bannerContainerLayout?.height ?: 0
                        val totalH = linearLayout.height
                        if (bannerH > 0 && totalH > 0) {
                            val webViewH = totalH - bannerH
                            if (bannerAtTop) {
                                view.layout(0, bannerH, linearLayout.width, bannerH + webViewH)
                            } else {
                                view.layout(0, 0, linearLayout.width, webViewH)
                            }
                            view.requestLayout()
                            view.forceLayout()

                            cordovaWebView.loadUrl(
                                "javascript:setTimeout(function(){ window.dispatchEvent(new Event('resize')); }, 300);"
                            )
                            log("+++ window resize event scheduled in 300ms")
                        }
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

            // determine the size of the advertising banner
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

            // set the banner size
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

                    (mBannerAdView?.parent as? ViewGroup)?.let {
                        it.removeView(mBannerAdView)
                    }

                    if (bannerParrentLayout != null) {
                        mBannerAdView?.let {
                            if(bannerParrentLayout?.contains(it) == true){
                                bannerParrentLayout?.removeView(it)
                            }
                        }
                    }

                    log("+++ AFTER REMOVE " + bannerContainerLayout?.childCount)
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

            mBannerAdView?.let { it.setAdSize(adSize) }

            bannerShown = false

            val adRequest: AdRequest = AdRequest.Builder(blockId).build() // FIXME_SDK8: Auto-generated during migration, please review.

            mBannerAdView?.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    bannerLoaded = true
                    log(ConstantsEvents.EVENT_BANNER_DID_LOAD)

                    if (bannerSize == null) {
                        val alignRule = if (bannerAtTop) RelativeLayout.ALIGN_PARENT_TOP
                        else RelativeLayout.ALIGN_PARENT_BOTTOM

                        val bannerParrentParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                        ).apply { addRule(alignRule) }

                        bannerParrentLayout?.addView(mBannerAdView, bannerParrentParams)
                        bannerParrentLayout?.bringToFront()
                    } else {
                        val layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                        )

                        layoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)

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

            bannerContainerLayout?.viewTreeObserver?.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    mBannerAdView?.viewTreeObserver?.removeOnGlobalLayoutListener(
                        this
                    )

                    mBannerAdView?.measuredHeight?.let {
                        bannerContainerLayout?.minimumHeight = it
                    }
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
                // restore WebView as sole content of Activity
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
