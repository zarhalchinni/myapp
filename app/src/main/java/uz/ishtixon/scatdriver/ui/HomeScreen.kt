@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package uz.ishtixon.scatdriver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uz.ishtixon.scatdriver.model.ActiveOrder
import uz.ishtixon.scatdriver.model.CompletedInfo
import uz.ishtixon.scatdriver.model.FreeOrder
import uz.ishtixon.scatdriver.model.OrderStatus
import uz.ishtixon.scatdriver.model.haversineKm
import uz.ishtixon.scatdriver.model.Region
import uz.ishtixon.scatdriver.net.ScatConnection

// Алиасы палитры (см. Theme.kt)
private val Green = Ui.On
private val Gray = Ui.Muted
private val Orange = Ui.Accent
private val Lime = Ui.Lime
private val PauseRed = Ui.PauseBg
private val RegionGreen = Color(0xFF9CCC3C)   // жёлто-зелёный: район со свободными заказами
private val BanRed = Color(0xFFB3261E)        // насыщенный красный (как верхняя плашка)
private val GpsYellow = Color(0xFFE6A700)     // жёлтый: слабый/устаревший GPS-фикс

private enum class Section(val title: String) {
    GRID("Главное меню"), DISTRICTS("Районы"), RESORDS("Предзаказы"),
    MESSAGES("Сообщения"), ADS("Объявления"), CABINET("Личный кабинет"),
    ADVANCED("Дополнительно"), SETTINGS("Настройки"), TAXOMETER("Таксометр"),
    LOG("Журнал"), EXIT("Выход"), MYORDERS("Мои заказы")
}

private data class Tile(val section: Section, val res: Int)

private val SkatBlue = Color(0xFF2F80ED)   // фирменный синий (для акцентов внутри экранов)

// сетка 3×3 как у штатного: готовые иконки-плитки из набора
private val tiles = listOf(
    Tile(Section.DISTRICTS, uz.ishtixon.scatdriver.R.drawable.ic_tile_districts),
    Tile(Section.TAXOMETER, uz.ishtixon.scatdriver.R.drawable.ic_tile_taximeter),
    Tile(Section.RESORDS, uz.ishtixon.scatdriver.R.drawable.ic_tile_preorders),
    Tile(Section.MESSAGES, uz.ishtixon.scatdriver.R.drawable.ic_tile_messages),
    Tile(Section.ADS, uz.ishtixon.scatdriver.R.drawable.ic_tile_ads),
    Tile(Section.CABINET, uz.ishtixon.scatdriver.R.drawable.ic_tile_profile),
    Tile(Section.ADVANCED, uz.ishtixon.scatdriver.R.drawable.ic_tile_additional),
    Tile(Section.SETTINGS, uz.ishtixon.scatdriver.R.drawable.ic_tile_settings),
    Tile(Section.EXIT, uz.ishtixon.scatdriver.R.drawable.ic_tile_exit),
)

/** Локализованное название раздела (ru/uz). */
private fun secLabel(s: Section): String = when (s) {
    Section.GRID -> tr("Главное меню", "Asosiy menyu")
    Section.DISTRICTS -> tr("Районы", "Tumanlar")
    Section.RESORDS -> tr("Предзаказы", "Oldindan buyurtmalar")
    Section.MESSAGES -> tr("Сообщения", "Xabarlar")
    Section.ADS -> tr("Объявления", "E'lonlar")
    Section.CABINET -> tr("Личный кабинет", "Shaxsiy kabinet")
    Section.ADVANCED -> tr("Дополнительно", "Qo'shimcha")
    Section.SETTINGS -> tr("Настройки", "Sozlamalar")
    Section.TAXOMETER -> tr("Таксометр", "Taksometr")
    Section.LOG -> tr("Журнал", "Jurnal")
    Section.EXIT -> tr("Выход", "Chiqish")
    Section.MYORDERS -> tr("Мои заказы", "Mening buyurtmalarim")
}

@Composable
fun HomeScreen(vm: MainViewModel, onExit: () -> Unit = {}) {
    val profile by vm.profile.collectAsState()
    val free by vm.freeOrders.collectAsState()
    val freeCount by vm.freeCount.collectAsState()
    val active by vm.activeOrder.collectAsState()
    val attached by vm.attachedOrders.collectAsState()
    val regions by vm.regions.collectAsState()
    val driver by vm.driverLoc.collectAsState()
    val gpsQuality by vm.gpsQuality.collectAsState()
    val orderTimes by vm.orderTimes.collectAsState()
    val state by vm.state.collectAsState()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(Section.GRID) }
    var showTariffs by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var askLogPass by remember { mutableStateOf(false) }   // пароль на журнал обмена
    var myTab by remember { mutableStateOf(0) }            // вкладка «Мои заказы» / «Мои предзаказы»
    // окно заказа можно свернуть кнопкой «назад» — заказ остаётся, внизу появляется полоса
    var orderShown by remember { mutableStateOf(true) }
    LaunchedEffect(active?.id) { if (active != null) orderShown = true }
    val showOrder = active != null && orderShown
    val online = state == ScatConnection.State.LOGGED_IN

    // мои заказы = реально принятые (не предложенные, не предзаказы) + текущий принятый
    val acceptedStatuses = listOf(
        OrderStatus.CONFIRMED, OrderStatus.ACCEPTED, OrderStatus.EXECUTING, OrderStatus.ARRIVED
    )
    val myOrders = remember(attached, active) {
        val base = attached.filter { it.status in acceptedStatuses }
        val a = active
        // входящий (OFFERED) заказ ещё не принят — в «Мои заказы» не считается
        if (a != null && a.status in acceptedStatuses && base.none { it.id == a.id }) listOf(a) + base else base
    }
    val myPre = remember(attached) { attached.filter { it.status == OrderStatus.RESERVED } }

    // «назад»: меню -> раздел (на главную/в окно заказа) -> закрыть окно предзаказа -> свернуть заказ в полосу
    val reservedOpen = showOrder && active?.status == OrderStatus.RESERVED
    BackHandler(enabled = drawer.isOpen || section != Section.GRID || showOrder) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            section != Section.GRID -> section = Section.GRID
            reservedOpen -> vm.closeOrder()   // предзаказ остаётся во «взятых», просто закрываем окно
            else -> orderShown = false
        }
    }

    // заголовок раздела под верхней панелью (как «Сообщения» в штатном клиенте)
    val sectionTitle: String? = when {
        section == Section.MYORDERS -> null    // у «Мои заказы» вместо заголовка — вкладки
        section == Section.LOG || section == Section.MESSAGES || section == Section.CABINET -> secLabel(section)
        showOrder || section == Section.GRID -> null
        else -> secLabel(section)
    }
    // окно заказа реально видно на экране?
    val orderVisible = showOrder && section != Section.LOG && section != Section.MESSAGES &&
        section != Section.CABINET && section != Section.MYORDERS

    ModalNavigationDrawer(
        drawerState = drawer,
        // открывать меню только кнопкой-гамбургером; свайпом можно лишь закрыть уже открытое
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Ui.Bg, drawerTonalElevation = 0.dp) {
                DrawerHeader(
                    code = profile?.code ?: "",
                    name = profile?.name ?: tr("Водитель","Haydovchi"),
                    city = profile?.cityName ?: "",
                    gps = driver
                )
                Spacer(Modifier.height(8.dp))
                DrawerItem(tr("Главное меню","Asosiy menyu"), Icons.Filled.Home, section == Section.GRID) {
                    section = Section.GRID; scope.launch { drawer.close() }
                }
                DrawerItem(tr("Мои заказы","Mening buyurtmalarim"), Icons.Filled.ListAlt, section == Section.MYORDERS) {
                    myTab = 0; section = Section.MYORDERS; scope.launch { drawer.close() }
                }
                DrawerItem(tr("Сообщения","Xabarlar"), Icons.Filled.ChatBubble, section == Section.MESSAGES) {
                    section = Section.MESSAGES; scope.launch { drawer.close() }
                }
                DrawerItem(tr("Настройки","Sozlamalar"), Icons.Filled.Settings, section == Section.SETTINGS) {
                    section = Section.SETTINGS; scope.launch { drawer.close() }
                }
                DrawerItem(tr("Выход","Chiqish"), Icons.Filled.PowerSettingsNew, false) {
                    scope.launch { drawer.close() }; showExit = true
                }
                Spacer(Modifier.weight(1f))
                // «Сообщить об ошибке» — открывает журнал обмена (его можно скопировать и отправить)
                FilledTonalButton(
                    onClick = { scope.launch { drawer.close() }; askLogPass = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Ui.SurfaceHi, contentColor = Ui.OnSurface),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(Icons.Filled.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Сообщить об ошибке","Xatolik haqida xabar"))
                }
                Spacer(Modifier.height(12.dp))
                Divider(color = Ui.Outline)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(uz.ishtixon.scatdriver.R.drawable.scat_logo),
                        contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("SKAT", color = Ui.OnSurface, modifier = Modifier.weight(1f))
                    Text(uz.ishtixon.scatdriver.BuildConfig.VERSION_NAME, color = Gray)
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Ui.Bg,
            topBar = {
                Column {
                    TopBar(
                        online = online,
                        gpsQuality = gpsQuality,
                        activeCount = if (active != null) 1 else 0,
                        freeCount = freeCount,
                        balance = profile?.balance ?: 0.0,
                        onMenu = { scope.launch { drawer.open() } }
                    )
                    if (sectionTitle != null) SectionTitle(sectionTitle)
                }
            },
            bottomBar = {
                Column {
                    // полоса активного заказа (когда окно заказа свёрнуто): тап возвращает в заказ
                    val a = active
                    if (a != null && !orderVisible)
                        OrderStrip(a) { orderShown = true; section = Section.GRID }
                    BottomBar(
                        orders = myOrders.size, preorders = myPre.size,
                        onOrders = { myTab = 0; section = Section.MYORDERS },
                        onPreorders = { myTab = 1; section = Section.MYORDERS }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val mock by vm.mockLocation.collectAsState()
                when {
                    section == Section.LOG -> LogView(vm)
                    // чат/кабинет доступны всегда, даже во время заказа (возврат — «назад»)
                    section == Section.MESSAGES -> ChatView(vm)
                    section == Section.CABINET -> CabinetView(vm)
                    section == Section.MYORDERS -> MyOrdersView(
                        orders = myOrders, preorders = myPre, tab = myTab, onTab = { myTab = it }, vm = vm,
                        onOpen = { o ->
                            if (active?.id != o.id) vm.openOrderWindow(o)
                            orderShown = true; section = Section.GRID
                        })
                    showOrder -> OrderView(active!!, orderTimes, vm, onMessages = { section = Section.MESSAGES })
                    section == Section.GRID -> MenuGrid { picked ->
                        if (picked == Section.EXIT) {
                            showExit = true
                        } else if (picked == Section.TAXOMETER) {
                            when {
                                !vm.tariffsLoaded() -> vm.gpsNote(tr("Тарифы ещё загружаются, подождите…","Tariflar yuklanmoqda, kuting…"))
                                !vm.taximeterAvailable() ->
                                    vm.gpsNote(tr("Таксометр недоступен для вашего аккаунта. Заказы берите в «Предзаказы».","Taksometr hisobingiz uchun mavjud emas. Buyurtmalarni «Oldindan buyurtmalar»dan oling."))
                                else -> { showTariffs = true }
                            }
                        } else {
                            section = picked
                        }
                    }
                    section == Section.DISTRICTS -> DistrictsView(regions, free, driver, vm)
                    section == Section.RESORDS -> PreordersView(vm)
                    section == Section.SETTINGS -> SettingsView(vm)
                    section == Section.ADS -> AdsView()
                    section == Section.ADVANCED -> AdvancedView(vm)
                    else -> Placeholder(secLabel(section))
                }
                if (mock) {
                    Surface(color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                        Text(tr("⚠ Обнаружено фиктивное местоположение — отключите mock-GPS","⚠ Soxta joylashuv aniqlandi — mock-GPS ni o'chiring"),
                            Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onError,
                            textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    }
                }

                val gpsOn by vm.gpsEnabled.collectAsState()
                if (!gpsOn) GpsOffGate()

                // баннеры сверху: системный (блок/разблок, один) + новые сообщения
                val popups by vm.chatPopups.collectAsState()
                val notice by vm.notice.collectAsState()
                if (notice != null || popups.isNotEmpty()) {
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        notice?.let { NoticeBanner(it, onOk = { vm.dismissNotice() }) }
                        popups.forEach { m ->
                            ChatPopup(m,
                                onOk = { vm.dismissPopup(m) },
                                onOpen = { vm.clearPopups(); section = Section.MESSAGES })
                        }
                    }
                }

                // постоянная полоса, пока водитель заблокирован
                val isBlocked by vm.blocked.collectAsState()
                val reason by vm.blockReason.collectAsState()
                if (isBlocked) {
                    Surface(
                        color = BanRed, shape = RoundedCornerShape(16.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Block, null, tint = Color.White, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(tr("Заблокировано","Bloklangan"), color = Color.White, fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp)
                                if (reason.isNotBlank())
                                    Text(reason, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp,
                                        lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    val completed by vm.completedOrder.collectAsState()
    completed?.let { CompletedDialog(it) { vm.clearCompleted() } }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(tr("Выход","Chiqish")) },
            text = {
                Text(if (active != null) tr("Закрыть приложение? Текущий заказ останется за вами — он вернётся при следующем запуске.","Ilovani yopasizmi? Joriy buyurtma sizda qoladi — keyingi ishga tushirishda qaytadi.")
                     else tr("Закрыть приложение и уйти с линии?","Ilovani yopib, liniyadan chiqasizmi?"))
            },
            confirmButton = { TextButton(onClick = { showExit = false; vm.prepareExit(); onExit() }) { Text(tr("Выйти","Chiqish")) } },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }

    if (askLogPass) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLogPass = false },
            title = { Text(tr("Пароль","Parol")) },
            text = {
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text(tr("Введите пароль","Parolni kiriting")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pass == "smsapk12") { askLogPass = false; section = Section.LOG }
                    else { pass = ""; vm.gpsNote(tr("Неверный пароль","Parol noto'g'ri")) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { askLogPass = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }

    if (showTariffs) {
        val allTariffs by vm.tariffs.collectAsState()
        val tariffs = remember(allTariffs) { vm.driverTariffs() }
        AlertDialog(
            onDismissRequest = { showTariffs = false },
            title = { Text(tr("Тариф","Tarif")) },
            text = {
                if (tariffs.isEmpty()) Text(tr("Тариф ещё не получен","Tarif hali olinmagan"), color = Gray)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(tariffs) { rate ->
                        Text(rate.name, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showTariffs = false; vm.startSelfOrder(rate) }
                                .padding(vertical = 14.dp))
                        Divider(color = Ui.Outline)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTariffs = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }
}

@Composable
private fun BoxScope.GpsOffGate() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.matchParentSize().background(Ui.Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.LocationOff, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Text(tr("Геолокация выключена","Joylashuv o'chirilgan"), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(tr("Для работы включите GPS (местоположение). Без него заказы недоступны.","Ishlash uchun GPS (joylashuv) yoqing. Usiz buyurtmalar mavjud emas."),
                color = Gray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(52.dp)
            ) { Text(tr("Включить геолокацию","Joylashuvni yoqish"), fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ───────────────────────── панели ─────────────────────────

@Composable
private fun TopBar(
    online: Boolean, gpsQuality: Int, activeCount: Int, freeCount: Int,
    balance: Double, onMenu: () -> Unit
) {
    Surface(color = Ui.Surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(50.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, tr("меню","menyu"), tint = Ui.OnSurface, modifier = Modifier.size(24.dp))
            }
            StatusChip(if (online) Icons.Filled.Wifi else Icons.Filled.WifiOff, if (online) Ui.On else Ui.Off)
            Spacer(Modifier.width(8.dp))
            // GPS: зелёный — хороший фикс, жёлтый — слабый/устаревший, красный — нет
            val gpsColor = when (gpsQuality) { 2 -> Ui.On; 1 -> GpsYellow; else -> Ui.Danger }
            StatusChip(if (gpsQuality > 0) Icons.Filled.LocationOn else Icons.Filled.LocationOff, gpsColor)
            Spacer(Modifier.weight(1f))
            BalanceChip(balance)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, bg: Color) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(19.dp), tint = Color.White) }
}

@Composable
private fun BalanceChip(balance: Double) {
    val text = String.format(java.util.Locale("ru"), "%.2f", balance)
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Ui.SurfaceHi)
            .padding(start = 5.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Ui.Teal.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.AttachMoney, null, Modifier.size(15.dp), tint = Ui.Teal) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            color = if (balance < 0) Ui.Danger else Ui.OnSurface)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Surface(color = Ui.Surface.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
        Text(title, Modifier.padding(vertical = 9.dp), color = Gray,
            textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BottomBar(orders: Int, preorders: Int, onOrders: () -> Unit, onPreorders: () -> Unit) {
    Surface(color = Ui.Surface) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(42.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomCounter(Icons.Filled.FilterNone, orders, if (orders > 0) Ui.Accent else Gray, onOrders)
            BottomCounter(Icons.Filled.Event, preorders, if (preorders > 0) Ui.Accent else Gray, onPreorders)
        }
    }
}

@Composable
private fun BottomCounter(icon: ImageVector, n: Int, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(5.dp))
        Text("$n", color = if (n > 0) Ui.OnSurface else Gray, fontSize = 15.sp)
    }
}

private fun statusTitle(o: ActiveOrder): String = when (o.status) {
    OrderStatus.OFFERED -> tr("Новый заказ","Yangi buyurtma")
    OrderStatus.CONFIRMED, OrderStatus.ACCEPTED -> tr("Подтвержден","Tasdiqlangan")
    OrderStatus.ARRIVED -> tr("Ожидание клиента","Mijozni kutish")
    OrderStatus.EXECUTING -> tr("В исполнении","Bajarilmoqda")
    OrderStatus.RESERVED -> tr("Предварительный","Oldindan")
    else -> OrderStatus.title(o.status)
}

/** Время заказа «ЧЧ:ММ» из JSON (подтверждён, иначе создан). */
private fun orderTime(o: ActiveOrder): String = runCatching {
    val t = org.json.JSONObject(o.rawJson).getJSONObject("attributes").getJSONObject("time")
    val s = t.optString("confirmed").ifBlank { t.optString("registered") }
    if (s.length >= 16) s.substring(11, 16) else ""
}.getOrDefault("")

/** Полоса активного заказа над нижней панелью (окно заказа свёрнуто). */
@Composable
private fun OrderStrip(o: ActiveOrder, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color(0xFF7A5400),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(statusTitle(o), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(o.fromStreet.ifBlank { o.fromName.ifBlank { "—" } }, color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ───────────────────────── мои заказы / предзаказы ─────────────────────────

@Composable
private fun MyOrdersView(
    orders: List<ActiveOrder>, preorders: List<ActiveOrder>, tab: Int, onTab: (Int) -> Unit,
    vm: MainViewModel, onOpen: (ActiveOrder) -> Unit
) {
    // как штатный: при открытии запрашиваем GETATTACHED и обновляем, пока экран открыт
    LaunchedEffect(Unit) {
        while (true) { vm.loadAttached(); kotlinx.coroutines.delay(8000) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            listOf(tr("МОИ ЗАКАЗЫ","BUYURTMALARIM"), tr("МОИ ПРЕДЗАКАЗЫ","OLDINDAN BUYURTMALAR")).forEachIndexed { i, t ->
                val sel = tab == i
                Column(
                    Modifier.weight(1f).clickable { onTab(i) }.padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(t, color = if (sel) Ui.OnSurface else Gray, fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp)
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp)
                        .background(if (sel) Ui.Teal else Color.Transparent))
                }
            }
        }
        Divider(color = Ui.Outline)

        val list = if (tab == 0) orders else preorders
        if (list.isEmpty())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(if (tab == 0) tr("Нет заказов","Buyurtmalar yo'q") else tr("Нет предзаказов","Oldindan buyurtmalar yo'q"),
                    Modifier.align(Alignment.Center), color = Gray)
            }
        else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.id }) { o ->
                Surface(
                    onClick = { onOpen(o) },
                    color = Ui.SurfaceHi, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(statusTitle(o), color = Orange, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(if (o.status == OrderStatus.RESERVED) fmtReservation(o.reservation)
                                 else orderTime(o), color = Gray, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(o.fromStreet.ifBlank { o.fromName.ifBlank { "—" } }, color = Ui.OnSurface,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        if (o.fromStreet.isNotBlank() && o.fromName.isNotBlank())
                            Text(o.fromName, color = Gray, fontSize = 15.sp)
                        if (o.service.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(o.service, color = Ui.OnSurface.copy(alpha = 0.85f), fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── районы ─────────────────────────

@Composable
private fun DistrictsView(
    regions: List<Region>, free: List<FreeOrder>, driver: Pair<Double, Double>?, vm: MainViewModel
) {
    var picked by remember { mutableStateOf<Region?>(null) }
    // как штатный клиент: при входе в «Районы» и в район — сразу NFORDS;1, затем обновляем, пока экран открыт
    val freeCnt by vm.freeCount.collectAsState()
    LaunchedEffect(picked) { vm.pollFreeNow() }
    LaunchedEffect(freeCnt) { vm.pollFreeNow() }          // сервер прислал F;… (число заказов изменилось) — обновить список
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10000); vm.pollFreeNow() } }   // как штатный: ~раз в 10 с

    // районы приходят с сервера (RGNSLST) — названия не зашиты. Нет списка -> просто свободные заказы.
    if (regions.isEmpty()) { FreeOrders(free, driver, vm); return }

    BackHandler(enabled = picked != null) { picked = null }   // из района — к списку районов

    val r = picked
    if (r != null) {
        // один район — фильтр не нужен (на случай расхождения форматов id); несколько — по regionId
        val list = if (regions.size <= 1) free else free.filter { it.regionId == r.id }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { picked = null }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ArrowBack, null, tint = Ui.Teal)
                Spacer(Modifier.width(10.dp))
                Text(r.name, color = Ui.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${list.size}", color = Gray, fontSize = 16.sp)
            }
            Divider(color = Ui.Outline)
            Box(Modifier.weight(1f).fillMaxWidth()) { FreeOrders(list, driver, vm) }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(regions, key = { it.id }) { reg ->
            val n = if (regions.size <= 1) free.size else free.count { it.regionId == reg.id }
            val has = n > 0
            // есть свободные заказы — плитка подсвечивается жёлто-зелёным (как у штатного)
            val accent = if (has) RegionGreen else Color(0xFF5B9BFF)
            Surface(
                onClick = { picked = reg },
                color = if (has) RegionGreen.copy(alpha = 0.12f) else Ui.Surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, if (has) RegionGreen.copy(alpha = 0.55f) else Ui.Outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.GridView, null, Modifier.size(22.dp), tint = accent) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(reg.name, color = if (has) RegionGreen else Ui.OnSurface,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("${tr("Свободных заказов","Bo'sh buyurtmalar")}: $n", color = if (has) RegionGreen.copy(alpha = 0.85f) else Gray,
                            fontSize = 13.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = if (has) RegionGreen else Gray)
                }
            }
        }
    }
}

// ───────────────────────── настройки ─────────────────────────

@Composable
private fun LangBtn(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Ui.Teal else Ui.SurfaceHi,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) null else BorderStroke(1.dp, Ui.Outline),
        modifier = modifier.height(46.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) Color(0xFF00201C) else Ui.OnSurface,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingsView(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Оформление
        val dark by vm.darkTheme.collectAsState()
        Surface(color = Ui.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Оформление","Ko'rinish"), color = Gray, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (dark) Icons.Filled.DarkMode else Icons.Filled.LightMode, null,
                        Modifier.size(22.dp), tint = Ui.Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(if (dark) tr("Тёмная тема","Qorong'i mavzu") else tr("Светлая тема","Yorug' mavzu"), color = Ui.OnSurface, fontSize = 16.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = dark, onCheckedChange = { vm.setDarkTheme(it) })
                }
            }
        }
        // Язык
        val lang by vm.lang.collectAsState()
        Surface(color = Ui.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Язык","Til"), color = Gray, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LangBtn("O'zbekcha", lang == "uz", Modifier.weight(1f)) { vm.setLang("uz") }
                    LangBtn("Русский", lang == "ru", Modifier.weight(1f)) { vm.setLang("ru") }
                }
            }
        }
        Text("SKAT ${uz.ishtixon.scatdriver.BuildConfig.VERSION_NAME}", color = Gray, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

// ───────────────────────── боковое меню ─────────────────────────

@Composable
private fun DrawerHeader(code: String, name: String, city: String, gps: Pair<Double, Double>?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(uz.ishtixon.scatdriver.R.drawable.scat_logo),
                contentDescription = null, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ui.OnSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (code.isNotBlank()) Text("ID $code", color = Gray, fontSize = 13.sp)
            }
        }
        // город (например «Наманган») из бокового меню убран по просьбе
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, fontSize = 15.sp) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, null) },
        shape = RoundedCornerShape(14.dp),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Ui.Surface,
            unselectedIconColor = Ui.Teal,
            unselectedTextColor = Ui.OnSurface,
            selectedContainerColor = Ui.Teal.copy(alpha = 0.16f),
            selectedIconColor = Ui.Teal,
            selectedTextColor = Ui.Teal
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

// ───────────────────────── главное меню ─────────────────────────

@Composable
private fun MenuGrid(onPick: (Section) -> Unit) {
    // классическая сетка 3×3 как у штатного
    val rows = tiles.chunked(3)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> MenuTile(t, Modifier.weight(1f), onPick) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Плитка меню в стиле штатного: готовая иконка-картинка и подпись снизу. */
@Composable
private fun MenuTile(t: Tile, modifier: Modifier, onPick: (Section) -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onPick(t.section) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(t.res),
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(secLabel(t.section), textAlign = TextAlign.Center, color = Ui.OnSurface,
            fontSize = 14.sp, lineHeight = 16.sp, maxLines = 2, fontWeight = FontWeight.Medium)
    }
}

// ───────────────────────── районы (свободные заказы) ─────────────────────────

@Composable
private fun FreeOrders(free: List<FreeOrder>, driver: Pair<Double, Double>?, vm: MainViewModel) {
    // свайп вниз = ручное обновление (как в штатном клиенте)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val catalog by vm.extrasCatalog.collectAsState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            vm.pollFreeNow()
            scope.launch { kotlinx.coroutines.delay(700); refreshing = false }
        },
        modifier = Modifier.fillMaxSize()
    ) {
    if (free.isEmpty()) {
        // пустой список тоже должен тянуться вниз — оборачиваем в прокручиваемый контейнер
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                    Text(tr("Нет свободных заказов","Bo'sh buyurtmalar yo'q"), Modifier.align(Alignment.Center), color = Gray)
                }
            }
        }
        return@PullToRefreshBox
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(free, key = { it.id }) { fo ->
            val dist = driver?.let { fo.distanceKm(it.first, it.second) }
            Surface(
                onClick = { vm.openFree(fo.id) },
                color = Ui.Surface, shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Ui.Outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // расстояние до заказа (без номера заказа)
                    if (dist != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Surface(color = Ui.Teal.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                                Text(String.format("%.2f км", dist), color = Ui.Teal, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                    Text(fo.fromName.ifBlank { fo.street }.ifBlank { tr("Заказ","Buyurtma") }, fontWeight = FontWeight.Bold,
                        fontSize = 17.sp, color = Orange)
                    // адрес отдельной строкой только если он не ушёл в заголовок
                    if (fo.fromName.isNotBlank() && fo.street.isNotBlank())
                        Text(fo.street, color = Ui.OnSurface, fontSize = 14.sp)
                    val sub = listOf(fo.client, fo.service).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = Gray, fontSize = 13.sp)
                    // доп. услуги: сумма (+2000) и названия из справочника, если известны
                    if (fo.extrasTotal > 0 || fo.extraIds.isNotEmpty()) {
                        val names = fo.extraIds.mapNotNull { catalog[it]?.shortName?.takeIf { s -> s.isNotBlank() } }
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Orange.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.AddShoppingCart, null, Modifier.size(13.dp), tint = Orange)
                                    Spacer(Modifier.width(4.dp))
                                    Text("+${fo.extrasTotal.toInt()} сўм", color = Orange, fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            if (names.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(names.joinToString(", "), color = Gray, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    }  // PullToRefreshBox
}

// ───────────────────────── окно заказа ─────────────────────────

private fun mmss(s: Int) = "%02d:%02d".format(s / 60, s % 60)
private fun hhmmss(s: Int) = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)

@Composable
private fun OrderView(o: ActiveOrder, orderTimes: List<Int>, vm: MainViewModel, onMessages: () -> Unit) {
    val paused by vm.paused.collectAsState()
    var tab by remember(o.id) { mutableStateOf(0) }
    val driver by vm.driverLoc.collectAsState()
    val settings by vm.settings.collectAsState()
    val bg = if (o.status == OrderStatus.EXECUTING && paused) PauseRed else Ui.Bg
    Column(Modifier.fillMaxSize().background(bg)) {
        // переключатель плавает поверх карты (zIndex + рисуется последним) — карта его больше не закрывает
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) OrderBody(o, vm, Modifier.fillMaxSize().padding(top = 64.dp))
            else OrderMap(o, driver, settings, Modifier.fillMaxSize())
            OrderToggle(tab, { tab = it },
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp).zIndex(1f))
        }

        // нижняя «шторка» с таймером и кнопками действий
        Surface(
            color = Ui.Surface,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                when (o.status) {
                    OrderStatus.OFFERED -> IncomingControls(o, orderTimes, vm)
                    OrderStatus.RESERVED -> PreorderControls(o, vm, onMessages)
                    OrderStatus.CONFIRMED -> ConfirmedControls(o, vm, onMessages)
                    OrderStatus.ARRIVED -> WaitingControls(o, vm, onMessages)
                    OrderStatus.EXECUTING -> MeterControls(o, vm, paused, onMessages)
                    else -> ConfirmedControls(o, vm, onMessages)
                }
            }
        }
    }
}

/** Плавающий сегментированный переключатель ЗАКАЗ/КАРТА (рисуется поверх карты). */
@Composable
private fun OrderToggle(tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Ui.Surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Ui.Outline), shadowElevation = 6.dp, modifier = modifier
    ) {
        Row(Modifier.padding(3.dp)) {
            listOf(tr("ЗАКАЗ","BUYURTMA"), tr("КАРТА","XARITA")).forEachIndexed { i, t ->
                val sel = tab == i
                Box(
                    Modifier.clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Ui.Teal else Color.Transparent)
                        .clickable { onTab(i) }
                        .padding(horizontal = 30.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(t, color = if (sel) Color(0xFF00201C) else Gray,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun OrderBody(o: ActiveOrder, vm: MainViewModel, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))

        // карточка заказа: название → адрес → служба → клиент → доп. услуги (всё под названием)
        val notExec = o.status != OrderStatus.EXECUTING   // после «Старт» службу/телефон прячем за «i»
        Surface(color = Ui.Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 9.dp).size(10.dp).clip(CircleShape).background(Gray))
                Spacer(Modifier.width(12.dp))
                Column {
                    // если названия точки нет — показываем адрес как заголовок (без "null")
                    val title = o.fromName.ifBlank { o.fromStreet }.ifBlank { tr("Заказ","Buyurtma") }
                    val sub = if (o.fromName.isNotBlank()) o.fromStreet else ""
                    Text(title, color = Orange, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        lineHeight = 25.sp)
                    if (sub.isNotBlank())
                        Text(sub, color = Ui.OnSurface.copy(alpha = 0.8f), fontSize = 16.sp, lineHeight = 20.sp)
                    // служба — под адресом
                    if (notExec && o.service.isNotBlank())
                        Text(o.service, color = Orange, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp))
                    if (o.clientName.isNotBlank())
                        Text(o.clientName, color = Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    if (notExec && o.clientPhone.isNotBlank())
                        Row(
                            Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                runCatching {
                                    ctx.startActivity(android.content.Intent(
                                        android.content.Intent.ACTION_DIAL,
                                        android.net.Uri.parse("tel:${o.clientPhone}"))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Phone, null, Modifier.size(16.dp), tint = Orange)
                            Spacer(Modifier.width(8.dp))
                            Text(o.clientPhone, color = Ui.Teal, fontSize = 15.sp,
                                textDecoration = TextDecoration.Underline)
                        }
                    // доп. услуги под названием: «+2000 · Багаж» (тап — подробности)
                    ExtrasLine(o, vm)
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Доп. услуги заказа — только просмотр. Их задаёт диспетчер; водитель ничего не включает/не меняет. */
@Composable
private fun ExtrasDialog(o: ActiveOrder, vm: MainViewModel, onClose: () -> Unit) {
    val catalog by vm.extrasCatalog.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Доп. услуги","Qo'shimcha xizmatlar")) },
        text = {
            if (o.extras.isEmpty()) {
                Text(tr("Диспетчер не добавил доп. услуг к заказу.","Dispetcher buyurtmaga qo'shimcha xizmat qo'shmagan."), color = Gray)
            } else {
                Column {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(o.extras, key = { it.id }) { e ->
                            val nm = catalog[e.id]?.shortName?.takeIf { it.isNotBlank() }
                                ?: e.name.ifBlank { "${tr("Услуга","Xizmat")} #${e.id}" }
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(nm, color = Ui.OnSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text("${(e.price * e.count).toInt()} сўм", color = Orange, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium)
                            }
                            Divider(color = Ui.Outline)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Итого доплата","Jami qo'shimcha"), color = Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("+${o.extrasTotal.toInt()} сўм", color = Orange, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Закрыть","Yopish")) } }
    )
}

private fun orderDistanceKm(o: ActiveOrder, driver: Pair<Double, Double>?): Double? {
    val la = o.lat; val lo = o.lon
    if (driver == null || la == null || lo == null || (la == 0.0 && lo == 0.0)) return null
    return haversineKm(driver.first, driver.second, la, lo)
}

private fun fmtKm(km: Double): String = String.format(java.util.Locale("ru"), "%.2f км", km)

// ───────────────────────── карта ─────────────────────────

private const val TILE_BASE_DEFAULT = "http://geo.cloudtaxi.ru/osm_tiles/"
private const val TILE_KEY_DEFAULT = "WeFeeJu1"

/** Тайлы как у штатного клиента: .../osm_tiles/{z}/{x}/{y}.png?key=... (адрес/ключ берём из настроек сервера, если есть). */
private fun scatTileSource(settings: Map<String, String>): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
    var base = TILE_BASE_DEFAULT
    var key = TILE_KEY_DEFAULT
    settings.values.firstOrNull { it.contains("osm_tiles") }?.let { v ->
        Regex("(https?://[^\\s\"'{]+/osm_tiles/)").find(v)?.let { base = it.groupValues[1] }
        Regex("key=([A-Za-z0-9]+)").find(v)?.let { key = it.groupValues[1] }
    }
    val b = base; val k = key
    return object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
        "ScatOSM", 3, 18, 256, ".png", arrayOf(b)
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
                org.osmdroid.util.MapTileIndex.getX(pMapTileIndex) + "/" +
                org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding + "?key=" + k
    }
}

@Composable
private fun OrderMap(o: ActiveOrder, driver: Pair<Double, Double>?, settings: Map<String, String>, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tiles = remember(settings) { scatTileSource(settings) }
    val map = remember {
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            val base = java.io.File(ctx.cacheDir, "osmdroid")
            osmdroidBasePath = base
            osmdroidTileCache = java.io.File(base, "tiles")
        }
        org.osmdroid.views.MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            controller.setZoom(15.0)
        }
    }
    remember(map, tiles) { map.setTileSource(tiles); 0 }   // один раз на источник тайлов
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }

    val orderPoint = remember(o.lat, o.lon) {
        val la = o.lat; val lo = o.lon
        if (la != null && lo != null && !(la == 0.0 && lo == 0.0)) org.osmdroid.util.GeoPoint(la, lo) else null
    }

    Box(modifier.fillMaxWidth()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { map },
            update = { m ->
                m.overlays.clear()
                // позиция водителя — синий круг
                driver?.let { (la, lo) ->
                    val c = org.osmdroid.views.overlay.Polygon(m).apply {
                        points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(org.osmdroid.util.GeoPoint(la, lo), 30.0)
                        fillPaint.color = android.graphics.Color.argb(90, 66, 133, 244)
                        outlinePaint.color = android.graphics.Color.rgb(66, 133, 244)
                        outlinePaint.strokeWidth = 4f
                    }
                    m.overlays.add(c)
                }
                // точка подачи — маркер
                orderPoint?.let { pt ->
                    val mk = org.osmdroid.views.overlay.Marker(m).apply {
                        position = pt
                        title = o.fromName.ifBlank { o.fromStreet }
                        setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    }
                    m.overlays.add(mk)
                }
                if (!centered) {
                    val target = orderPoint ?: driver?.let { org.osmdroid.util.GeoPoint(it.first, it.second) }
                    if (target != null) { m.controller.setCenter(target); centered = true }
                }
                m.invalidate()
            }
        )
        // центрировать на мне / на заказе
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (orderPoint != null)
                MapFab(Icons.Filled.Place) { map.controller.animateTo(orderPoint) }
            if (driver != null)
                MapFab(Icons.Filled.MyLocation) {
                    map.controller.animateTo(org.osmdroid.util.GeoPoint(driver.first, driver.second))
                }
        }
    }
}

@Composable
private fun MapFab(icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Ui.Surface, shape = CircleShape, shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = Ui.Teal) }
    }
}

// ── элементы управления заказом ──

@Composable
private fun SquareBtn(
    icon: ImageVector,
    modifier: Modifier = Modifier.size(52.dp),
    onClick: () -> Unit = {}
) {
    Surface(onClick = onClick, color = Ui.Btn, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = Color.White)
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, Modifier.fillMaxWidth(), color = Gray, textAlign = TextAlign.Center, fontSize = 14.sp)
}

@Composable
private fun BigTimer(text: String) {
    Text(text, color = Orange, fontSize = 44.sp, fontWeight = FontWeight.Medium)
}

/** Таймер по центру (кнопку навигации убрали — она теперь в ряду действий). */
@Composable
private fun NavTimerRow(timer: String) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { BigTimer(timer) }
}

/** Кнопка навигации: открывает карту/навигатор на точке подачи; без координат — подсказка. */
@Composable
private fun NavButton(o: ActiveOrder, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SquareBtn(Icons.Filled.Navigation, modifier) {
        val la = o.lat; val lo = o.lon
        if (la != null && lo != null && !(la == 0.0 && lo == 0.0)) {
            val label = android.net.Uri.encode(o.fromName.ifBlank { o.fromStreet }.ifBlank { tr("Заказ","Buyurtma") })
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("geo:$la,$lo?q=$la,$lo($label)"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            android.widget.Toast.makeText(ctx, tr("У заказа нет координат","Buyurtmada koordinata yo'q"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** Доп. услуги под названием заказа: «🛒 +2000 · Багаж, Доставка» (тап — подробности, только чтение). */
@Composable
private fun ExtrasLine(o: ActiveOrder, vm: MainViewModel, modifier: Modifier = Modifier) {
    if (o.extrasTotal <= 0 && o.extras.isEmpty()) return
    val catalog by vm.extrasCatalog.collectAsState()
    var show by remember { mutableStateOf(false) }
    val names = o.extras.mapNotNull { e ->
        catalog[e.id]?.shortName?.takeIf { it.isNotBlank() } ?: e.name.takeIf { it.isNotBlank() }
    }
    val label = "+${o.extrasTotal.toInt()} сўм" + if (names.isNotEmpty()) " · ${names.joinToString(", ")}" else ""
    Surface(onClick = { show = true }, color = Orange.copy(alpha = 0.16f),
        shape = RoundedCornerShape(10.dp), modifier = modifier.padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AddShoppingCart, null, Modifier.size(15.dp), tint = Orange)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (show) ExtrasDialog(o, vm) { show = false }
}

@Composable
private fun ActionBtn(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun IncomingControls(o: ActiveOrder, orderTimes: List<Int>, vm: MainViewModel) {
    val offer by vm.offerSec.collectAsState()
    val driver by vm.driverLoc.collectAsState()
    val dist = orderDistanceKm(o, driver)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (dist != null) Text(fmtKm(dist), color = Gray, fontSize = 14.sp)
        NavTimerRow(mmss(offer))
        // ряд кнопок времени подачи — только 3·5·7·10
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            orderTimes.take(4).forEach { m ->
                Button(
                    onClick = { vm.acceptWithTime(o.id, m) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color.White),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) { Text("$m", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
        // «Отказаться» — по центру, ровно под кнопками 5 и 7
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            ActionBtn(tr("Отказаться","Rad etish"), Ui.Warn, Modifier.weight(2f).height(44.dp)) { vm.reject(o.id); vm.closeOrder() }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreorderControls(o: ActiveOrder, vm: MainViewModel, onMessages: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Предварительный","Oldindan"), color = Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ActionBtn(tr("Отказаться","Rad etish"), Ui.Warn, Modifier.height(42.dp)) { vm.releasePreorder(o) }
        }
        NavTimerRow(fmtReservation(o.reservation).ifBlank { "—" }.let { if (it.length > 8) it.takeLast(5) else it })
        if (o.reservation.isNotBlank())
            Text(fmtReservation(o.reservation), Modifier.fillMaxWidth(), color = Gray,
                textAlign = TextAlign.Center, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ConfirmButton(tr("На месте","Yetib keldim"), tr("Вы подъехали к клиенту?","Mijoz oldiga yetib keldingizmi?"),
                modifier = Modifier.weight(1f).height(48.dp)) { vm.arrive(o) }
            SquareBtn(Icons.Filled.Forum, Modifier.size(width = 46.dp, height = 48.dp), onMessages)
            ConfirmButton(tr("Старт","Start"), tr("Начать поездку?","Safarni boshlaymizmi?"),
                modifier = Modifier.weight(2f).height(48.dp), container = Lime) { vm.startTrip(o) }
        }
    }
}

private fun fmtReservation(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val inF = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val d = inF.parse(iso.take(19))
        java.text.SimpleDateFormat("EE dd.MM HH:mm", java.util.Locale("ru")).format(d!!)
    }.getOrDefault(iso)
}

@Composable
private fun ConfirmedControls(o: ActiveOrder, vm: MainViewModel, onMessages: () -> Unit) {
    val sec by vm.phaseSec.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusLabel(tr("Подтвержден","Tasdiqlangan"))
        NavTimerRow(mmss(sec))
        // чат + навигация над основными кнопками
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquareBtn(Icons.Filled.Forum, Modifier.weight(1f).height(46.dp), onMessages)
            NavButton(o, Modifier.weight(1f).height(46.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ConfirmButton(tr("На месте","Yetib keldim"), tr("Вы подъехали к клиенту?","Mijoz oldiga yetib keldingizmi?"),
                modifier = Modifier.weight(1f).height(50.dp)) { vm.arrive(o) }
            ConfirmButton(tr("Старт","Start"), tr("Начать поездку?","Safarni boshlaymizmi?"),
                modifier = Modifier.weight(1f).height(50.dp), container = Lime) { vm.startTrip(o) }
        }
    }
}

@Composable
private fun WaitingControls(o: ActiveOrder, vm: MainViewModel, onMessages: () -> Unit) {
    val sec by vm.phaseSec.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusLabel(tr("Ожидание клиента","Mijozni kutish"))
        NavTimerRow(mmss(sec))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquareBtn(Icons.Filled.Forum, Modifier.weight(1f).height(46.dp), onMessages)
            NavButton(o, Modifier.weight(1f).height(46.dp))
        }
        ConfirmButton(tr("Старт","Start"), tr("Клиент в машине — начать поездку?","Mijoz mashinada — safarni boshlaymizmi?"),
            modifier = Modifier.fillMaxWidth().height(50.dp), container = Lime) { vm.startTrip(o) }
    }
}

@Composable
private fun MeterControls(o: ActiveOrder, vm: MainViewModel, paused: Boolean, onMessages: () -> Unit) {
    val amount by vm.meterAmount.collectAsState()
    val km by vm.meterKm.collectAsState()
    val sec by vm.meterSec.collectAsState()
    val stand by vm.meterStandSec.collectAsState()
    val speed by vm.meterSpeedKmh.collectAsState()
    val standingFlow by vm.standing.collectAsState()
    var showInfo by remember { mutableStateOf(false) }

    // «стоянка» — из ViewModel (с защитой от дрожания GPS), не по мгновенной скорости
    val standingNow = !paused && standingFlow
    // при стоянке мигает «Ожидание…», в движении — «В исполнении», на паузе — «Пауза»
    val statusText = when { paused -> tr("Пауза","Pauza"); standingNow -> tr("Ожидание…","Kutish…"); else -> tr("В исполнении","Bajarilmoqda") }
    val total = amount + o.extrasTotal   // счёт = таксометр + доп. услуги

    var showCart by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 1) СУММА — на самом верху, крупно и чётко (с учётом доп. услуг; разбивка — под названием заказа)
        Text("${total.toInt()} сўм", color = Orange, fontSize = 48.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // 2) статус (вместо баннера «автостоянка»)
        BlinkStatus(statusText, blink = standingNow)
        // 3) компактные счётчики: время · в пути · расстояние · скорость
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MiniStat(Icons.Filled.Schedule, hhmmss(sec), tr("время","vaqt"))
            MiniStat(Icons.Filled.DirectionsCar, hhmmss((sec - stand).coerceAtLeast(0)), tr("в пути","yo'lda"))
            MiniStat(Icons.Filled.Straighten, "%.2f".format(km), tr("км","km"))
            MiniStat(Icons.Filled.Speed, "${speed.toInt()}", tr("км/ч","km/soat"))
        }
        // 4) действия: навигация, чат, инфо, корзина
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val m = Modifier.weight(1f).height(46.dp)
            NavButton(o, m)
            SquareBtn(Icons.Filled.Forum, m, onMessages)
            SquareBtn(Icons.Filled.Info, m) { showInfo = true }
            SquareBtn(Icons.Filled.AddShoppingCart, m) { showCart = true }
        }
        // ручная стоянка — только если у тарифа нет автостоянки, но плата за стоянку есть
        if (o.rate?.autoStand != true && o.rate?.hasStandPrice == true) {
            val manual by vm.manualStand.collectAsState()
            ActionBtn(
                if (manual) tr("Стоянка — стоп","To'xtash — stop") else tr("Стоянка","To'xtash"),
                if (manual) Ui.Warn else Ui.Btn,
                Modifier.fillMaxWidth().height(46.dp)
            ) { vm.toggleStand() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (paused)
                ActionBtn(tr("Старт","Start"), Ui.Btn, Modifier.weight(1f).height(48.dp)) { vm.resume() }
            else
                ActionBtn(tr("Пауза","Pauza"), Ui.Btn, Modifier.weight(1f).height(48.dp)) { vm.pause() }
            ConfirmButton(tr("Стоп","Stop"), tr("Завершить поездку и выставить счёт?","Safarni yakunlab, hisob chiqaramizmi?"),
                modifier = Modifier.weight(2f).height(48.dp), container = Lime) { vm.stopTrip(o) }
        }
    }
    if (showInfo) OrderInfoDialog(o) { showInfo = false }
    if (showCart) ExtrasDialog(o, vm) { showCart = false }
}

/** Финальное окно завершённого заказа: итог к оплате + кнопка «Чек»/«Закрыть». */
@Composable
private fun CompletedDialog(info: CompletedInfo, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(color = Ui.Surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(0.95f)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Заказ завершён","Buyurtma yakunlandi"), color = Ui.OnSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(18.dp))
                Text(tr("К оплате","To'lovga"), color = Gray, fontSize = 14.sp)
                Text("${info.total.toInt()} сўм", color = Orange, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1)
                Spacer(Modifier.height(20.dp))
                InfoRow(tr("Поездка","Safar"), "${info.trip.toInt()} сўм")
                if (info.extras > 0) InfoRow(tr("Доп. услуги","Qo'shimcha xizmatlar"), "+${info.extras.toInt()} сўм")
                InfoRow(tr("Расстояние","Masofa"), "%.2f км".format(info.km))
                InfoRow(tr("Время","Vaqt"), hhmmss(info.sec))
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionBtn(tr("Чек","Chek"), Ui.Btn, Modifier.weight(1f).height(52.dp)) {
                        android.widget.Toast.makeText(ctx, tr("Чек появится позже","Chek keyinroq paydo bo'ladi"), android.widget.Toast.LENGTH_SHORT).show()
                        onClose()
                    }
                    ActionBtn(tr("Закрыть","Yopish"), Lime, Modifier.weight(1f).height(52.dp)) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ui.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Статус поездки: «Ожидание…» мигает при стоянке, иначе — ровный текст. */
@Composable
private fun BlinkStatus(text: String, blink: Boolean) {
    val alpha = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        t.animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a"
        ).value
    } else 1f
    Text(
        text, Modifier.fillMaxWidth().alpha(alpha),
        color = if (blink) Orange else Gray, textAlign = TextAlign.Center,
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold
    )
}

/** Компактный счётчик: иконка + значение, снизу подпись. */
@Composable
private fun MiniStat(icon: ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(15.dp), tint = Gray)
            Spacer(Modifier.width(4.dp))
            Text(value, color = Ui.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Text(label, color = Gray, fontSize = 11.sp)
    }
}

/** Служба и телефон клиента — после «Старт» спрятаны за кнопкой «i». */
@Composable
private fun OrderInfoDialog(o: ActiveOrder, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Информация о заказе","Buyurtma haqida")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // тариф (спрятан сюда с экрана таксометра)
                if (o.rateName.isNotBlank())
                    Text("${tr("Тариф","Tarif")}: ${o.rateName}", color = Ui.Teal, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                o.rate?.let { r ->
                    val perKm = r.farePerKm
                    val perMin = r.standPerMin
                    val parts = buildList {
                        if (perKm > 0) add("${perKm.toInt()} ${tr("сўм/км","so'm/km")}")
                        if (perMin > 0) add("${perMin.toInt()} ${tr("сўм/мин стоянка","so'm/daq to'xtash")}")
                    }
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Gray, fontSize = 13.sp)
                }
                if (o.service.isNotBlank()) Text(o.service, color = Orange, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (o.clientName.isNotBlank()) Text(o.clientName, color = Ui.OnSurface, fontSize = 15.sp)
                if (o.clientPhone.isNotBlank()) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            runCatching {
                                ctx.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    android.net.Uri.parse("tel:${o.clientPhone}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Phone, null, Modifier.size(16.dp), tint = Orange)
                        Spacer(Modifier.width(8.dp))
                        Text(o.clientPhone, color = Ui.Teal, fontSize = 16.sp, textDecoration = TextDecoration.Underline)
                    }
                }
                if (o.fromStreet.isNotBlank() || o.fromName.isNotBlank())
                    Text(listOf(o.fromName, o.fromStreet).filter { it.isNotBlank() }.joinToString(", "),
                        color = Gray, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Закрыть","Yopish")) } }
    )
}

@Composable
private fun ConfirmButton(
    text: String,
    question: String,
    modifier: Modifier = Modifier,
    container: Color? = null,
    onConfirm: () -> Unit,
) {
    var ask by remember { mutableStateOf(false) }
    ActionBtn(text, container ?: Ui.Btn, modifier) { ask = true }
    if (ask) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { ask = false }) {
            Surface(color = Ui.Surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(0.92f)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text, color = Ui.OnSurface, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Text(question, color = Gray, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionBtn(tr("Отмена","Bekor qilish"), Ui.Btn, Modifier.weight(1f).height(54.dp)) { ask = false }
                        ActionBtn(tr("Да","Ha"), container ?: Lime, Modifier.weight(1f).height(54.dp)) { ask = false; onConfirm() }
                    }
                }
            }
        }
    }
}

// ───────────────────────── личный кабинет ─────────────────────────

@Composable
private fun CabinetView(vm: MainViewModel) {
    // Штатный «Личный кабинет» — это веб-приложение сервера под /cabinet/.
    // Открываем его в WebView — экран один в один со штатным (тот же сервер, тот же UI).
    val url = vm.cabinetUrl()
    if (url == null) { Placeholder(tr("Личный кабинет","Shaxsiy kabinet")); return }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val web = android.webkit.WebView(ctx)
            web.apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    @Suppress("DEPRECATION") allowContentAccess = true
                    @Suppress("DEPRECATION") allowFileAccess = true
                    javaScriptCanOpenWindowsAutomatically = true
                }
                // cookie обязательны: без них сессия /cabinet/ теряется и тело не грузится
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                webViewClient = android.webkit.WebViewClient()
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d("CabinetWeb", "${m.messageLevel()} ${m.message()} @${m.lineNumber()}")
                        return true
                    }
                }
                setBackgroundColor(android.graphics.Color.parseColor("#11161D"))
                loadUrl(url)
            }
        }
    )
}

// ───────────────────────── сообщения ─────────────────────────

private val senderRe = Regex("^\\((.+?)\\)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

/** Входящее SMS приходит как «(22. Aladdin-1415-4) текст» — делим на отправителя и текст. */
private fun splitSender(t: String): Pair<String, String> =
    senderRe.find(t)?.let { it.groupValues[1] to it.groupValues[2] } ?: (tr("Диспетчер","Dispetcher") to t)

private fun fullDate(ts: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

@Composable
private fun Avatar(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
}

@Composable
private fun MsgCard(m: uz.ishtixon.scatdriver.model.ChatMsg, myCode: String) {
    val mine = !m.incoming
    val (sender, body) = if (mine) (myCode.ifBlank { tr("Я","Men") } to m.text) else splitSender(m.text)
    Surface(
        color = if (mine) Ui.SurfaceHi else Ui.Surface,
        shape = RoundedCornerShape(18.dp),
        border = if (mine) null else BorderStroke(1.dp, Ui.Outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            if (!mine) { Avatar(Icons.Filled.SupportAgent, Ui.Accent); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (mine) {
                        Text(fullDate(m.ts), color = Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(sender, color = Ui.OnSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    } else {
                        Text(sender, color = Ui.OnSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text(fullDate(m.ts), color = Gray, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(body, color = Ui.OnSurface, fontSize = 14.sp,
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth())
            }
            if (mine) { Spacer(Modifier.width(12.dp)); Avatar(Icons.Filled.LocalTaxi, Ui.Teal) }
        }
    }
}

@Composable
private fun ChatView(vm: MainViewModel) {
    val msgs by vm.messages.collectAsState()
    val templates by vm.smsTemplates.collectAsState()
    val profile by vm.profile.collectAsState()
    var showWrite by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { vm.clearPopups() }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        if (msgs.isEmpty())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(tr("Нет сообщений","Xabarlar yo'q"), Modifier.align(Alignment.Center), color = Gray)
            }
        else LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
        ) {
            items(msgs) { m -> MsgCard(m, profile?.code ?: "") }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionBtn(tr("Написать","Yozish"), Ui.Btn, Modifier.weight(1f).height(46.dp)) { showWrite = true }
            ActionBtn(tr("Шаблоны","Shablonlar"), Ui.Btn, Modifier.weight(1f).height(46.dp)) { showTemplates = true }
        }
    }

    if (showWrite) {
        AlertDialog(
            onDismissRequest = { showWrite = false },
            title = { Text(tr("Сообщение диспетчеру","Dispetcherga xabar")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("Текст сообщения","Xabar matni")) },
                    maxLines = 5
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { val t = text.trim(); if (t.isNotEmpty()) { vm.sms(t); text = "" }; showWrite = false }
                ) { Text(tr("Отправить","Yuborish")) }
            },
            dismissButton = { TextButton(onClick = { showWrite = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }
    if (showTemplates) {
        AlertDialog(
            onDismissRequest = { showTemplates = false },
            title = { Text(tr("Шаблоны","Shablonlar")) },
            text = {
                if (templates.isEmpty()) Text(tr("Шаблонов нет","Shablonlar yo'q"), color = Gray)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(templates) { t ->
                        Text(t, color = Ui.OnSurface,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showTemplates = false; vm.sms(t) }
                                .padding(vertical = 14.dp))
                        Divider(color = Ui.Outline)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTemplates = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }
}

// ───────────────────────── предзаказы ─────────────────────────

@Composable
private fun PreordersView(vm: MainViewModel) {
    val free by vm.preorders.collectAsState()
    val count by vm.preorderCount.collectAsState()
    val attachedAll by vm.attachedOrders.collectAsState()
    val attached = remember(attachedAll) { attachedAll.filter { it.status == OrderStatus.RESERVED } }
    var showAssigned by remember { mutableStateOf(false) }
    LaunchedEffect(showAssigned) {
        while (true) {
            if (showAssigned) vm.loadAttached() else vm.loadPreorders()
            kotlinx.coroutines.delay(10000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(if (showAssigned) "${tr("Назначенные","Tayinlangan")}: ${attached.size}" else "${tr("Предзаказы","Oldindan buyurtmalar")}: $count",
            Modifier.fillMaxWidth().padding(12.dp), color = Gray, textAlign = TextAlign.Center)

        val listEmpty = if (showAssigned) attached.isEmpty() else free.isEmpty()
        if (listEmpty)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(if (showAssigned) tr("Нет назначенных заказов","Tayinlangan buyurtmalar yo'q") else tr("Нет предзаказов","Oldindan buyurtmalar yo'q"),
                    Modifier.align(Alignment.Center), color = Gray)
            }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showAssigned) items(attached) { o ->
                Surface(onClick = { vm.openOrderWindow(o) },
                    color = Ui.Surface, shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Ui.Outline),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tr("Назначен (нажмите для старта)","Tayinlangan (start uchun bosing)"), color = Lime, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                        Text(o.fromName.ifBlank { "—" }, color = Orange,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        if (o.clientName.isNotBlank()) Text(o.clientName, color = Ui.OnSurface, fontSize = 14.sp)
                        if (o.rateName.isNotBlank()) Text(o.rateName, color = Gray, fontSize = 13.sp)
                    }
                }
            } else items(free) { po ->
                Surface(onClick = { vm.takePreorder(po.id) },
                    color = Ui.Surface, shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Ui.Outline),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Предварительный","Oldindan"), color = Gray, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Surface(color = Ui.Accent.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                                Text(fmtPreorderTime(po.whenLocal), color = Orange, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                        Text(po.from, color = Orange, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        if (po.client.isNotBlank()) Text(po.client, color = Ui.OnSurface, fontSize = 14.sp)
                        if (po.service.isNotBlank()) Text(po.service, color = Gray, fontSize = 13.sp)
                    }
                }
            }
        }
        ActionBtn(
            if (showAssigned) tr("← Свободные предзаказы","← Bo'sh oldindan buyurtmalar") else tr("Уже назначенные заказы","Allaqachon tayinlangan buyurtmalar"),
            Lime, Modifier.fillMaxWidth().padding(12.dp).height(56.dp)
        ) { showAssigned = !showAssigned }
    }
}

private fun fmtPreorderTime(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val inF = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val d = inF.parse(iso.take(19))
        java.text.SimpleDateFormat("EE dd.MM HH:mm", java.util.Locale("ru")).format(d!!)
    }.getOrDefault(iso)
}

// ───────────────────────── баннеры ─────────────────────────

@Composable
private fun NoticeBanner(n: uz.ishtixon.scatdriver.model.SystemNotice, onOk: () -> Unit) {
    val bg = if (n.block) Color(0xFFB3261E) else Color(0xFF2E7D32)
    Surface(color = bg, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (n.block) Icons.Filled.Lock else Icons.Filled.CheckCircle, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(n.text, Modifier.weight(1f), color = Color.White, maxLines = 3)
            TextButton(onClick = onOk,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("ОК") }
        }
    }
}

@Composable
private fun ChatPopup(m: uz.ishtixon.scatdriver.model.ChatMsg, onOk: () -> Unit, onOpen: () -> Unit) {
    val (sender, body) = splitSender(m.text)
    Surface(
        color = Ui.SurfaceHi,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Ui.Outline),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(Icons.Filled.SupportAgent, Ui.Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sender, style = MaterialTheme.typography.labelMedium, color = Orange,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(body, maxLines = 2, color = Ui.OnSurface)
            }
            TextButton(onClick = onOk) { Text("ОК") }
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize()) {
        Text("$title — ${tr("скоро","tez orada")}", Modifier.align(Alignment.Center), color = Gray, style = MaterialTheme.typography.titleMedium)
    }
}

// ───────────────────────── объявления ─────────────────────────

/** Объявления: пока список пуст. */
@Composable
private fun AdsView() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Campaign, null, Modifier.size(56.dp), tint = Gray)
        Spacer(Modifier.height(14.dp))
        Text(tr("Список пуст","Ro'yxat bo'sh"),
            color = Ui.OnSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(tr("Новых объявлений нет","Yangi e'lonlar yo'q"),
            color = Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

// ───────────────────────── дополнительно (инфо) ─────────────────────────

/** Дополнительно: информация о приложении и службе. */
@Composable
private fun AdvancedView(vm: MainViewModel) {
    val profile by vm.profile.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(uz.ishtixon.scatdriver.R.drawable.scat_logo),
            contentDescription = null,
            modifier = Modifier.size(84.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text("SKAT", color = Ui.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr("Версия","Versiya") + " " + uz.ishtixon.scatdriver.BuildConfig.VERSION_NAME,
            color = Gray, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Surface(color = Ui.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                profile?.serviceName?.takeIf { it.isNotBlank() }?.let {
                    InfoRow(Icons.Filled.LocalTaxi, tr("Служба","Xizmat"), it)
                }
                profile?.cityName?.takeIf { it.isNotBlank() }?.let {
                    InfoRow(Icons.Filled.LocationCity, tr("Город","Shahar"), it)
                }
                profile?.carClassName?.takeIf { it.isNotBlank() }?.let {
                    InfoRow(Icons.Filled.DirectionsCar, tr("Класс","Toifa"), it)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Surface(color = Ui.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Информация","Ma'lumot"), color = Ui.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Приложение для водителей службы такси. Берите заказы, ведите таксометр и держите связь с диспетчером.",
                       "Taksi xizmati haydovchilari uchun dastur. Buyurtma oling, taksometrdan foydalaning va dispetcher bilan aloqada bo'ling."),
                    color = Gray, fontSize = 14.sp, lineHeight = 20.sp
                )
                Text(
                    tr("По вопросам работы обращайтесь к своему диспетчеру.",
                       "Ish bo'yicha savollar bilan o'z dispetcheringizga murojaat qiling."),
                    color = Gray, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = SkatBlue)
        Spacer(Modifier.width(12.dp))
        Text(label, color = Gray, fontSize = 14.sp, modifier = Modifier.width(96.dp))
        Text(value, color = Ui.OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ───────────────────────── журнал ─────────────────────────

@Composable
private fun LogView(vm: MainViewModel) {
    val lines by vm.ioLog.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${tr("Обмен с сервером","Server almashinuvi")} (${lines.size})", color = Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("scatlog", lines.joinToString("\n")))
            }) { Text(tr("Копировать","Nusxalash")) }
            TextButton(onClick = { vm.clearLog() }) { Text(tr("Очистить","Tozalash")) }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B0F14)).padding(8.dp)
        ) {
            items(lines) { ln ->
                Text(
                    ln, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if (ln.startsWith(">>")) Color(0xFF7FB3FF) else Color(0xFF9AE6A0)
                )
            }
        }
    }
}
