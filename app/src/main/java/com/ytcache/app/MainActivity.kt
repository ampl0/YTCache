package com.ytcache.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.ytcache.app.data.CaptureSession
import com.ytcache.app.data.CaptureStore
import com.ytcache.app.data.VideoEntry
import com.ytcache.app.databinding.ActivityMainBinding
import com.ytcache.app.util.Format
import java.io.File

class MainActivity : AppCompatActivity(), CacheBridge.BridgeHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CaptureStore
    private lateinit var session: CaptureSession
    private lateinit var bridge: CacheBridge
    private lateinit var prefs: SharedPreferences

    private val main = Handler(Looper.getMainLooper())
    private var armed = false
    private var hookHandler: ScriptHandler? = null
    private var fullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pulse: ObjectAnimator? = null
    private var announcedVideoId: String? = null
    private var backProbe = false
    private var exitArmedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rootDir = File(getExternalFilesDir(null) ?: filesDir, "YTCache")
        store = CaptureStore(rootDir)
        session = CaptureSession(store)
        bridge = CacheBridge(this, store, session, this)
        armed = prefs.getBoolean(KEY_ARMED, false)

        binding.statusText.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        binding.statusPill.isFocusable = false
        binding.statusPill.isClickable = false

        configureWebView()
        updateCaptureButton()
        registerHook()

        binding.captureButton.setOnClickListener { toggleCapture() }
        binding.libraryButton.setOnClickListener { openLibrary() }
        binding.settingsButton.setOnClickListener { showSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> hideFullscreen()
                    backProbe -> Unit
                    else -> probePageBack()
                }
            }
        })

        if (savedInstanceState == null) {
            binding.webView.loadUrl(HOME_URL)
            maybeShowHint()
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(statsTick)
        main.post(statsTick)
        applyCaptureFlag()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(statsTick)
    }

    override fun onDestroy() {
        main.removeCallbacks(statsTick)
        pulse?.cancel()
        runCatching { bridge.release() }
        binding.webView.removeJavascriptInterface("YTCacheAndroid")
        (binding.webView.parent as? FrameLayout)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- webview

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val webView = binding.webView
        webView.setBackgroundColor(Color.WHITE)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            userAgentString = MOBILE_USER_AGENT
        }

        webView.addJavascriptInterface(bridge, JS_OBJECT)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/local/", LocalPathHandler(store))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return true
                if (scheme != "http" && scheme != "https") return true
                val host = url.host?.lowercase() ?: return true
                val internal = host == DOMAIN ||
                    host.endsWith("youtube.com") ||
                    host.endsWith("youtu.be") ||
                    host.endsWith("google.com") ||
                    host.endsWith("gstatic.com") ||
                    host.endsWith("googleusercontent.com") ||
                    host.endsWith("ggpht.com") ||
                    host.endsWith("ytimg.com")
                if (internal) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: ActivityNotFoundException) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                applyCaptureFlag()
                if (hookHandler == null) {
                    view.evaluateJavascript(hookScript(), null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.topBar.visibility = View.GONE
                binding.statusPill.visibility = View.GONE
                WindowInsetsControllerCompat(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() = hideFullscreen()

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "console: ${message.message()} (${message.lineNumber()})")
                }
                return true
            }
        }
    }

    private fun hideFullscreen() {
        val view = fullscreenView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.topBar.visibility = View.VISIBLE
        fullscreenView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------- back

    /**
     * YouTube's mobile site is a single-page app: most navigations are
     * pushState calls, so WebView.canGoBack() stays false and Android's back
     * gesture used to close the app instead of leaving the video. Ask the page
     * first, then fall back to native history, then to press-again-to-exit.
     */
    private fun probePageBack() {
        backProbe = true
        val answered = booleanArrayOf(false)
        main.postDelayed({
            if (!answered[0]) {
                answered[0] = true
                backProbe = false
                nativeBack()
            }
        }, 400)
        binding.webView.evaluateJavascript(PAGE_BACK_JS) { value ->
            if (answered[0]) return@evaluateJavascript
            answered[0] = true
            backProbe = false
            val handled = value != null && value.trim().trim('"') == "true"
            if (!handled) nativeBack()
        }
    }

    private fun nativeBack() {
        if (binding.webView.canGoBack()) {
            exitArmedAt = 0L
            binding.webView.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - exitArmedAt < EXIT_WINDOW_MS) {
            exitArmedAt = 0L
            finish()
        } else {
            exitArmedAt = now
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------- hook

    /**
     * A single document-start script: the back bridge has to wrap pushState
     * before YouTube's own code runs, so it is prepended to the capture hook.
     */
    private fun hookScript(): String =
        readAsset("hooks/back.js") + "\nwindow.__ytcCaptureEnabled = $armed;\n" + readAsset("hooks/v1.js")

    private fun readAsset(path: String): String = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "asset $path missing: ${e.message}")
        ""
    }

    private fun registerHook() {
        val webView = binding.webView
        hookHandler?.let { handler -> runCatching { handler.remove() } }
        hookHandler = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, hookScript(), setOf("*"))
            } else {
                null
            }
        }.getOrNull()
    }

    private fun applyCaptureFlag() {
        binding.webView.evaluateJavascript("window.__ytcCaptureEnabled = $armed;", null)
    }

    // --------------------------------------------------------------- capture

    private fun toggleCapture() {
        armed = !armed
        prefs.edit().putBoolean(KEY_ARMED, armed).apply()
        applyCaptureFlag()
        registerHook()
        updateCaptureButton()
        if (armed) {
            announcedVideoId = null
            Toast.makeText(this, "Caching armed — play a video", Toast.LENGTH_SHORT).show()
        } else {
            stopPulse()
            binding.statusPill.visibility = View.GONE
            binding.captureProgress.visibility = View.GONE
            bridge.finishCapture { id ->
                if (id != null) Toast.makeText(this, "Saved to library", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCaptureButton() {
        if (armed) {
            binding.captureButton.setText(R.string.stop)
            binding.captureButton.setIconResource(R.drawable.ic_stop)
            binding.captureButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_red))
            binding.captureButton.setTextColor(getColor(R.color.white))
            binding.captureButton.iconTint = ColorStateList.valueOf(getColor(R.color.white))
        } else {
            binding.captureButton.setText(R.string.cache)
            binding.captureButton.setIconResource(R.drawable.ic_record)
            binding.captureButton.backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface_muted))
            binding.captureButton.setTextColor(getColor(R.color.text_primary))
            binding.captureButton.iconTint = ColorStateList.valueOf(getColor(R.color.brand_red))
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            updateCaptureUi()
            main.postDelayed(this, 500)
        }
    }

    private fun updateCaptureUi() {
        val stats = session.stats()
        val fresh = stats.active && System.currentTimeMillis() - stats.lastWriteAt < 4000
        val pending = bridge.pendingBytes()
        val showPill = prefs.getBoolean(CacheBridge.KEY_PILL, true)

        binding.captureProgress.visibility =
            if (armed && showPill) View.VISIBLE else View.GONE

        if (!showPill) {
            binding.statusPill.visibility = View.GONE
            stopPulse()
            return
        }

        when {
            (armed || fresh) && stats.active && stats.bytes > 0 -> {
                val title = stats.title.ifBlank { "video" }
                val saving = if (pending > 1L * 1024 * 1024) " · saving" else ""
                binding.statusText.text =
                    "$title · ${stats.streams} streams · ${Format.size(stats.bytes)}$saving"
                binding.statusPill.visibility = View.VISIBLE
                if (armed) startPulse() else stopPulse()
            }
            armed -> {
                binding.statusText.text = "Watching for media…"
                binding.statusPill.visibility = View.VISIBLE
                startPulse()
            }
            fresh -> {
                binding.statusText.text = "Saved · ${Format.size(stats.bytes)}"
                binding.statusPill.visibility = View.VISIBLE
                stopPulse()
            }
            else -> {
                binding.statusPill.visibility = View.GONE
                stopPulse()
            }
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.2f).apply {
            duration = 720
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        binding.statusDot.alpha = 1f
    }

    private fun maybeShowHint() {
        if (prefs.getBoolean(KEY_HINT, false)) return
        prefs.edit().putBoolean(KEY_HINT, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hint_title)
            .setMessage(R.string.hint_body)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            getString(R.string.setting_pill),
            getString(R.string.setting_sound),
        )
        val checked = booleanArrayOf(
            prefs.getBoolean(CacheBridge.KEY_PILL, true),
            prefs.getBoolean(CacheBridge.KEY_SOUND, true),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                prefs.edit()
                    .putBoolean(CacheBridge.KEY_PILL, checked[0])
                    .putBoolean(CacheBridge.KEY_SOUND, checked[1])
                    .apply()
                updateCaptureUi()
            }
            .show()
    }

    // ------------------------------------------------------------------ host

    override fun onCaptureSaved(entry: VideoEntry) {
        if (announcedVideoId == entry.videoId) return
        announcedVideoId = entry.videoId
        Snackbar.make(binding.root, "Saved · ${entry.title}", Snackbar.LENGTH_LONG)
            .setAction(R.string.open_library) { openLibrary() }
            .show()
    }

    override fun onCaptureDuplicate(title: String) {
        Snackbar.make(
            binding.root,
            getString(R.string.already_cached) + " · " + title,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    override fun onMetadataUpdated(entry: VideoEntry) {
        if (announcedVideoId == entry.videoId) {
            session.noteTitle(entry.videoId, entry.title)
        }
    }

    override fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", text))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun export(videoId: String, kind: String) = Unit

    override fun closePlayer() = Unit

    override fun openLibrary() {
        startActivity(Intent(this, LibraryActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun deleteVideo(videoId: String) = Unit

    override fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "YTCacheMain"
        private const val DOMAIN = "appassets.androidplatform.net"
        private const val JS_OBJECT = "YTCacheAndroid"
        private const val HOME_URL = "https://m.youtube.com"
        private const val PREFS = "ytcache"
        private const val KEY_ARMED = "armed"
        private const val KEY_HINT = "hint_shown"
        private const val EXIT_WINDOW_MS = 2200L
        private const val PAGE_BACK_JS =
            "(function(){try{return String(!!(window.__ytcGoBack&&window.__ytcGoBack()))}" +
                "catch(e){return 'false'}})()"

        /**
         * Chrome's default WebView UA carries a "wv" token that YouTube treats
         * as an unsupported browser; this is the same Chrome build without it.
         */
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
