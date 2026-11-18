import UIKit
import YandexMobileAds

let EVENT_BANNER_DID_LOAD = "bannerDidLoad"
let EVENT_BANNER_DID_CLICK = "bannerDidClick"
let EVENT_BANNER_DID_TRACK_IMPRESSION_WITH = "bannerDidTrackImpressionWith"
let EVENT_BANNER_FAILED_TO_LOAD = "bannerFailedToLoad"

// MARK: - Overview
//
// Mirrors Android BannerAdsHelper:
//  - banner is always INLINE: BannerAdSize.inline(width, maxHeight) from bannerSize
//  - overlap == false -> a black spacer shrinks the WebView
//  - the banner itself lives in a separate container of bannerSize (W x H),
//    centered horizontally at top/bottom and placed ABOVE everything (over the spacer)
//  - overlap == true  -> no spacer, the same container simply overlays the WebView
//
// Safe area:
//  - the banner container sits at the edge of the SAFE AREA (below the Dynamic Island /
//    notch for "top", above the Home indicator for "bottom") and is centered inside the
//    safe area horizontally (landscape notch on the side)
//  - the spacer spans from the SCREEN edge to the far edge of the banner:
//    height = safe area inset + banner height
//
// Relayout:
//  - an invisible full-screen BannerLayoutObserverView gets layoutSubviews /
//    safeAreaInsetsDidChange from UIKit on every rotation, resize or safe area change,
//    and recomputes the frames of spacer, WebView and banner container.

/// Invisible full-size view that reports every layout / safe area change.
final class BannerLayoutObserverView: UIView {
    var onLayout: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        onLayout?()
    }
}

// MARK: - State (Swift extensions cannot add stored properties, so we use file-level backing)

private var _bannerPosition: String = "bottom"
private var _bannerWidth: CGFloat = 0
private var _bannerHeight: CGFloat = 0
private var _overlap: Bool = false
private var _bannerLoaded: Bool = false
private var _bannerShown: Bool = false
private var _bannerContainer: UIView?
private var _spacerView: UIView?
private var _layoutObserver: BannerLayoutObserverView?
private var _isRelayouting: Bool = false

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

        // Never request an ad wider than the safe area: window.innerWidth from JS is the full
        // WebView width (including notch / camera areas), and on devices with asymmetric
        // insets (e.g. iPhone Duo outer display) a wider banner would stick out past the edge.
        if let parent = webViewParent {
            let insets = parent.safeAreaInsets
            let safeWidth = parent.bounds.width - insets.left - insets.right
            if safeWidth > 0 {
                _bannerWidth = min(_bannerWidth, safeWidth)
            }
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

        // Container W x H; its frame is set in relayoutBanner()
        let container = UIView()
        container.backgroundColor = .clear
        container.autoresizingMask = []
        // If the available width shrinks after load (fold / unfold, Split View),
        // clip the banner to the container instead of letting it go past the screen edge
        container.clipsToBounds = true
        parent.addSubview(container)
        _bannerContainer = container

        // Banner centered inside the container (Android: CENTER_IN_PARENT)
        banner.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            banner.centerYAnchor.constraint(equalTo: container.centerYAnchor),
        ])

        ensureLayoutObserver()
        relayoutBanner()

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

        let spacer = UIView()
        spacer.backgroundColor = .black
        spacer.autoresizingMask = []
        parent.addSubview(spacer)
        _spacerView = spacer

        ensureLayoutObserver()
        relayoutBanner()
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

    // MARK: - Safe-area aware layout

    private func ensureLayoutObserver() {
        guard _layoutObserver == nil, let parent = webViewParent else { return }

        let observer = BannerLayoutObserverView(frame: parent.bounds)
        observer.onLayout = { [weak self] in
            self?.relayoutBanner()
        }
        // Behind everything, never intercepts touches
        parent.insertSubview(observer, at: 0)
        _layoutObserver = observer
    }

    private func removeLayoutObserver() {
        _layoutObserver?.onLayout = nil
        _layoutObserver?.removeFromSuperview()
        _layoutObserver = nil
    }

    /// Recomputes spacer, WebView and banner container frames from the current
    /// bounds and safe area insets. Called on show, on spacer creation and by the
    /// layout observer on every rotation / resize / safe area change.
    private func relayoutBanner() {
        guard !_isRelayouting, let parent = webViewParent else { return }
        guard _spacerView != nil || _bannerContainer != nil else { return }

        _isRelayouting = true
        defer { _isRelayouting = false }

        let bounds = parent.bounds
        let insets = (_layoutObserver ?? parent).safeAreaInsets

        // Inset on the banner's edge: Dynamic Island / notch (top) or Home indicator (bottom)
        let edgeInset = isTop ? insets.top : insets.bottom
        let bannerH = min(_bannerHeight, max(0, bounds.height - edgeInset))

        // Spacer: from the screen edge to the far edge of the banner
        if let spacer = _spacerView {
            let reserved = min(bannerH + edgeInset, bounds.height)
            if isTop {
                spacer.frame = CGRect(x: 0, y: 0, width: bounds.width, height: reserved)
                webView.frame = CGRect(x: 0, y: reserved, width: bounds.width, height: bounds.height - reserved)
            } else {
                spacer.frame = CGRect(x: 0, y: bounds.height - reserved, width: bounds.width, height: reserved)
                webView.frame = CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height - reserved)
            }
        }

        // Banner container: at the safe area edge, centered inside the safe area horizontally
        if let container = _bannerContainer {
            let safeX = insets.left
            let safeW = max(0, bounds.width - insets.left - insets.right)
            let bannerW = min(_bannerWidth, safeW)
            container.frame = CGRect(
                x: safeX + (safeW - bannerW) / 2,
                y: isTop ? edgeInset : bounds.height - edgeInset - bannerH,
                width: bannerW,
                height: bannerH
            )
            parent.bringSubviewToFront(container)
        }
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

        removeLayoutObserver()
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
