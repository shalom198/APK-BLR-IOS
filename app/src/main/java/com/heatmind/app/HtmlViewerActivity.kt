package com.heatmind.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.ScanResult
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.TimeUnit


class HtmlViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingAfterPerms: (() -> Unit)? = null

    // ============================================
    // 🔑 רשת ה-ESP הנוכחית — נשמרת ב-onAvailable
    // ============================================
    @Volatile
    private var espNetwork: Network? = null

    companion object {
        private const val TAG = "ESP_WEB"
        private const val ESP_TIMEOUT_MS = 7000
        // המפתח שדרכו מעבירים URL כשפותחים דף בחלון/Activity חדש
        const val EXTRA_URL = "extra_url"
    }

    // האם המסך הזה הוא דף-משנה שנפתח מעל דף אחר (ולכן מציג כפתור "חזרה")
    private var isSubPage = false

    // Extension function for ScanResult
    private fun ScanResult.ssidCompat(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.wifiSsid?.toString() ?: ""
        } else {
            @Suppress("DEPRECATION")
            this.SSID ?: ""
        }
    }

    private val writeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(this)) {
            Log.d("ESP_CONNECT", "קיבלנו הרשאת WRITE_SETTINGS")
            pendingAfterPerms?.invoke()
            pendingAfterPerms = null
        } else {
            Log.w("ESP_CONNECT", "לא קיבלנו הרשאת WRITE_SETTINGS")
            openWifiSettings()
        }
    }

    private val permsRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        val action = pendingAfterPerms
        pendingAfterPerms = null
        if (granted) {
            Log.d("ESP_CONNECT", "כל ההרשאות אושרו")
            action?.invoke()
        } else {
            Log.w("ESP_CONNECT", "הרשאות נדחו")
            openWifiSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // אם הופעל עם URL (דרך openNewWindow) — זהו דף-משנה שנפתח מעל דף קודם.
        // אם אין URL — זהו הדף הראשי (login.html).
        val startUrl = intent?.getStringExtra(EXTRA_URL)
        isSubPage = !startUrl.isNullOrEmpty()

        webView = WebView(this)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
            }
        }

        webView.addJavascriptInterface(JsBridge(this), "Android")
        webView.addJavascriptInterface(this, "AndroidInterface")
        webView.webViewClient = createWebViewClient()

        // עוטפים את ה-WebView ב-FrameLayout כדי שנוכל להוסיף מעליו כפתור "חזרה"
        val root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // כפתור "חזרה" צף — מוצג רק בדפי-משנה (לא בדף הראשי)
        if (isSubPage) {
            root.addView(createBackButton())
        }

        setContentView(root)

        webView.loadUrl(startUrl ?: "file:///android_asset/login.html")
    }

    // ============================================
    // 🔙 ניווט חזרה
    // ============================================

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun createBackButton(): Button {
        return Button(this).apply {
            text = "↩ חזרה"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#CC1976D2")) // כחול עם שקיפות קלה
            setPadding(dp(18), dp(8), dp(18), dp(8))
            elevation = dp(6).toFloat()
            setOnClickListener { goBackOrFinish() }

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // מיקום בפינה העליונה (start = ימין ב-RTL עברית)
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(12)
                marginStart = dp(12)
                leftMargin = dp(12)
            }
        }
    }

    private fun goBackOrFinish() {
        if (webView.canGoBack()) {
            webView.goBack()      // חזרה בתוך היסטוריית הדף הנוכחי
        } else {
            finish()              // אין היסטוריה — חזרה לדף הקודם (Activity הקודם)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ============================================
    // 🌐 WiFi Status Functions
    // ============================================

    @JavascriptInterface
    fun getAvailableNetworks(): String {
        return try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null || !wifiManager.isWifiEnabled) return "[]"

            val scanResults = wifiManager.scanResults
            if (scanResults.isNullOrEmpty()) return "[]"

            val networksArray = JSONArray()
            val seenSSIDs = mutableSetOf<String>()

            for (result in scanResults) {
                val ssid = result.ssidCompat()
                if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") continue

                val cleanSsid = ssid.replace("\"", "")
                if (seenSSIDs.contains(cleanSsid)) continue
                seenSSIDs.add(cleanSsid)

                try {
                    val network = JSONObject()
                    network.put("ssid", cleanSsid)
                    network.put("level", result.level)
                    network.put("capabilities", result.capabilities ?: "")
                    network.put("isSecure",
                        result.capabilities?.contains("WPA") == true ||
                        result.capabilities?.contains("WEP") == true)
                    val strength = when {
                        result.level > -50 -> "מעולה"
                        result.level > -60 -> "טוב"
                        result.level > -70 -> "בינוני"
                        result.level > -80 -> "חלש"
                        else -> "חלש מאוד"
                    }
                    network.put("signalStrength", strength)
                    network.put("timestamp", System.currentTimeMillis())
                    networksArray.put(network)
                } catch (e: JSONException) {
                    continue
                }
            }
            networksArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בקבלת רשתות זמינות", e)
            "[]"
        }
    }

    // ============================================
    // 🪟 פתיחת דף בחלון/Activity חדש
    // נקרא מ-JS דרך AndroidInterface.openNewWindow(url)
    // כל קריאה פותחת מופע חדש של HtmlViewerActivity מעל הקודם,
    // כך שכפתור החזרה (פיזי או הצף) מחזיר לדף הקודם, ושני הדפים נשארים פתוחים.
    // ============================================
    @JavascriptInterface
    fun openNewWindow(url: String) {
        Log.d(TAG, "openNewWindow: $url")
        runOnUiThread {
            try {
                val intent = Intent(this, HtmlViewerActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open new window", e)
            }
        }
    }

    @JavascriptInterface
    fun getCurrentNetworkInfo(): String {
        return try {
            val connectivityManager =
                applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
                ?: return createNetworkJson("לא מחובר", "אין חיבור", "0")
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return createNetworkJson("לא מחובר", "אין WiFi", "0")
            }
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo.ssid?.removeSurrounding("\"") ?: "לא ידוע"
            val rssi = wifiInfo.rssi
            val type = when {
                rssi > -50 -> "WiFi (חזק)"
                rssi > -70 -> "WiFi (בינוני)"
                rssi > -85 -> "WiFi (חלש)"
                else -> "WiFi"
            }
            createNetworkJson(ssid, type, rssi.toString())
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בקריאת מידע רשת: ${e.message}")
            createNetworkJson("שגיאה", "אין מידע", "0")
        }
    }

    @JavascriptInterface
    fun getCurrentIP(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return "0.0.0.0"
            val linkProperties = cm.getLinkProperties(activeNetwork) ?: return "0.0.0.0"
            for (linkAddress in linkProperties.linkAddresses) {
                if (linkAddress.address is Inet4Address) {
                    return linkAddress.address.hostAddress ?: "0.0.0.0"
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current IP", e)
            "0.0.0.0"
        }
    }

    @JavascriptInterface
    fun getCurrentWiFiSSID(): String {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ""
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo ?: return ""
            @Suppress("DEPRECATION")
            var ssid = wifiInfo.ssid
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") return ""
            ssid = ssid.replace("\"", "")
            Log.d(TAG, "✓ Current WiFi SSID: $ssid")
            ssid
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current WiFi SSID", e)
            ""
        }
    }

    private fun getWifiNetworkInfo(): String {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return createNetworkJson("WiFi לא זמין", "שגיאה", "0")
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
                ?: return createNetworkJson("אין WiFi", "לא מחובר", "0")
            @Suppress("DEPRECATION")
            var ssid = wifiInfo.ssid
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") {
                return createNetworkJson("רשת לא ידועה", "WiFi", "0")
            }
            ssid = ssid.replace("\"", "")
            val rssi = wifiInfo.rssi
            val networkType = when {
                rssi > -50 -> "WiFi (חזק)"
                rssi > -70 -> "WiFi (בינוני)"
                rssi > -85 -> "WiFi (חלש)"
                else -> "WiFi (חלש מאוד)"
            }
            createNetworkJson(ssid, networkType, rssi.toString())
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בקבלת מידע WiFi", e)
            createNetworkJson("שגיאה", "לא ניתן לקרוא", "0")
        }
    }

    private fun createNetworkJson(ssid: String, type: String, signal: String): String {
        return try {
            JSONObject().apply {
                put("ssid", ssid)
                put("type", type)
                put("signal", signal)
            }.toString()
        } catch (e: JSONException) {
            """{"ssid":"$ssid","type":"$type","signal":"$signal"}"""
        }
    }

    // ============================================
    // 📡 WiFi Connection Functions
    // ============================================

    @JavascriptInterface
    fun scanForNearbyNetworks(): String {
        return try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.e(TAG, "WifiManager is null")
                return "[]"
            }
            if (!wifiManager.isWifiEnabled) {
                Log.e(TAG, "WiFi is disabled")
                return "[]"
            }
            Log.d(TAG, "Starting WiFi scan...")
            val scanStarted = wifiManager.startScan()
            Log.d(TAG, "Scan initiation result: $scanStarted")
            if (!scanStarted) {
                Log.w(TAG, "Failed to start scan, returning cached results")
            }
            Thread.sleep(2000)
            val scanResults = wifiManager.scanResults
            if (scanResults.isNullOrEmpty()) {
                Log.d(TAG, "No scan results available")
                return "[]"
            }
            Log.d(TAG, "Processing ${scanResults.size} scan results")
            val networksArray = JSONArray()
            val seenSSIDs = mutableSetOf<String>()
            for (result in scanResults) {
                val ssid = result.ssidCompat()
                if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") continue
                val cleanSsid = ssid.replace("\"", "")
                if (seenSSIDs.contains(cleanSsid)) continue
                seenSSIDs.add(cleanSsid)
                try {
                    val network = JSONObject()
                    network.put("ssid", cleanSsid)
                    network.put("rssi", result.level)
                    network.put("level", result.level)
                    network.put("capabilities", result.capabilities ?: "")
                    networksArray.put(network)
                } catch (e: JSONException) {
                    Log.e(TAG, "Error creating network JSON for $cleanSsid", e)
                    continue
                }
            }
            val resultJson = networksArray.toString()
            Log.d(TAG, "Returning ${networksArray.length()} unique networks")
            resultJson
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanForNearbyNetworks", e)
            "[]"
        }
    }

    @JavascriptInterface
    fun requestScanPermissions(): Boolean {
        return try {
            runOnUiThread { ensureWifiScanPrereqs() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting scan permissions", e)
            false
        }
    }

    @JavascriptInterface
    fun hasWifiScanPermissions(): Boolean {
        return try {
            val hasLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasWifiState = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_WIFI_STATE
            ) == PackageManager.PERMISSION_GRANTED
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val locEnabled =
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            val result = hasLocation && hasWifiState && locEnabled
            Log.d(TAG, "Permissions check - Location: $hasLocation, WiFi: $hasWifiState, LocationEnabled: $locEnabled, Result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    private val WIFI_PERMS = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_WIFI_STATE,
        android.Manifest.permission.CHANGE_WIFI_STATE,
        android.Manifest.permission.NEARBY_WIFI_DEVICES
    )

    private fun ensureWifiScanPrereqs() {
        val needPerms = WIFI_PERMS.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needPerms.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, needPerms.toTypedArray(), 2001
            )
        }
        val lm =
            getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val locEnabled =
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!locEnabled) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {}
        }
        try {
            val wifi =
                applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (!wifi.isWifiEnabled) {
                wifi.isWifiEnabled = true
            }
        } catch (_: Exception) {}
    }

    // ============================================
    // 🔌 WiFi Connection Management
    // ============================================

    @JavascriptInterface
    fun connectToWifi(ssid: String, password: String, optionsJson: String? = null) {
        Log.d(TAG, "connectToWifi called: ssid=$ssid")
        runOnUiThread {
            ensureWifiPermissions {
                actualConnectToWifi(ssid, password, optionsJson)
            }
        }
    }

    private fun actualConnectToWifi(ssid: String, password: String, optionsJson: String?) {
        try {
            var bindNetwork = true
            var timeoutSec = 20
            if (!optionsJson.isNullOrEmpty()) {
                try {
                    val opts = JSONObject(optionsJson)
                    bindNetwork = opts.optBoolean("bind", true)
                    timeoutSec = opts.optInt("timeoutSec", 20)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse options JSON", e)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectUsingNetworkRequest(ssid, password, bindNetwork, timeoutSec)
            } else {
                connectLegacy(ssid, password)
            }
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה ב-actualConnectToWifi", e)
            notifyJsWifiResult(false, ssid)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectUsingNetworkRequest(
        ssid: String, password: String, bindNetwork: Boolean, timeoutSec: Int
    ) {
        try {
            Log.d(TAG, "Using NetworkRequest for connection (Android Q+)")

            val specifier = if (password.isEmpty()) {
                Log.d(TAG, "Empty password - connecting to saved network")
                WifiNetworkSpecifier.Builder().setSsid(ssid).build()
            } else {
                Log.d(TAG, "Password provided - creating new connection")
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // ESP AP ללא אינטרנט — מונע ניתוק אוטומטי
                .build()

            networkCallback?.let {
                try { cm.unregisterNetworkCallback(it) } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister old callback", e)
                }
            }
            espNetwork = null

            val timeoutMs = timeoutSec * 1000L
            val handler = Handler(Looper.getMainLooper())
            var callbackInvoked = false

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!callbackInvoked) {
                        callbackInvoked = true
                        handler.removeCallbacksAndMessages(null)
                        Log.d(TAG, "✓ Network available: $network")

                        // ============================================
                        // שמירת ה-Network object לשימוש ב-espHttpGet/Post
                        // ============================================
                        espNetwork = network

                        if (bindNetwork) {
                            try {
                                cm.bindProcessToNetwork(network)
                                Log.d(TAG, "✓ Process bound to network")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to bind to network", e)
                            }
                        }
                        notifyJsWifiResult(true, ssid)
                    }
                }

                override fun onUnavailable() {
                    if (!callbackInvoked) {
                        callbackInvoked = true
                        handler.removeCallbacksAndMessages(null)
                        Log.w(TAG, "✗ Network unavailable")
                        espNetwork = null
                        notifyJsWifiResult(false, ssid)
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: $network")
                    if (espNetwork == network) espNetwork = null
                }
            }

            handler.postDelayed({
                if (!callbackInvoked) {
                    Log.d(TAG, "⏱ Timeout after ${timeoutSec}s")
                    callbackInvoked = true
                    try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {}
                    espNetwork = null
                    notifyJsWifiResult(false, ssid)
                }
            }, timeoutMs)

            networkCallback = callback
            cm.requestNetwork(request, callback)
            Log.d(TAG, "✓ Network request submitted")

        } catch (e: Exception) {
            Log.e(TAG, "שגיאה ב-connectUsingNetworkRequest", e)
            espNetwork = null
            notifyJsWifiResult(false, ssid)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String, password: String) {
        Log.d(TAG, "Using legacy connection method (Android P and below)")
        try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId == -1) {
                Log.e(TAG, "✗ Failed to add network configuration")
                notifyJsWifiResult(false, ssid)
                return
            }
            val disconnected = wifiManager.disconnect()
            Log.d(TAG, "Disconnect result: $disconnected")
            val enabled = wifiManager.enableNetwork(netId, true)
            Log.d(TAG, "Enable network result: $enabled")
            val reconnected = wifiManager.reconnect()
            Log.d(TAG, "Reconnect result: $reconnected")
            if (enabled && reconnected) {
                Handler(Looper.getMainLooper()).postDelayed({
                    // במצב Legacy נשתמש ב-activeNetwork
                    espNetwork = cm.activeNetwork
                    Log.d(TAG, "Legacy: espNetwork set to activeNetwork")
                    notifyJsWifiResult(true, ssid)
                }, 3000)
            } else {
                notifyJsWifiResult(false, ssid)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "אין הרשאה לשנות רשתות", e)
            notifyJsWifiResult(false, ssid)
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה ב-connectLegacy", e)
            notifyJsWifiResult(false, ssid)
        }
    }

    // ============================================
    // 🌐 Native HTTP לתקשורת ישירה עם ESP
    //
    // הפתרון לבעיית bindProcessToNetwork:
    // WebView משתמש ב-Chromium network stack שלא
    // מכבד bindProcessToNetwork. פונקציות אלו
    // רצות על native thread וכן מכבדות אותו —
    // ולכן מגיעות תמיד ל-ESP ולא לאינטרנט.
    // ============================================

    @JavascriptInterface
    fun espHttpGet(url: String): String {
        Log.d(TAG, "espHttpGet: $url")
        return try {
            val network = resolveEspNetwork()
                ?: return buildErrorJson("no_network", "לא מחובר לרשת ESP")

            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = ESP_TIMEOUT_MS
                readTimeout    = ESP_TIMEOUT_MS
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            Log.d(TAG, "espHttpGet response: $responseCode")

            if (responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                body
            } else {
                conn.disconnect()
                buildErrorJson("http_$responseCode", "ESP החזיר קוד $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "espHttpGet error: ${e.message}")
            buildErrorJson("exception", e.message ?: "unknown error")
        }
    }

    @JavascriptInterface
    fun espHttpPost(url: String, body: String): String {
        Log.d(TAG, "espHttpPost: $url")
        return try {
            val network = resolveEspNetwork()
                ?: return buildErrorJson("no_network", "לא מחובר לרשת ESP")

            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.apply {
                requestMethod  = "POST"
                connectTimeout = ESP_TIMEOUT_MS
                readTimeout    = ESP_TIMEOUT_MS
                doOutput       = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val responseCode = conn.responseCode
            Log.d(TAG, "espHttpPost response: $responseCode")

            if (responseCode in 200..299) {
                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                responseBody
            } else {
                val errorBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }.getOrDefault("")
                conn.disconnect()
                buildErrorJson("http_$responseCode", "ESP החזיר קוד $responseCode: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "espHttpPost error: ${e.message}")
            buildErrorJson("exception", e.message ?: "unknown error")
        }
    }

    /**
     * מחזיר את רשת ה-ESP הנוכחית.
     * קודם מנסה את espNetwork השמורה,
     * ואם לא קיימת — נסה activeNetwork (למצב legacy / חיבור ידני).
     */
    private fun resolveEspNetwork(): Network? {
        espNetwork?.let {
            Log.d(TAG, "resolveEspNetwork: using saved espNetwork")
            return it
        }
        // Fallback: חיבור ידני — הטלפון כבר על espConfig אבל ללא NetworkRequest
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            if (isWifi) {
                Log.d(TAG, "resolveEspNetwork: fallback to activeNetwork (WiFi)")
                return active
            }
        }
        Log.w(TAG, "resolveEspNetwork: no suitable network found")
        return null
    }

    private fun buildErrorJson(code: String, message: String): String {
        return try {
            JSONObject().apply {
                put("error", code)
                put("message", message)
            }.toString()
        } catch (e: JSONException) {
            """{"error":"$code","message":"$message"}"""
        }
    }

    // ============================================
    // 🔌 Disconnect
    // ============================================

    @JavascriptInterface
    fun disconnectFromCurrentNetwork(): Boolean {
        Log.d(TAG, "🔌 מנתק מרשת נוכחית...")
        return try {
            networkCallback?.let { callback ->
                try {
                    cm.unregisterNetworkCallback(callback)
                    Log.d(TAG, "✓ NetworkCallback unregistered")
                } catch (e: Exception) {
                    Log.w(TAG, "NetworkCallback already unregistered", e)
                }
                networkCallback = null
            }
            espNetwork = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    cm.bindProcessToNetwork(null)
                    Log.d(TAG, "✓ Process unbound from network")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unbind process", e)
                }
            }
            Log.d(TAG, "✅ התנתקות הושלמה - Android יחזור לרשת שמורה")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ שגיאה בהתנתקות מרשת", e)
            false
        }
    }

    @JavascriptInterface
    fun bindToCurrentWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val network = cm.activeNetwork
                if (network != null) {
                    cm.bindProcessToNetwork(network)
                    espNetwork = network
                    Log.d(TAG, "✓ Bound to current WiFi network")
                } else {
                    Log.w(TAG, "No active network to bind to")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind to current WiFi", e)
            }
        }
    }

    @JavascriptInterface
    fun reconnectToKnownNetwork(ssid: String): Boolean {
        Log.d(TAG, "Attempting to reconnect to known network: $ssid")
        try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false

            @Suppress("DEPRECATION")
            val configuredNetworks = wifiManager.configuredNetworks

            if (configuredNetworks == null) {
                Log.w(TAG, "configuredNetworks is null - Android Q+ restriction")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Log.d(TAG, "Trying to connect without password (saved network)")
                    runOnUiThread {
                        try {
                            connectToWifi(ssid, "", """{"bind":false,"timeoutSec":20}""")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error calling connectToWifi", e)
                        }
                    }
                    return true
                }
                return false
            }

            Log.d(TAG, "Found ${configuredNetworks.size} configured networks")

            for (config in configuredNetworks) {
                val configSsid = config.SSID?.replace("\"", "") ?: continue
                Log.d(TAG, "Checking configured network: '$configSsid'")

                if (configSsid == ssid) {
                    Log.d(TAG, "✓ נמצאה רשת שמורה: $ssid")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Log.d(TAG, "Android Q+ - using connectToWifi")
                        runOnUiThread {
                            connectToWifi(ssid, "", """{"bind":false,"timeoutSec":20}""")
                        }
                        return true
                    }
                    try {
                        @Suppress("DEPRECATION")
                        val disconnected = wifiManager.disconnect()
                        @Suppress("DEPRECATION")
                        val enabled = wifiManager.enableNetwork(config.networkId, true)
                        @Suppress("DEPRECATION")
                        val reconnected = wifiManager.reconnect()
                        if (enabled && reconnected) {
                            Log.d(TAG, "✓ פקודות חיבור נשלחו בהצלחה")
                            return true
                        } else {
                            Log.w(TAG, "✗ פקודות חיבור נכשלו")
                            return false
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "אין הרשאה לשנות רשתות", e)
                        return false
                    }
                }
            }

            Log.d(TAG, "✗ רשת '$ssid' לא נמצאה ברשתות שמורות (${configuredNetworks.size} networks checked)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Trying to connect anyway (may work if network is saved)")
                runOnUiThread {
                    connectToWifi(ssid, "", """{"bind":false,"timeoutSec":20}""")
                }
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה ב-reconnectToKnownNetwork", e)
            return false
        }
    }

    // ============================================
    // 🌐 WebView Setup
    // ============================================

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "✓ Page loaded: $url")
            }
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WiFi settings", e)
        }
    }

    private fun ensureWifiPermissions(action: () -> Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val needPerms = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needPerms.isEmpty()) {
            action()
        } else {
            pendingAfterPerms = action
            permsRequester.launch(needPerms.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
                Log.d("ESP_CONNECT", "network callback נוקה")
            } catch (e: Exception) {
                Log.w("ESP_CONNECT", "שגיאה בניקוי network callback", e)
            }
            networkCallback = null
        }
        espNetwork = null
        cm.bindProcessToNetwork(null)
    }

    private fun notifyJsWifiResult(success: Boolean, ssid: String) {
        runOnUiThread {
            try {
                val js =
                    "if (typeof window.onWifiConnected === 'function') { window.onWifiConnected($success, '$ssid'); }"
                webView.evaluateJavascript(js) { result ->
                    Log.d(TAG, "JS callback result: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call JS callback", e)
            }
        }
    }

	@JavascriptInterface
	fun releaseEspBinding() {
		Log.d(TAG, "releaseEspBinding called")
		networkCallback?.let {
			try {
				cm.unregisterNetworkCallback(it)
				Log.d(TAG, "network callback released")
			} catch (e: Exception) {
				Log.w(TAG, "Failed to unregister network callback", e)
			}
			networkCallback = null
		}
		espNetwork = null
		cm.bindProcessToNetwork(null)
		Log.d(TAG, "✓ Process unbound - back to default network")
	}

    // ============================================
    // 📥 עדכון אפליקציה — הורדה בתוך האפליקציה והתקנה אוטומטית (בלי דפדפן)
    // ============================================
    private val APK_NAME = "HeatMind-update.apk"

    @JavascriptInterface
    fun installUpdate(url: String) {
        Log.d(TAG, "installUpdate called: $url")
        runOnUiThread {
            try {
                // ההורדה צריכה לצאת דרך האינטרנט, לא דרך רשת ה-ESP
                cm.bindProcessToNetwork(null)

                // מנקים הורדה קודמת אם נשארה
                try {
                    val old = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
                    if (old.exists()) old.delete()
                } catch (_: Exception) {}

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                    .setTitle("HeatMind")
                    .setDescription("מוריד עדכון…")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, APK_NAME)

                val downloadId = dm.enqueue(req)

                val onComplete = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val doneId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                        if (doneId != downloadId) return
                        try { unregisterReceiver(this) } catch (_: Exception) {}
                        promptInstall()
                    }
                }
                val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(onComplete, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "installUpdate failed", e)
            }
        }
    }

    private fun promptInstall() {
        try {
            // Android 8+: אם אין הרשאת "התקנה ממקור לא ידוע" — מפנים את המשתמש לאשר (פעם אחת)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "request install permission failed", e)
                }
            }

            val file = java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(install)
        } catch (e: Exception) {
            Log.e(TAG, "promptInstall failed", e)
        }
    }


    // ============================================
    // 🌉 JavaScript Bridge (Legacy Android Interface)
    // ============================================

    private class JsBridge(private val activity: HtmlViewerActivity) {

        @JavascriptInterface
        fun connectToWifi(ssid: String, password: String) {
            activity.runOnUiThread {
                activity.ensureWifiPermissions {
                    activity.connectToWifi(ssid, password)
                }
            }
        }

        @JavascriptInterface
        fun openWifiSettings() {
            activity.runOnUiThread { activity.openWifiSettings() }
        }

        @JavascriptInterface
        fun openNewWindow(url: String) {
            activity.openNewWindow(url)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[JS] $message")
        }

        @JavascriptInterface
        fun getCurrentWifi(): String? {
            return activity.getWifiNetworkInfo()
        }

        @JavascriptInterface
        fun reconnectToKnownNetwork(ssid: String): Boolean {
            return activity.reconnectToKnownNetwork(ssid)
        }

        @JavascriptInterface
        fun scanForNearbyNetworks(): String {
            return activity.scanForNearbyNetworks()
        }
    }
}