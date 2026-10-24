package com.streamtv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import com.streamtv.app.data.BookmarkManager
import com.streamtv.app.data.HistoryManager
import com.streamtv.app.data.SiteManager
import com.streamtv.app.ui.OverlayMenu
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var rootLayout: FrameLayout
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var historyManager: HistoryManager
    private lateinit var overlayMenu: OverlayMenu
    private lateinit var prefs: SharedPreferences
    private lateinit var gestureDetector: GestureDetector
    private lateinit var cursorOverlay: FrameLayout

    private var playerControls: View? = null
    private var playerSeekBar: SeekBar? = null
    private var playerTimeText: TextView? = null
    private var playerStateText: TextView? = null
    private var playerHintText: TextView? = null
    private var seekFeedbackText: TextView? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var currentTitle: String = ""
    private var currentUrl: String = ""
    private var menuButton: FrameLayout? = null
    private var isMenuButtonVisible = true
    private var currentSiteIndex = 0

    private val hideHandler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())

    private var cursorMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastCursorMoveAt = 0L

    private var okDownAt = 0L
    private var okLongPressTriggered = false
    private var lastNavAt = 0L
    private var lastSeekAt = 0L

    private var videoPollRunnable: Runnable? = null

    private val menuHideRunnable = Runnable {
        menuButton?.animate()?.alpha(0.2f)?.setDuration(600)?.start()
        isMenuButtonVisible = false
    }

    private val okLongPressRunnable = Runnable {
        if (customView != null) return@Runnable
        okLongPressTriggered = true
        overlayMenu.showMainMenu()
    }

    private val hidePlayerControlsRunnable = Runnable {
        playerControls?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
            if (customView != null) playerControls?.visibility = View.GONE
        }?.start()
    }

    companion object {
        private val HOME_URL = SiteManager.sites[0].url

        private val AD_BLOCK_LIST = listOf(
            "doubleclick.net", "googlesyndication.com", "adservice.google",
            "popads.net", "popcash.net", "propellerads.com", "adnxs.com",
            "exoclick.com", "juicyads.com", "trafficjunky.com", "ad.plus",
            "adsterra.com", "hilltopads.net", "onclickmax.com", "notifpush.com",
            "clickadu.com", "a-ads.com", "ad-maven.com", "admaven.com",
            "bidvertiser.com", "revcontent.com", "mgid.com", "taboola.com",
            "outbrain.com", "zedo.com", "yllix.com", "clicksor.com",
            "pushnotifications", "push-notifications", "onesignal.com",
            "popunder", "popmyads", "poperblocker", "adf.ly",
            "shorte.st", "sh.st", "bc.vc", "linkshrink"
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("pixeltv_prefs", MODE_PRIVATE)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize managers
        bookmarkManager = BookmarkManager(this)
        historyManager = HistoryManager(this)

        // Gesture detector for swipe — high threshold to avoid accidental triggers
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Threshold dinaikkan jauh: butuh swipe panjang & cepat
            private val SWIPE_THRESHOLD = 250        // jarak minimal (px) — sebelumnya 100
            private val SWIPE_VELOCITY = 1200        // kecepatan minimal — sebelumnya 100
            private val DIRECTION_RATIO = 2.5f       // arah harus dominan (horizontal vs vertical)

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                val absX = Math.abs(diffX)
                val absY = Math.abs(diffY)

                // Horizontal swipe — harus benar-benar dominan horizontal
                if (absX > absY * DIRECTION_RATIO) {
                    if (absX > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY) {
                        if (diffX > 0) {
                            // Swipe right — go back
                            if (webView.canGoBack()) webView.goBack()
                        } else {
                            // Swipe left — switch site
                            switchSite(1)
                        }
                        return true
                    }
                }
                // Swipe down — harus benar-benar dominan vertikal & dari atas
                else if (absY > absX * DIRECTION_RATIO) {
                    if (diffY > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY) {
                        // Hanya refresh kalau swipe dimulai dari area atas (pull-to-refresh)
                        if (e1.y < webView.height * 0.25f) {
                            webView.reload()
                            toast("🔄 Refresh")
                            return true
                        }
                    }
                }
                return false
            }
        })

        // Setup layout
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        fullscreenContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        val density = resources.displayMetrics.density
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (3 * density).toInt()
            )
            isIndeterminate = false
            max = 100
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.BLACK)
            requestFocus()
        }

        setupWebView()

        // Intercept touch for gestures (but still pass to WebView)
        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // let WebView handle it too
        }

        playerControls = createPlayerControls()
        fullscreenContainer.addView(playerControls)

        menuButton = createDraggableMenuButton()
        cursorOverlay = createCursorOverlay()

        overlayMenu = OverlayMenu(
            context = this,
            bookmarkManager = bookmarkManager,
            historyManager = historyManager,
            onNavigate = { url ->
                hideCustomView()
                webView.loadUrl(url)
            },
            onBookmarkCurrent = { bookmarkCurrentPage() },
            isCursorMode = { cursorMode },
            onPlay = { playBest() },
            onNextServer = {
                hideCustomView()
                nextServer()
            },
            onGoBack = {
                hideCustomView()
                if (webView.canGoBack()) webView.goBack()
                else toast("Tidak ada halaman sebelumnya")
            },
            onGoForward = {
                hideCustomView()
                if (webView.canGoForward()) webView.goForward()
                else toast("Tidak ada halaman berikutnya")
            },
            onSearch = {
                hideCustomView()
                showSearchDialog()
            },
            onToggleCursor = {
                hideCustomView()
                setCursorMode(!cursorMode)
            },
            onRefresh = {
                hideCustomView()
                webView.reload()
            }
        )

        rootLayout.addView(webView)
        rootLayout.addView(progressBar)
        rootLayout.addView(fullscreenContainer)
        rootLayout.addView(menuButton)
        rootLayout.addView(cursorOverlay)

        setContentView(rootLayout)

        rootLayout.post { restoreButtonPosition() }

        scheduleHideMenuButton()

        cursorMode = prefs.getBoolean("cursor_mode", false)
        setCursorMode(cursorMode, false)

        // Load URL from intent or default, and remember which site it belongs to.
        val url = intent.getStringExtra("url") ?: HOME_URL
        currentSiteIndex = SiteManager.siteIndexForUrl(url).coerceAtLeast(0)
        webView.loadUrl(url)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableMenuButton(): FrameLayout {
        val density = resources.displayMetrics.density
        val sizePx = (48 * density).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            alpha = 0.85f
            elevation = 998f
            isFocusable = false
        }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                Color.parseColor("#E50914"),
                Color.parseColor("#B00710")
            )
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        container.background = bg

        val icon = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = false
        }
        container.addView(icon)

        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    scheduleHideMenuButton()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - startX) > 10 || Math.abs(event.rawY - startY) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        view.x = (event.rawX + dX).coerceIn(0f, (webView.width - view.width).toFloat())
                        view.y = (event.rawY + dY).coerceIn(0f, (webView.height - view.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        overlayMenu.showMainMenu()
                    } else {
                        saveButtonPosition(view.x, view.y)
                    }
                    scheduleHideMenuButton()
                    true
                }
                else -> false
            }
        }

        container.setOnLongClickListener {
            bookmarkCurrentPage()
            true
        }

        return container
    }

    private fun createCursorOverlay(): FrameLayout {
        val density = resources.displayMetrics.density
        val size = (30 * density).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            visibility = View.GONE
            elevation = 1000f
            isClickable = false
            isFocusable = false
        }

        val ring = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33E50914"))
                setStroke((2 * density).toInt(), Color.parseColor("#E50914"))
            }
        }

        val dot = View(this).apply {
            val dotSize = (6 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }

        container.addView(ring)
        container.addView(dot)
        return container
    }

    private fun createPlayerControls(): View {
        val density = resources.displayMetrics.density

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

        val feedback = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(), (10 * density).toInt(),
                (20 * density).toInt(), (10 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.parseColor("#D9000000"))
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        seekFeedbackText = feedback
        container.addView(feedback)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (40 * density).toInt(), (16 * density).toInt(),
                (40 * density).toInt(), (24 * density).toInt()
            )
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.TRANSPARENT, Color.parseColor("#E6000000"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            isClickable = false
            isFocusable = false
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val state = TextView(this).apply {
            text = "▶"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        playerStateText = state
        row.addView(state)

        val time = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = (8 * density).toInt()
            }
        }
        playerTimeText = time
        row.addView(time)

        val hint = TextView(this).apply {
            text = "OK Play/Pause · ◀▶ ±10s · ▲▼ ±60s · BACK Keluar"
            textSize = 11f
            setTextColor(Color.parseColor("#B3B3B3"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        playerHintText = hint
        row.addView(hint)

        bottomBar.addView(row)

        val seek = SeekBar(this).apply {
            max = 1000
            progress = 0
            isFocusable = false
            isClickable = false
            progressTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#55FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
        }
        playerSeekBar = seek
        bottomBar.addView(seek)

        container.addView(bottomBar)
        return container
    }

    private fun saveButtonPosition(x: Float, y: Float) {
        prefs.edit().putFloat("btn_x", x).putFloat("btn_y", y).apply()
    }

    private fun restoreButtonPosition() {
        val density = resources.displayMetrics.density
        menuButton?.x = prefs.getFloat("btn_x", 16 * density)
        menuButton?.y = prefs.getFloat("btn_y", 16 * density)
    }

    private fun scheduleHideMenuButton() {
        hideHandler.removeCallbacks(menuHideRunnable)
        menuButton?.animate()?.alpha(0.85f)?.setDuration(200)?.start()
        isMenuButtonVisible = true
        hideHandler.postDelayed(menuHideRunnable, 5000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isAdUrl(url)) return true
                // Never let an ad open a Play Store, Telegram or intent:// page.
                val scheme = request.url.scheme?.lowercase()
                if (scheme != null && scheme != "http" && scheme != "https") return true
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isAdUrl(url)) return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                currentUrl = url ?: ""
                val index = SiteManager.siteIndexForUrl(currentUrl)
                if (index >= 0) currentSiteIndex = index
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                currentTitle = view?.title ?: url ?: "Untitled"
                currentUrl = url ?: ""
                if (!url.isNullOrBlank() && !isAdUrl(url)) {
                    historyManager.addToHistory(currentTitle, currentUrl)
                }
                injectHelpers()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    showSnackbar("Gagal memuat halaman. Periksa koneksi internet.", "Retry") {
                        webView.reload()
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) progressBar.visibility = View.GONE
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback

                webView.visibility = View.GONE
                menuButton?.visibility = View.GONE
                cursorOverlay.visibility = View.GONE
                playerControls?.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE

                fullscreenContainer.addView(
                    view, 0,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                playerControls?.let {
                    if (it.parent == null) fullscreenContainer.addView(it)
                    else it.bringToFront()
                }

                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                startVideoPolling()
                showPlayerControls()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    private fun injectHelpers() {
        // Load TV navigation JS from assets
        try {
            val inputStream = assets.open("tv_nav.js")
            val js = inputStream.bufferedReader().use { it.readText() }
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // Fallback minimal injection
            val fallbackJs = """
                (function() {
                    window.open = function() { return null; };
                    document.querySelectorAll('meta[http-equiv="refresh"]').forEach(function(m) { m.remove(); });
                })();
            """.trimIndent()
            webView.evaluateJavascript(fallbackJs, null)
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return AD_BLOCK_LIST.any { lowerUrl.contains(it) }
    }

    private fun bookmarkCurrentPage() {
        if (currentUrl.isBlank()) {
            toast("Tidak ada halaman untuk di-bookmark")
            return
        }
        if (bookmarkManager.isBookmarked(currentUrl)) {
            bookmarkManager.removeBookmark(currentUrl)
            toast("⭐ Bookmark dihapus")
        } else {
            bookmarkManager.addBookmark(currentTitle, currentUrl)
            toast("⭐ Bookmark disimpan")
        }
    }

    /**
     * Snackbar-style notification with action button
     */
    private fun showSnackbar(message: String, actionText: String, action: () -> Unit) {
        val density = resources.displayMetrics.density
        val root = window.decorView.findViewById<FrameLayout>(android.R.id.content) ?: return

        val snackbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#DD222222"))
            }
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (32 * density).toInt()
            }
            elevation = 8f
        }

        snackbar.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        snackbar.addView(TextView(this).apply {
            text = actionText
            textSize = 13f
            setTextColor(Color.parseColor("#E50914"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding((16 * density).toInt(), 0, 0, 0)
            setOnClickListener {
                action()
                root.removeView(snackbar)
            }
        })

        root.addView(snackbar)

        // Auto dismiss after 5s
        uiHandler.postDelayed({
            root.removeView(snackbar)
        }, 5000)
    }

    // ==================== REMOTE / KEY HANDLING ====================

    private fun navigate(direction: String) {
        if (cursorMode) {
            when (direction) {
                "up" -> moveCursor(0, -1)
                "down" -> moveCursor(0, 1)
                "left" -> moveCursor(-1, 0)
                "right" -> moveCursor(1, 0)
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastNavAt < 35) return
        lastNavAt = now
        webView.evaluateJavascript(
            "window.__pixeltv_navigate && window.__pixeltv_navigate('$direction')",
            null
        )
    }

    private fun moveCursor(dx: Int, dy: Int) {
        if (!cursorMode) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCursorMoveAt < 40) return
        lastCursorMoveAt = now

        val step = (48 * resources.displayMetrics.density).toInt()
        val maxX = (webView.width - cursorOverlay.width).coerceAtLeast(0).toFloat()
        val maxY = (webView.height - cursorOverlay.height).coerceAtLeast(0).toFloat()

        cursorX = (cursorX + dx * step).coerceIn(0f, maxX)
        cursorY = (cursorY + dy * step).coerceIn(0f, maxY)
        cursorOverlay.x = cursorX
        cursorOverlay.y = cursorY
    }

    private fun tapCursor() {
        tapAt(
            cursorX + cursorOverlay.width / 2f,
            cursorY + cursorOverlay.height / 2f
        )
        cursorOverlay.animate()
            .scaleX(1.25f).scaleY(1.25f).setDuration(80)
            .withEndAction {
                cursorOverlay.animate()
                    .scaleX(1f).scaleY(1f).setDuration(80)
                    .start()
            }
            .start()
    }

    /**
     * Real touch event. Needed for iframes and players that check isTrusted.
     */
    private fun tapAt(x: Float, y: Float) {
        if (webView.width <= 1 || webView.height <= 1) return
        val px = x.coerceIn(1f, webView.width - 1f)
        val py = y.coerceIn(1f, webView.height - 1f)
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        uiHandler.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, px, py, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    private fun nativeTap(normalizedX: Double, normalizedY: Double) {
        if (normalizedX < 0 || normalizedY < 0) return
        tapAt(
            (normalizedX * webView.width).toFloat(),
            (normalizedY * webView.height).toFloat()
        )
    }

    private fun clickFocusedElement() {
        webView.evaluateJavascript(
            "window.__pixeltv_click ? window.__pixeltv_click() : null"
        ) { result ->
            val obj = parseJson(result) ?: return@evaluateJavascript
            if (obj.optBoolean("native", false)) {
                nativeTap(obj.optDouble("x", -1.0), obj.optDouble("y", -1.0))
            }
        }
    }

    private fun playBest() {
        webView.evaluateJavascript(
            "window.__pixeltv_play ? window.__pixeltv_play() : null"
        ) { result ->
            val obj = parseJson(result)
            if (obj == null) {
                toast("Navigasi belum siap, coba lagi")
                return@evaluateJavascript
            }
            if (obj.optBoolean("native", false)) {
                nativeTap(obj.optDouble("x", -1.0), obj.optDouble("y", -1.0))
            }
            val message = obj.optString("message", "")
            if (message.isNotBlank()) toast(message)
        }
    }

    private fun nextServer() {
        webView.evaluateJavascript(
            "window.__pixeltv_next_server ? window.__pixeltv_next_server() : null"
        ) { result ->
            val obj = parseJson(result)
            if (obj == null) {
                toast("Navigasi belum siap, coba lagi")
                return@evaluateJavascript
            }
            if (obj.optBoolean("native", false)) {
                nativeTap(obj.optDouble("x", -1.0), obj.optDouble("y", -1.0))
            }
            val message = obj.optString("message", "Server diganti")
            if (message.isNotBlank()) toast(message)
        }
    }

    private fun setCursorMode(enabled: Boolean, notify: Boolean = true) {
        cursorMode = enabled
        prefs.edit().putBoolean("cursor_mode", enabled).apply()

        if (enabled) {
            hideCustomView()
            webView.evaluateJavascript("window.__pixeltv_hide && window.__pixeltv_hide()", null)
            cursorOverlay.visibility = View.VISIBLE
            cursorOverlay.post {
                if (cursorX <= 0f && cursorY <= 0f) {
                    cursorX = ((webView.width - cursorOverlay.width) / 2f).coerceAtLeast(0f)
                    cursorY = ((webView.height - cursorOverlay.height) / 2f).coerceAtLeast(0f)
                    cursorOverlay.x = cursorX
                    cursorOverlay.y = cursorY
                }
            }
            if (notify) toast("🎯 Mode Kursor: D-pad gerak · OK tap · BACK keluar")
        } else {
            cursorOverlay.visibility = View.GONE
            if (notify) toast("Navigasi D-pad aktif")
        }
    }

    private fun parseJson(result: String?): JSONObject? {
        val text = result?.trim() ?: return null
        if (text.isEmpty() || text == "null" || text == "undefined") return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== FULLSCREEN PLAYER ====================

    private fun showPlayerControls() {
        if (customView == null) return
        val controls = playerControls ?: return
        uiHandler.removeCallbacks(hidePlayerControlsRunnable)
        controls.animate().cancel()
        controls.alpha = 1f
        controls.visibility = View.VISIBLE
        uiHandler.postDelayed(hidePlayerControlsRunnable, 3500)
    }

    private fun startVideoPolling() {
        stopVideoPolling()
        val runnable = object : Runnable {
            override fun run() {
                if (customView == null) return
                webView.evaluateJavascript(
                    "window.__pixeltv_video_state ? window.__pixeltv_video_state() : null"
                ) { result ->
                    val obj = parseJson(result)
                    if (obj == null) {
                        updatePlayerUi(0.0, 0.0, true, true)
                    } else {
                        updatePlayerUi(
                            obj.optDouble("t", 0.0),
                            obj.optDouble("d", 0.0),
                            obj.optBoolean("paused", true),
                            false
                        )
                    }
                }
                uiHandler.postDelayed(this, 500)
            }
        }
        videoPollRunnable = runnable
        uiHandler.post(runnable)
    }

    private fun stopVideoPolling() {
        videoPollRunnable?.let { uiHandler.removeCallbacks(it) }
        videoPollRunnable = null
    }

    private fun updatePlayerUi(
        position: Double,
        duration: Double,
        paused: Boolean,
        unavailable: Boolean
    ) {
        val seek = playerSeekBar ?: return
        playerStateText?.text = if (paused) "▶" else "⏸"

        if (unavailable || duration <= 0.0) {
            seek.progress = 0
            playerTimeText?.text = if (unavailable) "Kontrol pemutar situs" else "00:00 / 00:00"
            if (unavailable) {
                playerHintText?.text = "OK: tekan pemutar di layar · MENU: menu"
            }
            return
        }

        seek.progress = ((position / duration) * 1000.0).toInt().coerceIn(0, 1000)
        playerTimeText?.text = formatTime(position) + " / " + formatTime(duration)
        playerHintText?.text = "OK Play/Pause · ◀▶ ±10s · ▲▼ ±60s · BACK Keluar"
    }

    private fun formatTime(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return "--:--"
        val total = seconds.toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    private fun toggleVideo() {
        webView.evaluateJavascript(
            "window.__pixeltv_video_toggle ? window.__pixeltv_video_toggle() : false"
        ) { result ->
            if (result != "true") {
                // Player lives inside an iframe: tap the middle so its own
                // controls receive a trusted touch event.
                tapAt(webView.width / 2f, webView.height / 2f)
            }
        }
        showPlayerControls()
    }

    private fun seekVideo(seconds: Double) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt < 140) return
        lastSeekAt = now

        webView.evaluateJavascript(
            "window.__pixeltv_video_seek ? window.__pixeltv_video_seek($seconds) : false"
        ) { result ->
            if (result == "true") {
                showSeekFeedback((if (seconds > 0) "+" else "") + seconds.toInt() + "s")
            } else {
                showSeekFeedback("Seek tidak tersedia untuk pemutar ini")
            }
        }
        showPlayerControls()
    }

    private fun showSeekFeedback(message: String) {
        val text = seekFeedbackText ?: return
        text.animate().cancel()
        text.text = message
        text.alpha = 1f
        text.visibility = View.VISIBLE
        text.animate()
            .alpha(0f)
            .setStartDelay(700)
            .setDuration(250)
            .withEndAction { text.visibility = View.GONE }
            .start()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        fullscreenContainer.visibility = View.GONE
        playerControls?.visibility = View.GONE
        webView.visibility = View.VISIBLE
        menuButton?.visibility = View.VISIBLE
        if (cursorMode) cursorOverlay.visibility = View.VISIBLE

        stopVideoPolling()
        webView.requestFocus()
    }

    private fun exitFullscreen() {
        hideCustomView()
    }

    private fun handlePlayerKey(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if ((event?.repeatCount ?: 0) == 0) toggleVideo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekVideo(-10.0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekVideo(10.0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { seekVideo(60.0); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { seekVideo(-60.0); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                toggleVideo(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { toggleVideo(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { seekVideo(60.0); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { seekVideo(-60.0); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { seekVideo(60.0); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { seekVideo(-60.0); return true }
            KeyEvent.KEYCODE_BACK -> { hideCustomView(); return true }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (customView != null && handlePlayerKey(keyCode, event)) return true

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (cursorMode) {
                    setCursorMode(false)
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
                showExitDialog()
                return true
            }

            // D-pad navigation → JS navigation layer (or cursor mode)
            KeyEvent.KEYCODE_DPAD_UP -> { navigate("up"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navigate("down"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navigate("left"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navigate("right"); return true }

            // OK/Enter → click focused element after a short press, menu on long press
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if ((event?.repeatCount ?: 0) == 0) {
                    okDownAt = SystemClock.uptimeMillis()
                    okLongPressTriggered = false
                    uiHandler.removeCallbacks(okLongPressRunnable)
                    uiHandler.postDelayed(okLongPressRunnable, 620)
                }
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_MEDIA_TOP_MENU,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                overlayMenu.showMainMenu()
                return true
            }

            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_BOOKMARK -> {
                bookmarkCurrentPage()
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> { switchSite(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchSite(-1); return true }

            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { playBest(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { toggleVideo(); return true }

            // Colored buttons are not on every remote, but when they exist they
            // turn the most common actions into one press.
            KeyEvent.KEYCODE_PROG_RED -> { playBest(); return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { hideCustomView(); nextServer(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                hideCustomView()
                overlayMenu.showSiteSelector()
                return true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                hideCustomView()
                showSearchDialog()
                return true
            }

            KeyEvent.KEYCODE_SEARCH -> {
                hideCustomView()
                showSearchDialog()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (customView != null) return true
            uiHandler.removeCallbacks(okLongPressRunnable)
            if (okDownAt != 0L && !okLongPressTriggered) {
                if (cursorMode) tapCursor() else clickFocusedElement()
            }
            okDownAt = 0L
            okLongPressTriggered = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showExitDialog() {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Keluar dari PIXELTV?")
            .setMessage("Yakin mau keluar dari aplikasi?")
            .setPositiveButton("Keluar") { _, _ -> finish() }
            .setNegativeButton("Batal", null)
            .setNeutralButton("🏠 Home") { _, _ ->
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun showSearchDialog() {
        val density = resources.displayMetrics.density

        val input = EditText(this).apply {
            hint = "Cari judul film..."
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt()
            )
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("🔍 Cari Film")
            .setView(input)
            .setPositiveButton("Cari") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchCurrentSite(query)
                }
            }
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun searchCurrentSite(query: String) {
        val site = SiteManager.siteForUrl(currentUrl)
            ?: SiteManager.sites.getOrNull(currentSiteIndex)
            ?: SiteManager.sites.firstOrNull()
            ?: return
        hideCustomView()
        webView.loadUrl(site.buildSearchUrl(query))
    }

    private fun switchSite(direction: Int) {
        val sites = SiteManager.sites
        if (sites.isEmpty()) return
        currentSiteIndex = (currentSiteIndex + direction + sites.size) % sites.size
        val site = sites[currentSiteIndex]
        hideCustomView()
        webView.loadUrl(site.url)
        toast("${site.icon} ${site.name}")
    }

    private fun toast(message: String) {
        if (message.isNotBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.requestFocus()
        if (cursorMode && customView == null) {
            cursorOverlay.visibility = View.VISIBLE
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        if (customView != null) startVideoPolling()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        if (customView != null) stopVideoPolling()
    }

    override fun onDestroy() {
        hideHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacksAndMessages(null)
        stopVideoPolling()
        webView.destroy()
        super.onDestroy()
    }
}
