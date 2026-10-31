package uz.ishtixon.scatdriver.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Язык интерфейса. [current] — реактивное состояние: при смене языка дерево
 * пересобирается (key(lang) в MainActivity), и все вызовы [tr] возвращают новый перевод.
 * По умолчанию — узбекский ("uz").
 */
object Lang {
    var current by mutableStateOf("uz")
}

/** Возвращает узбекский текст, если выбран uz, иначе русский. */
fun tr(ru: String, uz: String): String = if (Lang.current == "uz") uz else ru
