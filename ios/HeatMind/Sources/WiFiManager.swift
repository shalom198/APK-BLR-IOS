import Foundation
import NetworkExtension
import CoreLocation
import SystemConfiguration.CaptiveNetwork

/// ניהול WiFi באייפון — חיבור ל-AP של ה-ESP, קריאת הרשת הנוכחית, והרשאות.
///
/// הבדלים מהותיים מאנדרואיד:
///  • אין סריקת רשתות (אין API ציבורי) — מחזירים את ה-AP הידוע בלבד.
///  • החיבור נעשה דרך NEHotspotConfiguration ומציג למשתמש אישור מערכת.
final class WiFiManager: NSObject, CLLocationManagerDelegate {

    static let shared = WiFiManager()

    private let location = CLLocationManager()

    // ה-AP הקבוע של ה-ESP (מתוך espConfig.html)
    private static let espApSSID = "espConfig"
    private static let espApPass = "brand2016"

    private override init() {
        super.init()
        location.delegate = self
    }

    // MARK: - חיבור

    func connect(ssid: String, passphrase: String, optionsJSON: String?, completion: @escaping (Bool, String) -> Void) {
        let config: NEHotspotConfiguration
        if passphrase.isEmpty {
            config = NEHotspotConfiguration(ssid: ssid)
        } else {
            config = NEHotspotConfiguration(ssid: ssid, passphrase: passphrase, isWEP: false)
        }
        config.joinOnce = false

        NEHotspotConfigurationManager.shared.apply(config) { error in
            if let nsErr = error as NSError? {
                // "כבר מחובר" נחשב הצלחה.
                if nsErr.domain == NEHotspotConfigurationErrorDomain,
                   nsErr.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
                    completion(true, "alreadyAssociated")
                } else {
                    completion(false, "code \(nsErr.code): \(nsErr.localizedDescription)")
                }
            } else {
                completion(true, "applied")
            }
        }
    }

    /// ב-iOS אי אפשר לתשאל רשתות שמורות. עבור ה-AP הידוע של ה-ESP נחבר מחדש;
    /// אחרת מחזירים false וה-HTML ימשיך בזרימת הסיסמה שלו.
    func reconnect(ssid: String, completion: @escaping (Bool) -> Void) -> Bool {
        if ssid == WiFiManager.espApSSID {
            connect(ssid: ssid, passphrase: WiFiManager.espApPass, optionsJSON: nil) { ok, _ in completion(ok) }
            return true
        }
        return false
    }

    func disconnectESP() {
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: WiFiManager.espApSSID)
    }

    // MARK: - רשת נוכחית

    func currentSSID(_ completion: @escaping (String?) -> Void) {
        if #available(iOS 14.0, *) {
            NEHotspotNetwork.fetchCurrent { network in
                completion(network?.ssid)
            }
        } else {
            completion(WiFiManager.legacySSID())
        }
    }

    func currentNetworkInfoJSON(_ completion: @escaping (String) -> Void) {
        currentSSID { ssid in
            let dict: [String: Any] = [
                "ssid": ssid ?? "",
                "type": "WiFi",
                "signal": "0"
            ]
            let s = (try? JSONSerialization.data(withJSONObject: dict))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            completion(s)
        }
    }

    /// ל-iOS אין סריקת WiFi ציבורית. מציגים את ה-AP הידוע של ה-ESP כדי שממשק
    /// "הקש כדי להתחבר" הקיים ימשיך לעבוד.
    func syntheticScanJSON() -> String {
        let arr: [[String: Any]] = [[
            "ssid": WiFiManager.espApSSID,
            "rssi": -50,
            "level": -50,
            "capabilities": "[WPA2-PSK-CCMP]",
            "isSecure": true,
            "signalStrength": "מעולה"
        ]]
        return (try? JSONSerialization.data(withJSONObject: arr))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    // MARK: - הרשאות

    func hasLocationPermission() -> Bool {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = location.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    func requestLocationPermission() {
        location.requestWhenInUseAuthorization()
    }

    // MARK: - כתובת IP (ממשק en0 = WiFi)

    func currentIPv4() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = ptr {
            let interface = cur.pointee
            let family = interface.ifa_addr.pointee.sa_family
            if family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name == "en0" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface.ifa_addr,
                                socklen_t(interface.ifa_addr.pointee.sa_len),
                                &hostname, socklen_t(hostname.count),
                                nil, 0, NI_NUMERICHOST)
                    address = String(cString: hostname)
                }
            }
            ptr = interface.ifa_next
        }
        return address
    }

    // MARK: - SSID לגרסאות ישנות (לפני iOS 14)

    private static func legacySSID() -> String? {
        guard let interfaces = CNCopySupportedInterfaces() as? [String] else { return nil }
        for name in interfaces {
            if let info = CNCopyCurrentNetworkInfo(name as CFString) as? [String: Any],
               let ssid = info[kCNNetworkInfoKeySSID as String] as? String {
                return ssid
            }
        }
        return nil
    }
}
