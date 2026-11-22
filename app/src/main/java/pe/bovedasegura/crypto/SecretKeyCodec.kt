package pe.bovedasegura.crypto

/**
 * Clave secreta de 128 bits, mostrada como BSP-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX (base32).
 * Se imprime en el Kit de emergencia y nunca viaja dentro de los respaldos.
 */
object SecretKeyCodec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(): ByteArray = Crypto.random(16)

    fun format(key: ByteArray): String {
        val b32 = base32(key) // 26 caracteres
        val groups = listOf(b32.substring(0, 5), b32.substring(5, 10), b32.substring(10, 15), b32.substring(15, 20), b32.substring(20))
        return "BSP-" + groups.joinToString("-")
    }

    /** Acepta el texto con o sin guiones/espacios. Devuelve null si no es válido. */
    fun parse(text: String): ByteArray? {
        var t = text.uppercase().filter { it.isLetterOrDigit() }
        if (t.startsWith("BSP")) t = t.substring(3)
        if (t.length != 26) return null
        t = t.replace('0', 'O').replace('1', 'I').replace('8', 'B')
        val out = base32Decode(t) ?: return null
        return if (out.size >= 16) out.copyOf(16) else null
    }

    fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun base32Decode(s: String): ByteArray? {
        val clean = s.uppercase().filter { it != '=' && !it.isWhitespace() && it != '-' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
