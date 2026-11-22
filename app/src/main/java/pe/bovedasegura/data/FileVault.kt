package pe.bovedasegura.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import pe.bovedasegura.crypto.Crypto
import pe.bovedasegura.crypto.SecretKeyCodec
import pe.bovedasegura.util.TempFiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

@Serializable
data class SharedHeader(val name: String, val mime: String, val expires: Long, val readOnly: Boolean)

class ExpiredException : Exception("El archivo compartido ya venció")

/** Cofre de archivos: cada archivo se cifra con su propia llave, en bloques, dentro del almacenamiento privado. */
object FileVault {
    fun blob(meta: FileMeta) = File(Vault.blobDir(meta.group), "${meta.id}.bin")
    fun exists(meta: FileMeta) = blob(meta).exists()

    fun isImage(mime: String) = mime.startsWith("image/")
    fun isPdf(mime: String) = mime == "application/pdf"
    fun isWord(mime: String) = mime == "application/msword" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    fun isText(mime: String) = mime.startsWith("text/")

    data class UriInfo(val name: String, val mime: String, val size: Long)

    fun info(ctx: Context, uri: Uri): UriInfo {
        var name = "archivo"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        var mime = ctx.contentResolver.getType(uri) ?: guessMime(name)
        if (mime == "application/octet-stream") mime = guessMime(name)
        return UriInfo(name, mime, size)
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic", "heif" -> "image/heic"; "gif" -> "image/gif"
        "pdf" -> "application/pdf"; "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt" -> "text/plain"; "csv" -> "text/csv"
        "bsf" -> "application/x-bovedasegura"
        else -> "application/octet-stream"
    }

    /** ¿El contenido de este URI es un archivo compartido cifrado (.bsf)? */
    fun isShared(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val m = ByteArray(4); Crypto.readFully(s, m) == 4 && String(m) == "BSF1"
        } ?: false
    } catch (e: Exception) { false }

    suspend fun importUri(ctx: Context, uri: Uri, folder: String = "General"): FileMeta = withContext(Dispatchers.IO) {
        val i = info(ctx, uri)
        val kind = if (isImage(i.mime)) FileKind.IMAGE else FileKind.DOCUMENT
        val key = Crypto.random(32)
        val id = UUID.randomUUID().toString()
        val tmpMeta = FileMeta(id = id, name = i.name, mime = i.mime, size = 0, key = key.b64(), kind = kind, source = FileSource.IMPORT, folder = folder, created = System.currentTimeMillis())
        val target = blob(tmpMeta)
        val size = ctx.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(target).use { out -> Crypto.encryptStream(key, input, out).also { out.fd.sync() } }
        }
        val thumb = runCatching {
            when {
                kind == FileKind.IMAGE -> ctx.contentResolver.openInputStream(uri)?.use { thumbFromImage(it.readBytes()) }
                isPdf(i.mime) -> ctx.contentResolver.openFileDescriptor(uri, "r")?.use { thumbFromPdf(it) }
                else -> null
            }
        }.getOrNull()
        val meta = tmpMeta.copy(size = size, thumb = thumb)
        Vault.update { d -> d.copy(files = listOf(meta) + d.files) }
        key.fill(0)
        meta
    }

    suspend fun importBytes(bytes: ByteArray, name: String, mime: String, source: FileSource, folder: String, itemId: String? = null): FileMeta = withContext(Dispatchers.IO) {
        val kind = if (isImage(mime)) FileKind.IMAGE else FileKind.DOCUMENT
        val key = Crypto.random(32)
        val meta0 = FileMeta(id = UUID.randomUUID().toString(), name = name, mime = mime, size = bytes.size.toLong(), key = key.b64(), kind = kind, source = source, folder = folder, itemId = itemId, created = System.currentTimeMillis())
        FileOutputStream(blob(meta0)).use { out -> Crypto.encryptStream(key, ByteArrayInputStream(bytes), out); out.fd.sync() }
        val thumb = runCatching { if (kind == FileKind.IMAGE) thumbFromImage(bytes) else null }.getOrNull()
        val meta = meta0.copy(thumb = thumb)
        Vault.update { d -> d.copy(files = listOf(meta) + d.files) }
        key.fill(0)
        meta
    }

    fun decryptTo(meta: FileMeta, out: java.io.OutputStream) {
        blob(meta).inputStream().buffered().use { Crypto.decryptStream(meta.key.unb64(), it, out) }
    }

    fun decryptBytes(meta: FileMeta): ByteArray = ByteArrayOutputStream().also { decryptTo(meta, it) }.toByteArray()

    /** Decodifica una imagen en memoria, reducida a [maxDim] px. Nunca se escribe en disco. */
    fun decodeImage(bytes: ByteArray, maxDim: Int = 2560): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > maxDim || o.outHeight / sample > maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun thumbFromImage(bytes: ByteArray): String? {
        val bmp = decodeImage(bytes, 400) ?: return null
        return encodeThumb(bmp)
    }

    private fun thumbFromPdf(pfd: ParcelFileDescriptor): String? {
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return null
            r.openPage(0).use { p ->
                val w = 240
                val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return encodeThumb(bmp)
            }
        }
    }

    private fun encodeThumb(src: Bitmap): String {
        val scale = 240f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray().b64()
    }

    /**
     * Abre un PDF descifrado para PdfRenderer. En Android 11+ usa memoria anónima (memfd), sin tocar el disco;
     * en versiones anteriores usa un temporal en caché que se borra al cerrar.
     */
    fun openPdfDescriptor(ctx: Context, meta: FileMeta): Pair<ParcelFileDescriptor, File?> {
        val bytes = decryptBytes(meta)
        if (Build.VERSION.SDK_INT >= 30) {
            val fd = Os.memfd_create("pdf", 0)
            FileOutputStream(fd).let { it.write(bytes); it.flush() }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val pfd = ParcelFileDescriptor.dup(fd)
            Os.close(fd)
            bytes.fill(0)
            return pfd to null
        }
        val tmp = File(TempFiles.dir(ctx, "pdf"), UUID.randomUUID().toString())
        tmp.writeBytes(bytes)
        bytes.fill(0)
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY) to tmp
    }

    /** Copia temporal descifrada para abrir con otra app. Se borra al volver a Bóveda Segura. */
    fun tempForExternal(ctx: Context, meta: FileMeta): File {
        val dir = File(TempFiles.dir(ctx, "open"), UUID.randomUUID().toString()).apply { mkdirs() }
        val f = File(dir, meta.name)
        FileOutputStream(f).use { decryptTo(meta, it) }
        return f
    }

    fun exportTo(ctx: Context, meta: FileMeta, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "w")!!.use { decryptTo(meta, it) }
    }

    fun trash(id: String) = Vault.update { d -> d.copy(files = d.files.map { if (it.id == id) it.copy(deletedAt = System.currentTimeMillis()) else it }) }
    fun restore(id: String) = Vault.update { d -> d.copy(files = d.files.map { if (it.id == id) it.copy(deletedAt = null) else it }) }
    fun deleteForever(meta: FileMeta) {
        blob(meta).delete()
        Vault.update { d -> d.copy(files = d.files.filter { it.id != meta.id }) }
    }
    fun updateMeta(meta: FileMeta) = Vault.update { d -> d.copy(files = d.files.map { if (it.id == meta.id) meta else it }) }

    /** Intenta borrar el archivo original. Devuelve true si el proveedor lo permitió. */
    fun deleteOriginal(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (e2: Exception) { false }
    }

    // ------------------------------------------------------------------ compartir cifrado (.bsf)

    fun newShareCode(): String {
        val s = SecretKeyCodec.base32(Crypto.random(6)).take(8)
        return s.substring(0, 4) + "-" + s.substring(4)
    }

    private fun shareKey(code: String, salt: ByteArray): ByteArray {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }.toCharArray()
        return Crypto.argon2id(clean, salt, 32768, 3)
    }

    /** Genera un .bsf protegido con [code], que solo Bóveda Segura puede abrir antes de [expires]. */
    suspend fun createShared(ctx: Context, meta: FileMeta, code: String, expires: Long, readOnly: Boolean): File = withContext(Dispatchers.Default) {
        val salt = Crypto.random(16)
        val k = shareKey(code, salt)
        val hk = Crypto.hkdf(k, salt, "bsf-header")
        val dk = Crypto.hkdf(k, salt, "bsf-data")
        val header = Crypto.seal(hk, Vault.json.encodeToString(SharedHeader(meta.name, meta.mime, expires, readOnly)).toByteArray())
        val f = File(TempFiles.dir(ctx, "share"), meta.name.substringBeforeLast('.') + ".bsf")
        DataOutputStream(FileOutputStream(f).buffered()).use { out ->
            out.write("BSF1".toByteArray())
            out.write(salt)
            out.writeInt(header.size)
            out.write(header)
            val plain = ByteArrayOutputStream().also { decryptTo(meta, it) }.toByteArray()
            Crypto.encryptStream(dk, ByteArrayInputStream(plain), out)
            plain.fill(0)
        }
        k.fill(0)
        f
    }

    /** Importa un .bsf recibido. Lanza [ExpiredException] si venció o excepción si el código es incorrecto. */
    suspend fun importShared(ctx: Context, uri: Uri, code: String): FileMeta = withContext(Dispatchers.Default) {
        val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val inp = DataInputStream(ByteArrayInputStream(raw))
        val magic = ByteArray(4); inp.readFully(magic)
        require(String(magic) == "BSF1") { "No es un archivo de Bóveda Segura" }
        val salt = ByteArray(16); inp.readFully(salt)
        val hlen = inp.readInt()
        require(hlen in 28..10000) { "Archivo dañado" }
        val hbytes = ByteArray(hlen); inp.readFully(hbytes)
        val k = shareKey(code, salt)
        val header = Vault.json.decodeFromString<SharedHeader>(String(Crypto.open(Crypto.hkdf(k, salt, "bsf-header"), hbytes)))
        if (System.currentTimeMillis() > header.expires) throw ExpiredException()
        val out = ByteArrayOutputStream()
        Crypto.decryptStream(Crypto.hkdf(k, salt, "bsf-data"), inp, out)
        k.fill(0)
        val bytes = out.toByteArray()
        val m = importBytes(bytes, header.name, header.mime, FileSource.IMPORT, "Recibidos")
        bytes.fill(0)
        m
    }

    fun readAll(input: InputStream): ByteArray = input.readBytes()
}
