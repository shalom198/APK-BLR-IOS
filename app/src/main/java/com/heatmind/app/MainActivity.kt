package com.heatmind.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        add(Manifest.permission.CHANGE_NETWORK_STATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            // כל ההרשאות אושרו - בדוק WRITE_SETTINGS
            checkWriteSettingsPermission()
        } else {
            // יש הרשאות שנדחו
            val deniedPermissions = permissions.filter { !it.value }.keys
            showPermissionDeniedDialog(deniedPermissions.toList())
        }
    }

    private val writeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(this)) {
            // יש לנו את כל ההרשאות - עבור לאפליקציה
            proceedToApp()
        } else {
            showWriteSettingsDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // בדיקת הרשאות
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        when {
            missingPermissions.isEmpty() -> {
                // יש לנו את כל ההרשאות הרגילות - בדוק WRITE_SETTINGS
                checkWriteSettingsPermission()
            }
            else -> {
                // הסבר למשתמש למה צריך הרשאות
                showPermissionRationaleDialog(missingPermissions)
            }
        }
    }

    private fun checkWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                showWriteSettingsRationaleDialog()
                return
            }
        }

        // יש לנו הכל - עבור לאפליקציה
        proceedToApp()
    }

    private fun showPermissionRationaleDialog(permissions: List<String>) {
        val message = buildString {
            append("האפליקציה זקוקה להרשאות הבאות כדי לעבוד:\n\n")

            if (permissions.any { it.contains("LOCATION") }) {
                append("📍 מיקום - נדרש לסריקת רשתות WiFi (דרישת אנדרואיד)\n\n")
            }
            if (permissions.any { it.contains("WIFI") || it.contains("NETWORK") }) {
                append("📡 WiFi ורשת - לחיבור לבקר ESP\n\n")
            }

            append("בלי ההרשאות האלו, האפליקציה לא תוכל:\n")
            append("❌ לסרוק רשתות WiFi\n")
            append("❌ להתחבר לבקר\n")
            append("❌ לעבוד כראוי\n\n")
            append("האם להמשיך?")
        }

        AlertDialog.Builder(this)
            .setTitle("נדרשות הרשאות")
            .setMessage(message)
            .setPositiveButton("אישור") { _, _ ->
                permissionLauncher.launch(permissions.toTypedArray())
            }
            .setNegativeButton("ביטול") { _, _ ->
                showCannotProceedDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        val message = buildString {
            append("⚠️ ההרשאות הבאות נדחו:\n\n")

            deniedPermissions.forEach { permission ->
                when {
                    permission.contains("LOCATION") -> append("📍 מיקום\n")
                    permission.contains("WIFI") -> append("📡 WiFi\n")
                    permission.contains("NETWORK") -> append("🌐 רשת\n")
                }
            }

            append("\nבלי ההרשאות האלו, האפליקציה לא תעבוד!\n\n")
            append("יש לפתוח את ההגדרות ולתת הרשאות ידנית.")
        }

        AlertDialog.Builder(this)
            .setTitle("חסרות הרשאות")
            .setMessage(message)
            .setPositiveButton("פתח הגדרות") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("נסה שוב") { _, _ ->
                checkAndRequestPermissions()
            }
            .setCancelable(false)
            .show()
    }

    private fun showWriteSettingsRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("הרשאה נוספת נדרשת")
            .setMessage(
                "⚙️ האפליקציה צריכה הרשאה לשינוי הגדרות מערכת.\n\n" +
                        "זה נדרש כדי להתחבר אוטומטית לרשת WiFi של הבקר.\n\n" +
                        "בחלון הבא, אפשר את:\n" +
                        "\"שינוי הגדרות מערכת\" (Allow modify system settings)"
            )
            .setPositiveButton("המשך") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    writeSettingsLauncher.launch(intent)
                }
            }
            .setNegativeButton("דלג") { _, _ ->
                // המשך בלי ההרשאה הזו (חיבור WiFi יהיה ידני יותר)
                proceedToApp()
            }
            .setCancelable(false)
            .show()
    }

    private fun showWriteSettingsDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("הרשאה לא אושרה")
            .setMessage(
                "⚠️ הרשאת שינוי הגדרות לא אושרה.\n\n" +
                        "האפליקציה תעבוד, אבל:\n" +
                        "• חיבור WiFi יידרש אישור ידני\n" +
                        "• לא תהיה התחברות אוטומטית\n\n" +
                        "האם להמשיך בכל זאת?"
            )
            .setPositiveButton("כן, המשך") { _, _ ->
                proceedToApp()
            }
            .setNegativeButton("חזור להגדרות") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    writeSettingsLauncher.launch(intent)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showCannotProceedDialog() {
        AlertDialog.Builder(this)
            .setTitle("לא ניתן להמשיך")
            .setMessage(
                "❌ בלי ההרשאות הנדרשות, האפליקציה לא יכולה לעבוד.\n\n" +
                        "האם לנסות שוב?"
            )
            .setPositiveButton("נסה שוב") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("צא") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        finish()
    }

    private fun proceedToApp() {
        startActivity(Intent(this, HtmlViewerActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // אם חזרנו מהגדרות, בדוק שוב את ההרשאות
        if (!isFinishing) {
            val allPermissionsGranted = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }

            if (allPermissionsGranted) {
                checkWriteSettingsPermission()
            }
        }
    }
}