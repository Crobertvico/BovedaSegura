package pe.bovedasegura.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pe.bovedasegura.data.BackupGroup
import pe.bovedasegura.data.FileVault
import pe.bovedasegura.data.Prefs
import pe.bovedasegura.data.Vault
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class BackupResult(val ok: Boolean, val message: String, val bytes: Long = 0, val deepVerified: Boolean = false)

/**
 * Respaldo 1 (Bóveda): un único archivo .vault con las ranuras cifradas, el contenedor de viaje
 * y los documentos (PDF, Word, cámara segura, escaneos). Respaldo 2 (Multimedia): carpeta
 * incremental con las imágenes importadas, ya cifradas.
 *
 * Los respaldos copian los archivos tal como están cifrados en el teléfono: el proceso en segundo
 * plano nunca necesita (ni tiene) las llaves. La clave secreta NO se incluye: está en el Kit de emergencia.
 */
object BackupManager {
    private const val MAGIC = "BSVB"
    private const val MEDIA_DIR = "BovedaSegura_multimedia"
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ================================================================= BÓVEDA

    private fun vaultEntries(ctx: Context): List<Pair<String, File>> {
        val list = ArrayList<Pair<String, File>>()
        listOf(Vault.slotFile('a'), Vault.slotFile('b'), Vault.travelFile).forEach { if (it.exists()) list += "vault/${it.name}" to it }
        Vault.blobDir(BackupGroup.VAULT).listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }?.forEach { list += "blobs/vault/${it.name}" to it }
        return list
    }

    @Synchronized
    fun backupVault(ctx: Context, treeUriStr: String? = Prefs.vaultBackupUri): BackupResult {
        if (treeUriStr == null) return BackupResult(false, "No has elegido carpeta de respaldo")
        if (!Vault.isSetUp) return BackupResult(false, "Bóveda no configurada")
        Prefs.lastVaultBackup = System.currentTimeMillis()
        return try {
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUriStr)) ?: return fail("No se pudo abrir la carpeta")
            if (!tree.canWrite()) return fail("Sin permiso de escritura en la carpeta. Elígela de nuevo.")
            val name = "BovedaSegura_${nameFmt.format(Date())}.vault"
            val doc = tree.createFile("application/octet-stream", name) ?: return fail("No se pudo crear el archivo")
            val entries = vaultEntries(ctx)
            val digest = ctx.contentResolver.openOutputStream(doc.uri, "w")!!.use { os -> writeContainer(os, entries) }
            // Verificación: releer lo escrito y comprobar estructura + huella SHA-256.
            val check = ctx.contentResolver.openInputStream(doc.uri)!!.use { verifyContainer(it) }
            if (check == null || !check.first.contentEquals(digest) || check.second != entries.size) {
                doc.delete()
                return fail("La verificación del respaldo falló")
            }
            // Verificación profunda (solo si la bóveda está abierta): descifrar la ranura del respaldo.
            var deep = false
            val slot = Vault.currentSlot()
            if (slot != null) {
                val bytes = ctx.contentResolver.openInputStream(doc.uri)!!.use { extractEntry(it, "vault/slot_$slot.bsv") }
                if (bytes != null && Vault.deepVerify(bytes) != null) { deep = true; Prefs.lastDeepVerified = System.currentTimeMillis() }
            }
            prune(tree)
            val size = doc.length()
            Prefs.lastVaultBackupOk = System.currentTimeMillis()
            Prefs.lastVaultBackupSize = size
            Prefs.lastVaultBackupMsg = if (deep) "Verificado (descifrado)" else "Verificado (integridad)"
            Prefs.changeCounter = 0
            BackupResult(true, Prefs.lastVaultBackupMsg!!, size, deep)
        } catch (e: Exception) {
            fail("Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(msg: String): BackupResult {
        Prefs.lastVaultBackupMsg = msg
        return BackupResult(false, msg)
    }

    private fun writeContainer(os: OutputStream, entries: List<Pair<String, File>>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val dout = DataOutputStream(DigestOutputStream(os.buffered(), md))
        dout.write(MAGIC.toByteArray())
        dout.writeByte(1)
        dout.writeInt(entries.size)
        val buf = ByteArray(64 * 1024)
        for ((name, file) in entries) {
            val nb = name.toByteArray()
            dout.writeShort(nb.size)
            dout.write(nb)
            dout.writeLong(file.length())
            file.inputStream().use { inp ->
                while (true) { val r = inp.read(buf); if (r < 0) break; dout.write(buf, 0, r) }
            }
        }
        dout.flush()
        val digest = md.digest()
        os.write("END!".toByteArray())
        os.write(digest)
        os.flush()
        return digest
    }

    /** Devuelve (sha256, número de entradas) si el contenedor es íntegro. */
    fun verifyContainer(input: InputStream): Pair<ByteArray, Int>? = try {
        val md = MessageDigest.getInstance("SHA-256")
        val dis = java.security.DigestInputStream(input.buffered(), md)
        val din = DataInputStream(dis)
        val magic = ByteArray(4); din.readFully(magic)
        if (String(magic) != MAGIC) null else {
            din.readByte()
            val n = din.readInt()
            val buf = ByteArray(64 * 1024)
            repeat(n) {
                val nl = din.readShort().toInt(); val nb = ByteArray(nl); din.readFully(nb)
                var len = din.readLong()
                while (len > 0) { val r = din.read(buf, 0, minOf(buf.size.toLong(), len).toInt()); if (r < 0) throw IllegalStateException(); len -= r }
            }
            val digest = md.digest()
            dis.on(false)
            val end = ByteArray(4); din.readFully(end)
            val stored = ByteArray(32); din.readFully(stored)
            if (String(end) == "END!" && stored.contentEquals(digest)) digest to n else null
        }
    } catch (e: Exception) { null }

    fun extractEntry(input: InputStream, wanted: String): ByteArray? {
        var result: ByteArray? = null
        readEntries(input) { name, data -> if (name == wanted) result = data.readBytes() else data.skipAll() }
        return result
    }

    private fun InputStream.skipAll() { val b = ByteArray(8192); while (read(b) >= 0) {} }

    /** Recorre las entradas del contenedor. */
    fun readEntries(input: InputStream, onEntry: (String, InputStream) -> Unit) {
        val din = DataInputStream(input.buffered())
        val magic = ByteArray(4); din.readFully(magic)
        require(String(magic) == MAGIC) { "No es un respaldo de Bóveda Segura" }
        din.readByte()
        val n = din.readInt()
        repeat(n) {
            val nl = din.readShort().toInt(); val nb = ByteArray(nl); din.readFully(nb)
            val len = din.readLong()
            val limited = LimitedStream(din, len)
            onEntry(String(nb), limited)
            limited.skipAll()
        }
    }

    private class LimitedStream(val src: InputStream, var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = src.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    /** Conserva el más reciente de cada uno de los últimos 7 días y de cada una de las 4 semanas anteriores. */
    private fun prune(tree: DocumentFile) {
        val files = tree.listFiles().filter { it.name?.matches(Regex("BovedaSegura_\\d{8}_\\d{6}\\.vault")) == true }
            .sortedByDescending { it.name }
        val keep = HashSet<String>()
        val days = LinkedHashSet<String>()
        val weeks = LinkedHashSet<String>()
        val cal = Calendar.getInstance()
        for (f in files) {
            val n = f.name ?: continue
            val day = n.substring(13, 21)
            if (days.size < 7 && day !in days) { days += day; keep += n; continue }
            if (day in days) continue
            val d = runCatching { SimpleDateFormat("yyyyMMdd", Locale.US).parse(day) }.getOrNull() ?: continue
            cal.time = d
            val week = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.WEEK_OF_YEAR)}"
            if (weeks.size < 4 && week !in weeks) { weeks += week; keep += n }
        }
        files.filter { it.name !in keep }.forEach { it.delete() }
    }

    fun listVaultBackups(ctx: Context): List<DocumentFile> {
        val uri = Prefs.vaultBackupUri ?: return emptyList()
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: return emptyList()
        return tree.listFiles().filter { it.name?.endsWith(".vault") == true }.sortedByDescending { it.name }
    }

    /**
     * Restaura un .vault en el teléfono. Primero valida que se abre con la contraseña y la clave secreta;
     * si no, no toca nada.
     */
    fun restoreVault(ctx: Context, uri: Uri, password: CharArray, secret: ByteArray): BackupResult {
        val ok = ctx.contentResolver.openInputStream(uri)?.use { verifyContainer(it) } ?: return BackupResult(false, "No se pudo leer el archivo")
        if (ok == null) return BackupResult(false, "El respaldo está dañado o incompleto")
        val slotA = ctx.contentResolver.openInputStream(uri)!!.use { extractEntry(it, "vault/slot_a.bsv") } ?: return BackupResult(false, "Respaldo sin bóveda")
        val slotB = ctx.contentResolver.openInputStream(uri)!!.use { extractEntry(it, "vault/slot_b.bsv") }
        val opensA = Vault.canOpen(slotA, password, secret)
        val opensB = slotB != null && Vault.canOpen(slotB, password, secret)
        if (!opensA && !opensB) return BackupResult(false, "La contraseña o la clave secreta no corresponden a este respaldo")
        // Escribir todo
        Vault.lock()
        Vault.vaultDir.listFiles()?.forEach { it.delete() }
        Vault.blobDir(BackupGroup.VAULT).listFiles()?.forEach { it.delete() }
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            readEntries(input) { name, data ->
                val target = when {
                    name.startsWith("vault/") -> File(Vault.vaultDir, name.removePrefix("vault/"))
                    name.startsWith("blobs/vault/") -> File(Vault.blobDir(BackupGroup.VAULT), name.removePrefix("blobs/vault/"))
                    else -> null
                }
                if (target != null && !target.name.contains("..")) {
                    target.outputStream().use { data.copyTo(it) }
                }
            }
        }
        Vault.storeSecretKey(secret)
        return BackupResult(true, "Bóveda restaurada")
    }

    // ================================================================= MULTIMEDIA

    private fun mediaFolder(ctx: Context, treeUriStr: String): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUriStr)) ?: return null
        return tree.findFile(MEDIA_DIR) ?: tree.createDirectory(MEDIA_DIR)
    }

    /** Incremental: solo copia lo nuevo o modificado; lo borrado se conserva 30 días. */
    @Synchronized
    fun backupMedia(ctx: Context, treeUriStr: String? = Prefs.mediaBackupUri): BackupResult {
        if (treeUriStr == null) return BackupResult(false, "No has elegido carpeta para multimedia")
        return try {
            val folder = mediaFolder(ctx, treeUriStr) ?: return mfail("No se pudo abrir la carpeta")
            val local = Vault.blobDir(BackupGroup.MEDIA).listFiles()?.filter { it.name.endsWith(".bin") }.orEmpty()
            val remote = folder.listFiles().associateBy { it.name ?: "" }
            var copied = 0
            var bytes = 0L
            for (f in local) {
                val existing = remote[f.name]
                if (existing != null && existing.length() == f.length()) continue
                existing?.delete()
                val doc = folder.createFile("application/octet-stream", f.name) ?: continue
                ctx.contentResolver.openOutputStream(doc.uri, "w")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                if (doc.length() != f.length()) { doc.delete(); return mfail("La verificación de ${f.name.take(8)} falló") }
                copied++; bytes += f.length()
            }
            // Retención de borrados: 30 días
            val tomb = Json.decodeFromString<MutableMap<String, Long>>(Prefs.mediaTombstones)
            val localNames = local.map { it.name }.toSet()
            val now = System.currentTimeMillis()
            for ((name, doc) in remote) {
                if (name.isEmpty() || name in localNames) { tomb.remove(name); continue }
                val since = tomb.getOrPut(name) { now }
                if (now - since > 30L * 24 * 3600 * 1000) { doc.delete(); tomb.remove(name) }
            }
            Prefs.mediaTombstones = Json.encodeToString(tomb)
            val total = local.sumOf { it.length() }
            Prefs.lastMediaBackupOk = now
            Prefs.lastMediaBackupSize = total
            Prefs.lastMediaBackupMsg = if (copied == 0) "Sin cambios" else "$copied archivo(s) nuevo(s)"
            BackupResult(true, Prefs.lastMediaBackupMsg!!, bytes)
        } catch (e: Exception) {
            mfail("Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun mfail(msg: String): BackupResult { Prefs.lastMediaBackupMsg = msg; return BackupResult(false, msg) }

    /** Restaura las imágenes desde la carpeta multimedia. Solo copia las que la bóveda referencia. */
    fun restoreMedia(ctx: Context, treeUriStr: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): BackupResult {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUriStr)) ?: return BackupResult(false, "No se pudo abrir la carpeta")
        val folder = tree.findFile(MEDIA_DIR) ?: if (tree.name == MEDIA_DIR) tree else null
        folder ?: return BackupResult(false, "No se encontró la carpeta $MEDIA_DIR")
        val wanted = Vault.current().files.filter { it.group == BackupGroup.MEDIA && !FileVault.exists(it) }.map { "${it.id}.bin" }.toSet()
        val docs = folder.listFiles().filter { it.name in wanted }
        var n = 0
        for (d in docs) {
            val target = File(Vault.blobDir(BackupGroup.MEDIA), d.name!!)
            ctx.contentResolver.openInputStream(d.uri)!!.use { inp -> target.outputStream().use { inp.copyTo(it) } }
            n++
            onProgress(n, docs.size)
        }
        Prefs.mediaBackupUri = treeUriStr
        return BackupResult(true, "$n imagen(es) restaurada(s)")
    }
}
