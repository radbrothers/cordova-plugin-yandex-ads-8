package io.luzh.cordova.plugin.helpers

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var wrappedActivity: Activity? = null
    private var originalWindowCallback: Window.Callback? = null

    override fun getLoader() = RewardedAdLoader(cordova.context)

    private fun installDpadInterceptor() {
        val app = cordova.activity.application ?: return

        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // wrap only the ad Activity, not our Cordova Activity
                if (activity !== cordova.activity && wrappedActivity == null) {
                    wrapWindowCallback(activity)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === wrappedActivity) {
                    wrappedActivity = null
                    originalWindowCallback = null
                }
            }
        }

        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val original = window.callback ?: return
        originalWindowCallback = original
        wrappedActivity = activity

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
                    val decorView = window.decorView
                    val focused = decorView.findFocus()
                    if (focused != null) {
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

        // make all clickable views focusable and request focus on the first one
        window.decorView.postDelayed({
            val focusables = mutableListOf<android.view.View>()
            makeClickableViewsFocusable(window.decorView, focusables)
            log("D-pad: made ${focusables.size} views focusable in ${activity.javaClass.simpleName}")
            focusables.firstOrNull()?.requestFocus()
        }, 500)

        log("D-pad interceptor installed on ${activity.javaClass.simpleName}")
    }

    private fun makeClickableViewsFocusable(view: android.view.View, focusables: MutableList<android.view.View>) {
        if (view.isClickable && view.visibility == android.view.View.VISIBLE) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            focusables.add(view)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                makeClickableViewsFocusable(view.getChildAt(i), focusables)
            }
        }
    }

    private fun removeDpadInterceptor() {
        lifecycleCallbacks?.let {
            cordova.activity.application?.unregisterActivityLifecycleCallbacks(it)
        }
        lifecycleCallbacks = null

        // restore original callback if ad activity still alive
        originalWindowCallback?.let { original ->
            wrappedActivity?.window?.callback = original
        }
        originalWindowCallback = null
        wrappedActivity = null
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
            installDpadInterceptor()
            mRewardedAd?.show(cordova.activity)
            callbackContext.success()
        })
    }
}
