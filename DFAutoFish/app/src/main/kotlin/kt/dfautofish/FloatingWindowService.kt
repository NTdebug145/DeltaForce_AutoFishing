package kt.dfautofish

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "DFAutoFish"

        private const val BUTTON_SIZE_DP = 72

        // 检测框：20 x 60 dp
        private const val FRAME_W_DP = 20
        private const val FRAME_H_DP = 60
        private const val FRAME_STROKE_DP = 2

        private const val ANALYSIS_INTERVAL_MS = 200L

        private const val HIDE_DELAY_MS = 50L
        private const val RESTORE_DELAY_MS = 150L

        private const val WAIT_AFTER_A1_MS = 2000L
        private const val WAIT_BETWEEN_A2_B_MS = 200L
        private const val WAIT_AFTER_B_MS = 1000L

        /**
         * 检测色段默认范围（0~1，对应 R 值 0~255）。
         */
        private const val DEFAULT_MIN_T = 0.15f
        private const val DEFAULT_MAX_T = 0.80f

        /**
         * G、B 通道允许的最大距离平方。
         * 距离 = sqrt(g² + b²)，用距离平方比较避免开方。
         *
         * 130² = 16900：
         *   - 暗红/棕红 (g≈30~75, b≈25~65) 距离平方约 4000~9000，都能匹配
         *   - 灰色 (g=b=128) 距离平方 = 32768 → 排除
         *   - 白色、浅粉也都能被合理排除
         */
        private const val MAX_GB_DIST_SQ = 130 * 130

        /**
         * 命中率阈值：框内至少 0.5% 的像素命中，才算"检测到"。
         */
        private const val HIT_RATIO_THRESHOLD = 0.005f

        // 调试日志开关
        private const val DEBUG_FRAME_LOG = true
    }

    // ---------- 系统 ----------
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- 悬浮窗 ----------
    private var viewA: View? = null
    private var viewB: View? = null
    private var viewFrame: View? = null
    private var viewPanel: View? = null

    private var lpA: WindowManager.LayoutParams? = null
    private var lpB: WindowManager.LayoutParams? = null
    private var lpFrame: WindowManager.LayoutParams? = null
    private var lpPanel: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var rangeText: TextView? = null
    private var fishingToggle: Switch? = null
    private var colorSlider: ColorRangeSlider? = null

    // ---------- 屏幕 ----------
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    // ---------- 检测框（屏幕像素坐标）----------
    @Volatile private var frameX = 0
    @Volatile private var frameY = 0
    private var frameW = 0
    private var frameH = 0

    // ---------- 截屏 ----------
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallbackRegistered = false

    // ---------- 状态 ----------
    @Volatile private var autoFishing = false
    @Volatile private var sequenceInProgress = false
    @Volatile private var analysisInProgress = false

    // ★ 已删除 frameFromProjection —— 改用双通道兼容方案

    /** 用户选择的色段范围（0~1） */
    @Volatile private var minColorT = DEFAULT_MIN_T
    @Volatile private var maxColorT = DEFAULT_MAX_T

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateScreenSize()
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        refreshScreenMetrics()
        frameW = dp(FRAME_W_DP)
        frameH = dp(FRAME_H_DP)

        displayManager.registerDisplayListener(displayListener, mainHandler)
        Log.d(TAG, "onCreate screen=${screenW}x${screenH} dpi=$screenDpi " +
                "frame=${frameW}x${frameH}px")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra("data")
            if (mediaProjection == null &&
                resultCode == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                setupMediaProjection()
            }
        }

        if (viewA == null) {
            createAllOverlays()
            mainHandler.postDelayed(analysisRunnable, ANALYSIS_INTERVAL_MS)
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
        listOf(viewA, viewB, viewFrame, viewPanel).forEach {
            if (it != null) runCatching { windowManager.removeView(it) }
        }
        viewA = null; viewB = null; viewFrame = null; viewPanel = null
    }

    // ============================================================
    // 屏幕尺寸
    // ============================================================
    private fun refreshScreenMetrics() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi
    }

    private fun updateScreenSize() {
        val before = "$screenW x $screenH"
        refreshScreenMetrics()
        if (before == "$screenW x $screenH") return
        Log.d(TAG, "screen size changed: $before -> $screenW x $screenH")
        if (mediaProjection != null) createVirtualDisplay()
        clampOverlays()
    }

    private fun clampOverlays() {
        clampOne(viewA, lpA)
        clampOne(viewB, lpB)
        clampOne(viewFrame, lpFrame)
        clampOne(viewPanel, lpPanel)
    }

    private fun clampOne(v: View?, p: WindowManager.LayoutParams?) {
        if (v == null || p == null) return
        p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    // ============================================================
    // 前台服务
    // ============================================================
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

        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        if (type != 0) startForeground(1, n, type) else startForeground(1, n)
    }

    // ============================================================
    // MediaProjection（仅 API < 30）
    // ============================================================
    private fun setupMediaProjection() {
        val mp = mediaProjection ?: return
        if (!projectionCallbackRegistered) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    runCatching { virtualDisplay?.release() }
                    runCatching { imageReader?.close() }
                    virtualDisplay = null
                    imageReader = null
                    mediaProjection = null
                    projectionCallbackRegistered = false
                }
            }, mainHandler)
            projectionCallbackRegistered = true
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

    // ============================================================
    // 创建悬浮窗
    // ============================================================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun baseOverlayParams(w: Int, h: Int, x: Int, y: Int) =
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

    private fun createAllOverlays() {
        createA()
        createB()
        createFrame()
        createPanel()
    }

    private fun makeCircle(label: String, colorHex: String): View {
        val size = dp(BUTTON_SIZE_DP)
        return TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
    }

    private fun createA() {
        val size = dp(BUTTON_SIZE_DP)
        val view = makeCircle("A", "#80FF5722")
        val p = baseOverlayParams(size, size, dp(30), dp(120))
        attachDrag(view, p)
        viewA = view; lpA = p
        windowManager.addView(view, p)
    }

    private fun createB() {
        val size = dp(BUTTON_SIZE_DP)
        val view = makeCircle("B", "#802196F3")
        val p = baseOverlayParams(size, size, screenW - size - dp(30), dp(120))
        attachDrag(view, p)
        viewB = view; lpB = p
        windowManager.addView(view, p)
    }

    private fun createFrame() {
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(FRAME_STROKE_DP), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
        }
        val p = baseOverlayParams(frameW, frameH, dp(60), dp(300))
        frameX = p.x; frameY = p.y
        attachDrag(view, p, onMove = { x, y -> frameX = x; frameY = y })
        viewFrame = view; lpFrame = p
        windowManager.addView(view, p)
    }

    private fun createPanel() {
        val panel = LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        val panelW = dp(190)
        val panelH = dp(200)
        val p = baseOverlayParams(
            panelW, panelH,
            screenW - panelW - dp(20),
            dp(30)
        )
        attachDrag(panel, p)

        statusText = panel.findViewById(R.id.statusText)
        rangeText = panel.findViewById(R.id.rangeText)
        fishingToggle = panel.findViewById(R.id.fishingToggle)
        colorSlider = panel.findViewById(R.id.colorRangeSlider)

        colorSlider?.setRange(minColorT, maxColorT)
        updateRangeText()

        colorSlider?.onRangeChanged = { min, max ->
            minColorT = min
            maxColorT = max
            updateRangeText()
            Log.d(TAG, "color range updated: R∈[${(min * 255).toInt()}, ${(max * 255).toInt()}]")
        }

        fishingToggle?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            autoFishing = checked
            if (checked) {
                statusText?.text = "运行中…"
            } else {
                statusText?.text = "已停止"
                sequenceInProgress = false
            }
            Log.d(TAG, "autoFishing = $checked")
        }

        viewPanel = panel; lpPanel = p
        windowManager.addView(panel, p)
    }

    private fun updateRangeText() {
        val rMin = (minColorT * 255).toInt().coerceIn(0, 255)
        val rMax = (maxColorT * 255).toInt().coerceIn(0, 255)
        rangeText?.text = "R 范围: $rMin - $rMax"
    }

    // ============================================================
    // 拖动
    // ============================================================
    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        onMove: (Int, Int) -> Unit = { _, _ -> }
    ) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    onMove(params.x, params.y)
                    true
                }
                else -> false
            }
        }
    }

    // ============================================================
    // 点击穿透
    // ============================================================
    private fun overlays(): List<Pair<View, WindowManager.LayoutParams>> {
        val list = ArrayList<Pair<View, WindowManager.LayoutParams>>(4)
        viewA?.let { v -> lpA?.let { list.add(v to it) } }
        viewB?.let { v -> lpB?.let { list.add(v to it) } }
        viewFrame?.let { v -> lpFrame?.let { list.add(v to it) } }
        viewPanel?.let { v -> lpPanel?.let { list.add(v to it) } }
        return list
    }

    private fun setOverlaysTouchable(touchable: Boolean) {
        for ((v, p) in overlays()) {
            p.flags = if (touchable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    private fun clickThrough(x: Float, y: Float, onDone: () -> Unit) {
        val svc = AccessibilityClicker.instance
        if (svc == null) {
            Log.e(TAG, "无障碍未连接，跳过点击")
            onDone()
            return
        }

        setOverlaysTouchable(false)

        mainHandler.postDelayed({
            svc.clickAt(x, y) { ok ->
                Log.d(TAG, "clickThrough ($x,$y) ok=$ok")
                mainHandler.postDelayed({
                    setOverlaysTouchable(true)
                    onDone()
                }, RESTORE_DELAY_MS)
            }
        }, HIDE_DELAY_MS)
    }

    // ============================================================
    // 分析循环
    // ============================================================
    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (autoFishing && !sequenceInProgress && !analysisInProgress) {
                startAnalysis()
            }
            mainHandler.postDelayed(this, ANALYSIS_INTERVAL_MS)
        }
    }

    private fun startAnalysis() {
        analysisInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureWithAccessibility { onAnalysisFrame(it) }
        } else {
            captureWithMediaProjection { onAnalysisFrame(it) }
        }
    }

    private fun onAnalysisFrame(bmp: Bitmap) {
        val result = analyzeColorRange(bmp)
        bmp.recycle()
        analysisInProgress = false

        // 只要框里有 0.5% 的像素命中，就算检测到了红色
        val hit = result.ratio >= HIT_RATIO_THRESHOLD

        mainHandler.post {
            if (autoFishing) {
                statusText?.text = if (hit) {
                    "命中 ${result.hitCount}px (${"%.2f".format(result.ratio * 100)}%)"
                } else {
                    "未命中 (检查了${result.checkedPixels}个像素)"
                }
            }
        }

        if (!hit && autoFishing && !sequenceInProgress) {
            startClickSequence()
        }
    }

    // ============================================================
    // 点击序列：A → 2s → A → 0.2s → B → 1s → 下一轮
    // ============================================================
    private fun startClickSequence() {
        if (sequenceInProgress) return
        val a = lpA ?: return
        val b = lpB ?: return

        sequenceInProgress = true

        val ax = a.x + a.width / 2f
        val ay = a.y + a.height / 2f
        val bx = b.x + b.width / 2f
        val by = b.y + b.height / 2f

        Log.d(TAG, "Sequence start A=($ax,$ay) B=($bx,$by)")

        clickThrough(ax, ay) {
            if (!autoFishing) { sequenceInProgress = false; return@clickThrough }

            mainHandler.postDelayed({
                if (!autoFishing) { sequenceInProgress = false; return@postDelayed }

                clickThrough(ax, ay) {
                    if (!autoFishing) { sequenceInProgress = false; return@clickThrough }

                    mainHandler.postDelayed({
                        if (!autoFishing) { sequenceInProgress = false; return@postDelayed }

                        clickThrough(bx, by) {
                            mainHandler.postDelayed({
                                sequenceInProgress = false
                                Log.d(TAG, "Sequence done")
                            }, WAIT_AFTER_B_MS)
                        }
                    }, WAIT_BETWEEN_A2_B_MS)
                }
            }, WAIT_AFTER_A1_MS)
        }
    }

    // ============================================================
    // 截屏：API 30+ 无障碍 takeScreenshot
    // ============================================================
    private fun captureWithAccessibility(onSuccess: (Bitmap) -> Unit) {
        val svc = AccessibilityClicker.instance
        if (svc == null) { analysisInProgress = false; return }

        svc.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bmp = try {
                        val hw = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        copy
                    } catch (e: Throwable) {
                        Log.e(TAG, "screenshot copy failed", e)
                        null
                    }
                    if (bmp == null) analysisInProgress = false
                    else onSuccess(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    analysisInProgress = false
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                }
            }
        )
    }

    // ============================================================
    // 截屏：API < 30 MediaProjection
    // ============================================================
    private fun captureWithMediaProjection(onSuccess: (Bitmap) -> Unit) {
        val reader = imageReader
        if (reader == null) { analysisInProgress = false; return }

        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
        if (image == null) { analysisInProgress = false; return }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val imgW = image.width
            val imgH = image.height
            val rowPadding = rowStride - pixelStride * imgW
            val bmpW = imgW + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(bmpW, imgH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            buffer.rewind()
            onSuccess(bmp)
        } catch (e: Throwable) {
            Log.e(TAG, "MediaProjection capture failed", e)
            analysisInProgress = false
        } finally {
            runCatching { image.close() }
        }
    }

    // ============================================================
    // ★ 色段匹配检测（绝对严格遍历每一个像素）
    // ============================================================

    private data class RangeResult(
        val hitCount: Int,
        val totalCount: Int,
        val ratio: Float,
        val bestR: Int,
        val bestG: Int,
        val bestB: Int,
        val checkedPixels: Int
    )

    /**
     * 判断单个像素是否命中色段。
     * 这里会同时兼容 (R,G,B) 和 (B,G,R) 两种通道顺序。
     */
    private fun checkPixelRgb(r: Int, g: Int, b: Int): Boolean {
        // 1. 检查 R 通道是否在用户拖拽的色段范围内
        val t = r / 255f
        if (t < minColorT || t > maxColorT) return false

        // 2. 检查 G、B 通道是否足够低（排除灰色、白色、橙色）
        val gbDistSq = g * g + b * b
        return gbDistSq <= MAX_GB_DIST_SQ
    }

    private fun pixelMatches(b0: Int, b1: Int, b2: Int): Boolean {
        // b0, b1, b2 是像素被提取出来的 3 个字节
        // 顺序A: R=b2, G=b1, B=b0 （标准 ARGB）
        if (checkPixelRgb(b2, b1, b0)) return true
        // 顺序B: R=b0, G=b1, B=b2 （鸿蒙/华为 MediaProjection 经常把 R/B 对调）
        if (checkPixelRgb(b0, b1, b2)) return true
        return false
    }

    private fun analyzeColorRange(bmp: Bitmap): RangeResult {
        // 1. 严格按比例映射坐标，防止截图分辨率与屏幕分辨率不一致导致偏移
        val scaleX = bmp.width.toFloat() / screenW.toFloat()
        val scaleY = bmp.height.toFloat() / screenH.toFloat()

        val realX = (frameX * scaleX).toInt()
        val realY = (frameY * scaleY).toInt()
        val realW = (frameW * scaleX).toInt().coerceAtLeast(1)
        val realH = (frameH * scaleY).toInt().coerceAtLeast(1)

        val x = realX.coerceIn(0, (bmp.width - realW).coerceAtLeast(0))
        val y = realY.coerceIn(0, (bmp.height - realH).coerceAtLeast(0))
        val w = minOf(realW, bmp.width - x)
        val h = minOf(realH, bmp.height - y)

        if (w <= 0 || h <= 0) {
            return RangeResult(0, 0, 0f, 0, 0, 0, 0)
        }

        val totalPixels = w * h
        // 2. 严格分配像素数组并读取
        val pixels = IntArray(totalPixels)
        bmp.getPixels(pixels, 0, w, x, y, w, h)

        var hitCount = 0
        var bestR = 0
        var bestG = 0
        var bestB = 0
        var checkedPixels = 0
        var firstHitLogged = false

        // 3. 严格遍历每一个像素
        for (i in 0 until totalPixels) {
            checkedPixels++
            val px = pixels[i]

            // 提取出 3 个字节（忽略 alpha 通道）
            val b0 = px and 0xFF
            val b1 = (px shr 8) and 0xFF
            val b2 = (px shr 16) and 0xFF

            // 4. 检查是否命中色段
            if (pixelMatches(b0, b1, b2)) {
                hitCount++

                // 记录第一个命中的像素颜色，用于日志分析
                if (!firstHitLogged) {
                    firstHitLogged = true
                    // 判断到底哪种通道顺序命中
                    if (checkPixelRgb(b2, b1, b0)) {
                        bestR = b2; bestG = b1; bestB = b0
                    } else {
                        bestR = b0; bestG = b1; bestB = b2
                    }
                    Log.d(TAG, "★ 首次命中像素! 坐标(${x + (i % w)}, ${y + (i / w)}) " +
                            "原始字节(b0=$b0, b1=$b1, b2=$b2) " +
                            "命中颜色(r=$bestR, g=$bestG, b=$bestB)")
                }
            }
        }

        val ratio = if (totalPixels > 0) hitCount.toFloat() / totalPixels else 0f

        // 5. 打印详细日志，证明遍历了每一个像素
        if (DEBUG_FRAME_LOG) {
            Log.d(TAG,
                "analyze: bmp=${bmp.width}x${bmp.height} screen=${screenW}x${screenH} " +
                        "box=($x,$y,${w}x$h) " +
                        "range=[${"%.2f".format(minColorT)},${"%.2f".format(maxColorT)}] " +
                        "checkedPixels=$checkedPixels " +
                        "hits=$hitCount " +
                        "ratio=${"%.3f".format(ratio)}"
            )
        }

        return RangeResult(hitCount, totalPixels, ratio, bestR, bestG, bestB, checkedPixels)
    }
}