import UIKit
import WebKit

/// פעולות UI שהגשר מבקש מה-Activity המארח (מקביל ל-runOnUiThread באנדרואיד).
protocol BridgePresenter: AnyObject {
    func openNewWindow(_ url: String)
    func openWifiSettings()
}

/// מקביל ל-HtmlViewerActivity: מארח WKWebView, מזריק את הגשר, ומציג כפתור "חזרה"
/// בדפי-משנה (שנפתחו דרך openNewWindow).
final class WebViewController: UIViewController, WKUIDelegate, WKNavigationDelegate, BridgePresenter {

    private var webView: WKWebView!
    private let initialURL: URL?      // nil => login.html
    private let isSubPage: Bool
    private var bridge: NativeBridge!

    init(initialURL: URL?) {
        self.initialURL = initialURL
        self.isSubPage = (initialURL != nil)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func loadView() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true

        // גישה לקבצים מקומיים (file://) — מקביל ל-allowFileAccessFromFileURLs באנדרואיד.
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        config.setValue(true, forKey: "allowUniversalAccessFromFileURLs")

        // הזרקת גשר התאימות לפני טעינת הדף.
        if let js = Self.loadBridgeJS() {
            let script = WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true)
            config.userContentController.addUserScript(script)
        }

        // ה-HTML המשותף בונה נתיב אנדרואיד (file:///android_asset/espConfig.html)
        // שנחסם ע"י ה-sandbox של iOS ולא מגיע ל-decidePolicyFor. דורסים את
        // getConfigPageUrl כך שתנווט לקובץ היחסי שבתוך חבילת האפליקציה.
        let fixNavJS = """
        window.getConfigPageUrl = function(userName) {
          return 'espConfig.html' + (userName ? '?userName=' + encodeURIComponent(userName) : '');
        };
        """
        config.userContentController.addUserScript(
            WKUserScript(source: fixNavJS, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        )

        webView = WKWebView(frame: .zero, configuration: config)
        webView.uiDelegate = self
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        bridge = NativeBridge(webView: webView, presenter: self)

        if isSubPage { addBackButton() }

        let url = initialURL ?? Self.assetURL("login", "html")
        guard let url = url else {
            print("⚠️ login.html לא נמצא ב-bundle")
            return
        }
        if url.isFileURL {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        } else {
            webView.load(URLRequest(url: url))
        }
    }

    // MARK: - Bundle helpers

    static func assetURL(_ name: String, _ ext: String) -> URL? {
        return Bundle.main.url(forResource: name, withExtension: ext)
    }

    private static func loadBridgeJS() -> String? {
        guard let url = Bundle.main.url(forResource: "Bridge", withExtension: "js"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return s
    }

    // MARK: - BridgePresenter

    func openNewWindow(_ urlString: String) {
        guard let url = NativeBridge.resolveURL(urlString) else {
            print("⚠️ openNewWindow: לא ניתן לפענח URL: \(urlString)")
            return
        }
        let vc = WebViewController(initialURL: url)
        navigationController?.pushViewController(vc, animated: true)
    }

    func openWifiSettings() {
        // iOS לא מאפשר לפתוח ישירות את עמוד ה-WiFi; פותחים את הגדרות האפליקציה.
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    // MARK: - כפתור חזרה צף (מקביל ל-createBackButton באנדרואיד)

    private func addBackButton() {
        let btn = UIButton(type: .system)
        btn.setTitle("↩ חזרה", for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 14)
        btn.backgroundColor = UIColor(red: 0x19/255.0, green: 0x76/255.0, blue: 0xD2/255.0, alpha: 0.8)
        btn.contentEdgeInsets = UIEdgeInsets(top: 8, left: 18, bottom: 8, right: 18)
        btn.layer.cornerRadius = 6
        btn.translatesAutoresizingMaskIntoConstraints = false
        btn.addTarget(self, action: #selector(goBackTapped), for: .touchUpInside)
        view.addSubview(btn)
        NSLayoutConstraint.activate([
            btn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            btn.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12)
        ])
    }

    @objc private func goBackTapped() {
        if webView.canGoBack {
            webView.goBack()
        } else {
            navigationController?.popViewController(animated: true)
        }
    }

    // MARK: - WKUIDelegate (גשר ה-prompt הסינכרוני)

    func webView(_ webView: WKWebView,
                 runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        if prompt.hasPrefix(NativeBridge.prefix) {
            let payload = String(prompt.dropFirst(NativeBridge.prefix.count))
            bridge.handle(payload: payload, completion: completionHandler)
        } else {
            // prompt() אמיתי מתוך ה-HTML — באנדרואיד (ללא WebChromeClient) מחזיר null.
            completionHandler(nil)
        }
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        // באנדרואיד (ללא WebChromeClient) alert() לא מציג כלום.
        completionHandler()
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        // באנדרואיד (ללא WebChromeClient) confirm() מחזיר false.
        completionHandler(false)
    }

    // MARK: - WKNavigationDelegate

    /// ה-HTML המשותף מנווט לנתיבי אנדרואיד כמו file:///android_asset/espConfig.html
    /// (למשל בלחיצה על "עדכן בקר סמוך פיזית"). ב-iOS אין נתיב כזה — מיירטים את
    /// הניווט ומפנים לקובץ המקביל בתוך ה-bundle, תוך שמירת ה-query (?userName=...).
    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        print("🧭 nav: \(navigationAction.request.url?.absoluteString ?? "nil")")
        if let url = navigationAction.request.url,
           url.isFileURL,
           url.absoluteString.contains("android_asset") {
            let base = url.deletingPathExtension().lastPathComponent
            var ext = url.pathExtension
            if ext.isEmpty { ext = "html" }
            print("➡️ redirect android_asset → bundle: \(base).\(ext)")
            if let bundleURL = Bundle.main.url(forResource: base, withExtension: ext) {
                decisionHandler(.cancel)
                var finalURL = bundleURL
                if let q = url.query, !q.isEmpty,
                   var comps = URLComponents(url: bundleURL, resolvingAgainstBaseURL: false) {
                    comps.query = q
                    finalURL = comps.url ?? bundleURL
                }
                print("   loading: \(finalURL.path)")
                webView.loadFileURL(finalURL, allowingReadAccessTo: bundleURL.deletingLastPathComponent())
                return
            } else {
                print("⚠️ bundle file not found for \(base).\(ext)")
            }
        }
        decisionHandler(.allow)
    }
}
