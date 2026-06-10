import Foundation
import UIKit
import WebKit

/// מנתב קריאות מה-JS (דרך גשר ה-prompt) למימושים הנייטיביים.
/// מקביל למתודות @JavascriptInterface ב-HtmlViewerActivity.
final class NativeBridge {

    static let prefix = "HMBRIDGE::"

    private weak var webView: WKWebView?
    private weak var presenter: BridgePresenter?
    private let wifi = WiFiManager.shared
    private let esp = ESPClient()

    init(webView: WKWebView, presenter: BridgePresenter) {
        self.webView = webView
        self.presenter = presenter
    }

    /// מקבל payload בפורמט {"iface","method","args":[...]} ומחזיר תוצאה מקודדת ב-JSON.
    /// completion עשוי להיקרא אסינכרונית (למשל אחרי קריאת רשת) — ה-JS חסום עד אז.
    func handle(payload: String, completion rawCompletion: @escaping (String?) -> Void) {
        // ה-completionHandler של prompt ב-WKWebView חייב להיקרא ב-main thread;
        // חלק מהתשובות חוזרות מ-background (URLSession / fetchCurrent), לכן עוטפים.
        let completion: (String?) -> Void = { result in
            if Thread.isMainThread { rawCompletion(result) }
            else { DispatchQueue.main.async { rawCompletion(result) } }
        }

        guard let data = payload.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let method = obj["method"] as? String else {
            completion("null")
            return
        }
        let args = obj["args"] as? [Any] ?? []

        func str(_ i: Int) -> String {
            guard i < args.count else { return "" }
            if let s = args[i] as? String { return s }
            if args[i] is NSNull { return "" }
            return String(describing: args[i])
        }
        func optStr(_ i: Int) -> String? {
            guard i < args.count, !(args[i] is NSNull) else { return nil }
            return args[i] as? String
        }

        switch method {

        // MARK: HTTP אל ה-ESP
        case "espHttpGet":
            esp.get(str(0)) { body in completion(Self.encode(body)) }
        case "espHttpPost":
            esp.post(str(0), body: str(1)) { body in completion(Self.encode(body)) }

        // MARK: מידע רשת נוכחית
        case "getCurrentNetworkInfo", "getCurrentWifi":
            wifi.currentNetworkInfoJSON { json in completion(Self.encode(json)) }
        case "getCurrentWiFiSSID":
            wifi.currentSSID { ssid in completion(Self.encode(ssid ?? "")) }
        case "getCurrentIP":
            completion(Self.encode(wifi.currentIPv4() ?? "0.0.0.0"))

        // MARK: סריקה — אין API ציבורי ב-iOS, מחזירים את ה-AP הידוע של ה-ESP
        case "getAvailableNetworks", "scanForNearbyNetworks":
            completion(Self.encode(wifi.syntheticScanJSON()))

        // MARK: חיבור WiFi
        case "connectToWifi":
            let ssid = str(0)
            let pass = str(1)
            let opts = optStr(2)
            wifi.connect(ssid: ssid, passphrase: pass, optionsJSON: opts) { [weak self] success in
                self?.notifyWifi(success: success, ssid: ssid)
            }
            completion("null") // fire-and-forget; התוצאה מגיעה דרך window.onWifiConnected
        case "reconnectToKnownNetwork":
            let ssid = str(0)
            let handled = wifi.reconnect(ssid: ssid) { [weak self] success in
                self?.notifyWifi(success: success, ssid: ssid)
            }
            completion(handled ? "true" : "false")
        case "disconnectFromCurrentNetwork":
            wifi.disconnectESP()
            completion("true")
        case "releaseEspBinding", "bindToCurrentWifi":
            // ב-iOS ה"binding" מטופל ע"י allowsCellularAccess=false ב-ESPClient — אין מה לעשות.
            completion("null")

        // MARK: הרשאות
        case "hasWifiScanPermissions":
            completion(wifi.hasLocationPermission() ? "true" : "false")
        case "requestScanPermissions":
            wifi.requestLocationPermission()
            completion("true")

        // MARK: פעולות UI
        case "openWifiSettings":
            DispatchQueue.main.async { self.presenter?.openWifiSettings() }
            completion("null")
        case "openNewWindow":
            let u = str(0)
            DispatchQueue.main.async { self.presenter?.openNewWindow(u) }
            completion("null")

        // MARK: עדכון אפליקציה — אין מקביל ב-iOS (עדכונים דרך App Store / TestFlight)
        case "installUpdate":
            print("ℹ️ installUpdate לא נתמך ב-iOS — עדכונים מגיעים דרך App Store / TestFlight")
            completion("null")

        case "log":
            print("[JS] \(str(0))")
            completion("null")

        default:
            print("⚠️ מתודת גשר לא ידועה: \(method)")
            completion("null")
        }
    }

    private func notifyWifi(success: Bool, ssid: String) {
        let js = "if (typeof window.onWifiConnected === 'function') { window.onWifiConnected(\(success), \(Self.jsString(ssid))); }"
        DispatchQueue.main.async { self.webView?.evaluateJavaScript(js, completionHandler: nil) }
    }

    // MARK: - קידוד תוצאות

    /// ממיר ערך Swift לייצוג JSON שה-shim יפענח (JSON.parse).
    static func encode(_ value: Any?) -> String {
        switch value {
        case let b as Bool:
            return b ? "true" : "false"
        case let s as String:
            return jsString(s)
        case nil:
            return "null"
        default:
            return "null"
        }
    }

    /// מקודד מחרוזת ל-JSON string literal (עם מירכאות ובריחה).
    static func jsString(_ s: String) -> String {
        if let d = try? JSONEncoder().encode(s), let out = String(data: d, encoding: .utf8) {
            return out
        }
        return "\"\""
    }

    // MARK: - פענוח URL ל-openNewWindow

    static func resolveURL(_ s: String) -> URL? {
        if s.hasPrefix("http://") || s.hasPrefix("https://") {
            return URL(string: s)
        }
        // file:///android_asset/espConfig.html  או  "espConfig.html" → קובץ ב-bundle
        let name = (s as NSString).lastPathComponent
        let base = (name as NSString).deletingPathExtension
        var ext = (name as NSString).pathExtension
        if ext.isEmpty { ext = "html" }
        return Bundle.main.url(forResource: base, withExtension: ext)
    }
}
