package uz.ishtixon.scatdriver.proto

/**
 * Кадрирование протокола SCAT: $<body>*<CS>\r\n
 * CS = XOR всех байт body (между $ и *), 2 hex-символа в верхнем регистре (NMEA-стиль).
 */
object Frame {

    fun checksum(body: String): String {
        // XOR по БАЙТАМ UTF-8 (кадр уходит в UTF-8). Для ASCII совпадает с посимвольным,
        // но для кириллицы/узбекского — обязательно по байтам, иначе сервер отбросит кадр (напр. SMS).
        var x = 0
        for (b in body.toByteArray(Charsets.UTF_8)) x = x xor (b.toInt() and 0xFF)
        return "%02X".format(x and 0xFF)
    }

    /** Собрать кадр из готового body (без $ и *). */
    fun build(body: String): String = "\$$body*${checksum(body)}\r\n"

    /** Простая команда: $NAME;p1;p2 */
    fun command(name: String, vararg params: String): String {
        val body = if (params.isEmpty()) name else name + ";" + params.joinToString(";")
        return build(body)
    }

    /** Форма с номером запроса: $;<req>;NAME;<params> (строки берём в кавычки вызывающей стороной). */
    fun request(req: Int, name: String, vararg params: String): String {
        val tail = if (params.isEmpty()) "" else ";" + params.joinToString(";")
        return build(";$req;$name$tail")
    }

    data class Parsed(
        val name: String,
        val params: List<String>,
        val req: Int? = null,   // номер запроса для формы $;<req>;NAME;;result
        val ok: Boolean         // контрольная сумма сошлась
    )

    /** Разобрать одну строку кадра (без \r\n). Вернёт null, если это не кадр. */
    fun parse(line: String): Parsed? {
        val s = line.trim()
        if (!s.startsWith("$")) return null
        val star = s.lastIndexOf('*')
        if (star < 1 || star + 3 > s.length) return null
        val body = s.substring(1, star)
        val cs = s.substring(star + 1, star + 3)
        val ok = checksum(body).equals(cs, ignoreCase = true)

        // форма с номером запроса: body = ";<req>;NAME;;result" -> начинается с ';'
        if (body.startsWith(";")) {
            // срезаем ведущую ';' и делим; первый сегмент — req, второй — name, остальное — payload
            val parts = body.split(";")
            // parts[0] = "" , parts[1] = req, parts[2] = name, parts[3..] = payload (после ;;)
            val req = parts.getOrNull(1)?.toIntOrNull()
            val name = parts.getOrNull(2) ?: ""
            // payload идёт после "NAME;;" — склеиваем остаток как есть (может содержать ';')
            val marker = ";$req;$name;;"
            val idx = body.indexOf(";;")
            val payload = if (idx >= 0) body.substring(idx + 2) else ""
            return Parsed(name, if (payload.isEmpty()) emptyList() else listOf(payload), req, ok)
        }

        // простая форма: NAME;p1;p2...  но payload команд (ORDCH/ORDJSON) содержит JSON с ';' внутри -> режем только первый разделитель имени
        val semi = body.indexOf(';')
        if (semi < 0) return Parsed(body, emptyList(), null, ok)
        val name = body.substring(0, semi)
        val rest = body.substring(semi + 1)
        // для команд с JSON достаточно first-split по имени; остальные параметры делим по ';'
        val params = splitParams(name, rest)
        return Parsed(name, params, null, ok)
    }

    /** Команды, у которых хвост — единый JSON/текст, не делим по ';'. */
    private val RAW_TAIL = setOf("ORDJSON", "SERVERINFO", "USR_JSON", "PROFILESTATUS")

    private fun splitParams(name: String, rest: String): List<String> {
        if (name in RAW_TAIL) return listOf(rest)
        if (name == "ORDCH" || name == "SMS") {
            // ORDCH;<id>;<json> / SMS;<type>;<текст> — первый параметр, затем остаток одним куском
            val k = rest.indexOf(';')
            return if (k < 0) listOf(rest) else listOf(rest.substring(0, k), rest.substring(k + 1))
        }
        return rest.split(";")
    }
}
