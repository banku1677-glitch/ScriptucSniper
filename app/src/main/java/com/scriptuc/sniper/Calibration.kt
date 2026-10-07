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
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

object CalibResult {
    var onResult: ((String, Int, Int) -> Unit)? = null
}

class CalibrationService : Service() {

    companion object {
        const val EXTRA_KEY = "key"
        const val CHANNEL_ID = "calib"
        const val NOTIF_ID = 43
        const val BOX = 140
    }

    private lateinit var wm: WindowManager
    private var crossView: View? = null
    private var panelView: View? = null
    private var key = ""
    private var px = 0
    private var py = 0
    private var screenW = 0
    private var screenH = 0
    private lateinit var tvCoords: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Calibration", NotificationManager.IMPORTANCE_LOW)
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(ch)
        }
        startForeground(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Scriptuc Sniper")
            .setContentText("Калибровка")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        key = intent?.getStringExtra(EXTRA_KEY) ?: ""
        if (crossView == null) startOverlay()
        return START_STICKY
    }

    private fun startOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        px = screenW / 2
        py = screenH / 2

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val cross = CrosshairView(this)
        val cp = WindowManager.LayoutParams(
            BOX, BOX, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px - BOX / 2
            y = py - BOX / 2
        }
        wm.addView(cross, cp)
        crossView = cross

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E61A1026"))
            setPadding(30, 30, 30, 30)
        }
        val tvKey = TextView(this).apply {
            text = "Калибровка: $key"
            setTextColor(Color.parseColor("#C89BFF"))
            textSize = 16f
        }
        tvCoords = TextView(this).apply {
            text = "$px, $py"
            setTextColor(Color.parseColor("#EDE4F7"))
            textSize = 18f
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnOk = Button(this).apply {
            text = "ОК"
            setOnClickListener {
                CalibResult.onResult?.invoke(key, px, py)
                stopSelf()
            }
        }
        val btnCancel = Button(this).apply {
            text = "Отмена"
            setOnClickListener { stopSelf() }
        }
        btnRow.addView(btnOk)
        btnRow.addView(btnCancel)
        panel.addView(tvKey)
        panel.addView(tvCoords)
        panel.addView(btnRow)

        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
        }
        wm.addView(panel, pp)
        panelView = panel

        var startX = 0; var startY = 0
        var startCx = 0; var startCy = 0
        cross.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX.toInt()
                    startY = ev.rawY.toInt()
                    startCx = cp.x
                    startCy = cp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - startX
                    val dy = ev.rawY.toInt() - startY
                    cp.x = (startCx + dx).coerceIn(0, screenW - BOX)
                    cp.y = (startCy + dy).coerceIn(0, screenH - BOX)
                    wm.updateViewLayout(cross, cp)
                    px = cp.x + BOX / 2
                    py = cp.y + BOX / 2
                    tvCoords.text = "$px, $py"
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        try { crossView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { panelView?.let { wm.removeView(it) } } catch (_: Throwable) {}
        crossView = null
        panelView = null
        super.onDestroy()
    }
}

class CrosshairView(ctx: Context) : View(ctx) {
    private val p = Paint().apply {
        color = Color.parseColor("#A66BFF")
        strokeWidth = 4f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    init { setBackgroundColor(Color.TRANSPARENT) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 45, cy, cx + 45, cy, p)
        canvas.drawLine(cx, cy - 45, cx, cy + 45, p)
        canvas.drawCircle(cx, cy, 22f, p)
    }
}
