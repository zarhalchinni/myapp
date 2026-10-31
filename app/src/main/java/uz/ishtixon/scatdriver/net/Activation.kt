package uz.ishtixon.scatdriver.net

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Погашение кода подключения как штатный клиент SCAT:
 *   GET http://txa.su/<код>  -> 302 -> http://<сервер>/cars/get_settings/<uuid> -> base64(шифр)
 * Расшифровка (из APK, r7.o$a): AES-256-CBC, ключ = SHA256("E9zFp4SCFPY9-MAuyC"),
 *   IV = первые 16 байт декодированного base64, остальное — шифртекст (PKCS5).
 * Внутри JSON: {server,port,login,password,rserver,rport,hide}. Своего бэкенда НЕ требует.
 */
object Activation {

    data class Creds(
        val host: String, val port: Int,
        val login: String, val password: String,
        val reserveHost: String = "", val reservePort: Int = 0
    )

    private const val BASE = "http://txa.su/"
    private const val SECRET = "E9zFp4SCFPY9-MAuyC"

    suspend fun activate(code: String): Result<Creds> = withContext(Dispatchers.IO) {
        try {
            val c = extractCode(code)
            if (c.isBlank()) return@withContext Result.failure(Exception("Пустой код"))

            // QR может содержать сам зашифрованный блоб настроек -> расшифровать без сети
            if (c.length > 40) runCatching { decrypt(c) }.getOrNull()?.let { j ->
                if (j.contains("\"server\"")) return@withContext creds(j)
            }

            // иначе это код: txa.su редиректит на get_settings и отдаёт блоб
            var blob = fetch(BASE + c)
            // фолбэк: если редирект не сошёлся и пришёл HTML <a href="...get_settings...">Found</a>
            if (blob.contains("href=\"", true)) {
                val target = Regex("""href="([^"]+)"""").find(blob)?.groupValues?.get(1)
                if (target != null) blob = fetch(target)
            }
            if (blob.isBlank()) return@withContext Result.failure(Exception("Пустой ответ (код просрочен?)"))
            creds(decrypt(blob))
        } catch (e: Exception) {
            Log.w("Activation", "fail: ${e.message}")
            Result.failure(e)
        }
    }

    private fun creds(json: String): Result<Creds> = runCatching {
        Log.d("Activation", "settings: $json")
        val o = JSONObject(json)
        Creds(
            host = o.getString("server"),
            port = o.optString("port", "1050").toIntOrNull() ?: 1050,
            login = o.getString("login"),
            password = o.getString("password"),
            reserveHost = o.optString("rserver", ""),
            reservePort = o.optString("rport", "0").toIntOrNull() ?: 0
        )
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = true
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }

    /** AES-256-CBC расшифровка settings-блоба в JSON-строку. */
    private fun decrypt(base64Blob: String): String {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(SECRET.toByteArray(Charsets.UTF_8)), "AES")
        val data = Base64.decode(base64Blob, Base64.DEFAULT)
        val iv = IvParameterSpec(data.copyOfRange(0, 16))
        val ct = data.copyOfRange(16, data.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** Код из QR: raw-строка, ссылка txa.su/<код> или ?code=. */
    fun extractCode(raw: String): String {
        val s = raw.trim()
        Regex("""txa\.su/([^/?\s]+)""").find(s)?.let { return it.groupValues[1] }
        Regex("""[?&]code=([^&\s]+)""").find(s)?.let { return it.groupValues[1] }
        return s
    }
}
