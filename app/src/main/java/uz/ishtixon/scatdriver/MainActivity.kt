package uz.ishtixon.scatdriver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uz.ishtixon.scatdriver.net.ScatConnection
import uz.ishtixon.scatdriver.net.ScatService
import uz.ishtixon.scatdriver.ui.HomeScreen
import uz.ishtixon.scatdriver.ui.LoginScreen
import uz.ishtixon.scatdriver.ui.MainViewModel
import uz.ishtixon.scatdriver.ui.ScatTheme
import uz.ishtixon.scatdriver.ui.Ui

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            vm.onLocation(loc.latitude, loc.longitude, loc.speed.toDouble(), loc.bearing.toDouble(), isMock(loc))
        }
        override fun onProviderEnabled(p: String) {
            // GPS включили во время работы — сразу переподписываемся и берём последнюю точку,
            // чтобы индикатор позеленел без перезапуска приложения
            checkGpsEnabled(); startLocation()
        }
        override fun onProviderDisabled(p: String) { checkGpsEnabled() }
        @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    private fun checkGpsEnabled() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val on = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        vm.gpsEnabled.value = on
    }

    private fun isMock(loc: Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider

    private var overlayAsked = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startLocation(); requestBackgroundLocation(); requestOverlay() }

    /** Сразу при входе просим разрешение «поверх других окон» (для плавающего счётчика). */
    private fun requestOverlay() {
        if (overlayAsked) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            overlayAsked = true
            runCatching {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
    }

    // фоновое разрешение запрашивается ОТДЕЛЬНО и только после обычного (требование Android 10+)
    private val bgPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* пользователь выбрал «Разрешить всегда» или нет — работаем в любом случае */ }

    private fun requestBackgroundLocation() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED && bg != PackageManager.PERMISSION_GRANTED) {
            runCatching { bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // создаём канал заказов заранее (как штатный), importance HIGH → «всплывающие» сразу после установки
        uz.ishtixon.scatdriver.net.OrderAlert.ensureChannel(this)

        // показывать окно заказа поверх блокировки и будить экран (как у штатного)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        lifecycleScope.launch { vm.toast.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() } }
        lifecycleScope.launch {
            vm.state.collect { if (it == ScatConnection.State.LOGGED_IN) ScatService.start(this@MainActivity) }
        }

        // запрос разрешений ведёт окно PermissionsGate (при первом запуске), см. setContent.
        // если разрешения уже выданы (повторный запуск) — сразу запускаем геолокацию.
        if (uz.ishtixon.scatdriver.ui.requiredPermsGranted(this)) {
            startLocation(); requestBackgroundLocation()
        }

        // при старте поездки просим разрешение «поверх других окон» (для плавающего кружка)
        lifecycleScope.launch {
            uz.ishtixon.scatdriver.net.TripBubble.active.collect { on ->
                if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity,
                        "Разрешите «Поверх других приложений» для плавающего счётчика", Toast.LENGTH_LONG).show()
                    runCatching {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")))
                    }
                }
            }
        }

        setContent {
            val dark by vm.darkTheme.collectAsState()
            val lang by vm.lang.collectAsState()
            uz.ishtixon.scatdriver.ui.Lang.current = lang
            // иконки системной строки состояния: светлые на тёмной теме, тёмные на светлой
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !dark
            }
            ScatTheme(dark) {
                Surface(color = Ui.Bg) {
                    // key(dark, lang): при смене темы/языка дерево пересобирается
                    androidx.compose.runtime.key(dark, lang) {
                        val state by vm.state.collectAsState()
                        val banned by vm.bannedApp.collectAsState()
                        val b = banned
                        // окно разрешений при первом запуске (до ввода кода)
                        var permsDone by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(
                                uz.ishtixon.scatdriver.ui.requiredPermsGranted(this@MainActivity))
                        }
                        if (b != null)
                            uz.ishtixon.scatdriver.ui.BannedScreen(b) { vm.recheckBanned() }
                        else if (!permsDone && !vm.hasSession &&
                                 state == ScatConnection.State.DISCONNECTED)
                            uz.ishtixon.scatdriver.ui.PermissionsGate(onDone = {
                                permsDone = true
                                startLocation(); requestBackgroundLocation(); requestOverlay()
                            })
                        else if (state != ScatConnection.State.DISCONNECTED || vm.hasSession)
                            HomeScreen(vm, onExit = { exitApp() })
                        else
                            LoginScreen(vm)
                    }
                }
            }
        }
    }

    /**
     * «Выход» как в штатном клиенте: уйти с линии ($EXIT), закрыть соединение, остановить сервисы и
     * закрыть приложение. Сохранённый логин остаётся — при следующем запуске вход автоматический,
     * а активный заказ сервер вернёт сам (ORDCH после логина).
     */
    private fun exitApp() {
        uz.ishtixon.scatdriver.net.TripBubble.active.value = false   // не показывать плавающий кружок при закрытии
        val app = applicationContext
        uz.ishtixon.scatdriver.net.Scat.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // EXIT уходит в очередь записи; stop() ждёт её опустошения и только потом закрывает сокет
            uz.ishtixon.scatdriver.net.Scat.disconnect()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { ScatService.stop(app) }
                runCatching { uz.ishtixon.scatdriver.net.FloatingMeterService.stop(app) }
                finishAndRemoveTask()
            }
            android.os.Process.killProcess(android.os.Process.myPid())   // чистое состояние при следующем запуске
        }
    }

    private fun requestLocationPerms() {
        // одно окно запрашивает все нужные разрешения (геолокация, камера, телефон, уведомления)
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) { startLocation(); requestBackgroundLocation(); requestOverlay() }
        else permLauncher.launch(need.toTypedArray())
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            vm.gpsNote("Нет разрешения на геолокацию"); return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            runCatching { lm.removeUpdates(locListener) }   // снять прежние подписки — без дублей
            // последняя известная позиция — сразу, из любого провайдера
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            last?.let { vm.onLocation(it.latitude, it.longitude, it.speed.toDouble(), it.bearing.toDouble(), isMock(it)) }
            // Подписываемся на ОБА провайдера ВСЕГДА, даже если сейчас выключены:
            // когда пользователь включит GPS, слушатель получит onProviderEnabled и пойдут фиксы
            // (без этого индикатор оставался красным до перезапуска).
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching { lm.requestLocationUpdates(prov, 2000L, 0f, locListener) }
            }
            val anyOn = runCatching {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            if (!anyOn) vm.gpsNote("Включите геолокацию (GPS выключен)")
        } catch (_: SecurityException) {
            vm.gpsNote("Ошибка доступа к геолокации")
        }
        checkGpsEnabled()
    }

    override fun onStart() {
        super.onStart()
        // окно на экране — убираем кружок и полноэкранное уведомление о заказе
        uz.ishtixon.scatdriver.net.AppState.resumed = true
        uz.ishtixon.scatdriver.net.FloatingMeterService.stop(this)
        uz.ishtixon.scatdriver.net.OrderOverlayService.stop(this)   // окно заказа показываем внутри приложения
        vm.cancelOrderAlert()
        checkGpsEnabled()
    }

    override fun onStop() {
        super.onStop()
        uz.ishtixon.scatdriver.net.AppState.resumed = false
        // приложение свёрнуто: во время поездки показываем плавающий кружок
        if (uz.ishtixon.scatdriver.net.TripBubble.active.value &&
            android.provider.Settings.canDrawOverlays(this)) {
            uz.ishtixon.scatdriver.net.FloatingMeterService.start(this)
        }
    }

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        }
        super.onDestroy()
    }
}
