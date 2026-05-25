import YandexMobileAds

let EVENT_INTERSTITIAL_DID_LOAD = "interstitialDidLoad"
let EVENT_INTERSTITIAL_FAILED_TO_LOAD = "interstitialFailedToLoad"

extension YandexAdsPlugin {
    @objc(loadInterstitial:)
    func loadInterstitial(command: CDVInvokedUrlCommand) {
        if self.interstitialBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        let request = AdRequest(adUnitID: self.interstitialBlockId!) // FIXME_SDK8: Auto-generated during migration, please review.
        self.interstitialAdLoader.loadAd(with: request) { [weak self] result in // FIXME_SDK8: Auto-generated during migration, please review.
            guard let self = self else { return }
            switch result {
            case .success(let interstitialAd):
                self.interstitialAd = interstitialAd
                self.interstitialAd?.delegate = self
                self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_LOAD)
            case .failure(let error):
                let data = ErrorData(id: error.adUnitID, message: error.error.localizedDescription) // FIXME_SDK8: Auto-generated during migration, please review.
                self.emitWindowEvent(event: EVENT_INTERSTITIAL_FAILED_TO_LOAD, data: data)
            }
        }

        self.sendResult(command: command);
    }

    @objc(showInterstitial:)
    func showInterstitial(command: CDVInvokedUrlCommand) {
        if self.interstitialBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        self.interstitialAd?.show(from: viewController)

        self.sendResult(command: command);
    }
}

// MARK: - InterstitialAdDelegate
let EVENT_INTERSTITIAL_DID_FAIL_TO_SHOW_WITH_ERROR = "interstitialDidFailToShowWithError" // FIXME_SDK8: Auto-generated during migration, please review.
let EVENT_INTERSTITIAL_DID_SHOW = "interstitialDidShow"
let EVENT_INTERSTITIAL_DID_DISMISS = "interstitialDidDismiss"
let EVENT_INTERSTITIAL_DID_CLICK = "interstitialDidClick"
let EVENT_INTERSTITIAL_DID_TRACK_IMPRESSION_WITH = "interstitialDidTrackImpressionWith"

@MainActor // FIXME_SDK8: Auto-generated during migration, please review.
extension YandexAdsPlugin: InterstitialAdDelegate {
    func interstitialAd(_ interstitialAd: InterstitialAd, didFailToShow error: Error) { // FIXME_SDK8: Auto-generated during migration, please review.
        let data = ErrorData(message: error.localizedDescription)
        self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_FAIL_TO_SHOW_WITH_ERROR, data: data)
    }

    func interstitialAdDidShow(_ interstitialAd: InterstitialAd) {
        self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_SHOW)
    }

    func interstitialAdDidDismiss(_ interstitialAd: InterstitialAd) {
        self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_DISMISS)
    }

    func interstitialAdDidClick(_ interstitialAd: InterstitialAd) {
        self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_CLICK)
    }

    func interstitialAd(_ interstitialAd: InterstitialAd, didTrackImpression impressionData: ImpressionData?) { // FIXME_SDK8: Auto-generated during migration, please review.
        self.emitWindowEvent(event: EVENT_INTERSTITIAL_DID_TRACK_IMPRESSION_WITH)
    }
}
