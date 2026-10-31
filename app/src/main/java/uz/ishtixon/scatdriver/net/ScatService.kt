package uz.ishtixon.scatdriver.net

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import uz.ishtixon.scatdriver.R

/** Foreground-сервис: держит соединение живым, пока водитель на линии. */
class ScatService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onBind(intent: Intent?): IBinder? = null

    private var lastBalance = Double.NaN
    private var lastFree = -1            // -1 = первое значение берём за точку отсчёта
    private var lastStateText: String? = null
    private var watching = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(stateText("CONNECTING")))
        if (!watching) {
            watching = true
            // ВАЖНО: Scat.conn пересоздаётся при переподключении, поэтому не подписываемся на один
            // экземпляр (иначе статус зависал на «Подключение…»), а опрашиваем текущее соединение.
            scope.launch {
                while (true) {
                    val c = Scat.conn
                    if (c != null) {
                        // статус в уведомлении сервиса
                        val txt = stateText(c.state.value.name)
                        if (txt != lastStateText) { lastStateText = txt; updateNotification(txt) }
                        // пополнение баланса (даже когда приложение спит)
                        val bal = c.balance.value
                        if (!bal.isNaN()) {
                            if (!lastBalance.isNaN() && bal - lastBalance >= 100.0) notifyBalance(bal - lastBalance, bal)
                            lastBalance = bal
                        }
                        // новый свободный заказ — будим экран
                        val cnt = c.freeCount.value
                        if (lastFree >= 0 && cnt > lastFree) OrderAlert.wake(this@ScatService)
                        lastFree = cnt
                    }
                    kotlinx.coroutines.delay(1500)
                }
            }
        }
        return START_STICKY
    }

    /** Уведомление о пополнении баланса (со звуком, высокий приоритет). */
    private fun notifyBalance(delta: Double, balance: Double) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_BAL,
                uz.ishtixon.scatdriver.ui.tr("Баланс", "Balans"),
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = uz.ishtixon.scatdriver.ui.tr("Пополнение баланса", "Balans to'ldirish")
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }
        fun money(v: Double) = String.format(java.util.Locale("ru"), "%,.0f", v).replace(',', ' ')
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = launch?.let { PendingIntent.getActivity(this, 200, it, flags) }
        val n = NotificationCompat.Builder(this, CH_BAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(uz.ishtixon.scatdriver.ui.tr("Баланс пополнен", "Balans to'ldirildi"))
            .setContentText(uz.ishtixon.scatdriver.ui.tr(
                "+${money(delta)} сум. Баланс: ${money(balance)} сум",
                "+${money(delta)} so'm. Balans: ${money(balance)} so'm"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(NOTIF_BAL, n) }
    }

    private fun stateText(name: String): String = when (name) {
        "LOGGED_IN" -> uz.ishtixon.scatdriver.ui.tr("Скат работает", "SKAT dasturi ishlamoqda")
        "BANNED" -> uz.ishtixon.scatdriver.ui.tr("Заблокировано", "Bloklangan")
        "CONNECTED", "CONNECTING" -> uz.ishtixon.scatdriver.ui.tr("Подключение…", "Ulanmoqda…")
        else -> uz.ishtixon.scatdriver.ui.tr("Не в сети", "Oflayn")
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "SKAT", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("SKAT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }

    companion object {
        private const val CH_ID = "scat_fg"
        private const val CH_BAL = "scat_balance"
        private const val NOTIF_ID = 1
        private const val NOTIF_BAL = 8
        fun start(ctx: Context) {
            val i = Intent(ctx, ScatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ScatService::class.java))
    }
}
