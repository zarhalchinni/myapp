package uz.ishtixon.scatdriver.model

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Тариф SCAT и расчёт стоимости (по формуле из штатного клиента, TaxCounter.o):
 * расстояние считается по посегментным модификаторам (start=км, cost=цена/км),
 * плюс стоянка/ожидание (автостоянка) по waitingRate за минуту после бесплатного времени.
 */
data class Rate(
    val id: Int = 0,
    val name: String = "",
    val minFare: Double = 0.0,
    val farePerKm: Double = 0.0,
    val skipFirstKm: Double = 0.0,
    val stationaryFare: Double = 0.0,     // цена стоянки/мин
    val freeStationaryTime: Int = 0,      // мин бесплатной стоянки
    val enableAutoStationaryFare: Boolean = false,
    val freeWaitingTime: Int = 0,         // мин бесплатного ожидания
    val waitingRate: Double = 0.0,        // цена ожидания/мин
    val changeoverSpeed: Double = 5.0,    // км/ч: ниже — стоянка, выше — движение
    val roundTo: Double = 0.0,
    val markup: Double = 0.0,             // наценка, %
    val modStarts: List<Double> = emptyList(),  // пороги км
    val modCosts: List<Double> = emptyList(),    // цена/км для сегмента
    val assigned: Boolean = false                // поле[9]==0 -> тариф назначен ИМЕННО этому водителю
) {
    companion object {
        /**
         * Разбор тарифа из строки RTS (pipe-формат).
         * Индексы полей сверены с JSON того же тарифа из ORDJSON.
         * 0:id 1:имя 2:minFare 4:стоянка/мин 7:changeoverSpeed 13:roundTo
         * 15:беспл.стоянка(мин) 16:беспл.ожидание(мин) 17:ожидание/мин 27:модификаторы(start=cost:...)
         */
        fun fromRts(line: String): Rate? {
            val f = line.split("|")
            val id = f.getOrNull(0)?.toIntOrNull() ?: return null
            val name = f.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            fun d(i: Int) = f.getOrNull(i)?.toDoubleOrNull() ?: 0.0
            fun n(i: Int) = f.getOrNull(i)?.toDoubleOrNull()?.toInt() ?: 0
            val starts = ArrayList<Double>(); val costs = ArrayList<Double>()
            f.getOrNull(27)?.takeIf { it.isNotBlank() }?.split(":")?.forEach { seg ->
                val kv = seg.split("=")
                val s = kv.getOrNull(0)?.toDoubleOrNull(); val c = kv.getOrNull(1)?.toDoubleOrNull()
                if (s != null && c != null) { starts += s; costs += c }
            }
            val stationary = d(4)
            return Rate(
                id = id, name = name, minFare = d(2),
                stationaryFare = stationary, freeStationaryTime = n(15),
                enableAutoStationaryFare = stationary > 0,
                freeWaitingTime = n(16), waitingRate = d(17),
                changeoverSpeed = d(7).takeIf { it > 0 } ?: 5.0,
                roundTo = d(13), modStarts = starts, modCosts = costs,
                assigned = f.getOrNull(9)?.trim() == "0"   // сервер помечает тариф водителя нулём
            )
        }

        fun parse(o: JSONObject): Rate {
            val mods = o.optJSONArray("modifiers")
            val starts = ArrayList<Double>(); val costs = ArrayList<Double>()
            if (mods != null) for (i in 0 until mods.length()) {
                val m = mods.getJSONObject(i)
                starts += m.optDouble("start", 0.0); costs += m.optDouble("cost", 0.0)
            }
            return Rate(
                id = o.optInt("id"),
                name = o.optString("name"),
                minFare = o.optDouble("minFare", 0.0),
                farePerKm = o.optDouble("farePerKm", 0.0),
                skipFirstKm = o.optDouble("skipFirstKm", 0.0),
                stationaryFare = o.optDouble("stationaryFare", 0.0),
                freeStationaryTime = o.optInt("freeStationaryTime", 0),
                enableAutoStationaryFare = o.optBoolean("enableAutoStationaryFare", false),
                freeWaitingTime = o.optInt("freeWaitingTime", 0),
                waitingRate = o.optDouble("waitingRate", 0.0),
                changeoverSpeed = o.optDouble("changeoverSpeed", 5.0),
                roundTo = o.optDouble("roundTo", 0.0),
                markup = o.optDouble("markupRatio", 0.0),
                modStarts = starts, modCosts = costs
            )
        }
    }

    /** Стоимость за расстояние (км) — посегментно по модификаторам, затем наценка. */
    fun distanceFare(km: Double): Double {
        var fare: Double
        if (modStarts.isNotEmpty()) {
            val base = max(minOf(km, modStarts[0]) - skipFirstKm, 0.0) * farePerKm
            var tiers = 0.0
            var last = -1
            for (i in modStarts.indices) if (modStarts[i] <= km) last = i
            if (last >= 0) {
                var i = 0
                while (i < last) { tiers += (modStarts[i + 1] - modStarts[i]) * modCosts[i]; i++ }
                tiers += (km - modStarts[last]) * modCosts[last]
            }
            fare = base + tiers
        } else {
            fare = max(km - skipFirstKm, 0.0) * farePerKm
        }
        return fare * (markup + 100.0) / 100.0
    }

    /**
     * Автостоянка ВО ВРЕМЯ поездки: цена за минуту и бесплатное время (сек).
     * Берём stationaryFare/freeStationaryTime (у тарифа с автостоянкой); freeWaitingTime/waitingRate —
     * это ожидание клиента ДО поездки, к стоянке в пути не относится.
     */
    val standPerMin: Double
        get() = if (enableAutoStationaryFare && stationaryFare > 0) stationaryFare else waitingRate
    val standFreeSec: Int
        get() = (if (enableAutoStationaryFare && stationaryFare > 0) freeStationaryTime else freeWaitingTime) * 60

    /** Автостоянка включена на тарифе (сервер «Скат»). Если false — стоянку водитель включает вручную. */
    val autoStand: Boolean get() = enableAutoStationaryFare && stationaryFare > 0
    /** Есть ли вообще плата за стоянку (для ручной кнопки). */
    val hasStandPrice: Boolean get() = stationaryFare > 0 || waitingRate > 0

    /** Цена за км на текущем участке (по модификаторам: цена действует от порога start). */
    fun kmPrice(km: Double): Double {
        var price = farePerKm
        for (i in modStarts.indices) if (modStarts[i] <= km) price = modCosts[i]
        return price
    }

    /** Стоимость стоянки (автостоянка): секунды простоя сверх бесплатных. */
    fun standFare(standSeconds: Int): Double {
        val chargeable = max(standSeconds - standFreeSec, 0)
        return chargeable / 60.0 * standPerMin
    }

    /** Итоговая сумма с округлением и минималкой. */
    fun total(km: Double, standSeconds: Int): Double {
        var amount = distanceFare(km) + standFare(standSeconds)
        amount = max(amount, minFare)
        if (roundTo > 0) amount = (amount / roundTo).roundToLong() * roundTo
        return amount
    }
}
