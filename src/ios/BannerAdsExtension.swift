import UIKit
import YandexMobileAds

let EVENT_BANNER_DID_LOAD = "bannerDidLoad"
let EVENT_BANNER_DID_CLICK = "bannerDidClick"
let EVENT_BANNER_DID_TRACK_IMPRESSION_WITH = "bannerDidTrackImpressionWith"
let EVENT_BANNER_FAILED_TO_LOAD = "bannerFailedToLoad"

// MARK: - State (Swift extensions cannot add stored properties, so we use file-level backing)
//
// Mirrors Android BannerAdsHelper:
//  - banner is always INLINE: BannerAdSize.inline(width, maxHeight) from bannerSize
//  - overlap == false -> a black spacer of bannerSize.height shrinks the WebView
//  - the banner itself lives in a separate container of bannerSize (W x H),
//    centered horizontally at top/bottom and placed ABOVE everything (over the spacer)
//  - overlap == true  -> no spacer, the same container simply overlays the WebView
//
// Layout uses frames + autoresizingMask (not Auto Layout), so spacer, WebView and
// container follow orientation changes automatically without constraint conflicts.

private var _bannerPosition: String = "bottom"
private var _bannerWidth: CGFloat = 0
private var _bannerHeight: CGFloat = 0
private var _overlap: Bool = false
private var _bannerLoaded: Bool = false
private var _bannerShown: Bool = false
private var _bannerContainer: UIView?
private var _spacerView: UIView?

// Original WebView layout, saved before the spacer shrinks it
private var _savedWebViewMask: UIView.AutoresizingMask = [.flexibleWidth, .flexibleHeight]
private var _savedWebViewUsedConstraints: Bool = false
private var _savedWebViewConstraints: [NSLayoutConstraint] = []

let BANNER_POSITION_TOP = "top"
let BANNER_POSITION_BOTTOM = "bottom"

extension YandexAdsPlugin {

    private var isTop: Bool { _bannerPosition == BANNER_POSITION_TOP }

    private var webViewParent: UIView? { webView.superview ?? superView }

    // MARK: - Load

    @objc(loadBanner:)
    func loadBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil || self.bannerBlockId!.isEmpty {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!)
            return
        }

        // { bannerPosition: "top"|"bottom", bannerSize: {width, height}, overlap: bool }
        if let options = command.arguments?.first as? [String: Any] {
            _bannerPosition = (options["bannerPosition"] as? String) == BANNER_POSITION_TOP
                ? BANNER_POSITION_TOP : BANNER_POSITION_BOTTOM
            let size = options["bannerSize"] as? [String: Any]
            _bannerWidth = CGFloat((size?["width"] as? NSNumber)?.doubleValue ?? 0)
            _bannerHeight = CGFloat((size?["height"] as? NSNumber)?.doubleValue ?? 0)
            _overlap = options["overlap"] as? Bool ?? false
        }

        if _bannerWidth <= 0 || _bannerHeight <= 0 {
            self.sendError(command: command, code: "invalid_banner_size", message: "bannerSize.width and bannerSize.height (in points) are required")
            return
        }

        _bannerLoaded = false
        _bannerShown = false

        destroyBanner()

        if !_overlap && _spacerView == nil {
            createSpacer()
        }

        let adView = BannerAdView(adSize: BannerAdSize.inline(width: _bannerWidth, maxHeight: _bannerHeight))
        adView.delegate = self
        bannerAdViewCache = adView

        adView.loadAd(with: AdRequest(adUnitID: self.bannerBlockId!))

        self.sendResult(command: command)
    }

    // MARK: - Show

    @objc(showBanner:)
    func showBanner(command: CDVInvokedUrlCommand) {
        if self.bannerBlockId == nil || self.bannerBlockId!.isEmpty {
            self.sendError(command: command, code: PLUGIN_NOT_INITIALIZED_ERROR["code"]!, message: PLUGIN_NOT_INITIALIZED_ERROR["message"]!)
            return
        }

        guard let banner = bannerAdViewCache, _bannerLoaded, !_bannerShown,
              let parent = webViewParent else {
            self.sendResult(command: command)
            return
        }

        _bannerShown = true

        // Container W x H, centered horizontally, glued to top or bottom (Android: gravity)
        let bounds = parent.bounds
        let w = min(_bannerWidth, bounds.width)
        let h = min(_bannerHeight, bounds.height)
        let container = UIView(frame: CGRect(
            x: (bounds.width - w) / 2,
            y: isTop ? 0 : bounds.height - h,
            width: w,
            height: h
        ))
        container.backgroundColor = .clear
        container.autoresizingMask = isTop
            ? [.flexibleLeftMargin, .flexibleRightMargin, .flexibleBottomMargin]
            : [.flexibleLeftMargin, .flexibleRightMargin, .flexibleTopMargin]

        parent.addSubview(container)
        parent.bringSubviewToFront(container)
        _bannerContainer = container

        // Banner centered inside the container (Android: CENTER_IN_PARENT)
        banner.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            banner.centerYAnchor.constraint(equalTo: container.centerYAnchor),
        ])

        self.sendResult(command: command)
    }

    // MARK: - Spacer (shrinks WebView, overlap == false)

    private func createSpacer() {
        guard let parent = webViewParent else { return }

        // Save WebView layout. If it is constraint-based, switch it to frames for the
        // time the spacer exists, and restore the original constraints on hide.
        _savedWebViewMask = webView.autoresizingMask
        _savedWebViewUsedConstraints = !webView.translatesAutoresizingMaskIntoConstraints
        _savedWebViewConstraints = []
        if _savedWebViewUsedConstraints {
            _savedWebViewConstraints = parent.constraints.filter {
                $0.firstItem === webView || $0.secondItem === webView
            }
            NSLayoutConstraint.deactivate(_savedWebViewConstraints)
            webView.translatesAutoresizingMaskIntoConstraints = true
        }

        let bounds = parent.bounds
        let h = min(_bannerHeight, bounds.height)

        let spacer = UIView()
        spacer.backgroundColor = .black

        if isTop {
            spacer.frame = CGRect(x: 0, y: 0, width: bounds.width, height: h)
            spacer.autoresizingMask = [.flexibleWidth, .flexibleBottomMargin]
            webView.frame = CGRect(x: 0, y: h, width: bounds.width, height: bounds.height - h)
        } else {
            spacer.frame = CGRect(x: 0, y: bounds.height - h, width: bounds.width, height: h)
            spacer.autoresizingMask = [.flexibleWidth, .flexibleTopMargin]
            webView.frame = CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height - h)
        }
        // Fixed margin on the spacer side + flexible height -> gap is kept on rotation
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        parent.addSubview(spacer)
        _spacerView = spacer
    }

    private func removeSpacer() {
        guard let spacer = _spacerView else { return }
        spacer.removeFromSuperview()
        _spacerView = nil

        guard let parent = webViewParent else { return }

        webView.frame = parent.bounds
        webView.autoresizingMask = _savedWebViewMask

        if _savedWebViewUsedConstraints {
            webView.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate(_savedWebViewConstraints)
        }
        _savedWebViewConstraints = []
        _savedWebViewUsedConstraints = false
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
        _bannerShown = false
        _bannerLoaded = false

        removeSpacer()
        destroyBanner()
    }

    private func destroyBanner() {
        bannerAdViewCache?.removeFromSuperview()
        bannerAdViewCache?.delegate = nil
        bannerAdViewCache = nil

        _bannerContainer?.removeFromSuperview()
        _bannerContainer = nil
    }
}

// MARK: - BannerAdViewDelegate

@MainActor
extension YandexAdsPlugin: BannerAdViewDelegate {
    func bannerAdViewDidLoad(_ adView: BannerAdView) {
        _bannerLoaded = true
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
