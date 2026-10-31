package uz.ishtixon.scatdriver.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Обработка кнопки «Отказаться» прямо из уведомления о заказе. */
class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != OrderAlert.ACTION_REJECT) return
        val id = intent.getStringExtra(OrderAlert.EXTRA_ID)
        if (!id.isNullOrBlank()) {
            runCatching { Scat.conn?.rejectOrder(id) }      // $RJCT;id;MANUAL
            runCatching { Scat.conn?.clearLiveOrder() }
        }
        OrderAlert.cancel(ctx)
    }
}
