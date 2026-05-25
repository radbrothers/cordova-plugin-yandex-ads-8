import YandexMobileAds

let EVENT_APP_OPEN_DID_DISMISS = "appOpenDidDismiss"
let EVENT_APP_OPEN_DID_FAIL_TO_SHOW_WITH_ERROR = "appOpenDidFailToShowWithError"
let EVENT_APP_OPEN_DID_SHOW = "appOpenDidShow"
let EVENT_APP_OPEN_DID_CLICK = "appOpenDidClick"
let EVENT_APP_OPEN_DID_TRACK_IMPRESSION_WITH = "appOpenDidTrackImpressionWith"

extension YandexAdsPlugin {
    @objc(loadOpenAppAds:)
    func loadOpenAppAds(command: CDVInvokedUrlCommand) {
        if self.openAppBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        let request = AdRequest(adUnitID: self.openAppBlockId!) // FIXME_SDK8: Auto-generated during migration, please review.
        appOpenAdLoader.loadAd(with: request) { [weak self] result in // FIXME_SDK8: Auto-generated during migration, please review.
            guard let self = self else { return }
            switch result {
            case .success(let appOpenAd):
                self.appOpenAd = appOpenAd
                self.appOpenAd?.delegate = self
                self.emitWindowEvent(event: EVENT_APP_OPEN_DID_LOAD)
            case .failure(let error):
                self.appOpenAd = nil
                let data = ErrorData(id: error.adUnitID, message: error.error.localizedDescription) // FIXME_SDK8: Auto-generated during migration, please review.
                self.emitWindowEvent(event: EVENT_APP_OPEN_FAILED_TO_LOAD, data: data)
            }
        }

        self.sendResult(command: command);
    }

    @objc(showOpenAppAds:)
    func showOpenAppAds(command: CDVInvokedUrlCommand) {
        if self.openAppBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        self.appOpenAd?.show(from: viewController)

        self.sendResult(command: command);
    }
}

@MainActor // FIXME_SDK8: Auto-generated during migration, please review.
extension YandexAdsPlugin: AppOpenAdDelegate {
    func appOpenAdDidDismiss(_ appOpenAd: AppOpenAd) {
        self.appOpenAd?.delegate = nil
        self.appOpenAd = nil

        self.emitWindowEvent(event: EVENT_APP_OPEN_DID_DISMISS)
    }

    func appOpenAd(
        _ appOpenAd: AppOpenAd,
        didFailToShow error: Error // FIXME_SDK8: Auto-generated during migration, please review.
    ) {
        self.appOpenAd = nil

        let data = ErrorData(message: error.localizedDescription)
        self.emitWindowEvent(event: EVENT_APP_OPEN_DID_FAIL_TO_SHOW_WITH_ERROR, data: data)
    }

    func appOpenAdDidShow(_ appOpenAd: AppOpenAd) {
        self.emitWindowEvent(event: EVENT_APP_OPEN_DID_SHOW)
    }

    func appOpenAdDidClick(_ appOpenAd: AppOpenAd) {
        self.emitWindowEvent(event: EVENT_APP_OPEN_DID_CLICK)
    }

    func appOpenAd(_ appOpenAd: AppOpenAd, didTrackImpression impressionData: ImpressionData?) { // FIXME_SDK8: Auto-generated during migration, please review.
        self.emitWindowEvent(event: EVENT_APP_OPEN_DID_TRACK_IMPRESSION_WITH)
    }
}

let EVENT_APP_OPEN_DID_LOAD = "appOpenDidLoad"
let EVENT_APP_OPEN_FAILED_TO_LOAD = "appOpenFailedToLoad"

// AppOpenAdLoaderDelegate removed in SDK 8 — logic moved to loadAd completion handler above. // FIXME_SDK8: Auto-generated during migration, please review.
