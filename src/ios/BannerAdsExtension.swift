import YandexMobileAds

let EVENT_BANNER_DID_LOAD = "bannerDidLoad"
let EVENT_BANNER_DID_CLICK = "bannerDidClick"
let EVENT_BANNER_DID_TRACK_IMPRESSION_WITH = "bannerDidTrackImpressionWith"
let EVENT_BANNER_FAILED_TO_LOAD = "bannerFailedToLoad"

extension YandexAdsPlugin {
    func getBannerAdView() -> BannerAdView { // FIXME_SDK8: Auto-generated during migration, please review.
        if self.bannerAdViewCache != nil {
            return self.bannerAdViewCache!
        }

        let width = self.webView.safeAreaLayoutGuide.layoutFrame.width
        var adSize = BannerAdSize.sticky(containerWidth: width) // FIXME_SDK8: Auto-generated during migration, please review.

        if (self.bannerSize != nil && self.bannerSize?["width"] != nil && self.bannerSize?["height"] != nil) {
            adSize = BannerAdSize.inline(width: self.bannerSize?["width"] as! CGFloat, maxHeight: self.bannerSize?["height"] as! CGFloat) // FIXME_SDK8: Auto-generated during migration, please review.
        }

        let adView = BannerAdView(adSize: adSize) // FIXME_SDK8: Auto-generated during migration, please review.

        adView.delegate = self

        self.bannerAdViewCache = adView

        return adView
    }

    @objc(loadBanner:)
    func loadBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        let request = AdRequest(adUnitID: self.bannerBlockId!) // FIXME_SDK8: Auto-generated during migration, please review.
        self.getBannerAdView().loadAd(with: request) // FIXME_SDK8: Auto-generated during migration, please review.

        self.sendResult(command: command);
    }

    @objc(showBanner:)
    func showBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        let banner = self.getBannerAdView();

        if (self.bannerSize != nil && self.bannerSize?["width"] != nil && self.bannerSize?["height"] != nil) {
            self.showInlineBanner(banner: banner)
        } else {
            self.showOverlapBanner(banner: banner)
        }

        self.sendResult(command: command);
    }

    @objc(reloadBanner:)
    func reloadBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        // hide banner
        self.getBannerAdView().removeFromSuperview()

        self.getBannerAdView().delegate = nil
        self.bannerAdViewCache = nil

        // load new banner
        self.bannerReloaded = true

        let banner = self.getBannerAdView();
        let reloadRequest = AdRequest(adUnitID: self.bannerBlockId!) // FIXME_SDK8: Auto-generated during migration, please review.
        banner.loadAd(with: reloadRequest) // FIXME_SDK8: Auto-generated during migration, please review.

        // show banner
        if (self.bannerSize != nil && self.bannerSize?["width"] != nil && self.bannerSize?["height"] != nil) {
            banner.displayAtTop(in: self.bannerStackView!)

            NSLayoutConstraint.activate([
                self.bannerStackView!.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: 0.0),
                self.bannerStackView!.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: 0.0),
            ])
        } else {
            self.showOverlapBanner(banner: banner)
        }

        self.sendResult(command: command);
    }

    func showOverlapBanner(banner: BannerAdView) { // FIXME_SDK8: Auto-generated during migration, please review.
        if self.bannerAtTop != nil && self.bannerAtTop == true {
            banner.displayAtTop(in: webView)
        } else {
            banner.displayAtBottom(in: webView)
        }
    }

    func showInlineBanner(banner: BannerAdView) { // FIXME_SDK8: Auto-generated during migration, please review.
        let stackview: UIStackView = {
            let view = UIStackView()
            view.axis = .vertical
            view.distribution = .fill
            view.translatesAutoresizingMaskIntoConstraints = false
            return view
        }()

        self.stackViewInlineBannerView = stackview

        self.bannerStackView = {
            let view = UIView()
            view.backgroundColor = .black
            view.translatesAutoresizingMaskIntoConstraints = false
            return view
        }()

        self.superView?.addSubview(stackview)
        webView.removeFromSuperview()

        if self.bannerAtTop != nil && self.bannerAtTop == true {
            stackview.addArrangedSubview(self.bannerStackView!)
            stackview.addArrangedSubview(webView)
        } else {
            stackview.addArrangedSubview(webView)
            stackview.addArrangedSubview(self.bannerStackView!)
        }

        banner.displayAtTop(in: self.bannerStackView!)

        NSLayoutConstraint.activate([
            stackview.leadingAnchor.constraint(equalTo: self.superView!.leadingAnchor, constant: 0.0),
            stackview.trailingAnchor.constraint(equalTo: self.superView!.trailingAnchor, constant: 0.0),
            stackview.topAnchor.constraint(equalTo: self.superView!.topAnchor, constant: 0.0),
            stackview.bottomAnchor.constraint(equalTo: self.superView!.bottomAnchor, constant: 0.0),
            self.bannerStackView!.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: 0.0),
            self.bannerStackView!.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: 0.0),
        ])
    }

    @objc(hideBanner:)
    func hideBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!);
            return;
        }

        if (self.bannerSize != nil && self.bannerSize?["width"] != nil && self.bannerSize?["height"] != nil) {
            self.stackViewInlineBannerView?.removeFromSuperview()
            self.superView!.addSubview(webView)

            NSLayoutConstraint.activate([
                webView.leadingAnchor.constraint(equalTo: self.superView!.leadingAnchor, constant: 0.0),
                webView.trailingAnchor.constraint(equalTo: self.superView!.trailingAnchor, constant: 0.0),
                webView.topAnchor.constraint(equalTo: self.superView!.topAnchor, constant: 0.0),
                webView.bottomAnchor.constraint(equalTo: self.superView!.bottomAnchor, constant: 0.0),
            ])
        } else {
            self.getBannerAdView().removeFromSuperview()
        }

        self.getBannerAdView().delegate = nil
        self.bannerAdViewCache = nil

        self.sendResult(command: command);
    }
}

extension YandexAdsPlugin: BannerAdViewDelegate { // FIXME_SDK8: Auto-generated during migration, please review.
    func bannerAdViewDidLoad(_ bannerAdView: BannerAdView) { // FIXME_SDK8: Auto-generated during migration, please review.
        if (self.bannerReloaded == nil || self.bannerReloaded == false) {
            self.emitWindowEvent(event: EVENT_BANNER_DID_LOAD)
        }
        self.bannerReloaded = false
    }

    func bannerAdViewDidClick(_ bannerAdView: BannerAdView) { // FIXME_SDK8: Auto-generated during migration, please review.
        self.emitWindowEvent(event: EVENT_BANNER_DID_CLICK)
    }

    func bannerAdView(_ bannerAdView: BannerAdView, didTrackImpression impressionData: ImpressionData?) { // FIXME_SDK8: Auto-generated during migration, please review.
        self.emitWindowEvent(event: EVENT_BANNER_DID_TRACK_IMPRESSION_WITH)
    }

    func bannerAdViewDidFailLoading(_ bannerAdView: BannerAdView, error: Error) { // FIXME_SDK8: Auto-generated during migration, please review.
        let data = ErrorData(message: error.localizedDescription)
        self.emitWindowEvent(event: EVENT_BANNER_FAILED_TO_LOAD, data: data)
    }
}
