package uz.ishtixon.scatdriver.model

import android.content.Context
import java.util.UUID

/** Хранилище настроек подключения и учётки. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("scat", Context.MODE_PRIVATE)

    var mainHost: String get() = sp.getString("mainHost", "") ?: ""
        set(v) = sp.edit().putString("mainHost", v).apply()
    var mainPort: Int get() = sp.getInt("mainPort", 1050)
        set(v) = sp.edit().putInt("mainPort", v).apply()
    var reserveHost: String get() = sp.getString("reserveHost", "") ?: ""
        set(v) = sp.edit().putString("reserveHost", v).apply()
    var reservePort: Int get() = sp.getInt("reservePort", 0)
        set(v) = sp.edit().putInt("reservePort", v).apply()
    var login: String get() = sp.getString("login", "") ?: ""
        set(v) = sp.edit().putString("login", v).apply()
    var password: String get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    /** Тариф водителя (назначен с панели) — берём из его заказов, показываем в самозаказе только его. */
    var selfRateId: Int get() = sp.getInt("selfRateId", 0)
        set(v) = sp.edit().putInt("selfRateId", v).apply()

    /** Тёмная тема (по умолчанию — да, как было). */
    var darkTheme: Boolean get() = sp.getBoolean("darkTheme", true)
        set(v) = sp.edit().putBoolean("darkTheme", v).apply()

    /** Язык интерфейса: "uz" (по умолчанию) или "ru". */
    var lang: String get() = sp.getString("lang", "uz") ?: "uz"
        set(v) = sp.edit().putString("lang", v).apply()

    /** База depositapp для активации по коду. */
    var activationBase: String get() = sp.getString("activationBase", "https://531.ishtixon24.uz") ?: ""
        set(v) = sp.edit().putString("activationBase", v).apply()

    // ---- Снимок активного заказа/таксометра (переживает перезапуск, taxometrsave) ----
    var sOrderId: String get() = sp.getString("s_ord", "") ?: ""
        set(v) = sp.edit().putString("s_ord", v).apply()
    var sStatus: Int get() = sp.getInt("s_st", 0)
        set(v) = sp.edit().putInt("s_st", v).apply()
    var sKm: Float get() = sp.getFloat("s_km", 0f)
        set(v) = sp.edit().putFloat("s_km", v).apply()
    var sSec: Int get() = sp.getInt("s_sec", 0)
        set(v) = sp.edit().putInt("s_sec", v).apply()
    var sStandSec: Int get() = sp.getInt("s_stand", 0)
        set(v) = sp.edit().putInt("s_stand", v).apply()
    var sAmount: Float get() = sp.getFloat("s_amt", 0f)
        set(v) = sp.edit().putFloat("s_amt", v).apply()
    var sMeterActive: Boolean get() = sp.getBoolean("s_meter", false)
        set(v) = sp.edit().putBoolean("s_meter", v).apply()
    /** Момент (epoch ms), когда заканчивается текущий отсчёт фазы (подача/ожидание). 0 — нет. */
    var sPhaseDeadline: Long get() = sp.getLong("s_phase", 0L)
        set(v) = sp.edit().putLong("s_phase", v).apply()

    fun clearOrderSnapshot() = sp.edit()
        .remove("s_ord").remove("s_st").remove("s_km").remove("s_sec")
        .remove("s_stand").remove("s_amt").remove("s_meter").remove("s_phase").apply()

    /** Стабильный UUID устройства — генерируем один раз. */
    val deviceUuid: String
        get() {
            var u = sp.getString("uuid", null)
            if (u == null) { u = UUID.randomUUID().toString(); sp.edit().putString("uuid", u).apply() }
            return u
        }
}
