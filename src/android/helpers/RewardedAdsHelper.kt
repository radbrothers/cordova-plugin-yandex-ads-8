package io.luzh.cordova.plugin.helpers

import android.view.KeyEvent
import android.view.Window
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_AD_CLICKED
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_AD_DISMISSED
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_AD_IMPRESSION
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_FAILED_TO_LOAD
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_FAILED_TO_SHOW
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_LOADED
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_REWARDED
import io.luzh.cordova.plugin.utils.ConstantsEvents.EVENT_REWARDED_VIDEO_SHOWN
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView

internal class RewardedAdsHelper(
    cordovaPlugin: CordovaPlugin,
    cordovaWebView: CordovaWebView,
    blockId: String
) : BaseAdsHelper<RewardedAdLoader>(cordovaPlugin, cordovaWebView, blockId) {
    private var mRewardedAd: RewardedAd? = null
    private var originalWindowCallback: Window.Callback? = null

    override fun getLoader() = RewardedAdLoader(cordova.context)

    private fun installDpadInterceptor() {
        val window = cordova.activity.window ?: return
        originalWindowCallback = window.callback
        val original = window.callback

        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val keyCode = event.keyCode
                val isDpad = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                    keyCode == KeyEvent.KEYCODE_BACK ||
                    keyCode == KeyEvent.KEYCODE_BUTTON_B

                if (isDpad && event.action == KeyEvent.ACTION_DOWN) {
                    // find focused view in ad and dispatch navigation
                    val decorView = window.decorView
                    val focused = decorView.findFocus()
                    if (focused != null) {
                        // for directional keys — move focus
                        val direction = when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
                            KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
                            KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
                            KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
                            else -> null
                        }
                        if (direction != null) {
                            val next = focused.focusSearch(direction)
                            if (next != null && next.requestFocus()) {
                                return true
                            }
                        }
                    }
                }

                return original.dispatchKeyEvent(event)
            }
        }
    }

    private fun removeDpadInterceptor() {
        originalWindowCallback?.let { original ->
            cordova.activity.window?.callback = original
        }
        originalWindowCallback = null
    }

    private fun getAdLoadListener() = object : RewardedAdLoadListener {
        override fun onAdLoaded(rewarded: RewardedAd) {
            mRewardedAd = rewarded
            rewarded.setAdEventListener(object : RewardedAdEventListener {
                override fun onRewarded(reward: Reward) {
                    emitWindowEvent(EVENT_REWARDED_VIDEO_REWARDED)
                }

                override fun onAdShown() {
                    emitWindowEvent(EVENT_REWARDED_VIDEO_SHOWN)
                }

                override fun onAdFailedToShow(adError: AdError) {
                    removeDpadInterceptor()
                    emitWindowEvent(EVENT_REWARDED_VIDEO_FAILED_TO_SHOW)
                }

                override fun onAdDismissed() {
                    removeDpadInterceptor()
                    emitWindowEvent(EVENT_REWARDED_VIDEO_AD_DISMISSED)
                }

                override fun onAdClicked() {
                    emitWindowEvent(EVENT_REWARDED_VIDEO_AD_CLICKED)
                }

                override fun onAdImpression(impressionData: ImpressionData?) {
                    emitWindowEvent(EVENT_REWARDED_VIDEO_AD_IMPRESSION)
                }
            })

            emitWindowEvent(EVENT_REWARDED_VIDEO_LOADED)
        }

        override fun onAdFailedToLoad(error: AdRequestError) {
            emitWindowEvent(EVENT_REWARDED_VIDEO_FAILED_TO_LOAD, error.description)
        }
    }

    override fun load(callbackContext: CallbackContext) {
        cordova.getActivity().runOnUiThread(Runnable {
            getLoader().loadAd(AdRequest.Builder(blockId).build(), getAdLoadListener())
            callbackContext.success()
        })
    }

    override fun show(callbackContext: CallbackContext) {
        cordova.getActivity().runOnUiThread(Runnable {
            mRewardedAd?.show(cordova.activity)
            // install D-pad interceptor after ad opens (with delay for ad window to appear)
            cordova.activity.window.decorView.postDelayed({
                installDpadInterceptor()
            }, 500)
            callbackContext.success()
        })
    }
}