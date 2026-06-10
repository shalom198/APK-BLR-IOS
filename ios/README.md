# HeatMind — גרסת iOS

פורט של אפליקציית האנדרואיד HeatMind לאייפון. הגישה: אותה מעטפת WebView כמו
באנדרואיד — `WKWebView` טוען את **אותם** קבצי `login.html` / `espConfig.html`
(מתוך `app/src/main/assets/`, מקור אמת אחד לשתי הפלטפורמות), ושכבת Swift קצרה
מממשת מחדש את גשר ה-JavaScript.

---

## דרישות מקדימות (חובה)

1. **Mac** (פיזי או בענן: MacinCloud / MacStadium / AWS EC2 Mac) עם **Xcode 15+**.
2. **חשבון Apple Developer** ($99/שנה) — נדרש להרשאות WiFi ולהתקנה על מכשיר.
3. **XcodeGen** — יוצר את קובץ הפרויקט מתוך `project.yml`:
   ```bash
   brew install xcodegen
   ```

> ⚠️ אי אפשר לבנות iOS על Windows. את הקוד כותבים בכל מקום, אבל הבנייה,
> החתימה וההתקנה חייבות להתבצע על Mac.

---

## בנייה — צעד אחר צעד (על ה-Mac)

```bash
cd ios
xcodegen generate          # יוצר את HeatMind.xcodeproj מתוך project.yml
open HeatMind.xcodeproj
```

בתוך Xcode:

1. בחר את ה-target **HeatMind** → לשונית **Signing & Capabilities**.
2. סמן **Automatically manage signing** ובחר את ה-**Team** שלך.
3. ודא ששתי היכולות מופיעות (אם לא — הוסף עם **+ Capability**):
   - **Hotspot Configuration**
   - **Access WiFi Information**
4. בחר מכשיר/סימולטור ולחץ **Run** (▶).

> אם Xcode מתלונן על entitlements — בדוק שב-Apple Developer Portal מזהה האפליקציה
> (`com.heatmind.app`) כולל את שתי היכולות הנ"ל. בחתימה אוטומטית Xcode בד"כ מסדר זאת לבד.

---

## התקנה על אייפון אמיתי

האייפון מחובר פיזית למחשב שלך (Windows), לא ל-Mac בענן — לכן ההתקנה נעשית דרך
**TestFlight**:

1. ב-Xcode: **Product → Archive**.
2. **Distribute App → App Store Connect → Upload**.
3. ב-[App Store Connect](https://appstoreconnect.apple.com) → TestFlight → הוסף את עצמך כ-Tester.
4. התקן את אפליקציית **TestFlight** מה-App Store באייפון → התקן את HeatMind.

---

## מה עובד ומה שונה מאנדרואיד

| יכולת | iOS |
|------|-----|
| טעינת ה-HTML והרצת כל ה-UI | ✅ זהה |
| `espHttpGet` / `espHttpPost` (תקשורת עם הבקר) | ✅ `ESPClient` (URLSession, `allowsCellularAccess=false` מאלץ WiFi) |
| חיבור ל-AP של ה-ESP (`espConfig`/`brand2016`) | ✅ `NEHotspotConfiguration` (מציג אישור מערכת) |
| קריאת ה-SSID הנוכחי | ✅ `NEHotspotNetwork.fetchCurrent` (דורש הרשאת מיקום) |
| **סריקת רשתות WiFi** | ⚠️ אין API ב-iOS — מחזירים את ה-AP הידוע בלבד (`syntheticScanJSON`). בחירת רשת ביתית: הזנה ידנית או דרך סריקת ה-ESP עצמו (להחלטה) |
| **עדכון עצמי (`installUpdate`)** | ❌ אין מקביל — עדכונים דרך App Store / TestFlight (no-op) |
| מעבר אוטומטי בין רשתות | ⚠️ תמיד עם אישור מערכת |

---

## הערה חשובה לבדיקות

`NEHotspotConfiguration` **לא עובד בסימולטור** — רק על אייפון אמיתי. בסימולטור
אפשר לבדוק את כל המסכים/העיצוב, אבל את החיבור האמיתי לבוילר חובה לבדוק על מכשיר
פיזי (דרך TestFlight).

---

## מבנה הקבצים

```
ios/
├── project.yml                      # מפרט XcodeGen (יוצר את .xcodeproj)
├── README.md
└── HeatMind/
    ├── Sources/
    │   ├── AppDelegate.swift         # נקודת כניסה
    │   ├── SceneDelegate.swift       # יוצר חלון + WebViewController ראשי
    │   ├── WebViewController.swift    # מארח WKWebView + גשר prompt + כפתור חזרה
    │   ├── NativeBridge.swift         # ניתוב מתודות הגשר → מימוש נייטיבי
    │   ├── WiFiManager.swift          # NEHotspotConfiguration / fetchCurrent / IP
    │   └── ESPClient.swift            # HTTP אל ה-ESP (מאולץ על WiFi)
    └── Resources/
        ├── Info.plist
        ├── HeatMind.entitlements      # Hotspot Configuration + Access WiFi Information
        └── Bridge.js                  # shim שמגדיר AndroidInterface / Android ב-JS
```

קבצי `login.html`, `espConfig.html`, `heatmind_app_icon_512.png` נטענים ישירות
מ-`app/src/main/assets/` (משותפים עם אנדרואיד) דרך `project.yml`.

---

## איך הגשר עובד (לתחזוקה עתידית)

ב-JS, `Bridge.js` מגדיר את `window.AndroidInterface` ו-`window.Android` עם אותם
שמות מתודות כמו באנדרואיד. כל קריאה הופכת ל-`window.prompt("HMBRIDGE::" + payload)`.
`WebViewController` (כ-`WKUIDelegate`) מזהה את הקידומת, מעביר ל-`NativeBridge`,
שמבצע את העבודה ומחזיר ערך מקודד JSON — וה-JS ממשיך כאילו הקריאה הייתה סינכרונית.
זהה התנהגותית ל-`@JavascriptInterface` הסינכרוני של אנדרואיד.
