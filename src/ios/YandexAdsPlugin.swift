import Foundation
import UIKit

import YandexMobileAds

let PLUGIN_NOT_INITIALIZED_ERROR = [
    "code": "plugin_not_initialized",
    "message": "Plugin not initialized, call .init before ads load"
]

@MainActor
@objc(YandexAdsPlugin)
class YandexAdsPlugin: CDVPlugin {
    public var rewardedBlockId: String?
    public var interstitialBlockId: String?
    public var bannerBlockId: String?
    public var openAppBlockId: String?
    public var instreamBlockId: String?
    public var feedBlockId: String?

    public var interstitialAd: InterstitialAd?
    public var rewardedAd: RewardedAd?
    public var appOpenAd: AppOpenAd?
    public var bannerAdViewCache: BannerAdView?

    public var superView: UIView?
    public var stackViewInlineBannerView: UIStackView?
    public var bannerStackView: UIView?

    override func pluginInitialize() {
        super.pluginInitialize()

        self.superView = webView.superview
    }

    @objc(run:)
    func run(command: CDVInvokedUrlCommand) {
        self.rewardedBlockId = command.arguments[0] as? String ?? ""
        self.interstitialBlockId = command.arguments[1] as? String ?? ""
        self.bannerBlockId = command.arguments[2] as? String ?? ""
        self.openAppBlockId = command.arguments[3] as? String ?? ""
        self.instreamBlockId = command.arguments[4] as? String ?? ""
        self.feedBlockId = command.arguments[5] as? String ?? ""

        // options arg [6] is kept for forward compatibility but
        // banner parameters (bannerPosition, bannerSize, overlap)
        // are now passed via loadBanner(), matching Android behaviour.

        YandexAds.initializeSDK { [weak self] in
            self?.sendResult(command: command)
        }
    }

    public lazy var interstitialAdLoader = InterstitialAdLoader()
    public lazy var rewardedAdLoader = RewardedAdLoader()
    public lazy var appOpenAdLoader = AppOpenAdLoader()

    @objc(setUserConsent:)
    func setUserConsent(command: CDVInvokedUrlCommand) {
        YandexAds.setUserConsent(command.arguments[0] as? Bool ?? false)

        self.sendResult(command: command)
    }
}
