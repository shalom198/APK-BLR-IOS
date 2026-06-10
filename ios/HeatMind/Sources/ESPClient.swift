import Foundation

/// תקשורת HTTP ישירה עם בקר ה-ESP.
///
/// מקביל ל-espHttpGet/espHttpPost הנייטיביים באנדרואיד. שם הבעיה הייתה
/// ש-WebView לא מכבד את bindProcessToNetwork; כאן הפתרון המקביל הוא
/// allowsCellularAccess=false — מאלץ את התעבורה אל ה-ESP לעבור על ה-WiFi
/// (ה-AP של ה-ESP ללא אינטרנט), ולא דרך הסלולר.
final class ESPClient {

    private let session: URLSession
    private static let timeoutSec: TimeInterval = 7

    init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.allowsCellularAccess = false
        cfg.timeoutIntervalForRequest = ESPClient.timeoutSec
        cfg.timeoutIntervalForResource = ESPClient.timeoutSec
        cfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
    }

    func get(_ urlString: String, completion: @escaping (String) -> Void) {
        guard let url = URL(string: urlString) else {
            completion(Self.errorJSON("bad_url", urlString)); return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        run(req, completion)
    }

    func post(_ urlString: String, body: String, completion: @escaping (String) -> Void) {
        guard let url = URL(string: urlString) else {
            completion(Self.errorJSON("bad_url", urlString)); return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body.data(using: .utf8)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        run(req, completion)
    }

    private func run(_ req: URLRequest, _ completion: @escaping (String) -> Void) {
        session.dataTask(with: req) { data, resp, error in
            if let error = error {
                completion(Self.errorJSON("exception", error.localizedDescription))
                return
            }
            guard let http = resp as? HTTPURLResponse else {
                completion(Self.errorJSON("no_response", "אין תגובה מהבקר"))
                return
            }
            let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if (200...299).contains(http.statusCode) {
                completion(body)
            } else {
                completion(Self.errorJSON("http_\(http.statusCode)", "ESP החזיר קוד \(http.statusCode): \(body)"))
            }
        }.resume()
    }

    static func errorJSON(_ code: String, _ message: String) -> String {
        let dict: [String: String] = ["error": code, "message": message]
        if let d = try? JSONSerialization.data(withJSONObject: dict),
           let s = String(data: d, encoding: .utf8) {
            return s
        }
        return "{\"error\":\"\(code)\"}"
    }
}
