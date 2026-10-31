package uz.ishtixon.scatdriver.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import uz.ishtixon.scatdriver.R

/** Видно ли сейчас наше окно (activity на переднем плане). Ставится из MainActivity. */
object AppState {
    @Volatile var resumed = false
}

/**
 * Полноэкранное уведомление о входящем заказе — как у штатного:
 * показывается поверх блокировки экрана, будит экран, и прямо в уведомлении
 * есть кнопки «Взять заказ» / «Отказаться».
 */
object OrderAlert {
    private const val CH_ID = "scat_order2"   // новый канал: гарантированно высокий приоритет (heads-up)
    private const val NOTIF_ID = 7
    const val ACTION_REJECT = "uz.ishtixon.scatdriver.ORDER_REJECT"
    const val EXTRA_ID = "order_id"

    /** Принудительно будим экран (как у штатного) — при заказе и при новом свободном заказе. */
    @Suppress("DEPRECATION")
    fun wake(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return
            if (pm.isInteractive) return   // экран уже включён
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "scat:incoming_order")
            wl.acquire(12_000L)   // держим экран включённым ~12 c
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { if (wl.isHeld) wl.release() }
            }, 10_000L)
        }
    }

    /** Создать канал заказов заранее (как штатный — при старте приложения), importance HIGH. */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID,
            uz.ishtixon.scatdriver.ui.tr("Входящие заказы", "Kiruvchi buyurtmalar"),
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = uz.ishtixon.scatdriver.ui.tr("Прямые заказы от диспетчера", "Dispetcherdan to'g'ridan-to'g'ri buyurtmalar")
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            enableLights(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun show(ctx: Context, title: String, text: String, orderId: String?) {
        wake(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ensureChannel(ctx)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        // «Взять заказ» — открыть приложение (окно заказа, выбор времени подачи)
        val openIntent = Intent(ctx, uz.ishtixon.scatdriver.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piOpen = PendingIntent.getActivity(ctx, 100, openIntent, piFlags)

        // «Отказаться» — отклонить заказ прямо из уведомления (broadcast)
        val rejectIntent = Intent(ctx, OrderActionReceiver::class.java).apply {
            action = ACTION_REJECT
            putExtra(EXTRA_ID, orderId)
        }
        val piReject = PendingIntent.getBroadcast(ctx, 101, rejectIntent, piFlags)

        // Обычное срочное уведомление с кнопками — как у штатного. БЕЗ fullScreenIntent:
        // на HyperOS/MIUI уведомление с FSI, которое система не может развернуть, уходит в шторку
        // вместо heads-up. Без FSI оно нормально всплывает сверху (канал «Входящие заказы» = HIGH),
        // а на экране блокировки показывается обычным уведомлением. Экран будим через wake().
        val b = NotificationCompat.Builder(ctx, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(piOpen)
            .setAutoCancel(true)
            // НЕ setOngoing — иначе уведомление не всплывает сверху, а молча падает в шторку
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(R.drawable.ic_stat, uz.ishtixon.scatdriver.ui.tr("Взять заказ", "Buyurtma olish"), piOpen)
        if (!orderId.isNullOrBlank())
            b.addAction(R.drawable.ic_stat, uz.ishtixon.scatdriver.ui.tr("Отказаться", "Rad etish"), piReject)
        runCatching { nm.notify(NOTIF_ID, b.build()) }
        // окно заказа поверх всего (если выдано «поверх других окон») рисует OrderOverlayService.
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}
