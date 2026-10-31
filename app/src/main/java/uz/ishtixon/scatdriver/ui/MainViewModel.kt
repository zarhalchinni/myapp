package uz.ishtixon.scatdriver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import uz.ishtixon.scatdriver.model.*
import uz.ishtixon.scatdriver.net.Scat
import uz.ishtixon.scatdriver.net.ScatConnection

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    val state = MutableStateFlow(ScatConnection.State.DISCONNECTED)
    val profile = MutableStateFlow<Profile?>(null)
    val freeOrders = MutableStateFlow<List<FreeOrder>>(emptyList())
    val regions = MutableStateFlow<List<Region>>(emptyList())
    val freeCount = MutableStateFlow(0)
    val orderTimes = MutableStateFlow(listOf(3, 5, 7, 10, 15, 20))
    val tariffs = MutableStateFlow<List<uz.ishtixon.scatdriver.model.Rate>>(emptyList())
    private var selfOrderTemp: String? = null   // временный id самозаказа до ответа DORD
    val settings = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeOrder = MutableStateFlow<ActiveOrder?>(null)
    val offerSec = MutableStateFlow(0)          // отсчёт на входящем заказе
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val ioLog = MutableStateFlow<List<String>>(emptyList())
    val messages = MutableStateFlow<List<uz.ishtixon.scatdriver.model.ChatMsg>>(emptyList())
    val smsTemplates = MutableStateFlow<List<String>>(emptyList())
    val preorders = MutableStateFlow<List<uz.ishtixon.scatdriver.model.Preorder>>(emptyList())
    val preorderCount = MutableStateFlow(0)
    val preorderDetail = MutableStateFlow<uz.ishtixon.scatdriver.model.PreorderDetail?>(null)
    val attachedOrders = MutableStateFlow<List<ActiveOrder>>(emptyList())   // взятые предзаказы
    val extrasCatalog = MutableStateFlow<Map<Int, ExtraDef>>(emptyMap())    // справочник доп. услуг
    val completedOrder = MutableStateFlow<CompletedInfo?>(null)             // итог завершённого заказа (окно счёта)
    val bannedApp = MutableStateFlow<String?>(null)   // найдено запрещённое приложение (блокировка входа)
    /** Проверка установленных приложений по списку bannedapps из настроек сервера (как у штатного). */
    private fun checkBannedApps(settings: Map<String, String>) {
        val raw = settings["bannedapps"]?.trim().orEmpty()
        if (raw.isEmpty()) return
        // берём ТОЛЬКО точные имена пакетов (с точкой) — короткие слова вроде "clicker" игнорируем,
        // чтобы случайно не заблокировать обычные приложения водителя
        val banned = raw.split(",").map { it.trim().lowercase() }
            .filter { it.contains('.') && it.length >= 5 }.toHashSet()
        if (banned.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val app = getApplication<Application>()
            val mine = app.packageName.lowercase()
            val installed = runCatching {
                app.packageManager.getInstalledPackages(0).mapNotNull { it.packageName?.lowercase() }
            }.getOrDefault(emptyList())
            val hit = installed.firstOrNull { it != mine && it in banned }   // строго точное совпадение
            bannedApp.value = hit
        }
    }
    fun recheckBanned() { checkBannedApps(settings.value) }

    val darkTheme = MutableStateFlow(prefs.darkTheme)                       // тема оформления
    fun setDarkTheme(v: Boolean) { prefs.darkTheme = v; darkTheme.value = v }
    val lang = MutableStateFlow(prefs.lang)                                 // язык интерфейса (uz по умолчанию)
    fun setLang(v: String) { prefs.lang = v; lang.value = v; uz.ishtixon.scatdriver.ui.Lang.current = v }
    private var pendingComplete: CompletedInfo? = null
    fun clearCompleted() { completedOrder.value = null }
    private var pendingOpenId: String? = null
    fun loadPreorders() = Scat.conn?.pollPreorders()
    fun loadAttached() = Scat.conn?.requestAttached()
    fun clearPreorderDetail() { Scat.conn?.preorderDetail?.value = null; preorderDetail.value = null }

    /** Взять свободный предзаказ (RESORD) и открыть окно заказа, когда он попадёт в «назначенные». */
    fun takePreorder(id: String) {
        Scat.conn?.requestPreorder(id)   // RESORD = взять
        pendingOpenId = id
        viewModelScope.launch {
            repeat(6) { kotlinx.coroutines.delay(400); Scat.conn?.requestAttached() }
        }
    }

    /** Открыть взятый предзаказ как окно заказа (кнопки «На месте»/«Старт»/«Отказаться»). */
    fun openOrderWindow(o: ActiveOrder) { activeOrder.value = o }

    /** «Отказаться» от предзаказа — вернуть и закрыть окно. */
    fun releasePreorder(o: ActiveOrder) {
        Scat.conn?.rejectOrder(o.id)
        activeOrder.value = null
        Scat.conn?.requestAttached()
    }
    val chatPopups = MutableStateFlow<List<uz.ishtixon.scatdriver.model.ChatMsg>>(emptyList())  // макс 2
    private var lastMsgCount = 0
    fun dismissPopup(m: uz.ishtixon.scatdriver.model.ChatMsg) { chatPopups.value = chatPopups.value.filterNot { it === m } }
    fun clearPopups() { chatPopups.value = emptyList() }

    // системное уведомление (блок/разблок) — ОДИН баннер сверху, новый заменяет старый
    val notice = MutableStateFlow<uz.ishtixon.scatdriver.model.SystemNotice?>(null)
    val blocked = MutableStateFlow(false)       // заблокирован сейчас
    val blockReason = MutableStateFlow("")
    fun dismissNotice() { notice.value = null }
    private fun pushNotice(text: String, block: Boolean) {
        val n = uz.ishtixon.scatdriver.model.SystemNotice(text, block)
        notice.value = n
        viewModelScope.launch {
            kotlinx.coroutines.delay(6000)
            if (notice.value?.id == n.id) notice.value = null   // авто-скрытие, если не заменили/не закрыли
        }
    }
    fun clearLog() { ScatConnection.clearLog(); ioLog.value = emptyList() }

    // поля формы логина
    var host = prefs.mainHost
    var port = if (prefs.mainPort > 0) prefs.mainPort.toString() else "1050"
    var reserveHost = prefs.reserveHost
    var reservePort = if (prefs.reservePort > 0) prefs.reservePort.toString() else ""
    var login = prefs.login
    var password = prefs.password

    private var pollJob: kotlinx.coroutines.Job? = null

    init {
        // сессия сохраняется навсегда: если учётка есть — подключаемся сразу
        if (prefs.mainHost.isNotBlank() && prefs.login.isNotBlank()) connect()
    }

    val hasSession: Boolean get() = prefs.mainHost.isNotBlank() && prefs.login.isNotBlank()

    /** Полный выход: стереть сессию (иначе при старте снова подключится). */
    fun logout() {
        disconnect()
        prefs.login = ""; prefs.password = ""
        state.value = ScatConnection.State.DISCONNECTED
        profile.value = null; freeOrders.value = emptyList(); activeOrder.value = null
    }

    fun connect() {
        prefs.mainHost = host.trim()
        prefs.mainPort = port.toIntOrNull() ?: 1050
        prefs.reserveHost = reserveHost.trim()
        prefs.reservePort = reservePort.toIntOrNull() ?: 0
        prefs.login = login.trim()
        prefs.password = password

        val cfg = ScatConnection.Config(
            mainHost = prefs.mainHost,
            mainPort = prefs.mainPort,
            reserveHost = prefs.reserveHost.ifBlank { null },
            reservePort = prefs.reservePort,
            login = prefs.login,
            password = prefs.password,
            deviceUuid = prefs.deviceUuid
        )
        val conn = Scat.connect(cfg)
        bind(conn)
    }

    fun disconnect() { Scat.disconnect(); pollJob?.cancel() }

    // текущая локация водителя (для GPS и расчёта расстояния до заказа)
    val driverLoc = MutableStateFlow<Pair<Double, Double>?>(null)
    private var gpsFirst = true
    fun gpsNote(msg: String) { toast.tryEmit(msg) }
    val mockLocation = MutableStateFlow(false)   // обнаружено фиктивное местоположение
    val gpsEnabled = MutableStateFlow(true)      // включена ли геолокация на устройстве
    val gpsQuality = MutableStateFlow(0)         // 0 — нет (красный), 1 — слабый (жёлтый), 2 — хороший (зелёный)
    init {
        viewModelScope.launch {
            while (true) {
                val age = if (lastLocMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastLocMs
                gpsQuality.value = when {
                    !gpsEnabled.value -> 0   // геолокация выключена на устройстве — красный
                    age < 12000 -> 2         // свежий фикс — зелёный
                    else -> 1                // геолокация включена, фикс ещё ищем/устарел — жёлтый, не красный
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }
    fun onLocation(lat: Double, lon: Double, speed: Double = 0.0, bearing: Double = 0.0, isMock: Boolean = false) {
        if (isMock) {
            if (!mockLocation.value) toast.tryEmit(tr("Обнаружено фиктивное местоположение! Отключите mock-GPS.","Soxta joylashuv aniqlandi! Mock-GPS ni o'chiring."))
            mockLocation.value = true
            return   // фиктивную точку не используем и не шлём на сервер
        }
        mockLocation.value = false
        gpsFirst = false
        driverLoc.value = lat to lon
        Scat.conn?.sendGps(lat, lon, speed, bearing)
        // Скорость: иногда часть фиксов (сеть/потеря спутников) приходит с 0, хотя машина едет.
        // Берём максимум из GPS-скорости и скорости по координатам между точками.
        val nowMs = System.currentTimeMillis()
        var effKmh = speed * 3.6
        run {
            val pl = lastLat; val po = lastLon; val pt = lastLocMs
            if (pl != null && po != null && pt > 0L) {
                val dtSec = (nowMs - pt) / 1000.0
                if (dtSec in 0.4..10.0) {
                    val seg = uz.ishtixon.scatdriver.model.haversineKm(pl, po, lat, lon)   // км
                    // сдвиг учитываем только если он заметный (>25 м) — иначе это дрожь GPS на месте
                    if (seg.isFinite() && seg >= 0.025) {
                        val moveKmh = seg / (dtSec / 3600.0)
                        if (moveKmh in 0.0..150.0) effKmh = maxOf(effKmh, moveKmh)
                    }
                }
            }
        }
        lastLocMs = nowMs
        meterSpeedKmh.value = effKmh
        // накопление пробега во время поездки
        if (meterActive && !paused.value) {
            val pl = lastLat; val po = lastLon
            if (pl != null && po != null) {
                val r = 6371.0
                val dLat = Math.toRadians(lat - pl); val dLon = Math.toRadians(lon - po)
                val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                        Math.cos(Math.toRadians(pl)) * Math.cos(Math.toRadians(lat)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2)
                val seg = r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                // пробег копим ТОЛЬКО когда машина реально едет (скорость >= порога перехода).
                // Стоя (0 км/ч) любой сдвиг координат — это дрожь GPS, её не считаем (был скачок 0.33км).
                val co = activeOrder.value?.rate?.changeoverSpeed ?: 5.0
                val moving = meterSpeedKmh.value >= co
                if (moving && seg.isFinite() && seg in 0.008..1.0) meterKm.value += seg
            }
        }
        lastLat = lat; lastLon = lon
    }

    val activating = MutableStateFlow(false)

    /** Вход по коду подключения SCAT: гасим код на txa.su -> доступы -> подключение. */
    /** Человеческое сообщение об ошибке входа без упоминания серверов/адресов. */
    private fun friendlyActivationError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.SocketTimeoutException, is java.io.IOException ->
            "Нет связи с сервером. Проверьте интернет и попробуйте снова."
        else -> "Неверный или просроченный код."
    }

    fun activate(code: String) {
        if (code.isBlank()) { toast.tryEmit(tr("Пустой код","Kod bo'sh")); return }
        activating.value = true
        viewModelScope.launch {
            val res = uz.ishtixon.scatdriver.net.Activation.activate(code)
            activating.value = false
            res.onSuccess { cr ->
                host = cr.host; port = cr.port.toString()
                reserveHost = cr.reserveHost; reservePort = if (cr.reservePort > 0) cr.reservePort.toString() else ""
                login = cr.login; password = cr.password
                connect()   // connect() сам сохранит всё в Prefs -> постоянная сессия
            }.onFailure { toast.tryEmit(friendlyActivationError(it)) }
        }
    }

    private fun bind(conn: ScatConnection) {
        viewModelScope.launch { conn.state.collect { state.value = it } }
        viewModelScope.launch { conn.profile.collect { profile.value = it } }
        viewModelScope.launch { conn.freeOrders.collect { freeOrders.value = it } }
        viewModelScope.launch { conn.regions.collect { regions.value = it } }
        viewModelScope.launch { conn.ioLog.collect { ioLog.value = it } }
        // надёжное открытие окна заказа: StateFlow догонит, даже если событие пришло до подписки
        viewModelScope.launch {
            conn.liveOrder.collect { o ->
                if (o != null && activeOrder.value == null) {
                    activeOrder.value = o
                    when {
                        prefs.sOrderId == o.id -> restoreOrder(o)           // вернулись в активный заказ
                        o.status <= OrderStatus.OFFERED -> startOfferCountdown()
                    }
                } else if (o == null && activeOrder.value != null) {
                    // сервер закрыл заказ (завершён/снят) — чистим, если локально уже не в процессе завершения
                    if (!meterActive) activeOrder.value = null
                }
            }
        }
        // как только заказ принят (статус продвинулся) или снят — убираем полноэкранное уведомление
        viewModelScope.launch {
            activeOrder.collect { o ->
                if (o == null || o.status > OrderStatus.OFFERED)
                    uz.ishtixon.scatdriver.net.OrderAlert.cancel(getApplication<Application>())
            }
        }
        viewModelScope.launch { conn.freeCount.collect { freeCount.value = it } }
        viewModelScope.launch { conn.orderTimes.collect { orderTimes.value = it } }
        viewModelScope.launch { conn.tariffs.collect { tariffs.value = it } }
        viewModelScope.launch {
            conn.messages.collect { list ->
                messages.value = list
                if (list.size > lastMsgCount) {
                    val added = list.drop(lastMsgCount).filter { it.incoming }
                    if (added.isNotEmpty()) chatPopups.value = (chatPopups.value + added).takeLast(2)  // 3-е вытесняет 1-е
                }
                lastMsgCount = list.size
            }
        }
        viewModelScope.launch { conn.smsTemplates.collect { smsTemplates.value = it } }
        viewModelScope.launch { conn.preorders.collect { preorders.value = it } }
        viewModelScope.launch { conn.preorderCount.collect { preorderCount.value = it } }
        viewModelScope.launch { conn.preorderDetail.collect { preorderDetail.value = it } }
        viewModelScope.launch {
            conn.attachedOrders.collect { list ->
                attachedOrders.value = list
                // авто-открытие окна заказа после взятия свободного предзаказа
                pendingOpenId?.let { pid ->
                    list.firstOrNull { it.id == pid }?.let { o ->
                        pendingOpenId = null
                        activeOrder.value = o
                    }
                }
            }
        }
        // блокировка как у штатного: локально сверяем установленные приложения с bannedapps,
        // строго по ПОЛНОМУ имени пакета (короткие слова вроде "autoclicker" не блокируют)
        viewModelScope.launch { conn.settings.collect { settings.value = it; checkBannedApps(it) } }
        viewModelScope.launch { conn.extrasCatalog.collect { extrasCatalog.value = it } }
        viewModelScope.launch {
            conn.events.collect { ev ->
                when (ev) {
                    is ScatConnection.Event.Error -> toast.tryEmit("Ошибка: ${ev.code} ${ev.text}")
                    is ScatConnection.Event.Info -> toast.tryEmit(ev.msg)
                    is ScatConnection.Event.OrderOffered -> {
                        rememberDriverRate(ev.order)
                        activeOrder.value = ev.order
                        if (ev.order.status <= OrderStatus.OFFERED) startOfferCountdown()
                    }
                    is ScatConnection.Event.OrderChanged -> {
                        rememberDriverRate(ev.order)
                        val prev = activeOrder.value
                        // не откатываем идущую поездку назад, если сервер прислал более ранний статус
                        activeOrder.value =
                            if ((meterActive || prev?.status == OrderStatus.EXECUTING) && ev.order.id == prev?.id)
                                ev.order.copy(status = OrderStatus.EXECUTING)
                            else ev.order
                        // Восстановление после перезапуска: заказ пришёл с сервера, локального
                        // состояния не было, но есть снимок таксометра/таймера -> поднимаем его.
                        if (prev == null && prefs.sOrderId == ev.order.id) {
                            restoreOrder(ev.order)
                        } else {
                            when (ev.order.status) {
                                OrderStatus.CONFIRMED ->
                                    if (prev?.status != OrderStatus.CONFIRMED) startPhaseCountdown(chosenMinutes * 60)
                                OrderStatus.ARRIVED ->
                                    if (prev?.status != OrderStatus.ARRIVED)
                                        startPhaseCountdown((ev.order.rate?.freeWaitingTime ?: 3) * 60)
                            }
                        }
                    }
                    is ScatConnection.Event.Confirmed -> {
                        offerJob?.cancel()
                        // сервер подтвердил приём -> переводим экран в "Подтвержден" + таймер подачи
                        activeOrder.value?.let {
                            if (it.status <= OrderStatus.OFFERED) {
                                activeOrder.value = it.copy(status = OrderStatus.CONFIRMED)
                                startPhaseCountdown(chosenMinutes * 60)
                            }
                        }
                        toast.tryEmit(tr("Заказ подтверждён","Buyurtma tasdiqlandi"))
                    }
                    is ScatConnection.Event.Blocked -> {
                        blocked.value = true
                        blockReason.value = ev.reason
                        val head = if (ev.byAdmin) tr("Заблокировано администратором", "Administrator bloklagan")
                                   else tr("Заблокировано", "Bloklangan")
                        val msg = if (ev.reason.isNotBlank()) "$head: ${ev.reason}" else head
                        pushNotice(msg, block = true)
                    }
                    is ScatConnection.Event.Unblocked -> {
                        blocked.value = false
                        blockReason.value = ""
                        pushNotice(
                            if (ev.byPayment) tr("Баланс пополнен — разблокировано", "Balans to'ldirildi — blok ochildi")
                            else tr("Аккаунт разблокирован", "Hisob blokdan chiqarildi"),
                            block = false
                        )
                    }
                    is ScatConnection.Event.SelfOrderCreated -> {
                        // сервер выдал реальный id самозаказа — подменяем временный
                        val cur = activeOrder.value
                        if (cur != null && cur.id == selfOrderTemp) {
                            activeOrder.value = cur.copy(id = ev.id)
                            selfOrderTemp = null
                            saveSnapshot(activeOrder.value!!)
                        }
                    }
                    is ScatConnection.Event.CompleteOk -> {
                        activeOrder.value = null
                        prefs.clearOrderSnapshot()
                        uz.ishtixon.scatdriver.net.TripBubble.active.value = false
                        // финальное окно со счётом (вместо простого тоста)
                        completedOrder.value = (pendingComplete?.takeIf { it.id == ev.id })
                            ?: CompletedInfo(ev.id, meterAmount.value, meterAmount.value, 0.0, meterKm.value, meterSec.value)
                        pendingComplete = null
                        // счётчик «Мои заказы» должен обновиться сразу: убираем завершённый из списка и перезапрашиваем
                        attachedOrders.value = attachedOrders.value.filter { it.id != ev.id }
                        conn.getAttached()
                    }
                    is ScatConnection.Event.OrderStatusSet -> {
                        val cur = activeOrder.value
                        if (cur != null && cur.id == ev.id) {
                            when {
                                ev.status >= 90 -> {   // 95/96/100 — заказ снят/возвращён сервером
                                    // входящий ещё не принятый заказ исчез, и это не наш отказ → его взял другой водитель
                                    when {
                                        ev.id == rejectedId -> rejectedId = null   // наш отказ — молча
                                        cur.status == OrderStatus.OFFERED ->
                                            pushNotice(tr("Заказ взят другим водителем","Buyurtmani boshqa haydovchi oldi"), block = false)
                                        else -> toast.tryEmit(tr(tr("Заказ отменён диспетчером","Buyurtma dispetcher tomonidan bekor qilindi"),"Buyurtma dispetcher tomonidan bekor qilindi"))
                                    }
                                    meterActive = false; meterJob?.cancel(); phaseJob?.cancel()
                                    activeOrder.value = null; prefs.clearOrderSnapshot()
                                    uz.ishtixon.scatdriver.net.TripBubble.active.value = false
                                }
                                // прибыл: подтягиваем экран, если ещё до прибытия (подтверждён/принят)
                                ev.status == OrderStatus.ARRIVED &&
                                    cur.status in listOf(OrderStatus.CONFIRMED, OrderStatus.ACCEPTED) ->
                                    activeOrder.value = cur.copy(status = OrderStatus.ARRIVED)
                                // в пути: если ещё не на таксометре
                                ev.status == OrderStatus.EXECUTING && cur.status != OrderStatus.EXECUTING ->
                                    activeOrder.value = cur.copy(status = OrderStatus.EXECUTING)
                            }
                        }
                    }
                }
            }
        }
        // опрос свободных заказов, пока залогинены
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            conn.state.collectLatest { st ->
                if (st == ScatConnection.State.LOGGED_IN) {
                    while (true) { conn.pollFreeOrders(); kotlinx.coroutines.delay(5000) }
                }
            }
        }
    }

    // ---- таксометр / состояние заказа ----
    val meterAmount = MutableStateFlow(0.0)
    val meterKm = MutableStateFlow(0.0)
    val meterSec = MutableStateFlow(0)
    val meterStandSec = MutableStateFlow(0)
    val meterSpeedKmh = MutableStateFlow(0.0)
    val standing = MutableStateFlow(false)      // «стоянка» с защитой от дрожания GPS (устойчиво низкая скорость)
    val manualStand = MutableStateFlow(false)   // ручная стоянка (если у тарифа нет автостоянки)
    fun toggleStand() { manualStand.value = !manualStand.value }
    private var lowSpeedSec = 0                  // сколько секунд подряд скорость ниже порога
    private var lastLocMs = 0L                   // время прошлой точки (для расчёта скорости по координатам)
    val paused = MutableStateFlow(false)
    val phaseSec = MutableStateFlow(0)          // обратный отсчёт текущей фазы (подача/ожидание)
    private var chosenMinutes = 5
    private var meterJob: kotlinx.coroutines.Job? = null
    private var phaseJob: kotlinx.coroutines.Job? = null
    private var offerJob: kotlinx.coroutines.Job? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var meterActive = false

    // действия с заказом
    fun pollFreeNow() { Scat.conn?.pollFreeOrders() }   // NFORDS;1 сразу (вход в «Районы»)

    /** Отправить выбор доп. услуг для заказа: список (extraId, цена, включена?). */
    fun setOrderExtras(orderId: String, items: List<Triple<Int, Double, Boolean>>) =
        Scat.conn?.sendExtras(orderId, items)
    fun openFree(id: String) = Scat.conn?.requestOrderDetails(id)  // FORD -> ORDJSON (окно входящего)
    fun accept(id: String) = Scat.conn?.acceptOrder(id)
    fun acceptWithTime(id: String, minutes: Int) {
        chosenMinutes = minutes
        offerJob?.cancel()
        Scat.conn?.acceptWithTime(id, minutes)   // RCV + CFRM;id;minutes
        // экран переводим ТОЛЬКО по ответу сервера (CFRM/ORDCH) — честно отражаем приём
    }
    fun closeOrder() { activeOrder.value = null; Scat.conn?.clearLiveOrder() }

    /** Перед выходом из приложения: сохранить состояние идущей поездки (заказ остаётся за водителем на сервере). */
    fun prepareExit() {
        activeOrder.value?.let { if (meterActive || it.status == OrderStatus.EXECUTING) saveSnapshot(it) }
        pollJob?.cancel()
    }

    private fun startOfferCountdown() {
        offerJob?.cancel()
        val t = settings.value["timeselect_timeout"]?.toIntOrNull() ?: 12
        offerSec.value = t
        // входящий заказ, когда наше окно не на экране
        if (!uz.ishtixon.scatdriver.net.AppState.resumed) {
            val app = getApplication<Application>()
            val o = activeOrder.value
            val addr = o?.fromStreet?.takeIf { it.isNotBlank() } ?: o?.fromName.orEmpty()
            // если выдано «поверх других окон» — рисуем окно заказа поверх всего (надёжно на MIUI)
            if (android.provider.Settings.canDrawOverlays(app))
                uz.ishtixon.scatdriver.net.OrderOverlayService.start(app)
            // уведомление в любом случае (шторка/блокировка/heads-up)
            uz.ishtixon.scatdriver.net.OrderAlert.show(
                app,
                uz.ishtixon.scatdriver.ui.tr("Поступил заказ", "Buyurtma keldi"),
                addr.ifBlank { uz.ishtixon.scatdriver.ui.tr("Входящий заказ", "Kiruvchi buyurtma") },
                o?.id
            )
        }
        offerJob = viewModelScope.launch {
            while (offerSec.value > 0) { kotlinx.coroutines.delay(1000); offerSec.value -= 1 }
            // время вышло — убрать уведомление
            uz.ishtixon.scatdriver.net.OrderAlert.cancel(getApplication<Application>())
        }
    }

    /** Снять полноэкранное уведомление о входящем заказе. */
    fun cancelOrderAlert() = uz.ishtixon.scatdriver.net.OrderAlert.cancel(getApplication<Application>())

    /** «На месте» -> ONPLACE (сервер переведёт в статус 11), запустить отсчёт ожидания. */
    fun arrive(o: ActiveOrder) {
        Scat.conn?.onPlace(o.id)
        activeOrder.value = o.copy(status = OrderStatus.ARRIVED)   // экран сразу на «Ожидание/Старт»
        startPhaseCountdown((o.rate?.freeWaitingTime ?: 3) * 60)
    }

    /** «Старт» -> ORDST;id;4;lat;lon + запуск таксометра и трека. */
    fun startTrip(o: ActiveOrder) {
        Scat.conn?.setOrderStatus(o.id, OrderStatus.EXECUTING)
        activeOrder.value = o.copy(status = OrderStatus.EXECUTING)   // переводим экран на таксометр
        phaseJob?.cancel(); prefs.sPhaseDeadline = 0
        meterKm.value = 0.0; meterSec.value = 0; meterStandSec.value = 0; meterAmount.value = 0.0
        lastLat = null; lastLon = null                 // сброс базовой точки — без скачка на первом фиксе
        meterActive = true; paused.value = false
        uz.ishtixon.scatdriver.net.TripBubble.fare.value = 0
        uz.ishtixon.scatdriver.net.TripBubble.active.value = true
        Scat.conn?.sendTrack(o.id, o.rate?.id?.toString() ?: "0", 0.0, 0, 0)
        runMeterLoop(o)
        saveSnapshot(o)
    }

    /** Возобновить таксометр после перезапуска приложения (значения уже восстановлены). */
    private fun resumeMeter(o: ActiveOrder) {
        lastLat = null; lastLon = null                 // новая точка после перезапуска — не считаем разрыв
        meterActive = true; paused.value = false
        uz.ishtixon.scatdriver.net.TripBubble.fare.value = meterAmount.value.toInt()
        uz.ishtixon.scatdriver.net.TripBubble.active.value = true
        runMeterLoop(o)
    }

    private fun runMeterLoop(o: ActiveOrder) {
        val rateId = o.rate?.id?.toString() ?: "0"
        lowSpeedSec = 0; standing.value = false; manualStand.value = false
        meterJob?.cancel()
        meterJob = viewModelScope.launch {
            while (meterActive) {
                kotlinx.coroutines.delay(1000)
                if (paused.value) { standing.value = false; lowSpeedSec = 0 }
                if (!paused.value) {
                    meterSec.value += 1
                    val co = o.rate?.changeoverSpeed ?: 5.0
                    // автостоянка: включается только при устойчиво низкой скорости (≥4 с подряд),
                    // чтобы одиночный провал скорости до 0 на скорости 60 не мигал «Ожидание…».
                    if (meterSpeedKmh.value < co) lowSpeedSec++ else lowSpeedSec = 0
                    val isStanding =
                        if (o.rate?.autoStand == true) lowSpeedSec >= 4 else manualStand.value
                    standing.value = isStanding
                    if (isStanding) meterStandSec.value += 1
                    recompute(o)
                    val id = activeOrder.value?.id ?: o.id   // после ответа DORD — реальный id
                    if (meterSec.value % 30 == 0)
                        Scat.conn?.sendTrack(id, rateId, meterAmount.value, meterStandSec.value, meterSec.value)
                    if (meterSec.value % 10 == 0) activeOrder.value?.let { saveSnapshot(it) }
                }
            }
        }
    }

    fun pause() { paused.value = true }
    fun resume() { paused.value = false }

    /** «Стоп» -> CMPLT;id;amount;0;stand;0;total;{counters} + ORDERCHECK + UNBINDORD. */
    fun stopTrip(o: ActiveOrder) {
        meterActive = false; meterJob?.cancel(); prefs.clearOrderSnapshot()
        uz.ishtixon.scatdriver.net.TripBubble.active.value = false
        val rate = o.rate
        val km = meterKm.value
        val stand = meterStandSec.value
        // запоминаем итог для финального окна (покажем после подтверждения сервером)
        pendingComplete = CompletedInfo(
            id = o.id, total = meterAmount.value + o.extrasTotal,
            trip = meterAmount.value, extras = o.extrasTotal, km = km, sec = meterSec.value
        )
        Scat.conn?.apply {
            completeOrder(
                id = o.id, rateId = rate?.id?.toString() ?: "0",
                amount = meterAmount.value,
                distMeters = (km * 1000).toInt(),
                distAmount = rate?.distanceFare(km) ?: 0.0,
                standSec = stand,
                standAmount = rate?.standFare(stand) ?: 0.0,
                totalSec = meterSec.value
            )
            orderCheck(o.id)
            unbindOrder(o.id)
        }
    }

    fun refreshBalance() { Scat.conn?.requestBalance(); toast.tryEmit(tr("Обновляю баланс…","Balans yangilanmoqda…")) }

    /** Запоминаем тариф водителя (назначенный с панели) — он приходит в каждом его заказе. */
    private fun rememberDriverRate(o: ActiveOrder) {
        o.rate?.id?.takeIf { it > 0 }?.let { if (prefs.selfRateId != it) prefs.selfRateId = it }
    }

    /**
     * Тариф(ы) для самозаказа — СТРОГО назначенный водителю (поле[9]=0 в RTS).
     * Если такого нет — таксометр этому водителю недоступен (как в штатном: он берёт
     * заказы из предзаказов). НИКАКОГО отката на «все тарифы».
     */
    fun driverTariffs(): List<uz.ishtixon.scatdriver.model.Rate> =
        tariffs.value.filter { it.assigned }

    /** true — таксометр доступен (есть назначенный тариф). */
    fun taximeterAvailable(): Boolean = tariffs.value.any { it.assigned }
    /** true — список тарифов ещё не пришёл от сервера. */
    fun tariffsLoaded(): Boolean = tariffs.value.isNotEmpty()


    /** Самозаказ (standalone-таксометр): DORD -> сразу счётчик с временным id, статус «в исполнении». */
    fun startSelfOrder(rate: uz.ishtixon.scatdriver.model.Rate) {
        prefs.selfRateId = rate.id   // запоминаем выбранный тариф -> дальше показываем только его
        val temp = "self-${System.currentTimeMillis()}"
        selfOrderTemp = temp
        val o = ActiveOrder(
            id = temp, status = OrderStatus.EXECUTING,
            fromName = "Таксометр", service = "Самозаказ",
            rateName = rate.name, rate = rate
        )
        activeOrder.value = o
        meterKm.value = 0.0; meterSec.value = 0; meterStandSec.value = 0; meterAmount.value = 0.0
        lastLat = null; lastLon = null
        meterActive = true; paused.value = false
        uz.ishtixon.scatdriver.net.TripBubble.fare.value = 0
        uz.ishtixon.scatdriver.net.TripBubble.active.value = true
        Scat.conn?.selfOrder(rate.id)   // DORD;{tariff id} -> сервер вернёт DORD;<реальный id>
        runMeterLoop(o)
        saveSnapshot(o)
    }

    /** URL веб-кабинета сервера (как у штатного): http://<host>[:webport]/cabinet/?username&password. */
    fun cabinetUrl(): String? {
        val host = prefs.mainHost
        if (host.isBlank()) return null
        val ssl = settings.value["web_use_ssl"] == "1"
        val scheme = if (ssl) "https" else "http"
        val port = settings.value["webport"]?.toIntOrNull() ?: 80
        val portPart = if ((!ssl && port == 80) || (ssl && port == 443)) "" else ":$port"
        val u = java.net.URLEncoder.encode(prefs.login, "UTF-8")
        val p = java.net.URLEncoder.encode(prefs.password, "UTF-8")
        return "$scheme://$host$portPart/cabinet/?username=$u&password=$p&lang=system"
    }
    private var rejectedId: String? = null   // чтобы не показывать «взят другим», когда отказались сами
    fun reject(id: String) { rejectedId = id; Scat.conn?.rejectOrder(id); Scat.conn?.clearLiveOrder() }
    fun sms(text: String) = Scat.conn?.sendSms(0, text)

    private fun recompute(o: ActiveOrder) {
        meterAmount.value = o.rate?.total(meterKm.value, meterStandSec.value) ?: 0.0
        uz.ishtixon.scatdriver.net.TripBubble.fare.value = meterAmount.value.toInt()
    }

    private fun startPhaseCountdown(seconds: Int) {
        phaseJob?.cancel()
        phaseSec.value = seconds
        prefs.sPhaseDeadline = System.currentTimeMillis() + seconds * 1000L
        activeOrder.value?.let { saveSnapshot(it) }
        phaseJob = viewModelScope.launch {
            while (phaseSec.value > 0) { kotlinx.coroutines.delay(1000); phaseSec.value -= 1 }
        }
    }

    /** Сохранить снимок активного заказа/таксометра (переживает перезапуск). */
    private fun saveSnapshot(o: ActiveOrder) {
        prefs.sOrderId = o.id
        prefs.sStatus = o.status
        prefs.sKm = meterKm.value.toFloat()
        prefs.sSec = meterSec.value
        prefs.sStandSec = meterStandSec.value
        prefs.sAmount = meterAmount.value.toFloat()
        prefs.sMeterActive = meterActive
    }

    /** Восстановить таксометр/таймер, когда сервер вернул активный заказ после перезапуска. */
    private fun restoreOrder(o: ActiveOrder) {
        if (prefs.sOrderId != o.id) return          // снимок не от этого заказа
        meterKm.value = prefs.sKm.toDouble()
        meterSec.value = prefs.sSec
        meterStandSec.value = prefs.sStandSec
        meterAmount.value = prefs.sAmount.toDouble()
        // КРИТИЧНО: если поездка уже была запущена (нажат «Старт»), восстанавливаем таксометр
        // НЕЗАВИСИМО от того, какой статус сейчас вернул сервер. Иначе заказ «откатывается»
        // на «На месте» и водителю приходится стартовать заново.
        if (prefs.sMeterActive || prefs.sStatus == OrderStatus.EXECUTING) {
            val running = o.copy(status = OrderStatus.EXECUTING)
            activeOrder.value = running
            Scat.conn?.setOrderStatus(o.id, OrderStatus.EXECUTING)   // пере-синхронизируем сервер
            resumeMeter(running)
            return
        }
        // ещё не стартовали — восстанавливаем обратный отсчёт фазы (подача/ожидание)
        when (o.status) {
            OrderStatus.ARRIVED, OrderStatus.CONFIRMED -> {
                val remain = ((prefs.sPhaseDeadline - System.currentTimeMillis()) / 1000L).toInt()
                if (remain > 0) { phaseSec.value = remain
                    phaseJob?.cancel()
                    phaseJob = viewModelScope.launch {
                        while (phaseSec.value > 0) { kotlinx.coroutines.delay(1000); phaseSec.value -= 1 }
                    }
                }
            }
        }
    }
}
