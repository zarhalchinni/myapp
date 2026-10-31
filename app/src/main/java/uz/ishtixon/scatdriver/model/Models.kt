package uz.ishtixon.scatdriver.model

/** Профиль водителя из USR_JSON. */
data class Profile(
    val code: String = "",
    val name: String = "",
    val cityName: String = "",
    val serviceName: String = "",
    val queueName: String = "",
    val balance: Double = 0.0,
    val rating: Double? = null,
    val photo: String = "",
    val carClassName: String = "",
    val carClassId: Int = 0
)

/** Сообщение чата с диспетчером (SMS). */
data class ChatMsg(
    val text: String,
    val incoming: Boolean,            // true — от диспетчера, false — от водителя
    val ts: Long = System.currentTimeMillis()
)

/** Системное уведомление (блокировка/разблокировка) — показывается баннером сверху. */
data class SystemNotice(
    val text: String,
    val block: Boolean,               // true — блокировка (красный), false — разблокировка (зелёный)
    val id: Long = System.nanoTime()
)

/** Район из RGNSLST («1,TAXI»): список приходит с сервера и может меняться. */
data class Region(val id: String, val name: String)

/** Предзаказ (резерв) из списка GETFREERES. */
data class Preorder(
    val id: String,
    val from: String,
    val client: String,
    val service: String,
    val whenLocal: String,        // ISO reservationLocal, напр. "2027-06-13T09:00:22"
    val rateId: Int = 0
)

/** Детали предзаказа из RESORD. */
data class PreorderDetail(
    val id: String,
    val from: String,
    val whenStr: String,          // "13.06 09:00"
    val sub: String,
    val phone: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val rateName: String = ""
)

/** Свободный заказ из NFORDS (поля через '|'). */
data class FreeOrder(
    val id: String,
    val regionId: String,
    val fromName: String,
    val street: String,
    val client: String,
    val lat: Double?,
    val lon: Double?,
    val service: String,
    val priority: String,
    val raw: String,
    val extrasTotal: Double = 0.0,        // сумма доп. услуг (поле [2], напр. "+2000")
    val extraIds: List<Int> = emptyList() // id доп. услуг (поле [14], напр. "27x1")
) {
    companion object {
        fun parse(record: String): FreeOrder? {
            val f = record.split("|")
            if (f.isEmpty() || f[0].isBlank()) return null
            // поле [2] = "+2000" (сумма доп. услуг); поле [14] = "27x1,13x1" (extraId x count)
            val extrasSum = f.getOrElse(2) { "" }.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
            val ids = f.getOrElse(14) { "" }.split(",").mapNotNull { seg ->
                seg.substringBefore("x").trim().toIntOrNull()
            }
            return FreeOrder(
                id = f.getOrElse(0) { "" },
                regionId = f.getOrElse(1) { "" },
                fromName = f.getOrElse(3) { "" },
                street = f.getOrElse(4) { "" },
                client = f.getOrElse(6) { "" },
                lat = f.getOrNull(7)?.toDoubleOrNull(),
                lon = f.getOrNull(8)?.toDoubleOrNull(),
                service = f.getOrElse(10) { "" },
                priority = f.getOrElse(11) { "" },
                raw = record,
                extrasTotal = extrasSum,
                extraIds = ids
            )
        }
    }

    /** Расстояние (км) от точки водителя до заказа; null если нет координат. */
    fun distanceKm(fromLat: Double, fromLon: Double): Double? {
        var la = lat ?: return null; var lo = lon ?: return null
        if (Math.abs(la) > 90 && Math.abs(lo) <= 90) { val t = la; la = lo; lo = t }   // на случай lon;lat
        if (la == 0.0 && lo == 0.0) return null
        return haversineKm(fromLat, fromLon, la, lo)
    }
}

/** Итог завершённого заказа — для финального окна со счётом/чеком. */
data class CompletedInfo(
    val id: String,
    val total: Double,      // к оплате = поездка + доп. услуги
    val trip: Double,       // сумма по таксометру
    val extras: Double,     // сумма доп. услуг
    val km: Double,
    val sec: Int
)

/** Запись справочника доп. услуг (из SETEXTRAS): название, цена и исходный globalExtra для обратной отправки. */
data class ExtraDef(
    val id: Int,
    val shortName: String,
    val name: String,
    val price: Double,
    val globalExtraRaw: String = ""   // исходный JSON globalExtra — отправляем его обратно без изменений
)

/** Доп. услуга заказа (из массива "extras" в ORDJSON/ORDCH). */
data class OrderExtra(
    val id: Int,
    val price: Double,
    val count: Int = 1,
    val name: String = ""           // название подставляется из справочника услуг, если известно
)

/** Активный заказ (данные из ORDJSON/ORDCH, минимально нужное для UI). */
data class ActiveOrder(
    val id: String,
    val status: Int,
    val fromName: String = "",
    val fromStreet: String = "",
    val clientPhone: String = "",
    val clientName: String = "",
    val service: String = "",
    val rateName: String = "",
    val rate: Rate? = null,
    val reservation: String = "",   // ISO время предзаказа (для статуса RESERVED)
    val rawJson: String = "",
    val lat: Double? = null,        // координаты точки подачи (from.latitude/longitude из ORDJSON)
    val lon: Double? = null,
    val extras: List<OrderExtra> = emptyList()
) {
    /** Сумма доп. услуг (наценка), сўм. */
    val extrasTotal: Double get() = extras.sumOf { it.price * it.count }
}

/** Расстояние по прямой (км) между двумя точками (haversine). */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** Статусы заказа SCAT. */
object OrderStatus {
    const val OFFERED = 1     // предложен
    const val CONFIRMED = 2   // подтверждён/назначен
    const val ACCEPTED = 3    // принят в работу
    const val EXECUTING = 4   // выполнение (клиент в машине)
    const val ARRIVED = 11    // прибыл на место
    const val RESERVED = 51   // предзаказ (взят, ещё не начат)
    fun title(s: Int) = when (s) {
        OFFERED -> uz.ishtixon.scatdriver.ui.tr("Предложен", "Taklif qilindi")
        CONFIRMED -> uz.ishtixon.scatdriver.ui.tr("Подтверждён", "Tasdiqlangan")
        ACCEPTED -> uz.ishtixon.scatdriver.ui.tr("Принят", "Qabul qilindi")
        EXECUTING -> uz.ishtixon.scatdriver.ui.tr("В пути", "Yo'lda")
        ARRIVED -> uz.ishtixon.scatdriver.ui.tr("На месте", "Yetib keldi")
        else -> uz.ishtixon.scatdriver.ui.tr("Статус", "Holat") + " $s"
    }
}
