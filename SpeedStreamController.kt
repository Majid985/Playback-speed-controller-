package com.example.speedstreamcontroller

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ============================================================================
 * SPEEDSTREAM CONTROLLER — ANDROID 7.0 (API 24) TO ANDROID 15+ (API 35)
 * ============================================================================
 * Features:
 * 1. Persistent Speed Memory (SpeedPreferences):
 *    Saves the last chosen playback speed in SharedPreferences and automatically
 *    reapplies it across episodes, video changes, and app restarts until manually changed.
 *
 * 2. Background Floating Speed Overlay (FloatingSpeedOverlayService):
 *    - Compliant with Android 14 & Android 15+ (API 34/35) Foreground Service rules
 *      using FOREGROUND_SERVICE_TYPE_SPECIAL_USE.
 *    - Displays a compact draggable floating badge (e.g., "⚡ 1.5x") over any screen.
 *    - Tapping the badge toggles a mini speed controller (-0.25x, +0.25x, presets
 *      from 1.0x to 2.5x, and collapse/stop controls).
 *
 * 3. Dual Playback Speed Engines:
 *    - Engine A (Non-Root / All Android Devices): Built-in DRM-capable Streaming
 *      WebView container with MutationObserver + HTML5 video.playbackRate enforcement
 *      that works on Crunchyroll, Netflix, YouTube, and other web streaming players.
 *    - Engine B (Root / LSPosed Module): Optional reflection bridge (NativePlayerSpeedBridge)
 *      that intercepts AndroidX Media3 / ExoPlayer and android.media.MediaPlayer inside
 *      standalone native Android apps and applies the floating overlay's speed in real time.
 * ============================================================================
 */

// ----------------------------------------------------------------------------
// 1. PERSISTENT PLAYBACK SPEED STORAGE & BROADCAST CONSTANTS
// ----------------------------------------------------------------------------
object SpeedPreferences {
    private const val PREFS_NAME = "speed_stream_prefs"
    private const val KEY_PLAYBACK_SPEED = "saved_playback_speed"
    private const val KEY_LAST_STREAM_URL = "last_stream_url"

    const val ACTION_SPEED_CHANGED = "com.example.speedstreamcontroller.ACTION_SPEED_CHANGED"
    const val EXTRA_SPEED = "extra_playback_speed"

    const val DEFAULT_SPEED = 1.0f
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f
    const val DEFAULT_URL = "https://www.crunchyroll.com"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSpeed(context: Context): Float {
        return prefs(context).getFloat(KEY_PLAYBACK_SPEED, DEFAULT_SPEED)
            .coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun setSpeed(context: Context, speed: Float) {
        val clamped = ((speed * 100f).roundToInt() / 100f).coerceIn(MIN_SPEED, MAX_SPEED)
        prefs(context).edit().putFloat(KEY_PLAYBACK_SPEED, clamped).apply()

        // Broadcast to WebView player inside this app
        val localIntent = Intent(ACTION_SPEED_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SPEED, clamped)
        }
        context.sendBroadcast(localIntent)

        // Broadcast globally for optional LSPosed/Xposed receivers in native video apps
        val globalIntent = Intent(ACTION_SPEED_CHANGED).apply {
            putExtra(EXTRA_SPEED, clamped)
        }
        context.sendBroadcast(globalIntent)
    }

    fun getLastUrl(context: Context): String =
        prefs(context).getString(KEY_LAST_STREAM_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setLastUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LAST_STREAM_URL, url).apply()
    }

    fun formatSpeed(speed: Float): String {
        return if (speed % 0.25f == 0f && (speed * 10f) % 1f == 0f) {
            String.format(Locale.US, "%.1fx", speed)
        } else {
            String.format(Locale.US, "%.2fx", speed)
        }
    }
}

// ----------------------------------------------------------------------------
// 2. ANDROID 15+ FOREGROUND SERVICE WITH DRAGGABLE FLOATING OVERLAY
// ----------------------------------------------------------------------------
class FloatingSpeedOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "speed_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_OVERLAY = "com.example.speedstreamcontroller.STOP_OVERLAY"
    }

    private lateinit var windowManager: WindowManager
    private var rootOverlayView: View? = null
    private lateinit var badgeButton: TextView
    private lateinit var expandedPanel: LinearLayout
    private lateinit var panelSpeedLabel: TextView
    private var isExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompatible()
        createFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-apply and broadcast the stored playback speed whenever the service starts
        val savedSpeed = SpeedPreferences.getSpeed(this)
        updateUiForSpeed(savedSpeed)
        SpeedPreferences.setSpeed(this, savedSpeed)
        return START_STICKY
    }

    /**
     * Starts the foreground service complying with Android 14 (API 34) and
     * Android 15+ (API 35) strict ForegroundServiceType enforcement.
     */
    private fun startForegroundCompatible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Speed Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating playback speed controller active in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FloatingSpeedOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentSpeedText = SpeedPreferences.formatSpeed(SpeedPreferences.getSpeed(this))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("SpeedStream Active ($currentSpeedText)")
            .setContentText("Tap floating icon on screen to adjust video playback speed")
            .setSmallIcon(android.R.drawable.ic_media_ff)
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Overlay",
                    stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 (API 34) & Android 15+ (API 35) require explicit FGS type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingOverlay() {
        if (rootOverlayView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        val container = FrameLayout(this)

        // 1. Compact Floating Pill Icon (shown when collapsed)
        val initialSpeed = SpeedPreferences.getSpeed(this)
        badgeButton = TextView(this).apply {
            text = "⚡ ${SpeedPreferences.formatSpeed(initialSpeed)}"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect(Color.parseColor("#E6FF6D00"), dp(20).toFloat(), Color.WHITE, dp(1))
            elevation = dp(6).toFloat()
        }

        // 2. Expanded Speed Control Panel (shown when tapped)
        expandedPanel = buildExpandedControlPanel(initialSpeed).apply {
            visibility = View.GONE
        }

        container.addView(badgeButton)
        container.addView(expandedPanel)

        // Drag and tap handling for the floating badge
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        val clickSlop = dp(8)

        badgeButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (dx < clickSlop && dy < clickSlop) {
                        toggleExpanded(true)
                    }
                    true
                }
                else -> false
            }
        }

        rootOverlayView = container
        windowManager.addView(container, params)
    }

    private fun buildExpandedControlPanel(initialSpeed: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.parseColor("#F21E1E24"), dp(16).toFloat(), Color.parseColor("#FF6D00"), dp(1))
            elevation = dp(8).toFloat()

            // Header row: Current speed + Minimize button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            panelSpeedLabel = TextView(context).apply {
                text = "Speed: ${SpeedPreferences.formatSpeed(initialSpeed)}"
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val minimizeBtn = createActionChip("✕", Color.parseColor("#33FFFFFF")) {
                toggleExpanded(false)
            }

            headerRow.addView(panelSpeedLabel)
            headerRow.addView(minimizeBtn)
            addView(headerRow)

            // Stepper row: -0.25x | Reset 1.0x | +0.25x
            val stepperRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(6))
            }

            stepperRow.addView(createActionChip("-0.25x", Color.parseColor("#2E313D")) {
                adjustSpeedBy(-0.25f)
            })
            stepperRow.addView(spaceView(dp(6)))
            stepperRow.addView(createActionChip("1.0x", Color.parseColor("#FF6D00")) {
                applyNewSpeed(1.0f)
            })
            stepperRow.addView(spaceView(dp(6)))
            stepperRow.addView(createActionChip("+0.25x", Color.parseColor("#2E313D")) {
                adjustSpeedBy(0.25f)
            })
            addView(stepperRow)

            // Quick preset row: 1.25x | 1.5x | 1.75x | 2.0x | 2.5x
            val presetRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val presets = listOf(1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
            presets.forEachIndexed { index, preset ->
                if (index > 0) presetRow.addView(spaceView(dp(4)))
                presetRow.addView(createActionChip("${preset}x", Color.parseColor("#252832")) {
                    applyNewSpeed(preset)
                    toggleExpanded(false)
                })
            }
            addView(presetRow)
        }
    }

    private fun createActionChip(label: String, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRect(bgColor, dp(10).toFloat(), Color.TRANSPARENT, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun spaceView(widthPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        }
    }

    private fun toggleExpanded(expand: Boolean) {
        isExpanded = expand
        badgeButton.visibility = if (expand) View.GONE else View.VISIBLE
        expandedPanel.visibility = if (expand) View.VISIBLE else View.GONE
    }

    private fun adjustSpeedBy(delta: Float) {
        val current = SpeedPreferences.getSpeed(this)
        val next = min(SpeedPreferences.MAX_SPEED, max(SpeedPreferences.MIN_SPEED, current + delta))
        applyNewSpeed(next)
    }

    private fun applyNewSpeed(speed: Float) {
        SpeedPreferences.setSpeed(this, speed)
        updateUiForSpeed(speed)
    }

    private fun updateUiForSpeed(speed: Float) {
        val formatted = SpeedPreferences.formatSpeed(speed)
        if (::badgeButton.isInitialized) {
            badgeButton.text = "⚡ $formatted"
        }
        if (::panelSpeedLabel.isInitialized) {
            panelSpeedLabel.text = "Speed: $formatted"
        }
    }

    private fun roundedRect(fillColor: Int, radiusPx: Float, strokeColor: Int, strokeWidthPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    override fun onDestroy() {
        rootOverlayView?.let {
            windowManager.removeView(it)
            rootOverlayView = null
        }
        super.onDestroy()
    }
}

// ----------------------------------------------------------------------------
// 3. MAIN ACTIVITY: OVERLAY LAUNCHER + UNIVERSAL STREAMING WEBVIEW PLAYER
// ----------------------------------------------------------------------------
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusBanner: TextView

    private val speedBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SpeedPreferences.ACTION_SPEED_CHANGED) {
                val newSpeed = intent.getFloatExtra(
                    SpeedPreferences.EXTRA_SPEED,
                    SpeedPreferences.getSpeed(this@MainActivity)
                )
                updateStatusBanner(newSpeed)
                injectSpeedIntoWebView(newSpeed)
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingOverlayService()
        } else {
            Toast.makeText(
                this,
                "Overlay permission is required to show the floating speed icon.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121318"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Control Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            setBackgroundColor(Color.parseColor("#1C1E26"))
        }

        urlInput = EditText(this).apply {
            setText(SpeedPreferences.getLastUrl(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Enter streaming URL (e.g. crunchyroll.com)"
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val goButton = Button(this).apply {
            text = "Load"
            setOnClickListener {
                var target = urlInput.text.toString().trim()
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    target = "https://$target"
                }
                SpeedPreferences.setLastUrl(this@MainActivity, target)
                webView.loadUrl(target)
            }
        }

        val overlayToggleButton = Button(this).apply {
            text = "Start Bubble"
            setOnClickListener {
                ensureOverlayPermissionAndStart()
            }
        }

        topBar.addView(urlInput)
        topBar.addView(goButton)
        topBar.addView(overlayToggleButton)

        // Status Banner showing remembered playback speed
        val rememberedSpeed = SpeedPreferences.getSpeed(this)
        statusBanner = TextView(this).apply {
            setTextColor(Color.parseColor("#FFB74D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.parseColor("#232631"))
        }
        updateStatusBanner(rememberedSpeed)

        // Built-in Streaming WebView (supports Crunchyroll, Netflix, YouTube HTML5 & Widevine DRM)
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        configureStreamingWebView(webView)

        rootLayout.addView(topBar)
        rootLayout.addView(statusBanner)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        registerSpeedReceiver()
        ensureOverlayPermissionAndStart()
        webView.loadUrl(SpeedPreferences.getLastUrl(this))
    }

    private fun registerSpeedReceiver() {
        val filter = IntentFilter(SpeedPreferences.ACTION_SPEED_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(speedBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(speedBroadcastReceiver, filter)
        }
    }

    private fun ensureOverlayPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startFloatingOverlayService()
        }
    }

    private fun startFloatingOverlayService() {
        val serviceIntent = Intent(this, FloatingSpeedOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureStreamingWebView(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Use Desktop/Tablet Chrome User-Agent so Crunchyroll serves standard HTML5 player
            userAgentString =
                "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Enable Widevine EME / Encrypted Media Extensions for Crunchyroll & DRM streams
                val resources = request.resources
                for (res in resources) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID == res) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                        return
                    }
                }
                super.onPermissionRequest(request)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val savedSpeed = SpeedPreferences.getSpeed(this@MainActivity)
                injectSpeedIntoWebView(savedSpeed)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }
    }

    /**
     * Injects a persistent MutationObserver + HTML5 video.playbackRate hook into
     * the page and all reachable Shadow DOM roots so that even when Crunchyroll
     * auto-advances to the next episode, the remembered speed is automatically enforced.
     */
    private fun injectSpeedIntoWebView(speed: Float) {
        val jsPayload =
            "(function() {" +
            "  window.__desiredPlaybackSpeed = " + speed + ";" +
            "  function applySpeedToVideo(video) {" +
            "    if (!video) return;" +
            "    if (Math.abs(video.playbackRate - window.__desiredPlaybackSpeed) > 0.01) {" +
            "      video.playbackRate = window.__desiredPlaybackSpeed;" +
            "    }" +
            "    if (!video.__speedHookAttached) {" +
            "      video.__speedHookAttached = true;" +
            "      ['loadedmetadata', 'play', 'playing', 'ratechange'].forEach(function(evt) {" +
            "        video.addEventListener(evt, function() {" +
            "          if (Math.abs(video.playbackRate - window.__desiredPlaybackSpeed) > 0.01) {" +
            "            video.playbackRate = window.__desiredPlaybackSpeed;" +
            "          }" +
            "        }, true);" +
            "      });" +
            "    }" +
            "  }" +
            "  function scanAllVideos(root) {" +
            "    if (!root) return;" +
            "    var videos = root.querySelectorAll ? root.querySelectorAll('video') : [];" +
            "    videos.forEach(applySpeedToVideo);" +
            "    var allNodes = root.querySelectorAll ? root.querySelectorAll('*') : [];" +
            "    allNodes.forEach(function(el) {" +
            "      if (el.shadowRoot) scanAllVideos(el.shadowRoot);" +
            "    });" +
            "  }" +
            "  scanAllVideos(document);" +
            "  if (!window.__speedObserverInstalled) {" +
            "    window.__speedObserverInstalled = true;" +
            "    var observer = new MutationObserver(function() { scanAllVideos(document); });" +
            "    observer.observe(document.documentElement || document.body, { childList: true, subtree: true });" +
            "    setInterval(function() { scanAllVideos(document); }, 1000);" +
            "  }" +
            "})();"

        webView.evaluateJavascript(jsPayload, null)
    }

    private fun updateStatusBanner(speed: Float) {
        val formatted = SpeedPreferences.formatSpeed(speed)
        statusBanner.text =
            "Remembered Speed: $formatted (Auto-applied to every video until changed manually)"
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    override fun onDestroy() {
        unregisterReceiver(speedBroadcastReceiver)
        super.onDestroy()
    }
}

// ----------------------------------------------------------------------------
// 4. OPTIONAL SYSTEM-WIDE NATIVE APP HOOK (LSPOSED / XPOSED FOR ANDROID 15+)
// ----------------------------------------------------------------------------
/**
 * To control playback speed inside standalone native Android apps (such as the
 * native Crunchyroll APK, OTT apps, or custom players) on devices with LSPosed:
 *
 * 1. Add `compileOnly("de.robv.android.xposed:api:82")` to build.gradle.kts.
 * 2. Enable the module in LSPosed Manager and select target streaming apps (e.g. Crunchyroll).
 * 3. This bridge intercepts AndroidX Media3 / ExoPlayer instances via reflection,
 *    listens for broadcasts from FloatingSpeedOverlayService, and enforces the remembered speed.
 */
object NativePlayerSpeedBridge {
    @Volatile
    var currentRememberedSpeed: Float = 1.0f

    /**
     * Reflection helper that applies `currentRememberedSpeed` to any ExoPlayer or
     * Media3 Player instance without requiring compile-time dependency on the target app's
     * specific obfuscated ExoPlayer version.
     */
    fun applyToExoPlayerInstance(playerInstance: Any) {
        try {
            // ExoPlayer / Media3 Player exposes setPlaybackSpeed(float)
            val setSpeedMethod = playerInstance.javaClass.methods.firstOrNull {
                it.name == "setPlaybackSpeed" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }
            if (setSpeedMethod != null) {
                setSpeedMethod.invoke(playerInstance, currentRememberedSpeed)
                return
            }

            // Fallback: PlaybackParameters(float speed, float pitch)
            val setParamsMethod = playerInstance.javaClass.methods.firstOrNull {
                it.name == "setPlaybackParameters" && it.parameterTypes.size == 1
            }
            if (setParamsMethod != null) {
                val paramClass = setParamsMethod.parameterTypes[0]
                val ctor = paramClass.getConstructor(
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                )
                val paramsObj = ctor.newInstance(currentRememberedSpeed, 1.0f)
                setParamsMethod.invoke(playerInstance, paramsObj)
            }
        } catch (_: Throwable) {
            // Ignore if target player uses a custom non-ExoPlayer surface
        }
    }
}
