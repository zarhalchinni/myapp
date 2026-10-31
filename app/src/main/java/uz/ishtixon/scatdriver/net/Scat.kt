package uz.ishtixon.scatdriver.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Глобальный держатель соединения, чтобы его разделяли сервис и UI. */
object Scat {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var conn: ScatConnection? = null
        private set

    fun connect(cfg: ScatConnection.Config): ScatConnection {
        // Не пересоздаём живое соединение к тому же серверу/логину — иначе появляются
        // два экземпляра, и опрос/нажатия уходят в мёртвый (out=null).
        conn?.let { if (it.isRunning && it.sameTarget(cfg)) return it }
        conn?.stop()
        val c = ScatConnection(cfg, scope)
        conn = c
        c.start()
        return c
    }

    fun disconnect() {
        conn?.stop()
        conn = null
    }
}
