package uz.ishtixon.scatdriver.net

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import uz.ishtixon.scatdriver.model.OrderStatus
import uz.ishtixon.scatdriver.ui.tr

/**
 * Окно входящего заказа поверх всех приложений (через разрешение «поверх других окон») —
 * надёжно показывается на любой прошивке (в т.ч. MIUI/HyperOS), в отличие от heads-up
 * уведомления и фонового запуска активити, которые Xiaomi блокирует.
 */
class OrderOverlayService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wm: WindowManager? = null
    private var view: View? = null
    private var timerTv: TextView? = null
    private var orderId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val o = Scat.conn?.liveOrder?.value
        if (o == null || o.status > OrderStatus.OFFERED) { stopSelf(); return START_NOT_STICKY }
        orderId = o.id
        if (view == null) addWindow(o)
        // авто-закрытие: заказ принят/снят/сменился
        scope.launch {
            Scat.conn?.liveOrder?.collectLatest { cur ->
                if (cur == null || cur.id != orderId || cur.status > OrderStatus.OFFERED) stopSelf()
            }
        }
        OrderAlert.wake(this)
        return START_NOT_STICKY
    }

    private fun addWindow(o: uz.ishtixon.scatdriver.model.ActiveOrder) {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(18), px(18), px(18))
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(Color.parseColor("#F21A212B"))
                setStroke(px(2), Color.parseColor("#FFB066"))
            }
            elevation = px(12).toFloat()
        }

        card.addView(TextView(this).apply {
            text = tr("Поступил заказ", "Buyurtma keldi")
            setTextColor(Color.parseColor("#FFB066"))
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        })
        val addr = o.fromStreet.ifBlank { o.fromName }
        if (addr.isNotBlank()) card.addView(TextView(this).apply {
            text = addr; setTextColor(Color.WHITE); textSize = 17f
            setPadding(0, px(6), 0, 0)
        })
        if (o.fromName.isNotBlank() && o.fromName != addr) card.addView(TextView(this).apply {
            text = o.fromName; setTextColor(Color.parseColor("#93A1B1")); textSize = 14f
        })
        timerTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FFB066")); textSize = 30f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, px(10), 0, px(10))
        }
        card.addView(timerTv)

        // кнопки времени подачи 3·5·7·10
        val times = (Scat.conn?.orderTimes?.value ?: listOf(3, 5, 7, 10)).take(4)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52))
            times.forEach { m ->
                addView(Button(this@OrderOverlayService).apply {
                    text = "$m"; setTextColor(Color.WHITE); textSize = 17f
                    setTypeface(typeface, Typeface.BOLD); isAllCaps = false
                    background = GradientDrawable().apply {
                        cornerRadius = px(14).toFloat(); setColor(Color.parseColor("#5FAE3E"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        .apply { marginEnd = px(8) }
                    setOnClickListener { accept(m) }
                })
            }
        })
        // «Отказаться»
        card.addView(Button(this).apply {
            text = tr("Отказаться", "Rad etish"); setTextColor(Color.WHITE); textSize = 16f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = px(14).toFloat(); setColor(Color.parseColor("#E23B3B"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(48))
                .apply { topMargin = px(10) }
            setOnClickListener { reject() }
        })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = px(60); x = 0
            horizontalMargin = 0.03f
        }

        view = card
        runCatching { wm?.addView(card, lp) }

        // собственный обратный отсчёт (как у штатного)
        val timeout = Scat.conn?.settings?.value?.get("timeselect_timeout")?.toIntOrNull() ?: 12
        scope.launch {
            var t = timeout
            while (t >= 0) {
                timerTv?.text = String.format("00:%02d", t)
                delay(1000); t--
            }
            stopSelf()   // время вышло
        }
    }

    private fun accept(minutes: Int) {
        orderId?.let { Scat.conn?.acceptWithTime(it, minutes) }
        // открыть приложение для продолжения (тап пользователя разрешает запуск активити)
        runCatching {
            startActivity(Intent(this, uz.ishtixon.scatdriver.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        OrderAlert.cancel(this)
        stopSelf()
    }

    private fun reject() {
        orderId?.let { Scat.conn?.rejectOrder(it); Scat.conn?.clearLiveOrder() }
        OrderAlert.cancel(this)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { view?.let { wm?.removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = ctx.startService(Intent(ctx, OrderOverlayService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OrderOverlayService::class.java))
    }
}
