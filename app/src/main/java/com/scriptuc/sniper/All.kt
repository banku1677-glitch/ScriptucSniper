package com.scriptuc.sniper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class Config(
    val perebiv: Float = 0.01f,
    val maxPrice: Float = 80f,
    val loopMs: Long = 10L,
    val delayZakaz: Long = 600L,
    val delayBefore: Long = 400L,
    val delayAfter: Long = 200L,
    val delayInput: Long = 40L,
    val delayKlava: Long = 1500L,
    val delayOtmena: Long = 1500L,

    val zaprosRegion: IntArray = intArrayOf(1750, 165, 230, 50),
    val lotRegion: IntArray = intArrayOf(1700, 470, 400, 70),

    val btnZakaz: IntArray = intArrayOf(2139, 186),
    val priceField: IntArray = intArrayOf(1070, 443),
    val backspace: IntArray = intArrayOf(1942, 782),
    val btnVtoroyZakaz: IntArray = intArrayOf(1120, 765),
    val btnNazad: IntArray = intArrayOf(1740, 263),
    val btnOtmena: IntArray = intArrayOf(1748, 290),
    val btnGalochka: IntArray = intArrayOf(1925, 945),
    val btnTochka: IntArray = intArrayOf(1423, 920),

    val numKeys: Array<IntArray> = arrayOf(
        intArrayOf(903, 949),
        intArrayOf(401, 574),
        intArrayOf(925, 546),
        intArrayOf(1395, 595),
        intArrayOf(409, 696),
        intArrayOf(931, 708),
        intArrayOf(1433, 716),
        intArrayOf(418, 796),
        intArrayOf(915, 823),
        intArrayOf(1418, 850)
    ),
) {
    companion object {
        const val PREF = "scriptuc_cfg"
        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            fun pt(key: String, def: IntArray): IntArray {
                val s = p.getString(key, null) ?: return def
                val a = s.split(",").mapNotNull { it.trim().toIntOrNull() }
                return if (a.size == 2) a.toIntArray() else def
            }
            fun rg(key: String, def: IntArray): IntArray {
                val s = p.getString(key, null) ?: return def
                val a = s.split(",").mapNotNull { it.trim().toIntOrNull() }
                return if (a.size == 4) a.toIntArray() else def
            }
            val defNum = arrayOf(
                intArrayOf(903, 949), intArrayOf(401, 574), intArrayOf(925, 546),
                intArrayOf(1395, 595), intArrayOf(409, 696), intArrayOf(931, 708),
                intArrayOf(1433, 716), intArrayOf(418, 796), intArrayOf(915, 823),
                intArrayOf(1418, 850)
            )
            return Config(
                perebiv = p.getFloat("perebiv", 0.01f),
                maxPrice = p.getFloat("maxPrice", 80f),
                loopMs = p.getLong("loopMs", 10L),
                delayZakaz = p.getLong("delayZakaz", 600L),
                delayBefore = p.getLong("delayBefore", 400L),
                delayAfter = p.getLong("delayAfter", 200L),
                delayInput = p.getLong("delayInput", 40L),
                delayKlava = p.getLong("delayKlava", 1500L),
                delayOtmena = p.getLong("delayOtmena", 1500L),
                zaprosRegion = rg("zaprosRegion", intArrayOf(1750, 165, 230, 50)),
                lotRegion = rg("lotRegion", intArrayOf(1700, 470, 400, 70)),
                btnZakaz = pt("btnZakaz", intArrayOf(2139, 186)),
                priceField = pt("priceField", intArrayOf(1070, 443)),
                backspace = pt("backspace", intArrayOf(1942, 782)),
                btnVtoroyZakaz = pt("btnVtoroyZakaz", intArrayOf(1120, 765)),
                btnNazad = pt("btnNazad", intArrayOf(1740, 263)),
                btnOtmena = pt("btnOtmena", intArrayOf(1748, 290)),
                btnGalochka = pt("btnGalochka", intArrayOf(1925, 945)),
                btnTochka = pt("btnTochka", intArrayOf(1423, 920)),
                numKeys = Array(10) { i -> pt("num$i", defNum[i]) },
            )
        }
    }
}

class TapService : AccessibilityService() {
    companion object {
        @Volatile var instance: TapService? = null
            private set
    }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    suspend fun tapAndWait(x: Int, y: Int, waitMs: Long) {
        tap(x, y)
        delay(waitMs)
    }
}

class ScreenReader(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    var width = 0
    var height = 0
    private var dpi = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            virtualDisplay = null
        }
    }

    @SuppressLint("WrongConstant")
    fun start(resultCode: Int, data: Intent) {
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data)
        proj.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = proj

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (sw, sh) = getScreenSize(wm)
        width = maxOf(sw, sh)
        height = minOf(sw, sh)
        dpi = ctx.resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = proj.createVirtualDisplay(
            "scriptuc-capture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )
    }

    private fun getScreenSize(wm: WindowManager): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    fun stop() {
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null; imageReader = null; projection = null
    }

    suspend fun capture(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        val deadline = System.currentTimeMillis() + 500L
        var image: Image? = null
        while (System.currentTimeMillis() < deadline) {
            image = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
            if (image != null) break
            delay(5)
        }
        val img = image ?: return@withContext null
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * width

            val raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
            raw.recycle()
            cropped
        } catch (t: Throwable) {
            null
        } finally {
            img.close()
        }
    }
}

class PriceOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readNumber(frame: Bitmap, region: IntArray): Float? {
        val x = region[0]
        val y = region[1]
        val w = region[2]
        val h = region[3]
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        val cw = w.coerceAtMost(frame.width - cx)
        val ch = h.coerceAtMost(frame.height - cy)
        if (cw <= 0 || ch <= 0) return null

        val crop = Bitmap.createBitmap(frame, cx, cy, cw, ch)
        val sw = cw * 6
        val sh = ch * 6
        val scaled = Bitmap.createScaledBitmap(crop, sw, sh, true)
        crop.recycle()

        try {
            val pixels = IntArray(sw * sh)
            scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                pixels[i] = if (lum > 170) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            scaled.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        } catch (_: Throwable) {}

        val image = InputImage.fromBitmap(scaled, 0)

        return suspendCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    try { scaled.recycle() } catch (_: Throwable) {}
                    val cleaned = result.text.replace(Regex("[^0-9.,]"), "").replace(',', '.')
                    cont.resume(cleaned.toFloatOrNull())
                }
                .addOnFailureListener {
                    try { scaled.recycle() } catch (_: Throwable) {}
                    cont.resume(null)
                }
        }
    }
}

class SniperService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "sniper"
        const val NOTIF_ID = 42

        @Volatile var running: Boolean = false
            private set
        var logSink: ((String) -> Unit)? = null
    }

    private lateinit var cfg: Config
    private lateinit var screenReader: ScreenReader
    private lateinit var ocr: PriceOcr
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            try {
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.txt").appendText(
                    "\n\n=== CRASH ${System.currentTimeMillis()} ===\n" + sw.toString()
                )
            } catch (_: Throwable) {}
            android.util.Log.e("ScriptucCrash", "FATAL", t)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        cfg = Config.load(this)
        screenReader = ScreenReader(this)
        ocr = PriceOcr()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLoop(); stopSelf(); return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            startForegroundCompat()
            log("Нет данных MediaProjection — стоп")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        try {
            screenReader.start(rc, data)
            log(">>> ScreenReader стартовал")
        } catch (t: Throwable) {
            log("Ошибка старта ScreenReader: ${t.message}")
            stopSelf(); return START_NOT_STICKY
        }
        TradeState.running = true
        TradeState.paused = false
        startLoop()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        stopLoop(); scope.cancel(); screenReader.stop()
        running = false
        TradeState.running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        running = true
        log("Скрипт запущен. Перебив +${cfg.perebiv}  Max=${cfg.maxPrice}")
        loopJob = scope.launch { try { snipeLoop() } catch (_: Throwable) {} }
    }

    private fun stopLoop() {
        loopJob?.cancel(); loopJob = null; running = false
        log("Скрипт остановлен.")
    }

    private suspend fun snipeLoop() {
        var prevZapros = 0f
        var cenaLota = 0f
        var lastRefresh = System.currentTimeMillis()
        var tick = 0
        var lastPausedLog = false

        while (running) {
            tick++

            if (TradeState.paused) {
                if (!lastPausedLog) { log(">>> ПАУЗА"); lastPausedLog = true }
                val f = screenReader.capture()
                if (f != null) {
                    val cur = ocr.readNumber(f, cfg.zaprosRegion)
                    if (cur != null && cur > 0f) prevZapros = cur
                    f.recycle()
                }
                delay(200); continue
            } else if (lastPausedLog) {
                log(">>> ПРОДОЛЖАЮ"); lastPausedLog = false
            }

            val frame = screenReader.capture()
            if (frame == null) { delay(50); continue }

            try {
                val lot = ocr.readNumber(frame, cfg.lotRegion)
                if (lot != null && lot > 0f) cenaLota = lot

                val cur = ocr.readNumber(frame, cfg.zaprosRegion)

                if (tick % 10 == 0) {
                    log("tick=$tick cur=$cur lot=$lot prev=$prevZapros cenaLota=$cenaLota")
                }

                if (cur != null && cur > 0f) {
                    val newZapros = cur + cfg.perebiv
                    if (cur > prevZapros && prevZapros > 0f && newZapros < cenaLota) {
                        if (cfg.maxPrice > 0f && newZapros > cfg.maxPrice) {
                            log(">>> пропуск: new=$newZapros > max=${cfg.maxPrice}")
                        } else {
                            log(">>> SNIPE new=$newZapros")
                            doSnipe(newZapros)
                            lastRefresh = System.currentTimeMillis()
                        }
                    }
                    prevZapros = cur
                }

                if (System.currentTimeMillis() - lastRefresh > 5000) {
                    prevZapros = 0f
                    lastRefresh = System.currentTimeMillis()
                }
            } finally {
                try { frame.recycle() } catch (_: Throwable) {}
            }

            delay(cfg.loopMs)
        }
    }

    private suspend fun doSnipe(newZapros: Float) {
        val tap = TapService.instance
        if (tap == null) { log("TapService не подключён"); return }

        tap.tapAndWait(cfg.btnZakaz[0], cfg.btnZakaz[1], cfg.delayZakaz)
        tap.tapAndWait(cfg.priceField[0], cfg.priceField[1], cfg.delayBefore)
        repeat(8) { tap.tapAndWait(cfg.backspace[0], cfg.backspace[1], cfg.delayInput) }
        inputNumber(tap, newZapros.toString())
        tap.tapAndWait(cfg.btnGalochka[0], cfg.btnGalochka[1], cfg.delayAfter)
        tap.tapAndWait(cfg.btnVtoroyZakaz[0], cfg.btnVtoroyZakaz[1], cfg.delayKlava)
        tap.tapAndWait(cfg.btnOtmena[0], cfg.btnOtmena[1], cfg.delayOtmena)

        log(">>> ордер выставлен на $newZapros")
    }

    private suspend fun inputNumber(tap: TapService, s: String) {
        for (ch in s) {
            when {
                ch == '.' || ch == ',' -> tap.tapAndWait(cfg.btnTochka[0], cfg.btnTochka[1], cfg.delayInput)
                ch.isDigit() -> {
                    val d = ch - '0'
                    tap.tapAndWait(cfg.numKeys[d][0], cfg.numKeys[d][1], cfg.delayInput)
                }
            }
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sniper", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scriptuc Sniper")
            .setContentText("Работает")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun log(msg: String) { logSink?.invoke(msg) }
}

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        appendLog(">>> callback: rc=${res.resultCode} data=${res.data != null}")
        if (res.data != null) startSniperService(res.resultCode, res.data!!)
        else appendLog("MediaProjection отклонён")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvLog = findViewById(R.id.tvLog)

        SniperService.logSink = { line -> runOnUiThread { appendLog(line) } }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnCheck).setOnClickListener {
            val inst = TapService.instance
            val mgr = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = mgr.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val names = if (enabled.isEmpty()) "(пусто)" else enabled.joinToString("\n") { it.id }
            appendLog("instance = $inst")
            appendLog("enabled services:\n$names")
            try {
                val f = java.io.File(filesDir, "crash.txt")
                if (f.exists()) {
                    appendLog("=== crash.txt ===")
                    appendLog(f.readText().takeLast(2000))
                } else appendLog("crash.txt нет")
            } catch (t: Throwable) { appendLog("crash.txt ошибка: ${t.message}") }
        }

        btnStart.setOnClickListener {
            appendLog(">>> нажат Start")
            if (TapService.instance == null) {
                appendLog(">>> TapService null")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            requestProjection()
        }

        btnStop.setOnClickListener {
            startService(Intent(this, SniperService::class.java).apply { action = SniperService.ACTION_STOP })
            try { startService(Intent(this, FloatingButtonService::class.java).apply { action = "stop" }) } catch (_: Throwable) {}
            TradeState.running = false
            updateButtons(false)
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }
        updateButtons(SniperService.running)
    }

    override fun onResume() {
        super.onResume()
        updateButtons(SniperService.running)
    }

    private fun requestProjection() {
        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (t: Throwable) { appendLog(">>> ОШИБКА: ${t.message}") }
    }

    private fun startSniperService(resultCode: Int, data: Intent) {
        val i = Intent(this, SniperService::class.java).apply {
            action = SniperService.ACTION_START
            putExtra(SniperService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SniperService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)

        try {
            val fb = Intent(this, FloatingButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(fb)
            else startService(fb)
        } catch (_: Throwable) {}

        updateButtons(true)
    }

    private fun updateButtons(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun appendLog(line: String) { tvLog.append(line + "\n") }
}

class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private val numFields = arrayOfNulls<EditText>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cfg = Config.load(this)

        val etZapros = findViewById<EditText>(R.id.etZapros)
        val etLot = findViewById<EditText>(R.id.etLot)
        val etPriceField = findViewById<EditText>(R.id.etPriceField)
        val etBackspace = findViewById<EditText>(R.id.etBackspace)
        etZapros.setText(cfg.zaprosRegion.joinToString(","))
        etLot.setText(cfg.lotRegion.joinToString(","))
        etPriceField.setText(cfg.priceField.joinToString(","))
        etBackspace.setText(cfg.backspace.joinToString(","))

        findViewById<Button>(R.id.btnPickZapros).setOnClickListener { startPick("zaprosRegion", etZapros) }
        findViewById<Button>(R.id.btnPickLot).setOnClickListener { startPick("lotRegion", etLot) }
        findViewById<Button>(R.id.btnPickPriceField).setOnClickListener { startPick("priceField", etPriceField) }
        findViewById<Button>(R.id.btnPickBackspace).setOnClickListener { startPick("backspace", etBackspace) }

        bindPoint("btnZakaz", R.id.etBtnZakaz, R.id.btnPickBtnZakaz, cfg.btnZakaz)
        bindPoint("btnVtoroyZakaz", R.id.etBtnVtoroy, R.id.btnPickBtnVtoroy, cfg.btnVtoroyZakaz)
        bindPoint("btnNazad", R.id.etBtnNazad, R.id.btnPickBtnNazad, cfg.btnNazad)
        bindPoint("btnOtmena", R.id.etBtnOtmena, R.id.btnPickBtnOtmena, cfg.btnOtmena)
        bindPoint("btnGalochka", R.id.etBtnGalochka, R.id.btnPickBtnGalochka, cfg.btnGalochka)
        bindPoint("btnTochka", R.id.etBtnTochka, R.id.btnPickBtnTochka, cfg.btnTochka)

        val cont = findViewById<LinearLayout>(R.id.numContainer)
        for (i in 0..9) {
            val row = layoutInflater.inflate(R.layout.row_num, cont, false)
            row.findViewById<TextView>(R.id.tvNum).text = "$i:"
            val et = row.findViewById<EditText>(R.id.etNum)
            et.setText(cfg.numKeys[i].joinToString(","))
            row.findViewById<Button>(R.id.btnPickNum).setOnClickListener {
                startPick("num$i", et)
            }
            cont.addView(row)
            numFields[i] = et
        }

        findViewById<EditText>(R.id.etPerebiv).setText(cfg.perebiv.toString())
        findViewById<EditText>(R.id.etMaxPrice).setText(cfg.maxPrice.toString())
        findViewById<EditText>(R.id.etLoopMs).setText(cfg.loopMs.toString())
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun startPick(key: String, et: EditText) {
        CalibResult.onResult = { k, value ->
            runOnUiThread {
                et.setText(value)
                Toast.makeText(this, "$k = $value", Toast.LENGTH_SHORT).show()
                CalibResult.onResult = null
            }
        }
        launchCalibOverlay(key)
    }

    private fun bindPoint(key: String, etId: Int, btnId: Int, value: IntArray) {
        val et = findViewById<EditText>(etId)
        et.setText(value.joinToString(","))
        findViewById<Button>(btnId).setOnClickListener { startPick(key, et) }
    }

    private fun launchCalibOverlay(key: String) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        val i = Intent(this, CalibrationService::class.java).apply {
            putExtra(CalibrationService.EXTRA_KEY, key)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun save() {
        val p = getSharedPreferences(Config.PREF, MODE_PRIVATE).edit()
        savePoint(p, "btnZakaz", R.id.etBtnZakaz)
        savePoint(p, "priceField", R.id.etPriceField)
        savePoint(p, "backspace", R.id.etBackspace)
        savePoint(p, "btnVtoroyZakaz", R.id.etBtnVtoroy)
        savePoint(p, "btnNazad", R.id.etBtnNazad)
        savePoint(p, "btnOtmena", R.id.etBtnOtmena)
        savePoint(p, "btnGalochka", R.id.etBtnGalochka)
        savePoint(p, "btnTochka", R.id.etBtnTochka)
        for (i in 0..9) {
            numFields[i]?.text?.toString()?.let { v ->
                val a = v.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (a.size == 2) p.putString("num$i", "${a[0]},${a[1]}")
            }
        }
        saveRegion(p, "zaprosRegion", R.id.etZapros)
        saveRegion(p, "lotRegion", R.id.etLot)
        val perebiv = findViewById<EditText>(R.id.etPerebiv).text.toString().toFloatOrNull() ?: 0.01f
        val maxP = findViewById<EditText>(R.id.etMaxPrice).text.toString().toFloatOrNull() ?: 80f
        val loop = findViewById<EditText>(R.id.etLoopMs).text.toString().toLongOrNull() ?: 10L
        p.putFloat("perebiv", perebiv)
        p.putFloat("maxPrice", maxP)
        p.putLong("loopMs", loop)
        p.apply()
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
    }

    private fun savePoint(p: SharedPreferences.Editor, key: String, etId: Int) {
        val a = findViewById<EditText>(etId).text.toString()
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        if (a.size == 2) p.putString(key, "${a[0]},${a[1]}")
    }

    private fun saveRegion(p: SharedPreferences.Editor, key: String, etId: Int) {
        val a = findViewById<EditText>(etId).text.toString()
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        if (a.size == 4) p.putString(key, a.joinToString(","))
    }
}

class PickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(110, 30, 10, 60))
        val hint = TextView(this).apply {
            text = "Тапни по нужному элементу"
            setTextColor(Color.parseColor("#FFD766"))
            textSize = 20f
            setBackgroundColor(Color.parseColor("#CC1A1026"))
            setPadding(24, 24, 24, 24)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 80; leftMargin = 40 })
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                val out = Intent().apply {
                    putExtra("x", ev.rawX.toInt())
                    putExtra("y", ev.rawY.toInt())
                }
                setResult(RESULT_OK, out)
                finish()
                true
            } else false
        }
        setContentView(root)
    }
}
