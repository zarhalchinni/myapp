package uz.ishtixon.scatdriver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import uz.ishtixon.scatdriver.model.ActiveOrder
import uz.ishtixon.scatdriver.model.OrderStatus
import uz.ishtixon.scatdriver.net.ScatConnection

/** Нужно ли показывать окно разрешений: не выданы обязательные (геолокация, камера, уведомления). */
fun requiredPermsGranted(ctx: android.content.Context): Boolean {
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var r = ok(android.Manifest.permission.ACCESS_FINE_LOCATION) && ok(android.Manifest.permission.CAMERA)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        r = r && ok(android.Manifest.permission.POST_NOTIFICATIONS)
    return r
}

private enum class PKind { RUNTIME, BACKGROUND, OVERLAY, BATTERY }

private data class PermItem(
    val perm: String?,          // runtime-разрешение (для RUNTIME/BACKGROUND)
    val kind: PKind,
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val required: Boolean       // без него вход закрыт
)

/**
 * Окно разрешений при первом запуске: видно, что выдано, а что нет; снизу кнопка «Готово».
 * Если на «Готово» остались невыданные обязательные — спрашиваем их снова (перед вводом кода).
 */
@Composable
fun PermissionsGate(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? android.app.Activity
    var refresh by remember { mutableStateOf(0) }
    val asked = remember { mutableStateListOf<String>() }

    // перепроверяем статусы при возврате в приложение (из системных настроек)
    val owner = ctx as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val runtimeLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    fun overlayOk() = android.provider.Settings.canDrawOverlays(ctx)
    fun batteryOk(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    }
    fun openAppSettings() {
        runCatching {
            settingsLauncher.launch(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${ctx.packageName}")))
        }
    }
    fun openOverlaySettings() {
        runCatching {
            settingsLauncher.launch(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")))
        }
    }
    @android.annotation.SuppressLint("BatteryLife")
    fun requestBattery() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        runCatching {
            settingsLauncher.launch(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")))
        }
    }

    refresh   // чтение для пересборки при изменении статусов

    val sdk = android.os.Build.VERSION.SDK_INT
    val permItems = buildList {
        add(PermItem(android.Manifest.permission.ACCESS_FINE_LOCATION, PKind.RUNTIME, Icons.Filled.LocationOn,
            tr("Геолокация","Joylashuv"),
            tr("Для приёма заказов и работы таксометра","Buyurtma va taksometr uchun"), true))
        // фоновая геолокация — чтобы GPS работал «всегда», а не только при использовании (как у штатного)
        if (sdk >= android.os.Build.VERSION_CODES.Q)
            add(PermItem(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, PKind.BACKGROUND, Icons.Filled.MyLocation,
                tr("Геолокация в фоне","Fonda joylashuv"),
                tr("Выберите «Разрешить всегда» — GPS в любом режиме","«Doimo ruxsat» tanlang — har qanday rejimda GPS"), false))
        add(PermItem(android.Manifest.permission.CAMERA, PKind.RUNTIME, Icons.Filled.CameraAlt,
            tr("Камера","Kamera"),
            tr("Для сканирования QR-кода настроек","Sozlamalar QR-kodini skanerlash uchun"), true))
        if (sdk >= android.os.Build.VERSION_CODES.TIRAMISU)
            add(PermItem(android.Manifest.permission.POST_NOTIFICATIONS, PKind.RUNTIME, Icons.Filled.Notifications,
                tr("Уведомления","Bildirishnomalar"),
                tr("Статус работы и входящие заказы","Ish holati va kiruvchi buyurtmalar"), true))
        // отключить экономию батареи — чтобы система не выгружала приложение в фоне
        if (sdk >= android.os.Build.VERSION_CODES.M)
            add(PermItem(null, PKind.BATTERY, Icons.Filled.BatteryChargingFull,
                tr("Работа в фоне","Fonda ishlash"),
                tr("Отключите экономию батареи, чтобы заказы не терялись","Buyurtmalar yo'qolmasligi uchun batareya tejashni o'chiring"), false))
        add(PermItem(android.Manifest.permission.READ_PHONE_STATE, PKind.RUNTIME, Icons.Filled.PhoneAndroid,
            tr("Телефон","Telefon"),
            tr("Идентификация устройства","Qurilmani aniqlash"), false))
        add(PermItem(null, PKind.OVERLAY, Icons.Filled.Layers,
            tr("Поверх других окон","Boshqa oynalar ustida"),
            tr("Для плавающего счётчика и окна заказа","Suzuvchi hisoblagich va buyurtma oynasi uchun"), false))
    }
    fun isOk(p: PermItem) = when (p.kind) {
        PKind.OVERLAY -> overlayOk()
        PKind.BATTERY -> batteryOk()
        else -> granted(p.perm!!)
    }
    fun request(p: PermItem) {
        when (p.kind) {
            PKind.OVERLAY -> openOverlaySettings()
            PKind.BATTERY -> requestBattery()
            PKind.BACKGROUND -> {
                // фоновую дают только после обычной геолокации
                if (!granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    asked.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    runtimeLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    // системный запрос фоновой геолокации: на Android 11+ открывает именно
                    // страницу разрешения «Геолокация» с выбором «Разрешить всегда»
                    asked.add(p.perm!!)
                    runtimeLauncher.launch(arrayOf(p.perm))
                }
            }
            PKind.RUNTIME -> {
                val perm = p.perm!!
                if (perm in asked && activity != null &&
                    !activity.shouldShowRequestPermissionRationale(perm)) openAppSettings()
                else { asked.add(perm); runtimeLauncher.launch(arrayOf(perm)) }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 40.dp, bottom = 20.dp)
    ) {
        Text(tr("Разрешения","Ruxsatlar"),
            color = Ui.OnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(tr("Чтобы приложение работало корректно, выдайте доступ:",
                "Dastur to'g'ri ishlashi uchun ruxsat bering:"),
            color = Ui.Muted, fontSize = 15.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(permItems) { p ->
                val ok = isOk(p)
                Surface(color = Ui.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(p.icon, null, Modifier.size(26.dp),
                            tint = if (ok) Ui.Lime else Ui.Muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(p.title, color = Ui.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (!p.required) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("(не обяз.)","(ixtiyoriy)"), color = Ui.Muted, fontSize = 12.sp)
                                }
                            }
                            Text(p.desc, color = Ui.Muted, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (ok) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.Filled.CheckCircle, null,
                                    Modifier.size(22.dp), tint = Ui.Lime)
                            }
                        } else {
                            Button(onClick = { request(p) }, shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Ui.Btn, contentColor = Color.White)) {
                                Text(tr("Разрешить","Ruxsat"), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        val missingReq = permItems.filter { it.required && !isOk(it) }
        Spacer(Modifier.height(12.dp))
        if (missingReq.isNotEmpty()) {
            Text(
                tr("Остались обязательные разрешения — нажмите «Выдать», выдайте каждое по очереди.",
                   "Majburiy ruxsatlar qoldi — «Berish» bosib, har birini navbat bilan bering."),
                color = Ui.Muted, fontSize = 13.sp, lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                // запрашиваем по одному обязательному — так корректно для фона/батареи/настроек
                val next = permItems.firstOrNull { it.required && !isOk(it) }
                if (next == null) onDone() else request(next)
            },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ui.Lime, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text(if (missingReq.isEmpty()) tr("Готово","Tayyor") else tr("Выдать разрешения","Ruxsat berish"),
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

/** Экран блокировки: обнаружено запрещённое приложение (как у штатного). Вход закрыт. */
@Composable
fun BannedScreen(pkg: String, onRecheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.Block, null,
            Modifier.size(72.dp), tint = Color(0xFFD64541))
        Spacer(Modifier.height(20.dp))
        Text(tr("Доступ заблокирован", "Kirish bloklandi"),
            color = Ui.OnSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Обнаружено запрещённое приложение. Удалите его, чтобы продолжить:",
               "Taqiqlangan dastur aniqlandi. Davom etish uchun uni o'chiring:"),
            color = Ui.Muted, fontSize = 15.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(pkg, color = Color(0xFFD64541), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRecheck, shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ui.Lime, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text(tr("Проверить снова", "Qayta tekshirish"), fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: MainViewModel) {
    var showCode by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val activating by vm.activating.collectAsState()

    val scan = rememberLauncherForActivityResult(ScanContract()) { res ->
        res.contents?.let { vm.activate(it) }   // QR = настройки/код -> активация
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(uz.ishtixon.scatdriver.R.drawable.scat_logo),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("SKAT", color = Ui.OnSurface, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(40.dp))

        Button(onClick = { showCode = true }, enabled = !activating,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (activating) tr("Активация…","Faollashtirish…") else tr("Настроить по коду","Kod bo'yicha sozlash"), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            scan.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)   // только QR
                    .setOrientationLocked(true)                      // как у штатного — вертикально
                    .setBeepEnabled(true)
                    .setPrompt(tr("Наведите на QR-код","QR-kodga qarating"))
            )
        }, shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ui.Btn, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(tr("Сканировать настройки","Sozlamalarni skanerlash"), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showManual = true }, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(tr("Настроить вручную","Qo'lda sozlash"))
        }
    }

    if (showCode) {
        AlertDialog(
            onDismissRequest = { showCode = false },
            title = { Text(tr("Код подключения","Ulanish kodi")) },
            text = {
                OutlinedTextField(code, { code = it }, singleLine = true,
                    label = { Text(tr("Введите код","Kodni kiriting")) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = { showCode = false; vm.activate(code) }) { Text(tr("Войти","Kirish")) } },
            dismissButton = { TextButton(onClick = { showCode = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }
    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text(tr("Ручная настройка","Qo'lda sozlash")) },
            text = { ManualLogin(vm) },
            confirmButton = { TextButton(onClick = { showManual = false; vm.connect() }) { Text(tr("Подключиться","Ulanish")) } },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text(tr("Отмена","Bekor qilish")) } }
        )
    }
}

@Composable
private fun ManualLogin(vm: MainViewModel) {
    var host by remember { mutableStateOf(vm.host) }
    var port by remember { mutableStateOf(vm.port) }
    var login by remember { mutableStateOf(vm.login) }
    var password by remember { mutableStateOf(vm.password) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(host, { host = it; vm.host = it }, label = { Text(tr("Сервер","Server")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it; vm.port = it }, label = { Text(tr("Порт (1050)","Port (1050)")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(login, { login = it; vm.login = it }, label = { Text(tr("Логин","Login")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it; vm.password = it }, label = { Text(tr("Пароль","Parol")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
}
