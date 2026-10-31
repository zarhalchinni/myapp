package uz.ishtixon.scatdriver.net

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import uz.ishtixon.scatdriver.R
import kotlin.math.abs

/** Плавающий кружок (логотип + сумма) поверх других приложений во время поездки. */
class FloatingMeterService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wm: WindowManager? = null
    private var view: View? = null
    private var fareText: TextView? = null
    private lateinit var lp: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view == null) addBubble()
        // обновление суммы + авто-скрытие при завершении поездки
        scope.launch { TripBubble.fare.collectLatest { fareText?.text = "$it сўм" } }
        scope.launch { TripBubble.active.collectLatest { if (!it) stopSelf() } }
        return START_STICKY
    }

    private fun addBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(6), px(16), px(6))
            background = GradientDrawable().apply {
                cornerRadius = px(26).toFloat()
                setColor(Color.parseColor("#F0141A20"))
                setStroke(px(2), Color.parseColor("#FFB066"))   // оранжевая рамка под бренд
            }
            elevation = px(8).toFloat()
        }
        // логотип в белом круглом бейдже
        val badge = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(42), px(42))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE)
            }
            addView(ImageView(this@FloatingMeterService).apply {
                setImageResource(R.drawable.scat_logo)
                layoutParams = android.widget.FrameLayout.LayoutParams(px(32), px(32), Gravity.CENTER)
            })
        }
        fareText = TextView(this).apply {
            text = "${TripBubble.fare.value} сўм"
            setTextColor(Color.parseColor("#FFB066"))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(10), 0, 0, 0)
        }
        root.addView(badge)
        root.addView(fareText)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(16); y = px(120)
        }

        // перетаскивание в любую точку экрана
        root.setOnTouchListener(object : View.OnTouchListener {
            var dx = 0; var dy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = lp.x; dy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val nx = (e.rawX - tx).toInt(); val ny = (e.rawY - ty).toInt()
                        if (abs(nx) > 8 || abs(ny) > 8) moved = true
                        lp.x = dx + nx; lp.y = dy + ny
                        runCatching { wm?.updateViewLayout(v, lp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) { // тап — открыть приложение
                            startActivity(packageManager.getLaunchIntentForPackage(packageName)
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                return true
            }
        })

        view = root
        runCatching { wm?.addView(root, lp) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { view?.let { wm?.removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = ctx.startService(Intent(ctx, FloatingMeterService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, FloatingMeterService::class.java))
    }
}
