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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class Config(
    val perebiv: Float = 0.01f,
    val loopMs: Long = 10L,
    val delayZakaz: Long = 400L,
    val delayBefore: Long = 300L,
    val delayAfter: Long = 100L,
    val delayInput: Long = 10L,
    val delayKlava: Long = 1200L,
    val delayOtmena: Long = 1200L,

    val zaprosRegion: IntArray = intArrayOf(1764, 187, 145, 27),
    val lotRegion: IntArray = intArrayOf(1133, 386, 253, 75),

    val btnZakaz: IntArray = intArrayOf(2111, 183),
    val btnVtoroyZakaz: IntArray = intArrayOf(1880, 189),
    val btnNazad: IntArray = intArrayOf(1740, 263),
    val btnOtmena: IntArray = intArrayOf(1730, 282),
    val btnGalochka: IntArray = intArrayOf(1944, 912),
    val btnTochka: IntArray = intArrayOf(1419, 972),

    val numKeys: Array<IntArray> = arrayOf(
        intArrayOf(929, 894),
        intArrayOf(449, 559),
        intArrayOf(941, 571),
        intArrayOf(1401, 560),
        intArrayOf(414, 682),
        intArrayOf(938, 669),
        intArrayOf(1427, 651),
        intArrayOf(414, 795),
        intArrayOf(934, 788),
        intArrayOf(1398, 810)
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

            val defaultNumKeys = arrayOf(
                intArrayOf(929, 894), intArrayOf(449, 559), intArrayOf(941, 571),
                intArrayOf(1401, 560), intArrayOf(414, 682), intArrayOf(938, 669),
                intArrayOf(1427, 651), intArrayOf(414, 795), intArrayOf(934, 788),
                intArrayOf(1398, 810)
            )
            val loadedNumKeys = Array(10) { i -> pt("num$i", defaultNumKeys[i]) }

            return Config(
                perebiv = p.getFloat("perebiv", 0.01f),
                loopMs = p.getLong("loopMs", 10L),
                delayZakaz = p.getLong("delayZakaz", 400L),
                delayBefore = p.getLong("delayBefore", 300L),
                delayAfter = p.getLong("delayAfter", 100L),
                delayInput = p.getLong("delayInput", 10L),
                delayKlava = p.getLong("delayKlava", 1200L),
                delayOtmena = p.getLong("delayOtmena", 1200L),
                zaprosRegion = rg("zaprosRegion", intArrayOf(1764, 187, 145, 27)),
                lotRegion = rg("lotRegion", intArrayOf(1133, 386, 253, 75)),
                btnZakaz = pt("btnZakaz", intArrayOf(2111, 183)),
                btnVtoroyZakaz = pt("btnVtoroyZakaz", intArrayOf(1880, 189)),
                btnNazad = pt("btnNazad", intArrayOf(1740, 263)),
                btnOtmena = pt("btnOtmena", intArrayOf(1730, 282)),
                btnGalochka = pt("btnGalochka", intArrayOf(1944, 912)),
                btnTochka = pt("btnTochka", intArrayOf(1419, 972)),
                numKeys = loadedNumKeys,
            )
        }
    }
}

class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapAndWait(x: Int, y: Int, waitMs: Long) {
        tap(x, y)
        Thread.sleep(waitMs)
    }
}

class ScreenReader(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
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
        val bounds = wm.currentWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        dpi = ctx.resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = proj.createVirtualDisplay(
            "scriptuc-capture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, Handler(Looper.getMainLooper())
        )
    }

    fun stop() {
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    suspend fun capture(): Bitmap? = suspendCoroutine { cont ->
        val reader = imageReader
        if (reader == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        reader.setOnImageAvailableListener({ r ->
            var image: Image? = null
            var bmp: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                image = r.acquireLatestImage()
                if (image == null) {
                    cont.resume(null)
                    return@setOnImageAvailableListener
                }
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * width

                bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                cont.resume(cropped)
            } catch (t: Throwable) {
                try { cropped?.recycle() } catch (_: Throwable) {}
                cont.resume(null)
            } finally {
                try { bmp?.recycle() } catch (_: Throwable) {}
                image?.close()
                r.setOnImageAvailableListener(null, null)
            }
        }, Handler(Looper.getMainLooper()))
    }
}

class PriceOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readNumber(frame: Bitmap, region: IntArray): Float? {
        val x = region[0]; val y = region[1]; val w = region[2]; val h = region[3]
        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        val cw = w.coerceAtMost(frame.width - cx)
        val ch = h.coerceAtMost(frame.height - cy)
        if (cw <= 0 || ch <= 0) return null

        val crop = Bitmap.createBitmap(frame, cx, cy, cw, ch)
        val scaled = Bitmap.createScaledBitmap(crop, cw * 3, ch * 3, true)
        val image = InputImage.fromBitmap(scaled, 0)

        return suspendCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val raw = result.text
                    val cleaned = raw.replace(Regex("[^0-9.,]"), "").replace(',', '.')
                    cont.resume(cleaned.toFloatOrNull())
                }
                .addOnFailureListener { cont.resume(null) }
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

        @Volatile
        var running: Boolean = false
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
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) {
                    startForegroundCompat()
                    log("Нет данных MediaProjection — стоп")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundCompat()
                try {
                    screenReader.start(rc, data)
                } catch (t: Throwable) {
                    log("Ошибка старта ScreenReader: ${t.message}")
                    try {
                        val sw = java.io.StringWriter()
                        t.printStackTrace(java.io.PrintWriter(sw))
                        java.io.File(filesDir, "crash.txt").appendText(
                            "\n\n=== ScreenReader.start ${System.currentTimeMillis()} ===\n" + sw.toString()
                        )
                    } catch (_: Throwable) {}
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLoop()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        stopLoop()
        scope.cancel()
        screenReader.stop()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        running = true
        log("Скрипт запущен. Перебив +${cfg.perebiv}")
        loopJob = scope.launch { snipeLoop() }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        running = false
        log("Скрипт остановлен.")
    }

    private suspend fun snipeLoop() {
        var prevZapros = 0f
        var cenaLota = 0f
        var lastRefresh = System.currentTimeMillis()

        while (running) {
            val frame = screenReader.capture()
            if (frame == null) {
                delay(20); continue
            }

            val lot = ocr.readNumber(frame, cfg.lotRegion)
            if (lot != null && lot > 0f) cenaLota = lot

            val cur = ocr.readNumber(frame, cfg.zaprosRegion)

            if (cur != null && cur > 0f) {
                val newZapros = cur + cfg.perebiv
                log("zapros=%.2f prev=%.2f lot=%.2f new=%.2f".format(cur, prevZapros, cenaLota, newZapros))

                if (cur > prevZapros && prevZapros > 0f && newZapros < cenaLota) {
                    doSnipe(newZapros)
                    lastRefresh = System.currentTimeMillis()
                }
                prevZapros = cur
            }

            if (System.currentTimeMillis() - lastRefresh > 5000) {
                prevZapros = 0f
                lastRefresh = System.currentTimeMillis()
            }

            delay(cfg.loopMs)
        }
    }

    private fun doSnipe(newZapros: Float) {
        val tap = TapService.instance
        if (tap == null) {
            log("TapService не подключён — включи Accessibility")
            return
        }
        tap.tapAndWait(cfg.btnZakaz[0], cfg.btnZakaz[1], cfg.delayZakaz)
        inputNumber(tap, newZapros.toString())
        Thread.sleep(cfg.delayBefore)
        tap.tapAndWait(cfg.btnGalochka[0], cfg.btnGalochka[1], cfg.delayAfter)
        tap.tapAndWait(cfg.btnVtoroyZakaz[0], cfg.btnVtoroyZakaz[1], cfg.delayKlava)
        tap.tapAndWait(cfg.btnOtmena[0], cfg.btnOtmena[1], cfg.delayOtmena)
    }

    private fun inputNumber(tap: TapService, s: String) {
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

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scriptuc Sniper")
            .setContentText("Работает")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun log(msg: String) {
        logSink?.invoke(msg)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        appendLog(">>> callback: rc=${res.resultCode} data=${res.data != null}")
        if (res.data != null) {
            startSniperService(res.resultCode, res.data!!)
        } else {
            appendLog("MediaProjection отклонён (нет data)")
        }
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
                } else {
                    appendLog("crash.txt нет")
                }
            } catch (t: Throwable) {
                appendLog("crash.txt ошибка: ${t.message}")
            }
        }

        btnStart.setOnClickListener {
            appendLog(">>> нажат Start")
            if (TapService.instance == null) {
                appendLog(">>> TapService null, открываю Accessibility")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            appendLog(">>> вызываю createScreenCaptureIntent")
            requestProjection()
        }

        btnStop.setOnClickListener {
            startService(Intent(this, SniperService::class.java).apply {
                action = SniperService.ACTION_STOP
            })
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
            val i = mgr.createScreenCaptureIntent()
            appendLog(">>> intent создан: $i")
            projectionLauncher.launch(i)
            appendLog(">>> launch() вызван")
        } catch (t: Throwable) {
            appendLog(">>> ОШИБКА launch: ${t.message}")
        }
    }

    private fun startSniperService(resultCode: Int, data: Intent) {
        val i = Intent(this, SniperService::class.java).apply {
            action = SniperService.ACTION_START
            putExtra(SniperService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SniperService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        updateButtons(true)
    }

    private fun updateButtons(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun appendLog(line: String) {
        tvLog.append(line + "\n")
    }
}

class SettingsActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private var pendingTarget: String? = null
    private val numFields = arrayOfNulls<EditText>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cfg = Config.load(this)

        findViewById<EditText>(R.id.etZapros).setText(cfg.zaprosRegion.joinToString(","))
        findViewById<EditText>(R.id.etLot).setText(cfg.lotRegion.joinToString(","))

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
                launchCalib("num$i")
            }
            cont.addView(row)
            numFields[i] = et
        }

        findViewById<EditText>(R.id.etPerebiv).setText(cfg.perebiv.toString())
        findViewById<EditText>(R.id.etLoopMs).setText(cfg.loopMs.toString())

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun bindPoint(key: String, etId: Int, btnId: Int, value: IntArray) {
        val et = findViewById<EditText>(etId)
        et.setText(value.joinToString(","))
        findViewById<Button>(btnId).setOnClickListener { launchCalib(key) }
    }

    private fun launchCalib(key: String) {
        if (!Settings.canDrawOverlays(this)) {
            pendingTarget = key
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

    override fun onResume() {
        super.onResume()
        CalibResult.onResult = { k, x, y ->
            runOnUiThread {
                setTargetValue(k, x, y)
                Toast.makeText(this, "$k = $x, $y", Toast.LENGTH_SHORT).show()
            }
        }
        val pk = pendingTarget
        if (!pk.isNullOrEmpty() && Settings.canDrawOverlays(this)) {
            pendingTarget = null
            launchCalib(pk)
        }
    }

    override fun onPause() {
        super.onPause()
        CalibResult.onResult = null
    }

    private fun setTargetValue(key: String, x: Int, y: Int) {
        val etId = when (key) {
            "btnZakaz" -> R.id.etBtnZakaz
            "btnVtoroyZakaz" -> R.id.etBtnVtoroy
            "btnNazad" -> R.id.etBtnNazad
            "btnOtmena" -> R.id.etBtnOtmena
            "btnGalochka" -> R.id.etBtnGalochka
            "btnTochka" -> R.id.etBtnTochka
            else -> {
                val idx = key.removePrefix("num").toIntOrNull() ?: return
                numFields[idx]?.setText("$x,$y")
                return
            }
        }
        findViewById<EditText>(etId).setText("$x,$y")
    }

    private fun save() {
        val p = getSharedPreferences(Config.PREF, MODE_PRIVATE).edit()

        savePoint(p, "btnZakaz", R.id.etBtnZakaz)
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
        val loop = findViewById<EditText>(R.id.etLoopMs).text.toString().toLongOrNull() ?: 10L
        p.putFloat("perebiv", perebiv)
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
            setTextColor(Color.parseColor("#C89BFF"))
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
