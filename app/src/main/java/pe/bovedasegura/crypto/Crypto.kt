package pe.bovedasegura.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitivas criptográficas. Todo lo sensible usa AES-256-GCM con nonce aleatorio. */
object Crypto {
    val rng = SecureRandom()

    const val ARGON_MEM_KB = 32768
    const val ARGON_ITER = 3

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** Argon2id(contraseña, sal) -> 32 bytes. */
    fun argon2id(password: CharArray, salt: ByteArray, memKb: Int = ARGON_MEM_KB, iter: Int = ARGON_ITER): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iter)
            .withMemoryAsKB(memKb)
            .withParallelism(1)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(password, out)
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869) con salida de 32 bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info.toByteArray() + byteArrayOf(1))
    }

    /**
     * Clave de cifrado de llaves (KEK): combina contraseña maestra (Argon2id) con la
     * clave secreta del dispositivo. Sin ambas no se puede abrir la bóveda ni sus respaldos.
     */
    fun deriveKek(password: CharArray, salt: ByteArray, secretKey: ByteArray, purpose: String, memKb: Int = ARGON_MEM_KB, iter: Int = ARGON_ITER): ByteArray {
        val a = argon2id(password, salt, memKb, iter)
        val k = hkdf(a, secretKey, "BovedaSegura/v1/$purpose")
        a.fill(0)
        return k
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Cifra: devuelve nonce(12) || ciphertext+tag. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** Descifra el formato de [seal]. Lanza excepción si la llave es incorrecta o hubo manipulación. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        if (sealed.size < 28) throw AEADBadTagException("datos cortos")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    fun tryOpen(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? =
        try { open(key, sealed, aad) } catch (e: Exception) { null }

    // ---------- Cifrado por bloques para archivos grandes ----------
    private const val CHUNK = 64 * 1024
    private val STREAM_MAGIC = "BSC1".toByteArray()

    /**
     * Cifra un flujo en bloques de 64 KB. Cada bloque usa AES-GCM con nonce = base(8)||contador(4)
     * y lleva en el AAD si es el último, para detectar truncamiento.
     */
    fun encryptStream(key: ByteArray, input: InputStream, output: OutputStream): Long {
        val base = random(8)
        output.write(STREAM_MAGIC)
        output.write(base)
        val buf = ByteArray(CHUNK)
        var counter = 0
        var total = 0L
        var pending = readFully(input, buf)
        var pendingBytes = buf.copyOf(pending)
        while (true) {
            val nextBuf = ByteArray(CHUNK)
            val nextLen = if (pending == CHUNK) readFully(input, nextBuf) else 0
            val last = nextLen <= 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, counter)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            val ct = c.doFinal(pendingBytes)
            output.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            output.write(ct)
            total += pendingBytes.size
            counter++
            if (last) break
            pending = nextLen
            pendingBytes = nextBuf.copyOf(nextLen)
        }
        output.flush()
        return total
    }

    fun decryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
        val inp = java.io.PushbackInputStream(input, 1)
        val magic = ByteArray(4)
        if (readFully(inp, magic) != 4 || !magic.contentEquals(STREAM_MAGIC)) throw IllegalStateException("Formato inválido")
        val base = ByteArray(8)
        if (readFully(inp, base) != 8) throw IllegalStateException("Formato inválido")
        var counter = 0
        while (true) {
            val lenBuf = ByteArray(4)
            val r = readFully(inp, lenBuf)
            if (r != 4) throw IllegalStateException("Archivo truncado")
            val len = ByteBuffer.wrap(lenBuf).int
            if (len < 16 || len > CHUNK + 16) throw IllegalStateException("Bloque inválido")
            val ct = ByteArray(len)
            if (readFully(inp, ct) != len) throw IllegalStateException("Archivo truncado")
            val peek = inp.read()
            val last = peek == -1
            if (!last) inp.unread(peek)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, counter)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            output.write(c.doFinal(ct))
            counter++
            if (last) break
        }
        output.flush()
    }

    private fun nonceFor(base: ByteArray, counter: Int): ByteArray =
        base + ByteBuffer.allocate(4).putInt(counter).array()

    fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
