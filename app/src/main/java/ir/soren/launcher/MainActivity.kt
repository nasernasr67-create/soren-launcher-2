package ir.soren.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity(), LocationListener {

    companion object {
        const val PREFS = "launcher"

        /** Packages tried (in order) for each button before falling back to a system intent. */
        val DEFAULT_PACKAGES: Map<String, List<String>> = mapOf(
            "map" to listOf(
                "ir.balad",
                "org.rajman.neshan.traffic.tehran",
                "com.google.android.apps.maps",
                "com.waze"
            ),
            "video" to listOf(
                "org.videolan.vlc",
                "com.mxtech.videoplayer.ad"
            )
        )

        /** Words looked for in installed app names / package names, in this order. */
        val KEYWORDS: Map<String, List<String>> = mapOf(
            "map" to listOf("neshan", "نشان"),
            "bluetooth" to listOf("bluetooth", "btphone", "بلوتوث", "تماس", "phone", "dial", "تلفن")
        )
    }

    private lateinit var web: WebView
    private var locationStarted = false

    /** Light mode: fewer effects, lower frame rate. Toggled by long-pressing the clock at the top. */
    private var lite = true

    private val handler = Handler(Looper.getMainLooper())
    private val musicPoll = object : Runnable {
        override fun run() {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            web.evaluateJavascript("window.setMusicActive && window.setMusicActive(${am.isMusicActive})", null)
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lite = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("lite", true)
        buildWebView()
        ensureLocationPermission()
        web.post { showLastCrash() }
    }

    // ---- web view ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        val old: WebView? = if (::web.isInitialized) web else null
        val w = WebView(this)
        w.setBackgroundColor(Color.parseColor("#020818"))
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = true
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Bridge(), "Android")
        w.webViewClient = object : WebViewClient() {
            // If the renderer dies (usually low memory), rebuild the view in light mode
            // instead of letting the whole app crash.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                saveCrash("WebView renderer ended (crashed=${detail.didCrash()}, lite=$lite)")
                lite = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("lite", true).apply()
                buildWebView()
                return true
            }
        }
        web = w
        setContentView(w)
        w.loadUrl("file:///android_asset/launcher.html" + if (lite) "?lite=1" else "")
        old?.destroy()
    }

    // ---- crash report (shown on the next start so it can be sent for diagnosis) ----

    private fun saveCrash(text: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_crash", text.take(3000)).commit()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                saveCrash(sw.toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLastCrash() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val text = prefs.getString("last_crash", null) ?: return
        prefs.edit().remove("last_crash").apply()
        AlertDialog.Builder(this)
            .setTitle("گزارش خطای آخر")
            .setMessage(text)
            .setPositiveButton("باشه", null)
            .show()
    }

    // ---- full screen ----

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // As the home screen, the back button should do nothing.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideBars()
    }

    // ---- speed from GPS ----

    private fun ensureLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationStarted) return
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            locationStarted = true
        } catch (e: Exception) {
            // No GPS provider on this unit.
        }
    }

    private fun stopLocation() {
        if (!locationStarted) return
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        locationStarted = false
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        startLocation()
        handler.post(musicPoll)
    }

    override fun onPause() {
        stopLocation()
        handler.removeCallbacks(musicPoll)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    override fun onLocationChanged(location: Location) {
        val kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val shown = if (kmh < 3f) 0 else Math.round(kmh)
        web.evaluateJavascript("window.setSpeedNative && window.setSpeedNative($shown)", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---- buttons ----

    private fun launchKey(key: String) {
        if (key == "apps") {
            startActivity(Intent(this, AppsActivity::class.java))
            return
        }
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("pkg_$key", null)
        if (saved != null) {
            val intent = packageManager.getLaunchIntentForPackage(saved)
            if (intent != null) {
                tryStart(intent)
                return
            }
        }
        val byName = KEYWORDS[key]?.let { findLaunchIntentByKeywords(it) }
        if (byName != null) {
            tryStart(byName)
            return
        }
        for (pkg in DEFAULT_PACKAGES[key] ?: emptyList()) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                tryStart(intent)
                return
            }
        }
        when (key) {
            "map" -> tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
            "bluetooth" -> tryStart(Intent(Intent.ACTION_DIAL))
            "music" -> tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            "video" -> tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            "carsettings" -> tryStart(Intent(Settings.ACTION_SETTINGS))
            else -> {
                Toast.makeText(this, "برنامه این دکمه را از لیست برنامه‌ها با نگه‌داشتن انگشت انتخاب کن", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AppsActivity::class.java))
            }
        }
    }

    /** Finds an installed launchable app whose name or package contains one of the words. */
    private fun findLaunchIntentByKeywords(words: List<String>): Intent? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
        for (word in words) {
            val w = word.lowercase()
            for (info in apps) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) continue
                val label = info.loadLabel(packageManager).toString().lowercase()
                if (label.contains(w) || pkg.lowercase().contains(w)) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) return intent
                }
            }
        }
        return null
    }

    private fun tryStart(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "برنامه‌ای برای این دکمه پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    /** Sends a media key only while music is playing, so tapping never opens another app by surprise. */
    private fun mediaKey(code: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!am.isMusicActive && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return
        if (!am.isMusicActive) {
            launchKey("music")
            return
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    inner class Bridge {
        @JavascriptInterface
        fun launch(key: String) {
            runOnUiThread { launchKey(key) }
        }

        @JavascriptInterface
        fun media(cmd: String) {
            runOnUiThread {
                when (cmd) {
                    "play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    "next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    "prev" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
        }

        @JavascriptInterface
        fun toggleLite() {
            runOnUiThread {
                lite = !lite
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("lite", lite).apply()
                Toast.makeText(
                    this@MainActivity,
                    if (lite) "حالت سبک روشن شد" else "حالت سبک خاموش شد (افکت کامل)",
                    Toast.LENGTH_LONG
                ).show()
                buildWebView()
            }
        }
    }
}
