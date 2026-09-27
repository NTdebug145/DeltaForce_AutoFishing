package kt.dfautofish

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingWindowService : Service() {

    companion object {
        const val DEFAULT_FRAME_W = 50
        const val DEFAULT_FRAME_H = 50
        const val FRAME_STROKE = 2
        const val INTERVAL_MS = 200L

        const val RED_CHANNEL_MIN = 130
        const val RED_DIFF_MIN = 50
        const val RATIO_NO_RED_MAX = 0.01f

        private const val TAG = "DFAutoFish"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager

    private var btnA: View? = null
    private var btnB: View? = null
    private var frameView: View? = null
    private var panelView: View? = null

    private var lpA: WindowManager.LayoutParams? = null
    private var lpB: WindowManager.LayoutParams? = null
    private var lpFrame: WindowManager.LayoutParams? = null

    @Volatile private var frameX = 100
    @Volatile private var frameY = 300
    @Volatile private var frameW = DEFAULT_FRAME_W
    @Volatile private var frameH = DEFAULT_FRAME_H

    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callbackRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var autoFishing = false
    @Volatile private var busy = false

    private var pieChart: PieChartView? = null
    private var statusText: TextView? = null
    private var toggle: Switch? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateScreenSize()
        }
    }

    private fun updateScreenSize() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi
        Log.d(TAG, "screen changed: ${screenW}x${screenH} dpi=$screenDpi")
        if (mediaProjection != null) createVirtualDisplay()
        mainHandler.post { statusText?.text = "方向: ${screenW}x${screenH}" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi
        Log.d(TAG, "onCreate screen: ${screenW}x${screenH} dpi=$screenDpi")
        Log.d(TAG, "density=${resources.displayMetrics.density}")

        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra("data")
            if (mediaProjection == null && resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                setupMediaProjection()
            }
        }

        if (btnA == null) {
            createAllWindows()
            mainHandler.postDelayed(analysisRunnable, INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        listOf(btnA, btnB, frameView, panelView).forEach {
            if (it != null) runCatching { windowManager.removeView(it) }
        }
    }

    private fun startForegroundCompat() {
        val channelId = "dfautofish"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "自动钓鱼",
                        NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DFAutoFish 运行中")
            .setContentText("悬浮窗已启动")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupMediaProjection() {
        val mp = mediaProjection ?: return
        if (!callbackRegistered) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { statusText?.text = "屏幕录制已停止" }
                    runCatching { virtualDisplay?.release() }
                    runCatching { imageReader?.close() }
                    virtualDisplay = null
                    imageReader = null
                    mediaProjection = null
                    callbackRegistered = false
                }
            }, mainHandler)
            callbackRegistered = true
        }
        createVirtualDisplay()
    }

    private fun createVirtualDisplay() {
        val mp = mediaProjection ?: return
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "DFAutoFishCapture",
            screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun createAllWindows() {
        createBtnA(); createBtnB(); createFrame(); createPanel()
    }

    private fun makeCircleButton(label: String, colorStr: String): View {
        val size = dp(72)
        val tv = TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(colorStr))
            }
        }
        tv.layoutParams = ViewGroup.LayoutParams(size, size)
        return tv
    }

    private fun createBtnA() {
        val view = makeCircleButton("A", "#80FF5722")
        val size = dp(72)
        val p = overlayParams(size, size, 100, 100)
        attachDrag(view, p) { autoFishing }
        lpA = p
        windowManager.addView(view, p)
        btnA = view
        Log.d(TAG, "A created at (100,100) size=$size")
    }

    private fun createBtnB() {
        val view = makeCircleButton("B", "#802196F3")
        val size = dp(72)
        val p = overlayParams(size, size, screenW - size - dp(40), 100)
        attachDrag(view, p) { autoFishing }
        lpB = p
        windowManager.addView(view, p)
        btnB = view
    }

    private fun createFrame() {
        val view = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setStroke(dp(FRAME_STROKE), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
        }
        val p = overlayParams(dp(frameW), dp(frameH), 100, 300)
        attachDrag(view, p) { autoFishing }
        lpFrame = p
        windowManager.addView(view, p)
        frameView = view
    }

    private fun createPanel() {
        val panel = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        val panelW = dp(150)
        val panelH = dp(340)
        val p = overlayParams(panelW, panelH,
            screenW - panelW - dp(20), screenH - panelH - dp(100))
        attachDrag(panel, p) { false }

        pieChart = panel.findViewById(R.id.pieChart)
        statusText = panel.findViewById(R.id.statusText)
        toggle = panel.findViewById(R.id.fishingToggle)

        val inputW = panel.findViewById<EditText>(R.id.inputW)
        val inputH = panel.findViewById<EditText>(R.id.inputH)
        inputW.setText(frameW.toString())
        inputH.setText(frameH.toString())

        toggle?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            autoFishing = checked
            statusText?.text = if (checked) "运行中…" else "已暂停"
        }

        panel.findViewById<Button>(R.id.btnApplySize).setOnClickListener {
            val w = inputW.text.toString().toIntOrNull() ?: frameW
            val h = inputH.text.toString().toIntOrNull() ?: frameH
            applyFrameSize(w.coerceIn(10, 500), h.coerceIn(10, 500))
        }

        // 测试点击 A
        panel.findViewById<Button>(R.id.btnTestClick).setOnClickListener {
            testClickA()
        }

        // 测试点击屏幕中心（判断坐标偏移）
        panel.findViewById<Button>(R.id.btnTestCenter).setOnClickListener {
            testClickCenter()
        }

        windowManager.addView(panel, p)
        panelView = panel
    }

    private fun applyFrameSize(wDp: Int, hDp: Int) {
        frameW = wDp
        frameH = hDp
        val p = lpFrame ?: return
        p.width = dp(wDp)
        p.height = dp(hDp)
        frameView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        statusText?.text = "红框: ${wDp}x${hDp}"
    }

    /** 测试点击 A 按钮中心 */
    private fun testClickA() {
        val svc = AccessibilityClicker.instance
        if (svc == null) {
            statusText?.text = "测试: 无障碍未连接"
            Log.e(TAG, "testClickA: svc==null")
            return
        }
        val a = lpA ?: return
        val cx = a.x + a.width / 2f
        val cy = a.y + a.height / 2f

        Log.d(TAG, "testClickA: A param x=${a.x} y=${a.y} w=${a.width} h=${a.height}")
        Log.d(TAG, "testClickA: center=($cx,$cy)")

        statusText?.text = "点A(${cx.toInt()},${cy.toInt()})"

        svc.clickAt(cx, cy) { ok ->
            Log.d(TAG, "testClickA result=$ok")
            mainHandler.post {
                statusText?.text = "A结果: ${if (ok) "OK" else "失败"}"
            }
        }
    }

    /** 测试点击屏幕几何中心，用来判断坐标是否偏移 */
    private fun testClickCenter() {
        val svc = AccessibilityClicker.instance
        if (svc == null) {
            statusText?.text = "测试: 无障碍未连接"
            return
        }
        val cx = screenW / 2f
        val cy = screenH / 2f
        Log.d(TAG, "testClickCenter: screen=${screenW}x${screenH} center=($cx,$cy)")
        statusText?.text = "点中心(${cx.toInt()},${cy.toInt()})"

        svc.clickAt(cx, cy) { ok ->
            Log.d(TAG, "testClickCenter result=$ok")
            mainHandler.post {
                statusText?.text = "中心结果: ${if (ok) "OK" else "失败"}"
            }
        }
    }

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        locked: () -> Boolean
    ) {
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (locked()) return@setOnTouchListener true
                    startX = params.x; startY = params.y
                    rawX = event.rawX; rawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked()) return@setOnTouchListener true
                    params.x = startX + (event.rawX - rawX).toInt()
                    params.y = startY + (event.rawY - rawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    if (view === frameView) {
                        frameX = params.x
                        frameY = params.y
                    }
                    true
                }
                else -> false
            }
        }
    }

    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (autoFishing && !busy) captureAndAnalyze()
            mainHandler.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun captureAndAnalyze() {
        busy = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureWithTakeScreenshot { bmp ->
                val ratio = analyzeBitmap(bmp)
                bmp.recycle()
                onRatioReady(ratio)
            }
        } else {
            captureWithMediaProjection { bmp ->
                val ratio = analyzeBitmap(bmp)
                bmp.recycle()
                onRatioReady(ratio)
            }
        }
    }

    private fun captureWithTakeScreenshot(onSuccess: (Bitmap) -> Unit) {
        val svc = AccessibilityClicker.instance
        if (svc == null) {
            statusText?.text = "无障碍未开启"
            busy = false
            return
        }
        svc.takeScreenshot(
            Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (bmp != null) onSuccess(bmp)
                        else { statusText?.text = "截屏返回空"; busy = false }
                    } catch (e: Throwable) {
                        statusText?.text = "异常: ${e.javaClass.simpleName}"
                        busy = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false
                    statusText?.text = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                            "截图太频繁，等下一轮…"
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                            "无障碍无截屏权限"
                        else -> "截图失败: $errorCode"
                    }
                }
            })
    }

    private fun captureWithMediaProjection(onSuccess: (Bitmap) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            statusText?.text = "截图未就绪（无ImageReader）"
            busy = false
            return
        }
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
        if (image == null) {
            statusText?.text = "等待屏幕帧…"
            busy = false
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val frameWPx = image.width
            val frameHPx = image.height
            val rowPadding = rowStride - pixelStride * frameWPx
            val bmpW = frameWPx + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(bmpW, frameHPx, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            buffer.rewind()
            onSuccess(bmp)
        } catch (e: Throwable) {
            statusText?.text = "异常: ${e.javaClass.simpleName}"
            busy = false
        } finally {
            runCatching { image.close() }
        }
    }

    private fun isReddish(r: Int, g: Int, b: Int): Boolean {
        return r >= RED_CHANNEL_MIN &&
               (r - g) >= RED_DIFF_MIN &&
               (r - b) >= RED_DIFF_MIN
    }

    private fun analyzeBitmap(bmp: Bitmap): Float {
        val wPx = dp(frameW)
        val hPx = dp(frameH)
        val x = frameX.coerceIn(0, (bmp.width - wPx).coerceAtLeast(0))
        val y = frameY.coerceIn(0, (bmp.height - hPx).coerceAtLeast(0))
        val cw = minOf(wPx, bmp.width - x)
        val ch = minOf(hPx, bmp.height - y)
        if (cw <= 0 || ch <= 0) return 0f

        val pixels = IntArray(cw * ch)
        bmp.getPixels(pixels, 0, cw, x, y, cw, ch)

        var redCount = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (isReddish(r, g, b)) redCount++
        }
        return redCount.toFloat() / pixels.size
    }

    private fun onRatioReady(ratio: Float) {
        val hasRed = ratio >= RATIO_NO_RED_MAX

        mainHandler.post {
            pieChart?.setRatio(ratio)
            statusText?.text = if (hasRed) {
                "有红色: ${(ratio * 100).toInt()}%"
            } else {
                "无红色: ${(ratio * 100).toInt()}% → 点击中"
            }
        }

        if (hasRed) {
            busy = false
        } else {
            triggerClicks()
        }
    }

    private fun triggerClicks() {
        val svc = AccessibilityClicker.instance
        if (svc == null) {
            statusText?.text = "点击失败: 无障碍未连接"
            busy = false
            return
        }
        val a = lpA
        val b = lpB
        if (a == null || b == null) {
            statusText?.text = "点击失败: 坐标丢失"
            busy = false
            return
        }
        val ax = a.x + a.width / 2f
        val ay = a.y + a.height / 2f
        val bx = b.x + b.width / 2f
        val by = b.y + b.height / 2f

        Log.d(TAG, "triggerClicks: A($ax,$ay) B($bx,$by)")
        statusText?.text = "点A(${ax.toInt()},${ay.toInt()})"

        svc.clickAt(ax, ay) { ok1 ->
            Log.d(TAG, "click A #1 ok=$ok1")
        }

        mainHandler.postDelayed({
            svc.clickAt(ax, ay) { ok2 ->
                Log.d(TAG, "click A #2 ok=$ok2")
            }
            mainHandler.postDelayed({
                svc.clickAt(bx, by) { ok3 ->
                    Log.d(TAG, "click B ok=$ok3")
                    mainHandler.postDelayed({ busy = false }, 300)
                }
            }, 300)
        }, 2000)
    }
}