package uz.ishtixon.scatdriver.net

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import uz.ishtixon.scatdriver.model.*
import uz.ishtixon.scatdriver.ui.tr
import uz.ishtixon.scatdriver.proto.Frame
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ядро связи с сервером SCAT. Один постоянный TCP-сокет, текстовый протокол $...*CS\r\n.
 * Держит логин/сессию, heartbeat, реконнект на резервный сервер, разбирает входящие команды.
 */
class ScatConnection(
    private val cfg: Config,
    private val scope: CoroutineScope
) {
    data class Config(
        val mainHost: String,
        val mainPort: Int,
        val reserveHost: String? = null,
        val reservePort: Int = 0,
        val login: String,
        val password: String,
        val appVersion: String = "4.7.36",
        val deviceUuid: String,
        val locale: String = "ru",
        val features: List<String> = listOf(
            "noncash.ordpay", "order.no_modify", "order.chat",
            "rate.decimalRounding", "cmd.usr_json"
        )
    )

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, LOGGED_IN, BANNED, ERROR }

    sealed class Event {
        data class Error(val code: String, val text: String) : Event()
        data class Info(val msg: String) : Event()
        data class OrderOffered(val order: ActiveOrder) : Event()   // ORDJSON
        data class OrderChanged(val order: ActiveOrder) : Event()   // ORDCH
        data class Confirmed(val id: String, val code: String) : Event()
        data class CompleteOk(val id: String) : Event()
        data class OrderStatusSet(val id: String, val status: Int) : Event()   // входящий ORDST;id;status
        data class SelfOrderCreated(val id: String) : Event()                  // ответ DORD;<id> на самозаказ
        data class Blocked(val reason: String, val byAdmin: Boolean) : Event() // $ERR;youban
        data class Unblocked(val byPayment: Boolean) : Event()                 // сервер заново прислал сессию
    }

    val state = MutableStateFlow(State.DISCONNECTED)
    // Текущий заказ как StateFlow: переживает гонку подписки при перезапуске (окно всегда откроется).
    val liveOrder = MutableStateFlow<ActiveOrder?>(null)

    // Журнал сырого обмена для отладки (последние 300 строк): ">>" отправка, "<<" приём.
    // Общий журнал на все экземпляры — чтобы видеть, если их несколько.
    val ioLog get() = sharedLog
    private val cid = instanceCounter.incrementAndGet()
    private val oid get() = Integer.toHexString(System.identityHashCode(this))
    private fun log(line: String) = appendLog("#$cid·$oid $line")
    val profile = MutableStateFlow<Profile?>(null)
    // текущий баланс (из USR_JSON и BLNC) — отдельный поток, чтобы сервис ловил пополнение даже без экрана
    val balance = MutableStateFlow(Double.NaN)
    val settings = MutableStateFlow<Map<String, String>>(emptyMap())
    val freeOrders = MutableStateFlow<List<FreeOrder>>(emptyList())
    val regions = MutableStateFlow<List<Region>>(emptyList())   // районы (RGNSLST)
    val freeCount = MutableStateFlow(0)
    val orderTimes = MutableStateFlow(listOf(3, 5, 7, 10, 15, 20))
    val tariffs = MutableStateFlow<List<Rate>>(emptyList())   // тарифы из RTS (с ценами)
    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())   // чат с диспетчером
    val smsTemplates = MutableStateFlow<List<String>>(emptyList())
    val preorders = MutableStateFlow<List<Preorder>>(emptyList())   // свободные предзаказы (GETFREERES)
    val preorderCount = MutableStateFlow(0)
    val preorderDetail = MutableStateFlow<PreorderDetail?>(null)
    val attachedOrders = MutableStateFlow<List<ActiveOrder>>(emptyList())   // взятые предзаказы (GETATTACHED, status 51)
    // справочник доп. услуг: extraId -> название/цена (наполняется из SETEXTRAS, когда сервер его присылает)
    val extrasCatalog = MutableStateFlow<Map<Int, ExtraDef>>(emptyMap())
    val sid = MutableStateFlow<String?>(null)
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var ioJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var running = false
    val isRunning get() = running
    fun sameTarget(c: Config) = c.mainHost == cfg.mainHost && c.login == cfg.login
    private val reqCounter = AtomicInteger(0)
    @Volatile private var pingIntervalSec = 25
    @Volatile private var cachedDataTs: Long = 0   // §3: timestamp закэшированных справочников

    fun start() {
        if (running) return
        running = true
        log("== START экземпляра")
        ioJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stop() {
        log("== STOP экземпляра")
        running = false
        events.tryEmit(Event.Info(tr("Смена закрыта, желаем удачи!","Smena yopildi, omad tilaymiz!")))
        runCatching { send(Frame.command("EXIT")) }  // уйти с линии
        // ВАЖНО: даём очереди записи отправить EXIT до закрытия сокета (раньше сокет закрывался
        // сразу, и сервер не получал EXIT — водитель оставался «на линии»).
        runCatching {
            writeExec.shutdown()
            writeExec.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        closeSocket()
        pingJob?.cancel()
        ioJob?.cancel()
        runCatching { writeExec.shutdownNow() }
        state.value = State.DISCONNECTED
    }

    // последние координаты водителя. ВНИМАНИЕ: SCAT в GPS-командах шлёт долготу;широту (lon;lat)!
    @Volatile private var curLat = 0.0
    @Volatile private var curLon = 0.0
    @Volatile private var hasFix = false
    private fun f6(v: Double) = String.format(Locale.US, "%.6f", v)
    // Штатный клиент добавляет координаты ТОЛЬКО когда есть GPS-фикс.
    // Без фикса шлёт команду без координат (CFRM;id;min), иначе сервер её игнорирует.
    private fun lonLat(): Array<String> =
        if (hasFix) arrayOf(f6(curLon), f6(curLat)) else emptyArray()   // lon, lat

    // ---- исходящие действия (API для UI) ----
    fun pollFreeOrders() = send(Frame.command("NFORDS", "1"))
    fun pollPreorders() = send(Frame.request(0, "GETFREERES", "\"\""))      // список свободных предзаказов
    fun requestPreorder(id: String) = send(Frame.request(1, "RESORD", id))  // RESORD = ВЗЯТЬ предзаказ (+детали)
    fun requestAttached() = send(Frame.request(20, "GETATTACHED", ""))      // взятые предзаказы (назначенные)
    fun requestOrderDetails(id: String) = send(Frame.command("FORD", id))
    fun acceptOrder(id: String) = send(Frame.command("RCV", id))

    /** Принять заказ: RCV;id + CFRM;id;minutes;lon;lat (как штатный клиент). */
    fun acceptWithTime(id: String, minutes: Int) {
        send(Frame.command("RCV", id))
        send(Frame.command("CFRM", id, minutes.toString(), *lonLat()))
    }
    fun onPlace(id: String) = send(Frame.command("ONPLACE", id, *lonLat()))
    fun setOrderStatus(id: String, status: Int) = send(Frame.command("ORDST", id, status.toString(), *lonLat()))
    fun rejectOrder(id: String, reason: String = "MANUAL") = send(Frame.command("RJCT", id, reason))

    /** Трек во время поездки: TRACK;id;lon;lat;rateId;0;0;amount;0;0;stand;total. */
    fun sendTrack(id: String, rateId: String, amount: Double, standSec: Int, totalSec: Int) =
        send(Frame.command("TRACK", id, f6(curLon), f6(curLat), rateId,
            "0.000000", "0.000000", f6(amount), "0", "0", standSec.toString(), totalSec.toString()))

    /** Завершение: CMPLT;id;amount;0;stand;0;total;{counters}. */
    fun completeOrder(id: String, rateId: String, amount: Double, distMeters: Int,
                      distAmount: Double, standSec: Int, standAmount: Double, totalSec: Int) {
        val counters = JSONObject().apply {
            put("seat", 0); put("wait", 0); put("waitTime", 0)
            put("counters", JSONObject().apply {
                put(rateId, JSONObject().apply {
                    put("stand", standSec); put("standAmount", "%.2f".format(Locale.US, standAmount))
                    put("time", totalSec); put("timeAmount", "0.00")
                    put("dist", distMeters); put("distAmount", "%.2f".format(Locale.US, distAmount))
                    put("amount", amount.toInt()); put("sort", 0)
                })
            })
            put("extrasCounters", JSONObject())
        }
        send(Frame.command("CMPLT", id, f6(amount), "0", standSec.toString(), "0", totalSec.toString(), counters.toString()))
    }

    fun orderCheck(id: String) = send(Frame.command("ORDERCHECK", id, "0"))
    fun requestBalance() = send(Frame.command("BLNC"))
    fun clearLiveOrder() { liveOrder.value = null }
    fun unbindOrder(id: String) = send(Frame.request(reqCounter.incrementAndGet(), "UNBINDORD", "\"$id\""))
    fun getAttached() = send(Frame.request(reqCounter.incrementAndGet(), "GETATTACHED"))
    fun getFreeReserves() = send(Frame.request(reqCounter.incrementAndGet(), "GETFREERES", "\"\""))
    fun sendGps(lat: Double, lon: Double, speed: Double = 0.0, course: Double = 0.0) {
        val real = lat != 0.0 || lon != 0.0          // фикс считаем настоящим только при ненулевых координатах
        if (real) { curLat = lat; curLon = lon; hasFix = true }
        if (real) send(Frame.command("GPS", f6(lon), f6(lat), f6(speed), f6(course)))  // долгота;широта
    }
    fun sendSms(templateIdx: Int, text: String): Boolean {
        val ok = send(Frame.command("SMS", templateIdx.toString(), text))
        if (text.isNotBlank()) messages.value = messages.value + ChatMsg(text, incoming = false)
        return ok
    }
    fun selfOrder(rateId: Int) =
        send(Frame.command("DORD", JSONObject().apply {
            put("attributes", JSONObject().put("tariff", JSONObject().put("id", rateId)))
        }.toString()))

    // ---- связь ----
    private suspend fun connectLoop() {
        val targets = buildList {
            add(cfg.mainHost to cfg.mainPort)
            if (!cfg.reserveHost.isNullOrBlank() && cfg.reservePort > 0)
                add(cfg.reserveHost to cfg.reservePort)
        }
        var i = 0
        while (running) {
            val (host, port) = targets[i % targets.size]
            try {
                state.value = State.CONNECTING
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s; out = s.getOutputStream()
                liveSocket = s; liveOut = out       // регистрируем живой сокет на уровне синглтона
                state.value = State.CONNECTED
                log("== сокет подключён $host:$port")
                login()
                startPing()
                readLoop(s)   // блокируется, пока сокет жив
                log("== readLoop завершился")
            } catch (e: Exception) {
                Log.w(TAG, "connect/read error: ${e.message}")
                log("== ошибка соединения: ${e.message}")
                // не пугаем водителя при намеренном выходе (running=false) — там своё сообщение
                if (running) events.tryEmit(Event.Info("${tr("Соединение потеряно","Aloqa uzildi")}: ${e.message}"))
            } finally {
                pingJob?.cancel()
                closeSocket()
                if (running) state.value = State.CONNECTING
            }
            if (!running) break
            i++
            delay(3000)  // пауза перед переподключением / переходом на резерв
        }
    }

    private fun login() {
        val ts = System.currentTimeMillis() / 1000
        val body = listOf(
            "LGN", cfg.login, cfg.password, "a${cfg.appVersion}",
            cachedDataTs.toString(), ts.toString(), cfg.deviceUuid, cfg.locale,
            JSONArray(cfg.features).toString()
        ).joinToString(";")
        send(Frame.build(body))
    }

    /** Стартовая инициализация после логина — как делает штатный клиент (разблокирует поток заказов). */
    private fun sendInit() {
        send(Frame.command("RGNS"))
        send(Frame.request(reqCounter.incrementAndGet(), "GETPROFILESTATUS"))
        send(Frame.command("GETRTS"))
        send(Frame.command("GETRATEREGIONS"))
        send(Frame.command("GETADS"))
        send(Frame.command("GETEXTRAS"))
        send(Frame.command("GETPOLYGONS"))
        send(Frame.command("GETBUSYSTATE"))
        send(Frame.command("GETPREPAIDDATE"))
        send(Frame.command("BLNC"))
        send(Frame.command("BONUSACCOUNT"))
        send(Frame.request(reqCounter.incrementAndGet(), "GETATTACHED", ""))
        // первый опрос свободных (реальный GPS придёт от LocationManager отдельно)
        pollFreeOrders()
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (running && socket?.isConnected == true) {
                delay(pingIntervalSec * 1000L)
                send("p")   // через ту же очередь записи
                // периодический опрос баланса (~каждые 2 пинга) — чтобы поймать пополнение,
                // даже когда приложение спит и сервер не прислал обновление сам
                if (++tick % 2 == 0) send(Frame.command("BLNC"))
            }
        }
    }

    private fun readLoop(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        while (running) {
            val line = reader.readLine() ?: break   // null = сокет закрыт
            if (line.isEmpty() || line == "p") continue
            Log.d(TAG, "<< $line")                   // смотреть: adb logcat -s ScatConnection
            log("<< $line")
            val f = Frame.parse(line) ?: continue
            handle(f)
        }
    }

    @Volatile private var lastFailToastTs = 0L
    private fun notifyFail(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailToastTs > 4000) {
            lastFailToastTs = now
            events.tryEmit(Event.Info("Нет соединения — команда не отправлена ($what)"))
        }
    }

    // Все записи в сокет выполняем на выделенном потоке: Android запрещает сетевые
    // операции в главном потоке (NetworkOnMainThreadException), а send() зовётся из UI/опроса.
    private val writeExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "scat-send").apply { isDaemon = true }
    }

    private fun send(frame: String): Boolean {
        val o = out ?: liveOut            // свой поток вывода или общий живой
        val isPing = frame.trim() == "p"
        if (o == null) {
            if (!isPing) {
                val name = frame.removePrefix("$").takeWhile { it != ';' && it != '*' }
                log("!! нет соединения: ${frame.trim()}")
                // тост только для важных действий водителя, не для фоновых GPS/NFORDS/TRACK
                if (name in setOf("RCV", "CFRM", "ONPLACE", "ORDST", "CMPLT", "FORD", "RJCT"))
                    notifyFail(name)
            }
            return false
        }
        if (!isPing) { Log.d(TAG, ">> ${frame.trim()}"); log(">> ${frame.trim()}") }
        // ВАЖНО: ping тоже должен оканчиваться переводом строки, иначе "p" склеится
        // со следующим кадром и сервер увидит мусорную команду ("p$NFORDS" -> badcommand).
        val bytes = (if (isPing) "p\r\n" else frame).toByteArray(Charsets.UTF_8)
        try {
            writeExec.execute {
                try { synchronized(o) { o.write(bytes); o.flush() } }
                catch (e: Exception) {
                    Log.w(TAG, "write failed: ${e.message}")
                    if (!isPing) log("!! ошибка записи: ${frame.trim()} (${e.javaClass.simpleName}: ${e.message})")
                }
            }
        } catch (e: Exception) {
            if (!isPing) log("!! очередь закрыта: ${frame.trim()}")
            return false
        }
        return true
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        if (liveOut === out) { liveOut = null; liveSocket = null }   // снимаем живую ссылку только если наша
        socket = null; out = null
    }

    // ---- диспетчер входящих ----
    private fun handle(f: Frame.Parsed) {
        when (f.name) {
            "SID" -> sid.value = f.params.getOrNull(0)
            "LGN" -> { state.value = State.LOGGED_IN; sendInit() }
            "SERVERINFO" -> { /* f.params[0] = json version/features */ }
            "SETTINGS" -> { logFull("SETTINGS", f.params.joinToString(";")); parseSettings(f.params) }
            "USR_JSON" -> { logFull("USR_JSON", f.params.getOrNull(0)); parseProfile(f.params.getOrNull(0)) }
            "NFORDS" -> parseFreeOrders(f.params)
            "RGNSLST" -> parseRegions(f.params)
            "ORDERTIMES" -> f.params.getOrNull(0)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }?.let { orderTimes.value = it }
            "RTS" -> {
                // RTS;<версия>;<id|имя|цены...>;...
                val list = f.params.drop(1).mapNotNull { Rate.fromRts(it) }
                if (list.isNotEmpty()) tariffs.value = list
                logFull("RTS", f.params.joinToString(";"))
                // повторный пакет сессии при БЛОКЕ = снятие блокировки (явного UNBAN сервер не шлёт)
                if (state.value == State.BANNED || lastBanReason != null) clearBan()
            }
            "F" -> f.params.getOrNull(1)?.toIntOrNull()?.let {
                freeCount.value = it
                if (it == 0) freeOrders.value = emptyList()   // заказов нет -> чистим список
            }
            "NO" -> { /* нет изменений */ }
            "ORDJSON" -> parseOrder(f.params.getOrNull(0))?.let { liveOrder.value = it; events.tryEmit(Event.OrderOffered(it)) }
            "ORDCH" -> parseOrderCh(f.params)?.let { liveOrder.value = it; events.tryEmit(Event.OrderChanged(it)) }
            "ORDST" -> {
                val id = f.params.getOrNull(0) ?: ""
                val st = f.params.getOrNull(1)?.toIntOrNull()
                if (id.isNotBlank() && st != null) {
                    if (st >= 90 && liveOrder.value?.id == id) liveOrder.value = null   // снят/возвращён
                    events.tryEmit(Event.OrderStatusSet(id, st))
                }
            }
            "DORD" -> f.params.getOrNull(0)?.takeIf { it.toLongOrNull() != null }
                ?.let { events.tryEmit(Event.SelfOrderCreated(it)) }   // ответ: DORD;<реальный id>
            "CFRM" -> events.tryEmit(Event.Confirmed(f.params.getOrElse(0) { "" }, f.params.getOrElse(1) { "" }))
            "CMPLTOK" -> { liveOrder.value = null; events.tryEmit(Event.CompleteOk(f.params.getOrElse(0) { "" })) }
            "SMSTMPLTS" -> smsTemplates.value = f.params.filter { it.isNotBlank() }
            "SMS" -> {
                // входящее от диспетчера: SMS;<type>;<текст>
                val text = (f.params.getOrNull(1) ?: f.params.getOrNull(0) ?: "").trim()
                if (text.isNotBlank()) {
                    messages.value = messages.value + ChatMsg(text, incoming = true)
                    send(Frame.command("SMSOK"))   // подтверждаем приём
                }
            }
            "BLNC" -> f.params.getOrNull(0)?.toDoubleOrNull()?.let { lastBalance = it; balance.value = it }
            "RESORDS" -> f.params.getOrNull(0)?.toIntOrNull()?.let { preorderCount.value = it }
            "GETFREERES" -> if (f.req != null) parsePreorders(f.params.getOrNull(0))
            "RESORD" -> if (f.req == null) parsePreorderDetail(f.params)   // данные (не ack ";1;RESORD;;null")
            "GETATTACHED" -> if (f.req != null) parseAttached(f.params.getOrNull(0))   // взятые предзаказы
            "SETEXTRAS" -> {
                val arr = f.params.firstOrNull { it.trimStart().startsWith("[") }
                if (arr != null) parseExtrasCatalog(arr)                                  // справочник услуг
                else if (f.params.getOrNull(0).equals("OK", true)) events.tryEmit(Event.Info(tr("Доп. услуги сохранены","Qo'shimcha xizmatlar saqlandi")))
            }
            // ответ на GETEXTRAS — справочник услуг (имя кадра может отличаться на сервере)
            "EXTRAS", "GETEXTRAS", "ALLEXTRAS" ->
                f.params.firstOrNull { it.trimStart().startsWith("[") }?.let { parseExtrasCatalog(it) }
            "ERR" -> handleErr(f.params)
            "UNBAN" -> clearBan()
            else -> {
                // диагностика: незнакомый кадр, в котором есть JSON-массив со справочником услуг
                if (f.name.contains("EXTRA", true) || f.params.any { it.contains("globalExtra") || it.contains("short_name") }) {
                    logFull("?${f.name}", f.params.joinToString(";"))
                    f.params.firstOrNull { it.trimStart().startsWith("[") }?.let { parseExtrasCatalog(it) }
                }
            }
        }
    }

    @Volatile private var lastBalance: Double = Double.NaN
    private var lastBanReason: String? = null   // null = не заблокирован
    private var lastBanKind: String? = null      // "reject" / "balance" / "admin"

    /** Снятие блокировки: сервер заново прислал сессию (RTS/SETTINGS) либо пришёл UNBAN. */
    private fun clearBan() {
        if (lastBanReason == null && lastBanKind == null && state.value != State.BANNED) return
        val kind = lastBanKind
        lastBanReason = null; lastBanKind = null
        if (state.value == State.BANNED) state.value = State.LOGGED_IN
        // короткую блокировку за отказ снимаем молча; иначе сообщаем по виду блокировки
        when (kind) {
            "reject" -> {}
            "balance" -> events.tryEmit(Event.Unblocked(byPayment = true))
            else -> events.tryEmit(Event.Unblocked(byPayment = false))
        }
    }

    private fun handleErr(params: List<String>) {
        val code = params.getOrElse(0) { "" }
        val text = params.getOrElse(1) { "" }
        if (code == "needssl") { events.tryEmit(Event.Info("Сервер требует TLS (needssl)")) ; return }
        val isBan = code == "youban" || text.contains("заблокирован", ignoreCase = true)
        if (isBan) {
            state.value = State.BANNED
            var admin = ""
            var reject = ""
            params.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val o = JSONObject(it)
                    if (!o.isNull("admin")) admin = o.optString("admin", "").trim()
                    if (!o.isNull("order_reject")) reject = o.optString("order_reject", "").trim()  // "на 10 секунд"
                }
            }
            if (admin == "null") admin = ""
            val bal = profile.value?.balance ?: lastBalance
            // вид блокировки: отказ / баланс / администратор
            val kind = when {
                reject.isNotBlank() -> "reject"
                !bal.isNaN() && bal < 0 -> "balance"
                else -> "admin"
            }
            val reason = when (kind) {
                "reject" -> "${tr("Отказ от заказа — блокировка","Buyurtmadan voz kechish — bloklash")} $reject"
                "balance" -> tr("Недостаточно средств на балансе (%.0f сум)","Balansda mablag' yetarli emas (%.0f so'm)").format(bal)
                else -> admin   // пусто, если имени нет (покажем просто «Заблокировано администратором»)
            }
            if (reason != lastBanReason || kind != lastBanKind) {   // не спамим одинаковыми youban
                lastBanReason = reason; lastBanKind = kind
                events.tryEmit(Event.Blocked(reason, byAdmin = kind == "admin"))
                // короткая блокировка за отказ снимается сервером (UNBAN); подстрахуемся, если он не придёт
                if (kind == "reject") scope.launch {
                    delay(12000); if (lastBanReason == reason) clearBan()
                }
            }
            return                                    // не показываем как обычную ошибку
        }
        events.tryEmit(Event.Error(code, text))
    }

    private fun parseSettings(params: List<String>) {
        val m = HashMap<String, String>()
        for (p in params) {
            val k = p.indexOf('=')
            if (k > 0) m[p.substring(0, k)] = p.substring(k + 1)
        }
        settings.value = m
        m["ping_interval"]?.toIntOrNull()?.let { if (it in 5..120) pingIntervalSec = it }
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String, def: String): String {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank()) return v }
        return def
    }
    private fun firstInt(o: JSONObject, vararg keys: String, def: Int): Int {
        for (k in keys) if (o.has(k) && !o.isNull(k)) o.optInt(k, 0).let { if (it > 0) return it }
        return def
    }

    private fun parseProfile(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val o = JSONObject(json)
            val cur = profile.value ?: Profile()
            profile.value = cur.copy(
                code = o.optString("code", cur.code),
                name = o.optString("name", cur.name),
                cityName = o.optString("cityName", cur.cityName),
                serviceName = o.optString("serviceName", cur.serviceName),
                queueName = o.optString("queueName", cur.queueName),
                balance = o.optDouble("balance", cur.balance),
                rating = if (o.isNull("rating")) cur.rating else o.optDouble("rating"),
                photo = o.optString("photo", cur.photo),
                carClassName = firstNonBlank(o, "carClassName", "car_class_name", "className", "class_name", def = cur.carClassName),
                carClassId = firstInt(o, "carClassId", "car_class", "classId", "class", def = cur.carClassId)
            )
            profile.value?.balance?.let { if (!it.isNaN()) balance.value = it }
        }
    }

    private fun parsePreorders(json: String?) {
        if (json.isNullOrBlank()) { preorders.value = emptyList(); return }
        runCatching {
            val arr = org.json.JSONArray(json)
            val list = ArrayList<Preorder>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = o.optJSONObject("attributes") ?: continue
                list += Preorder(
                    id = o.optString("id"),
                    from = a.optString("from"),
                    client = a.optJSONObject("client")?.optString("name") ?: "",
                    service = a.optJSONObject("service")?.optString("name") ?: "",
                    whenLocal = a.optJSONObject("time")?.optString("reservationLocal") ?: "",
                    rateId = a.optJSONObject("tariff")?.optInt("id") ?: a.optInt("rateId", 0)
                )
            }
            preorders.value = list
        }
    }

    private fun parseAttached(json: String?) {
        if (json.isNullOrBlank()) { attachedOrders.value = emptyList(); return }
        runCatching {
            val arr = org.json.JSONArray(json)
            val list = ArrayList<ActiveOrder>(arr.length())
            for (i in 0 until arr.length()) parseOrder(arr.getJSONObject(i).toString())?.let { list += it }
            attachedOrders.value = list
        }
    }

    private fun parsePreorderDetail(p: List<String>) {
        val id = p.getOrNull(0) ?: return
        preorderDetail.value = PreorderDetail(
            id = id,
            whenStr = p.getOrNull(1) ?: "",
            rateName = p.getOrNull(13) ?: "",
            from = p.getOrNull(4) ?: "",
            sub = p.getOrNull(5) ?: "",
            phone = (p.getOrNull(6) ?: "").trim(),
            lat = p.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
            lon = p.getOrNull(8)?.toDoubleOrNull() ?: 0.0
        )
    }

    /** RGNSLST;1,TAXI[;2,ДРУГОЙ...] — районы приходят от сервера, не зашиваем названия. */
    private fun parseRegions(params: List<String>) {
        val list = params.flatMap { it.split(";") }.mapNotNull { tok ->
            val i = tok.indexOf(',')
            if (i <= 0) return@mapNotNull null
            val id = tok.substring(0, i).trim()
            val name = tok.substring(i + 1).trim()
            if (id.isBlank() || name.isBlank()) null else Region(id, name)
        }
        if (list.isNotEmpty()) regions.value = list
    }

    private fun parseFreeOrders(params: List<String>) {
        // NFORDS;<rec>;<rec>;...  — записи через ';', поля внутри через '|'
        val list = params.mapNotNull { FreeOrder.parse(it) }
        freeOrders.value = list
    }

    /** optString, но пустую строку возвращает и для JSON null, и для литерала "null". */
    private fun jstr(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        val v = o.optString(key, "")
        return if (v == "null") "" else v
    }

    private fun parseOrder(json: String?): ActiveOrder? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val id = root.optString("id")
            val a = root.optJSONObject("attributes") ?: JSONObject()
            val from = a.optJSONObject("from") ?: JSONObject()
            val client = a.optJSONObject("client") ?: JSONObject()
            val rateObj = a.optJSONObject("rate")
            val service = a.optJSONObject("service")?.optString("name") ?: ""
            ActiveOrder(
                id = id,
                status = a.optInt("status", 0),
                fromName = jstr(from, "name"),
                fromStreet = jstr(from, "street"),
                clientPhone = jstr(client, "phone"),
                clientName = jstr(client, "name"),
                service = service,
                rateName = rateObj?.optString("name") ?: "",
                rate = rateObj?.let { Rate.parse(it) },
                reservation = a.optJSONObject("time")?.optString("reservation") ?: "",
                rawJson = json,
                lat = if (from.has("latitude") && !from.isNull("latitude")) from.optDouble("latitude") else null,
                lon = if (from.has("longitude") && !from.isNull("longitude")) from.optDouble("longitude") else null,
                extras = parseExtras(a.optJSONArray("extras"))
            )
        }.getOrNull()
    }

    /** Доп. услуги из массива "extras": берём id/цену/кол-во, название — из справочника, если известно. */
    private fun parseExtras(arr: org.json.JSONArray?): List<OrderExtra> {
        if (arr == null) return emptyList()
        val cat = extrasCatalog.value
        val out = ArrayList<OrderExtra>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = if (e.has("extraId")) e.optInt("extraId") else e.optInt("extra_id")
            if (id == 0) continue
            out += OrderExtra(
                id = id,
                price = e.optDouble("price", 0.0),
                count = e.optInt("count", 1).coerceAtLeast(1),
                name = cat[id]?.shortName ?: ""
            )
        }
        return out
    }

    /** Разбор справочника доп. услуг из SETEXTRAS;<id>;[...]: название + цена + исходный globalExtra. */
    private fun parseExtrasCatalog(jsonArray: String?) {
        if (jsonArray.isNullOrBlank()) return
        runCatching {
            val arr = org.json.JSONArray(jsonArray)
            val map = LinkedHashMap<Int, ExtraDef>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // справочник может прийти двумя видами: {extraId, shortName, globalExtra{...}} или сам globalExtra {id, short_name, ...}
                val g = o.optJSONObject("globalExtra") ?: o
                val id = o.optInt("extraId", 0).takeIf { it != 0 } ?: g.optInt("id", 0)
                if (id == 0) continue
                val short = listOf(o.optString("shortName"), g.optString("short_name"), g.optString("shortName"))
                    .firstOrNull { it.isNotBlank() } ?: ""
                val name = listOf(o.optString("name"), g.optString("name")).firstOrNull { it.isNotBlank() } ?: ""
                val price = when {
                    o.has("price") -> o.optDouble("price")
                    g.has("price") -> g.optDouble("price")
                    else -> 0.0
                }
                // для обратной отправки сохраняем вложенный globalExtra, либо сам объект, если он и есть globalExtra
                val rawGlobal = o.optJSONObject("globalExtra")?.toString() ?: o.toString()
                map[id] = ExtraDef(id, short.ifBlank { name }, name, price, rawGlobal)
            }
            if (map.isNotEmpty()) {
                extrasCatalog.value = extrasCatalog.value + map
                appendLog("@@ справочник услуг: ${map.size} (${map.values.joinToString(",") { it.shortName }})")
            }
        }
    }

    /**
     * Отправить выбранные доп. услуги: $SETEXTRAS;<orderId>;[{enabled,extraId,count,price,...,globalExtra}].
     * Для каждой услуги подставляем исходный globalExtra из справочника, иначе собираем из id/цены.
     */
    fun sendExtras(orderId: String, items: List<Triple<Int, Double, Boolean>>) {
        val arr = org.json.JSONArray()
        val cat = extrasCatalog.value
        for ((id, price, enabled) in items) {
            val def = cat[id]
            val defName = def?.name ?: ""
            val defShort = def?.shortName ?: ""
            val name = defName.ifBlank { defShort }.ifBlank { "Услуга" }
            val short = defShort.ifBlank { name }
            val global = def?.globalExtraRaw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject().apply {
                    put("id", id); put("name", name); put("short_name", short); put("price", price)
                    put("price_percent", 0); put("default", false); put("access", "operator,api")
                    put("allow_multiple", false); put("icon_name", ""); put("icon_color", "")
                }
            arr.put(JSONObject().apply {
                put("enabled", enabled); put("extraId", id); put("count", 1)
                put("price", price); put("pricePercent", 0); put("ratePrice", 0)
                put("shortName", short); put("name", name); put("globalExtra", global)
            })
        }
        send(Frame.command("SETEXTRAS", orderId, arr.toString()))
    }

    private fun parseOrderCh(params: List<String>): ActiveOrder? {
        // ORDCH;<id>;<json>
        val json = params.getOrNull(1) ?: return null
        return parseOrder(json)
    }

    companion object {
        private const val TAG = "ScatConnection"
        private val instanceCounter = java.util.concurrent.atomic.AtomicInteger(0)
        // Ссылка на ЖИВОЙ поток вывода на уровне синглтона: любой экземпляр, не нашедший
        // свой out, отправит через него. Это гарантирует доставку в реальный сокет,
        // даже если команда пришла из объекта-соединения, который уже не держит сокет.
        @Volatile var liveOut: OutputStream? = null
        @Volatile var liveSocket: Socket? = null
        val sharedLog = MutableStateFlow<List<String>>(emptyList())
        @Synchronized private fun appendLog(raw: String) {
            val line = if (raw.length > 160) raw.take(160) + "…" else raw   // режем гигантские справочники
            val cur = sharedLog.value
            sharedLog.value = if (cur.size >= 300) cur.drop(cur.size - 299) + line else cur + line
        }
        fun clearLog() { sharedLog.value = emptyList() }
        /** Полный дамп длинной строки в журнал (кусками по 150) — для диагностики. */
        fun logFull(tag: String, text: String?) {
            if (text.isNullOrBlank()) { appendLog("@@ $tag: <пусто>"); return }
            appendLog("@@ $tag (${text.length}):")
            text.chunked(150).forEachIndexed { i, c -> appendLog("@@$i $c") }
        }
    }
}
