import UIKit
import CoreLocation

class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?
    private let locationManager = CLLocationManager()

    func scene(_ scene: UIScene,
               willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        // הרשאת מיקום נדרשת כדי לקרוא את שם רשת ה-WiFi הנוכחית (fetchCurrent).
        locationManager.requestWhenInUseAuthorization()

        let window = UIWindow(windowScene: windowScene)
        let root = WebViewController(initialURL: nil) // nil => login.html (הדף הראשי)
        let nav = UINavigationController(rootViewController: root)
        nav.isNavigationBarHidden = true              // מקביל ל-NoActionBar באנדרואיד
        window.rootViewController = nav
        self.window = window
        window.makeKeyAndVisible()
    }
}
