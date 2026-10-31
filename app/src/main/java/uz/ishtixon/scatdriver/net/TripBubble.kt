package uz.ishtixon.scatdriver.net

import kotlinx.coroutines.flow.MutableStateFlow

/** Состояние активной поездки для плавающего кружка (логотип + сумма) поверх других приложений. */
object TripBubble {
    val active = MutableStateFlow(false)   // идёт поездка (таксометр включён)
    val fare = MutableStateFlow(0)         // текущая сумма, сўм
}
