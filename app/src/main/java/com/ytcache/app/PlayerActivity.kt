package com.ytcache.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ytcache.app.data.CaptureSession
import com.ytcache.app.data.CaptureStore
import com.ytcache.app.data.VideoEntry
import com.ytcache.app.databinding.ActivityPlayerBinding
import java.io.File
import java.util.concurrent.Executors

class PlayerActivity : AppCompatActivity(), CacheBridge.BridgeHost {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var store: CaptureStore
    private lateinit var bridge: CacheBridge
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        store = CaptureStore(File(getExternalFilesDir(null) ?: filesDir, "YTCache"))
        bridge = CacheBridge(this, store, CaptureSession(store), this)

        val startId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        setUpWebView(startId)
    }

    private fun setUpWebView(startId: String) {
        val webView = binding.webView
        webView.setBackgroundColor(Color.BLACK)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.addJavascriptInterface(bridge, "YTCacheAndroid")

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
            ): Boolean = true
        }

        webView.loadUrl("https://$DOMAIN/assets/player/shorts.html?start=$startId")
    }

    override fun onDestroy() {
        runCatching { bridge.release() }
        io.shutdownNow()
        binding.webView.removeJavascriptInterface("YTCacheAndroid")
        binding.webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ host

    override fun onCaptureSaved(entry: VideoEntry) = Unit

    override fun onCaptureDuplicate(title: String) = Unit

    override fun onMetadataUpdated(entry: VideoEntry) = Unit

    override fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube", text))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun export(videoId: String, kind: String) {
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show()
        io.execute {
            val outcome = Exporter.export(this, store, videoId, kind)
            runOnUiThread { Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show() }
        }
    }

    override fun closePlayer() = finish()

    override fun openLibrary() {
        startActivity(Intent(this, LibraryActivity::class.java))
        finish()
    }

    override fun deleteVideo(videoId: String) {
        val entry = store.entry(videoId)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_body, entry?.title ?: videoId))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    store.delete(videoId)
                    runOnUiThread {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .show()
    }

    override fun log(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("YTCachePlayer", message)
    }

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        private const val DOMAIN = "appassets.androidplatform.net"
    }
}
