package com.scriptuc.sniper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

object TradeState {
    @Volatile var paused: Boolean = false
    @Volatile var running: Boolean = false
}

class FloatingButtonService : Service() {

    companion object {
        const val CHANNEL_ID = "float"
        const val NOTIF_ID = 44
        const val WIDTH = 260
        const val HEIGHT = 110
        const val MIN_SIZE = 70
    }

    private var wm: WindowManager? = null
    private var view: FloatingView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Float", NotificationManager.IMPORTANCE_LOW)
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scriptuc Sniper")
            .setContentText("Плавающая кнопка")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (view == null) addButton()
        return START_NOT_STICKY
    }

    private fun addButton() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val v = FloatingView(this)
        val p = WindowManager.LayoutParams(
            WIDTH, HEIGHT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - WIDTH - 30
            y = screenH / 3
        }
        try { w.addView(v, p) } catch (_: Throwable) { return }
        view = v

        var startX = 0; var startY = 0
        var startPx = 0; var startPy = 0
        var moved = false
        var downTime = 0L

        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX.toInt(); startY = ev.rawY.toInt()
                    startPx = p.x; startPy = p.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - startX
                    val dy = ev.rawY.toInt() - startY
                    if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true
                    if (moved) {
                        p.x = (startPx + dx).coerceIn(0, screenW - v.width)
                        p.y = (startPy + dy).coerceIn(0, screenH - v.height)
                        try { w.updateViewLayout(v, p) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dur = System.currentTimeMillis() - downTime
                    if (moved) {
                        // перетаскивание — игнор
                    } else if (dur > 700) {
                        // долгое нажатие = свернуть/развернуть
                        v.minimized = !v.minimized
                        val newW = if (v.minimized) MIN_SIZE else WIDTH
                        val newH = if (v.minimized) MIN_SIZE else HEIGHT
                        val newP = WindowManager.LayoutParams(
                            newW, newH, overlayType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.START
                            x = p.x.coerceIn(0, screenW - newW)
                            y = p.y.coerceIn(0, screenH - newH)
                        }
                        try { w.updateViewLayout(v, newP) } catch (_: Throwable) {}
                        // заменяем ссылку на актуальный layout params
                        p.x = newP.x; p.y = newP.y
                        v.invalidate()
                    } else {
                        // короткий тап = пауза/продолжить
                        TradeState.paused = !TradeState.paused
                        v.invalidate()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        try { view?.let { wm?.removeView(it) } } catch (_: Throwable) {}
        view = null
        super.onDestroy()
    }
}

class FloatingView(ctx: Context) : View(ctx) {
    var minimized: Boolean = false

    private val gold = Color.parseColor("#E8B23A")
    private val goldBright = Color.parseColor("#FFD766")
    private val redEye = Color.parseColor("#FF3D3D")
    private val black = Color.parseColor("#0A0505")
    private val gray = Color.parseColor("#4A4038")

    private val pFill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val pStroke = Paint().apply {
        isAntiAlias = true
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }
    private val pStrokeThin = Paint().apply {
        isAntiAlias = true
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val pText = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pDot = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = 6f
        val rect = RectF(pad, pad, width - pad, height - pad)
        val radius = height / 2f

        if (minimized) {
            pFill.color = black
            canvas.drawRoundRect(rect, radius, radius, pFill)
            pStroke.color = if (TradeState.running && !TradeState.paused) goldBright else gray
            canvas.drawRoundRect(rect, radius, radius, pStroke)
            pDot.color = if (TradeState.running && !TradeState.paused) redEye else gray
            canvas.drawCircle(width / 2f, height / 2f, width * 0.22f, pDot)
            return
        }

        pFill.color = black
        canvas.drawRoundRect(rect, radius, radius, pFill)

        val isActive = TradeState.running && !TradeState.paused
        val isPaused = TradeState.running && TradeState.paused

        val mainColor = when {
            isActive -> goldBright
            isPaused -> gold
            else -> gray
        }
        pStroke.color = mainColor
        canvas.drawRoundRect(rect, radius, radius, pStroke)

        val innerRect = RectF(pad + 6f, pad + 6f, width - pad - 6f, height - pad - 6f)
        pStrokeThin.color = mainColor
        pStrokeThin.alpha = 120
        canvas.drawRoundRect(innerRect, radius - 6f, radius - 6f, pStrokeThin)
        pStrokeThin.alpha = 255

        val label = when {
            isActive -> "ON"
            isPaused -> "OFF"
            else -> "—"
        }
        pText.color = mainColor
        pText.textSize = height * 0.42f
        canvas.drawText(label, width / 2f, height / 2f + pText.textSize * 0.35f, pText)

        if (isActive) {
            pDot.color = redEye
            val cx = width - radius + 6f
            val cy = radius - 6f
            canvas.drawCircle(cx, cy, radius * 0.32f, pDot)
            pDot.color = Color.WHITE
            canvas.drawCircle(cx - 2f, cy - 2f, radius * 0.1f, pDot)
        }
    }
}
