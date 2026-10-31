package uz.ishtixon.scatdriver.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Палитра приложения. Значения — `var`: при переключении темы мы меняем их,
 * а дерево целиком перерисовывается (key(dark) в MainActivity), поэтому все экраны
 * подхватывают новые цвета без правок в каждом месте.
 * ВАЖНО: кнопки ActionBtn/SquareBtn рисуют белый текст/иконки на [Btn] — поэтому [Btn]
 * остаётся тёмным и в светлой теме, иначе текст стал бы невидимым.
 */
object Ui {
    var Bg = Color(0xFF11161D)
    var Surface = Color(0xFF1A212B)
    var SurfaceHi = Color(0xFF232C38)
    var Outline = Color(0xFF2E3846)

    var OnSurface = Color(0xFFE8EDF3)
    var Muted = Color(0xFF93A1B1)

    var Accent = Color(0xFFFFB066)     // оранжевый (адреса, таймеры, суммы)
    var Teal = Color(0xFF6FD3C4)       // ссылки, активные элементы
    var Warn = Color(0xFFF28C28)       // «Отказаться»
    var Lime = Color(0xFF5FAE3E)       // «Старт», «Стоп», «На месте»
    var Btn = Color(0xFF2E3B4B)        // нейтральные кнопки (белый текст — держим тёмным)
    var Info = Color(0xFF3A4C6B)       // плашка тарифа (белый текст)

    var On = Color(0xFF2FA84F)         // индикатор «в сети / есть GPS»
    var Off = Color(0xFF5B6570)        // индикатор «нет»
    var Danger = Color(0xFFFF8A80)
    var PauseBg = Color(0xFF2A1518)    // фон окна заказа на паузе
}

/** Тёмная палитра (как было). */
private fun applyDark() {
    Ui.Bg = Color(0xFF11161D); Ui.Surface = Color(0xFF1A212B); Ui.SurfaceHi = Color(0xFF232C38)
    Ui.Outline = Color(0xFF2E3846); Ui.OnSurface = Color(0xFFE8EDF3); Ui.Muted = Color(0xFF93A1B1)
    Ui.Accent = Color(0xFFFFB066); Ui.Teal = Color(0xFF6FD3C4); Ui.Warn = Color(0xFFF28C28)
    Ui.Lime = Color(0xFF5FAE3E); Ui.Btn = Color(0xFF2E3B4B); Ui.Info = Color(0xFF3A4C6B)
    Ui.On = Color(0xFF2FA84F); Ui.Off = Color(0xFF5B6570); Ui.Danger = Color(0xFFFF8A80)
    Ui.PauseBg = Color(0xFF2A1518)
}

/** Светлая палитра. Кнопочные цвета остаются насыщёнными/тёмными (на них белый текст). */
private fun applyLight() {
    Ui.Bg = Color(0xFFEEF1F5); Ui.Surface = Color(0xFFFFFFFF); Ui.SurfaceHi = Color(0xFFE6EBF1)
    Ui.Outline = Color(0xFFD2DAE3); Ui.OnSurface = Color(0xFF16202B); Ui.Muted = Color(0xFF5E6B79)
    Ui.Accent = Color(0xFFE0781A); Ui.Teal = Color(0xFF128C7E); Ui.Warn = Color(0xFFE0781A)
    Ui.Lime = Color(0xFF4E9E30); Ui.Btn = Color(0xFF3C4A5A); Ui.Info = Color(0xFF3A4C6B)
    Ui.On = Color(0xFF2FA84F); Ui.Off = Color(0xFFAAB4BE); Ui.Danger = Color(0xFFD64541)
    Ui.PauseBg = Color(0xFFFFE3E0)
}

private fun scheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Ui.Teal, onPrimary = Color(0xFF00201C), primaryContainer = Color(0xFF1F4D47),
    onPrimaryContainer = Color(0xFFBDF3EA), secondary = Ui.Accent, onSecondary = Color(0xFF3A2200),
    background = Ui.Bg, onBackground = Ui.OnSurface, surface = Ui.Surface, onSurface = Ui.OnSurface,
    surfaceVariant = Ui.SurfaceHi, onSurfaceVariant = Ui.Muted,
    surfaceContainerLowest = Ui.Bg, surfaceContainerLow = Ui.Bg, surfaceContainer = Ui.Surface,
    surfaceContainerHigh = Ui.SurfaceHi, surfaceContainerHighest = Ui.SurfaceHi,
    outline = Ui.Outline, outlineVariant = Ui.Outline, error = Ui.Danger, onError = Color(0xFF3B0A07)
) else lightColorScheme(
    primary = Ui.Teal, onPrimary = Color.White, primaryContainer = Color(0xFFBDF3EA),
    onPrimaryContainer = Color(0xFF00201C), secondary = Ui.Accent, onSecondary = Color.White,
    background = Ui.Bg, onBackground = Ui.OnSurface, surface = Ui.Surface, onSurface = Ui.OnSurface,
    surfaceVariant = Ui.SurfaceHi, onSurfaceVariant = Ui.Muted,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Ui.Bg, surfaceContainer = Ui.Surface,
    surfaceContainerHigh = Ui.SurfaceHi, surfaceContainerHighest = Ui.SurfaceHi,
    outline = Ui.Outline, outlineVariant = Ui.Outline, error = Ui.Danger, onError = Color.White
)

private val ScatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ScatTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    // применяем палитру ДО композиции детей
    if (dark) applyDark() else applyLight()
    // Крупный системный шрифт на больших экранах ломал вёрстку — ограничиваем масштаб шрифта до 1.0
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.0f))) {
        MaterialTheme(colorScheme = scheme(dark), shapes = ScatShapes, content = content)
    }
}
