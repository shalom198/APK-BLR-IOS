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

    // חלונית דיבאג על המסך — מאפשרת לראות מה קורה בלי לוג של Xcode (חשוב ב-TestFlight).
    window.__hmLog = function (msg) {
        try {
            var box = document.getElementById('__hmDebugBox');
            if (!box) {
                if (!document.body) return;
                box = document.createElement('div');
                box.id = '__hmDebugBox';
                box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;max-height:38%;overflow:auto;' +
                    'z-index:2147483647;background:rgba(0,0,0,0.82);color:#0f0;' +
                    'font:11px/1.35 monospace;padding:4px 6px;direction:ltr;white-space:pre-wrap;';
                var clr = document.createElement('div');
                clr.textContent = '✕ נקה';
                clr.style.cssText = 'position:sticky;top:0;float:left;color:#f88;cursor:pointer;';
                clr.onclick = function () { box.innerHTML = ''; box.appendChild(clr); };
                box.appendChild(clr);
                document.body.appendChild(box);
            }
            var line = document.createElement('div');
            var d = new Date();
            line.textContent = '[' + d.toLocaleTimeString() + '] ' + msg;
            box.appendChild(line);
            box.scrollTop = box.scrollHeight;
        } catch (e) {}
    };

    console.log("✓ HeatMind iOS bridge ready");
})();
