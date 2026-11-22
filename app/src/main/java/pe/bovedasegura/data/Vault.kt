package pe.bovedasegura.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pe.bovedasegura.crypto.Crypto
import pe.bovedasegura.crypto.DeviceKeys
import pe.bovedasegura.crypto.SecretKeyCodec
import pe.bovedasegura.util.Domains
import java.io.File
import java.util.UUID

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

@Serializable
data class VaultHeader(val v: Int = 1, val salt: String, val mem: Int, val iter: Int, val wrapped: String)

@Serializable
data class VaultRecord(val k: String, val d: String)

@Serializable
data class VaultFileContent(val h: VaultHeader, val r: List<VaultRecord>)

@Serializable
data class RecordPayload(val t: String, val item: Item? = null, val file: FileMeta? = null, val settings: VaultSettings? = null)

@Serializable
data class TravelStash(val items: List<Item>, val files: List<FileMeta>)

sealed class UnlockResult {
    object Ok : UnlockResult()
    data class Wrong(val attempts: Int, val lockedForMs: Long, val remainingBeforeWipe: Int?) : UnlockResult()
    data class LockedOut(val ms: Long) : UnlockResult()
    object Wiped : UnlockResult()
}

/**
 * Núcleo de la bóveda.
 *
 * - Dos "ranuras" indistinguibles en disco: A (real) y B (señuelo). Ambas existen siempre;
 *   la B contiene basura aleatoria hasta que el usuario configura una bóveda señuelo.
 * - Cada registro (cuenta, archivo, configuración) se cifra con su propia llave aleatoria,
 *   y esa llave se cifra con la llave de la bóveda.
 * - La llave de la bóveda se cifra con la KEK = HKDF(Argon2id(contraseña), clave secreta).
 */
object Vault {
    private lateinit var app: Context
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Session(val slot: Char, val vaultKey: ByteArray)

    @Volatile private var session: Session? = null
    private val _data = MutableStateFlow<VaultData?>(null)
    val data: StateFlow<VaultData?> = _data

    var onChanged: (() -> Unit)? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    val vaultDir: File get() = File(app.filesDir, "vault").apply { mkdirs() }
    fun slotFile(slot: Char) = File(vaultDir, "slot_$slot.bsv")
    val travelFile: File get() = File(vaultDir, "travel.bin")
    private val secretFile: File get() = File(app.filesDir, "secret.bin")
    fun blobDir(group: BackupGroup): File = File(app.filesDir, "blobs/${group.dir}").apply { mkdirs() }

    val isSetUp: Boolean get() = slotFile('a').exists() && secretFile.exists()
    val isUnlocked: Boolean get() = session != null
    val isDecoy: Boolean get() = session?.slot == 'b'
    fun current(): VaultData = _data.value ?: VaultData()

    fun secretKey(): ByteArray = DeviceKeys.deviceOpen(secretFile.readBytes())
    fun storeSecretKey(key: ByteArray) { atomicWrite(secretFile, DeviceKeys.deviceSeal(key)) }

    // ------------------------------------------------------------------ creación

    /** Crea una bóveda nueva. Devuelve la clave secreta para el Kit de emergencia. */
    suspend fun create(password: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val secret = SecretKeyCodec.generate()
        storeSecretKey(secret)
        val vaultKey = Crypto.random(32)
        writeSlot('a', password, secret, vaultKey, VaultData())
        writeRandomSlot('b')
        writeRandomTravel()
        session = Session('a', vaultKey)
        _data.value = VaultData()
        Prefs.failedAttempts = 0
        secret
    }

    private fun newHeader(password: CharArray, secret: ByteArray, vaultKey: ByteArray): VaultHeader {
        val salt = Crypto.random(16)
        val kek = Crypto.deriveKek(password, salt, secret, "vault")
        val wrapped = Crypto.seal(kek, vaultKey, "vault-key".toByteArray())
        kek.fill(0)
        return VaultHeader(salt = salt.b64(), mem = Crypto.ARGON_MEM_KB, iter = Crypto.ARGON_ITER, wrapped = wrapped.b64())
    }

    private fun writeSlot(slot: Char, password: CharArray, secret: ByteArray, vaultKey: ByteArray, d: VaultData) {
        val header = newHeader(password, secret, vaultKey)
        atomicWrite(slotFile(slot), encode(header, vaultKey, d))
    }

    private fun writeRandomSlot(slot: Char) {
        // Ranura señuelo sin configurar: mismo formato, llave que nadie conoce.
        val fakeHeader = VaultHeader(salt = Crypto.random(16).b64(), mem = Crypto.ARGON_MEM_KB, iter = Crypto.ARGON_ITER, wrapped = Crypto.random(60).b64())
        val key = Crypto.random(32)
        val filler = (0 until 3 + Crypto.rng.nextInt(6)).map { VaultRecord(Crypto.random(60).b64(), Crypto.random(200 + Crypto.rng.nextInt(400)).b64()) }
        atomicWrite(slotFile(slot), json.encodeToString(VaultFileContent(fakeHeader, filler)).toByteArray())
        key.fill(0)
    }

    private fun writeRandomTravel() {
        atomicWrite(travelFile, Crypto.random(2048 + Crypto.rng.nextInt(6144)))
    }

    // ------------------------------------------------------------------ formato

    private fun encode(header: VaultHeader, vaultKey: ByteArray, d: VaultData): ByteArray {
        val payloads = buildList {
            add(RecordPayload("s", settings = d.settings))
            d.items.forEach { add(RecordPayload("i", item = it)) }
            d.files.forEach { add(RecordPayload("f", file = it)) }
        }
        val records = payloads.map { p ->
            val rk = Crypto.random(32)
            val ct = Crypto.seal(rk, json.encodeToString(p).toByteArray())
            val wk = Crypto.seal(vaultKey, rk, "record-key".toByteArray())
            rk.fill(0)
            VaultRecord(wk.b64(), ct.b64())
        }.shuffled(Crypto.rng)
        return json.encodeToString(VaultFileContent(header, records)).toByteArray()
    }

    fun decode(content: VaultFileContent, vaultKey: ByteArray): VaultData {
        var settings = VaultSettings()
        val items = ArrayList<Item>()
        val files = ArrayList<FileMeta>()
        for (r in content.r) {
            val rk = Crypto.open(vaultKey, r.k.unb64(), "record-key".toByteArray())
            val p = json.decodeFromString<RecordPayload>(String(Crypto.open(rk, r.d.unb64())))
            rk.fill(0)
            when (p.t) {
                "s" -> p.settings?.let { settings = it }
                "i" -> p.item?.let { items += it }
                "f" -> p.file?.let { files += it }
            }
        }
        return VaultData(items.sortedBy { it.title.lowercase() }, files.sortedByDescending { it.created }, settings)
    }

    fun readContent(bytes: ByteArray): VaultFileContent = json.decodeFromString(String(bytes))

    private fun tryUnwrap(content: VaultFileContent, password: CharArray, secret: ByteArray): ByteArray? {
        return try {
            val kek = Crypto.deriveKek(password, content.h.salt.unb64(), secret, "vault", content.h.mem, content.h.iter)
            val k = Crypto.tryOpen(kek, content.h.wrapped.unb64(), "vault-key".toByteArray())
            kek.fill(0)
            k
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ desbloqueo

    fun lockoutRemaining(): Long = (Prefs.lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0)

    suspend fun unlock(password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        val wait = lockoutRemaining()
        if (wait > 0) return@withContext UnlockResult.LockedOut(wait)
        val secret = secretKey()
        val a = readContent(slotFile('a').readBytes())
        val b = runCatching { readContent(slotFile('b').readBytes()) }.getOrNull()
        // Se calculan ambas ranuras siempre, para que el tiempo no revele cuál abrió.
        val ka = tryUnwrap(a, password, secret)
        val kb = b?.let { tryUnwrap(it, password, secret) }
        when {
            ka != null -> { openSession('a', ka, a); kb?.fill(0); UnlockResult.Ok }
            kb != null && b != null -> { openSession('b', kb, b); UnlockResult.Ok }
            else -> registerFailure()
        }
    }

    /** Desbloqueo con biometría: recibe la llave de la bóveda real ya descifrada por el Keystore. */
    suspend fun unlockWithKey(vaultKey: ByteArray): Boolean = withContext(Dispatchers.Default) {
        try {
            val a = readContent(slotFile('a').readBytes())
            openSession('a', vaultKey, a); true
        } catch (e: Exception) { false }
    }

    private fun openSession(slot: Char, key: ByteArray, content: VaultFileContent) {
        val d = decode(content, key)
        session = Session(slot, key)
        _data.value = d
        Prefs.failedAttempts = 0
        Prefs.lockoutUntil = 0
        purgeTrash()
    }

    private fun registerFailure(): UnlockResult {
        val n = Prefs.failedAttempts + 1
        Prefs.failedAttempts = n
        val limit = Prefs.selfDestructAfter
        if (limit > 0 && n >= limit) {
            wipeEverything()
            return UnlockResult.Wiped
        }
        val delay = if (n < 3) 0L else (30_000L shl (n - 3).coerceAtMost(7)).coerceAtMost(3_600_000L)
        Prefs.lockoutUntil = System.currentTimeMillis() + delay
        return UnlockResult.Wrong(n, delay, if (limit > 0) limit - n else null)
    }

    /** Comprueba la contraseña de la sesión actual sin cambiar el estado (reautenticación). */
    suspend fun verifyPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext false
        val c = readContent(slotFile(s.slot).readBytes())
        val k = tryUnwrap(c, password, secretKey())
        val ok = k != null && k.contentEquals(s.vaultKey)
        k?.fill(0)
        ok
    }

    fun vaultKeyForBiometric(): ByteArray? = session?.takeIf { it.slot == 'a' }?.vaultKey?.copyOf()

    fun lock() {
        session?.vaultKey?.fill(0)
        session = null
        _data.value = null
    }

    // ------------------------------------------------------------------ guardado

    @Synchronized
    fun update(transform: (VaultData) -> VaultData) {
        val s = session ?: return
        val newData = transform(current())
        val file = slotFile(s.slot)
        val content = readContent(file.readBytes())
        atomicWrite(file, encode(content.h, s.vaultKey, newData))
        _data.value = newData
        onChanged?.invoke()
    }

    fun upsertItem(item: Item) = update { d ->
        val now = System.currentTimeMillis()
        val old = d.items.find { it.id == item.id }
        val merged = if (old == null) item.copy(updated = now) else {
            val pk = Schemas.passwordKey(item.type)
            val oldPw = pk?.let { old.fields[it] }.orEmpty()
            val newPw = pk?.let { item.fields[it] }.orEmpty()
            if (pk != null && oldPw.isNotEmpty() && oldPw != newPw) {
                item.copy(updated = now, pwChanged = now, history = (listOf(HistoryEntry(oldPw, now)) + old.history).take(20))
            } else item.copy(updated = now)
        }
        d.copy(items = (d.items.filter { it.id != item.id } + merged).sortedBy { it.title.lowercase() })
    }

    fun newItem(type: ItemType): Item {
        val now = System.currentTimeMillis()
        return Item(id = UUID.randomUUID().toString(), type = type, title = "", created = now, updated = now, pwChanged = now)
    }

    fun trashItem(id: String) = update { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(deletedAt = System.currentTimeMillis()) else it }) }
    fun restoreItem(id: String) = update { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(deletedAt = null) else it }) }
    fun deleteItemForever(id: String) = update { d -> d.copy(items = d.items.filter { it.id != id }, files = d.files.map { if (it.itemId == id) it.copy(itemId = null) else it }) }
    fun markUsed(id: String) {
        // Sin disparar respaldo por cada uso.
        val s = session ?: return
        val d = current().let { d -> d.copy(items = d.items.map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it }) }
        val file = slotFile(s.slot)
        val content = readContent(file.readBytes())
        atomicWrite(file, encode(content.h, s.vaultKey, d))
        _data.value = d
    }

    fun addPackageToItem(id: String, pkg: String) = update { d ->
        d.copy(items = d.items.map { if (it.id == id && pkg !in it.packages) it.copy(packages = it.packages + pkg) else it })
    }

    fun updateSettings(transform: (VaultSettings) -> VaultSettings) = update { d -> d.copy(settings = transform(d.settings)) }

    /** Elimina definitivamente lo que lleva más de 30 días en la papelera. */
    fun purgeTrash() {
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val d = current()
        val oldItems = d.items.filter { (it.deletedAt ?: Long.MAX_VALUE) < limit }
        val oldFiles = d.files.filter { (it.deletedAt ?: Long.MAX_VALUE) < limit }
        if (oldItems.isEmpty() && oldFiles.isEmpty()) return
        oldFiles.forEach { File(blobDir(it.group), "${it.id}.bin").delete() }
        update { v -> v.copy(items = v.items - oldItems.toSet(), files = v.files - oldFiles.toSet()) }
    }

    /** Busca una cuenta existente con el mismo dominio/app y usuario (para no duplicar). */
    fun findExisting(webDomain: String?, pkg: String?, username: String): Item? =
        current().items.firstOrNull { it.deletedAt == null && it.type == ItemType.LOGIN &&
            it.username.equals(username, ignoreCase = true) &&
            ((webDomain != null && Domains.matches(it.url, webDomain)) || (pkg != null && pkg in it.packages)) }

    // ------------------------------------------------------------------ contraseña maestra y clave secreta

    suspend fun changePassword(current: CharArray, newPassword: CharArray): Boolean = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext false
        if (!verifyPassword(current)) return@withContext false
        val file = slotFile(s.slot)
        val content = readContent(file.readBytes())
        val header = newHeader(newPassword, secretKey(), s.vaultKey)
        atomicWrite(file, json.encodeToString(content.copy(h = header)).toByteArray())
        onChanged?.invoke()
        true
    }

    /** Rota la clave secreta. Requiere la contraseña real y, si existe, la de la bóveda señuelo. */
    suspend fun rotateSecretKey(master: CharArray, decoy: CharArray?): ByteArray? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext null
        if (s.slot != 'a' || current().settings.travelActive) return@withContext null
        val oldSecret = secretKey()
        val a = readContent(slotFile('a').readBytes())
        val ka = tryUnwrap(a, master, oldSecret) ?: return@withContext null
        var kb: ByteArray? = null
        var b: VaultFileContent? = null
        if (current().settings.decoyConfigured) {
            if (decoy == null) return@withContext null
            b = readContent(slotFile('b').readBytes())
            kb = tryUnwrap(b, decoy, oldSecret) ?: return@withContext null
        }
        val newSecret = SecretKeyCodec.generate()
        val ha = newHeader(master, newSecret, ka)
        val newA = json.encodeToString(a.copy(h = ha)).toByteArray()
        val newB = if (b != null && kb != null && decoy != null) json.encodeToString(b.copy(h = newHeader(decoy, newSecret, kb))).toByteArray() else null
        atomicWrite(slotFile('a'), newA)
        if (newB != null) atomicWrite(slotFile('b'), newB)
        storeSecretKey(newSecret)
        onChanged?.invoke()
        newSecret
    }

    // ------------------------------------------------------------------ bóveda señuelo

    suspend fun configureDecoy(decoyPassword: CharArray, sample: VaultData): Boolean = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext false
        if (s.slot != 'a') return@withContext false
        val secret = secretKey()
        // No puede coincidir con la contraseña real.
        val a = readContent(slotFile('a').readBytes())
        tryUnwrap(a, decoyPassword, secret)?.let { it.fill(0); return@withContext false }
        val kb = Crypto.random(32)
        writeSlot('b', decoyPassword, secret, kb, sample)
        kb.fill(0)
        updateSettings { it.copy(decoyConfigured = true) }
        true
    }

    fun removeDecoy() {
        if (session?.slot != 'a') return
        writeRandomSlot('b')
        updateSettings { it.copy(decoyConfigured = false) }
    }

    // ------------------------------------------------------------------ modo viaje

    /** Saca de la bóveda todo lo que no está marcado como "seguro para viaje" y lo guarda en un contenedor aparte. */
    suspend fun enableTravel(travelPassword: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (session == null || current().settings.travelActive) return@withContext false
        val d = current()
        val stash = TravelStash(d.items.filter { !it.travelSafe }, d.files.filter { !it.travelSafe })
        val salt = Crypto.random(16)
        val k = Crypto.deriveKek(travelPassword, salt, secretKey(), "travel")
        val sealed = Crypto.seal(k, json.encodeToString(stash).toByteArray())
        k.fill(0)
        atomicWrite(travelFile, salt + sealed)
        update { v -> v.copy(items = v.items.filter { it.travelSafe }, files = v.files.filter { it.travelSafe }, settings = v.settings.copy(travelActive = true)) }
        Prefs.biometricEnabled = false
        DeviceKeys.deleteBioKey()
        true
    }

    suspend fun disableTravel(travelPassword: CharArray): Boolean = withContext(Dispatchers.Default) {
        if (session == null || !current().settings.travelActive) return@withContext false
        val raw = travelFile.readBytes()
        if (raw.size < 44) return@withContext false
        val salt = raw.copyOfRange(0, 16)
        val k = Crypto.deriveKek(travelPassword, salt, secretKey(), "travel")
        val plain = Crypto.tryOpen(k, raw.copyOfRange(16, raw.size))
        k.fill(0)
        if (plain == null) return@withContext false
        val stash = json.decodeFromString<TravelStash>(String(plain))
        update { v -> v.copy(items = (v.items + stash.items).distinctBy { it.id }.sortedBy { it.title.lowercase() }, files = (v.files + stash.files).distinctBy { it.id }, settings = v.settings.copy(travelActive = false)) }
        writeRandomTravel()
        true
    }

    // ------------------------------------------------------------------ restauración

    /** Comprueba si un contenido de ranura se abre con la contraseña y clave secreta dadas. */
    fun canOpen(slotBytes: ByteArray, password: CharArray, secret: ByteArray): Boolean {
        val c = runCatching { readContent(slotBytes) }.getOrNull() ?: return false
        val k = tryUnwrap(c, password, secret) ?: return false
        k.fill(0)
        return true
    }

    /** Descifra el contenido de una ranura de respaldo con la llave de la sesión (verificación profunda). */
    fun deepVerify(slotBytes: ByteArray): Int? {
        val s = session ?: return null
        return try {
            val c = readContent(slotBytes)
            decode(c, s.vaultKey).let { it.items.size + it.files.size + 1 }
        } catch (e: Exception) { null }
    }

    fun currentSlot(): Char? = session?.slot

    // ------------------------------------------------------------------ borrado total

    fun wipeEverything() {
        lock()
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        DeviceKeys.deleteAll()
        Prefs.clearAll()
    }

    fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }
}
