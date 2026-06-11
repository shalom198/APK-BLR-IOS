/*
 * Bridge.js — שכבת תאימות בין ה-HTML המשותף (login.html / espConfig.html)
 * לבין שכבת ה-Swift באייפון.
 *
 * באנדרואיד פונקציות הגשר (espHttpGet וכו') הן סינכרוניות: ה-JS קורא ומקבל
 * מחרוזת בחזרה מיד. ב-WKWebView אין מנגנון סינכרוני רגיל, ולכן אנו מנצלים את
 * window.prompt() כתעלה סינכרונית: ה-JS קורא ל-prompt() עם payload מקודד,
 * שכבת ה-Swift (WKUIDelegate) מבצעת את העבודה ומחזירה ערך, וה-JS ממשיך.
 *
 * זה זהה התנהגותית ל-@JavascriptInterface הסינכרוני של אנדרואיד.
 */
(function () {
    if (window.__HEATMIND_BRIDGE__) return;
    window.__HEATMIND_BRIDGE__ = true;

    var PREFIX = "HMBRIDGE::";

    function call(ifaceName, method, argsLike) {
        var payload = JSON.stringify({
            iface: ifaceName,
            method: method,
            args: Array.prototype.slice.call(argsLike)
        });
        var raw = window.prompt(PREFIX + payload);
        if (raw === null || raw === undefined || raw === "") return null;
        try {
            return JSON.parse(raw);
        } catch (e) {
            return raw;
        }
    }

    function makeInterface(name, methods) {
        var obj = {};
        methods.forEach(function (m) {
            obj[m] = function () { return call(name, m, arguments); };
        });
        return obj;
    }

    // מקביל ל-addJavascriptInterface(this, "AndroidInterface") — מתודות HtmlViewerActivity
    window.AndroidInterface = makeInterface("AndroidInterface", [
        "getAvailableNetworks",
        "openNewWindow",
        "getCurrentNetworkInfo",
        "getCurrentIP",
        "getCurrentWiFiSSID",
        "scanForNearbyNetworks",
        "requestScanPermissions",
        "hasWifiScanPermissions",
        "connectToWifi",
        "espHttpGet",
        "espHttpPost",
        "disconnectFromCurrentNetwork",
        "bindToCurrentWifi",
        "reconnectToKnownNetwork",
        "releaseEspBinding",
        "installUpdate",
        "openWifiSettings"
    ]);

    // מקביל ל-addJavascriptInterface(JsBridge, "Android")
    window.Android = makeInterface("Android", [
        "connectToWifi",
        "openWifiSettings",
        "openNewWindow",
        "log",
        "getCurrentWifi",
        "reconnectToKnownNetwork",
        "scanForNearbyNetworks"
    ]);

    // חלונית הדיבאג הוסרה מהתצוגה. נשארת פונקציה ריקה לתאימות (הגשר עדיין קורא לה).
    window.__hmLog = function () {};

    console.log("✓ HeatMind iOS bridge ready");
})();
