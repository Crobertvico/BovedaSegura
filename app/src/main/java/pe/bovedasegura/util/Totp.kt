package pe.bovedasegura.util

import android.net.Uri
import pe.bovedasegura.crypto.SecretKeyCodec
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpSpec(val secret: ByteArray, val digits: Int = 6, val period: Int = 30, val algorithm: String = "SHA1", val issuer: String = "", val account: String = "")

/** Códigos 2FA (RFC 6238). Acepta una URI otpauth:// o el secreto base32. */
object Totp {
    fun parse(input: String): TotpSpec? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("otpauth://", ignoreCase = true)) {
            return try {
                val u = Uri.parse(t)
                val secret = SecretKeyCodec.base32Decode(u.getQueryParameter("secret") ?: return null) ?: return null
                if (secret.isEmpty()) return null
                val label = Uri.decode(u.path?.removePrefix("/") ?: "")
                TotpSpec(
                    secret = secret,
                    digits = u.getQueryParameter("digits")?.toIntOrNull() ?: 6,
                    period = u.getQueryParameter("period")?.toIntOrNull() ?: 30,
                    algorithm = (u.getQueryParameter("algorithm") ?: "SHA1").uppercase(),
                    issuer = u.getQueryParameter("issuer") ?: label.substringBefore(':', ""),
                    account = label.substringAfter(':')
                )
            } catch (e: Exception) { null }
        }
        val secret = SecretKeyCodec.base32Decode(t.replace(" ", "")) ?: return null
        return if (secret.size >= 5) TotpSpec(secret) else null
    }

    fun code(spec: TotpSpec, timeMs: Long = System.currentTimeMillis()): String {
        val counter = timeMs / 1000 / spec.period
        val alg = when (spec.algorithm) { "SHA256" -> "HmacSHA256"; "SHA512" -> "HmacSHA512"; else -> "HmacSHA1" }
        val mac = Mac.getInstance(alg)
        mac.init(SecretKeySpec(spec.secret, alg))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xf
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1
        repeat(spec.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(spec.digits, '0')
    }

    fun secondsLeft(spec: TotpSpec, timeMs: Long = System.currentTimeMillis()): Int =
        spec.period - ((timeMs / 1000) % spec.period).toInt()

    fun codeFor(input: String): String? = parse(input)?.let { code(it) }
}
