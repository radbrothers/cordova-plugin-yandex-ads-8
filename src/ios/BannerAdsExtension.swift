import UIKit
import YandexMobileAds

let EVENT_BANNER_DID_LOAD = "bannerDidLoad"
let EVENT_BANNER_DID_CLICK = "bannerDidClick"
let EVENT_BANNER_DID_TRACK_IMPRESSION_WITH = "bannerDidTrackImpressionWith"
let EVENT_BANNER_FAILED_TO_LOAD = "bannerFailedToLoad"

// MARK: - Banner parameters stored via associated objects
// (Swift extensions cannot add stored properties, so we use static backing)

private var _bannerPosition: String = "bottom"
private var _bannerSizeParam: [String: Any]?
private var _overlap: Bool = false
private var _bannerLoaded: Bool = false
private var _bannerShown: Bool = false

extension YandexAdsPlugin {

    // MARK: - Accessors

    var bannerPosition: String {
        get { _bannerPosition }
        set { _bannerPosition = newValue }
    }
    var bannerSizeParam: [String: Any]? {
        get { _bannerSizeParam }
        set { _bannerSizeParam = newValue }
    }
    var overlap: Bool {
        get { _overlap }
        set { _overlap = newValue }
    }
    var bannerLoaded: Bool {
        get { _bannerLoaded }
        set { _bannerLoaded = newValue }
    }
    var bannerShown: Bool {
        get { _bannerShown }
        set { _bannerShown = newValue }
    }
    private var isInlineBanner: Bool {
        guard let size = bannerSizeParam,
              size["width"] != nil,
              size["height"] != nil else {
            return false
        }
        return true
    }

    // MARK: - Create BannerAdView

    private func createBannerAdView() -> BannerAdView {
        // Destroy previous
        bannerAdViewCache?.delegate = nil
        bannerAdViewCache = nil

        let adSize: BannerAdSize
        if let size = bannerSizeParam,
           let w = size["width"] as? CGFloat,
           let h = size["height"] as? CGFloat {
            adSize = BannerAdSize.inline(width: w, maxHeight: h)
        } else {
            var width = self.webView.safeAreaLayoutGuide.layoutFrame.width
            if width <= 0 {
                // Fallback: webView layout may not be ready yet (e.g. after orientation change)
                width = UIScreen.main.bounds.width
            }
            adSize = BannerAdSize.sticky(containerWidth: width)
        }

        let adView = BannerAdView(adSize: adSize)
        adView.delegate = self
        adView.translatesAutoresizingMaskIntoConstraints = false
        bannerAdViewCache = adView
        return adView
    }

    // MARK: - Load

    @objc(loadBanner:)
    func loadBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil || self.bannerBlockId!.isEmpty {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!)
            return
        }

        // Parse params from JS — same structure as Android:
        // { bannerPosition: "top"|"bottom", bannerSize: {width, height}, overlap: bool }
        if let options = command.arguments?.first as? [String: Any] {
            bannerPosition = options["bannerPosition"] as? String ?? "bottom"
            bannerSizeParam = options["bannerSize"] as? [String: Any]
            overlap = options["overlap"] as? Bool ?? false
        }

        bannerLoaded = false
        bannerShown = false

        // For inline (non-overlap) banner, create the spacer layout BEFORE loading
        // so WebView is already resized when the ad arrives.
        if isInlineBanner && !overlap && stackViewInlineBannerView == nil {
            createSpacer()
        }

        let banner = createBannerAdView()
        banner.loadAd(with: AdRequest(adUnitID: self.bannerBlockId!))

        self.sendResult(command: command)
    }

    // MARK: - Show

    @objc(showBanner:)
    func showBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil || self.bannerBlockId!.isEmpty {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!)
            return
        }

        guard let banner = self.bannerAdViewCache, bannerLoaded, !bannerShown else {
            self.sendResult(command: command)
            return
        }

        bannerShown = true

        if isInlineBanner && !overlap {
            showInlineBanner(banner: banner)
        } else {
            showOverlapBanner(banner: banner)
        }

        self.sendResult(command: command)
    }

    // MARK: - Overlap banner (overlay on top of WebView)

    private func showOverlapBanner(banner: BannerAdView) {
        if bannerPosition == "top" {
            banner.displayAtTop(in: webView)
        } else {
            banner.displayAtBottom(in: webView)
        }
    }

    // MARK: - Inline banner (push WebView with spacer)

    private func createSpacer() {
        guard let superView = self.superView else { return }

        let stackview = UIStackView()
        stackview.axis = .vertical
        stackview.distribution = .fill
        stackview.translatesAutoresizingMaskIntoConstraints = false
        self.stackViewInlineBannerView = stackview

        let spacer = UIView()
        spacer.backgroundColor = .black
        spacer.translatesAutoresizingMaskIntoConstraints = false
        self.bannerStackView = spacer

        superView.addSubview(stackview)
        webView.removeFromSuperview()

        // UIStackView manages arranged subviews layout —
        // autoresizingMask must be off to avoid conflicting constraints
        webView.translatesAutoresizingMaskIntoConstraints = false

        if bannerPosition == "top" {
            stackview.addArrangedSubview(spacer)
            stackview.addArrangedSubview(webView)
        } else {
            stackview.addArrangedSubview(webView)
            stackview.addArrangedSubview(spacer)
        }

        var constraints = [
            stackview.leadingAnchor.constraint(equalTo: superView.leadingAnchor),
            stackview.trailingAnchor.constraint(equalTo: superView.trailingAnchor),
            stackview.topAnchor.constraint(equalTo: superView.topAnchor),
            stackview.bottomAnchor.constraint(equalTo: superView.bottomAnchor),
        ]

        // Fix spacer height from bannerSize
        if let size = bannerSizeParam,
           let h = size["height"] as? CGFloat {
            constraints.append(spacer.heightAnchor.constraint(equalToConstant: h))
        }

        NSLayoutConstraint.activate(constraints)
    }

    private func showInlineBanner(banner: BannerAdView) {
        guard let spacer = self.bannerStackView else { return }

        // Documentation: add banner via addSubview + constraints, not displayAtTop
        spacer.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.leadingAnchor.constraint(equalTo: spacer.leadingAnchor),
            banner.trailingAnchor.constraint(equalTo: spacer.trailingAnchor),
            banner.topAnchor.constraint(equalTo: spacer.topAnchor),
        ])
    }

    // MARK: - Hide

    @objc(hideBanner:)
    func hideBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil || self.bannerBlockId!.isEmpty {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!)
            return
        }

        hideBannerView()
        self.sendResult(command: command)
    }

    private func hideBannerView() {
        bannerShown = false
        bannerLoaded = false

        // Remove overlay banner
        bannerAdViewCache?.removeFromSuperview()

        // Restore WebView from stack layout if inline
        if stackViewInlineBannerView != nil {
            stackViewInlineBannerView?.removeFromSuperview()
            stackViewInlineBannerView = nil
            bannerStackView = nil

            if let superView = self.superView {
                superView.addSubview(webView)
                // Restore Cordova's original layout: autoresizingMask, not constraints
                webView.translatesAutoresizingMaskIntoConstraints = true
                webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                webView.frame = superView.bounds
            }
        }

        // Destroy banner
        bannerAdViewCache?.delegate = nil
        bannerAdViewCache = nil
    }
}

// MARK: - BannerAdViewDelegate

@MainActor
extension YandexAdsPlugin: BannerAdViewDelegate {
    func bannerAdViewDidLoad(_ adView: BannerAdView) {
        bannerLoaded = true
        self.emitWindowEvent(event: EVENT_BANNER_DID_LOAD)
    }

    func bannerAdViewDidClick(_ adView: BannerAdView) {
        self.emitWindowEvent(event: EVENT_BANNER_DID_CLICK)
    }

    func bannerAdView(_ adView: BannerAdView, didTrackImpression impressionData: ImpressionData?) {
        self.emitWindowEvent(event: EVENT_BANNER_DID_TRACK_IMPRESSION_WITH)
    }

    func bannerAdViewDidFailLoading(_ adView: BannerAdView, error: Error) {
        let data = ErrorData(message: error.localizedDescription)
        self.emitWindowEvent(event: EVENT_BANNER_FAILED_TO_LOAD, data: data)
    }
}
