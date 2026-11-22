package pe.bovedasegura.data

import kotlinx.serialization.Serializable

@Serializable
enum class ItemType(val label: String) {
    LOGIN("Inicio de sesión"),
    CARD("Tarjeta"),
    BANK("Cuenta bancaria"),
    IDENTITY("Identidad"),
    NOTE("Nota segura"),
    WIFI("Red Wi-Fi"),
    SERVER("Servidor / Base de datos"),
    SSH_API("Clave SSH / API"),
    LICENSE("Licencia de software");
}

data class FieldDef(val key: String, val label: String, val secret: Boolean = false, val multiline: Boolean = false, val keyboard: String = "text")

object Schemas {
    fun fields(type: ItemType): List<FieldDef> = when (type) {
        ItemType.LOGIN -> listOf(
            FieldDef("username", "Usuario o correo", keyboard = "email"),
            FieldDef("password", "Contraseña", secret = true),
            FieldDef("url", "Sitio web o app", keyboard = "uri"),
        )
        ItemType.CARD -> listOf(
            FieldDef("holder", "Titular"),
            FieldDef("number", "Número de tarjeta", secret = true, keyboard = "number"),
            FieldDef("expiry", "Vencimiento (MM/AA)"),
            FieldDef("cvv", "CVV", secret = true, keyboard = "number"),
            FieldDef("pin", "PIN", secret = true, keyboard = "number"),
            FieldDef("bank", "Banco emisor"),
        )
        ItemType.BANK -> listOf(
            FieldDef("bank", "Banco"),
            FieldDef("holder", "Titular"),
            FieldDef("account", "Número de cuenta", secret = true),
            FieldDef("cci", "CCI", secret = true),
            FieldDef("accountType", "Tipo de cuenta"),
            FieldDef("username", "Usuario de banca"),
            FieldDef("password", "Clave de internet", secret = true),
        )
        ItemType.IDENTITY -> listOf(
            FieldDef("fullName", "Nombres y apellidos"),
            FieldDef("docType", "Tipo de documento"),
            FieldDef("docNumber", "Número de documento", secret = true),
            FieldDef("birthDate", "Fecha de nacimiento"),
            FieldDef("email", "Correo", keyboard = "email"),
            FieldDef("phone", "Teléfono", keyboard = "phone"),
            FieldDef("address", "Dirección", multiline = true),
            FieldDef("city", "Ciudad / Distrito"),
        )
        ItemType.NOTE -> emptyList()
        ItemType.WIFI -> listOf(
            FieldDef("ssid", "Nombre de la red (SSID)"),
            FieldDef("password", "Contraseña", secret = true),
            FieldDef("security", "Seguridad (WPA2/WPA3)"),
        )
        ItemType.SERVER -> listOf(
            FieldDef("host", "Host / IP"),
            FieldDef("port", "Puerto", keyboard = "number"),
            FieldDef("service", "SID / Base de datos"),
            FieldDef("username", "Usuario"),
            FieldDef("password", "Contraseña", secret = true),
        )
        ItemType.SSH_API -> listOf(
            FieldDef("username", "Usuario / Cliente"),
            FieldDef("key", "Clave o token", secret = true, multiline = true),
            FieldDef("publicKey", "Clave pública", multiline = true),
            FieldDef("passphrase", "Frase de la clave", secret = true),
        )
        ItemType.LICENSE -> listOf(
            FieldDef("product", "Producto"),
            FieldDef("licenseKey", "Clave de licencia", secret = true),
            FieldDef("email", "Correo registrado", keyboard = "email"),
            FieldDef("version", "Versión"),
        )
    }

    /** Campo que representa "la contraseña" de este tipo (para salud de contraseñas y copiar). */
    fun passwordKey(type: ItemType): String? = when (type) {
        ItemType.LOGIN, ItemType.BANK, ItemType.WIFI, ItemType.SERVER -> "password"
        else -> null
    }

    fun usernameKey(type: ItemType): String? = when (type) {
        ItemType.LOGIN, ItemType.BANK, ItemType.SERVER, ItemType.SSH_API -> "username"
        ItemType.IDENTITY -> "email"
        else -> null
    }

    val categories = listOf("Trabajo", "Bancos", "Redes", "Correo", "Personal", "Hogar", "Otros")
}

@Serializable
data class HistoryEntry(val value: String, val changedAt: Long)

@Serializable
data class Item(
    val id: String,
    val type: ItemType,
    val title: String,
    val fields: Map<String, String> = emptyMap(),
    val notes: String = "",
    val category: String = "Otros",
    val favorite: Boolean = false,
    val totp: String = "",
    val travelSafe: Boolean = false,
    val packages: List<String> = emptyList(),
    val created: Long,
    val updated: Long,
    val pwChanged: Long,
    val history: List<HistoryEntry> = emptyList(),
    val deletedAt: Long? = null,
    val lastUsed: Long = 0,
) {
    val username: String get() = Schemas.usernameKey(type)?.let { fields[it] }.orEmpty()
    val password: String get() = Schemas.passwordKey(type)?.let { fields[it] }.orEmpty()
    val url: String get() = fields["url"].orEmpty()
}

@Serializable
enum class FileKind { IMAGE, DOCUMENT }

@Serializable
enum class FileSource { IMPORT, CAMERA, SCAN }

/** Grupo de respaldo: las imágenes importadas van al respaldo multimedia; todo lo demás va con la bóveda. */
enum class BackupGroup(val dir: String) { VAULT("vault"), MEDIA("media") }

@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val key: String,          // llave del archivo (base64), protegida porque el registro está cifrado
    val kind: FileKind,
    val source: FileSource,
    val folder: String = "General",
    val tags: List<String> = emptyList(),
    val itemId: String? = null,
    val thumb: String? = null, // miniatura JPEG en base64 (también cifrada dentro del registro)
    val travelSafe: Boolean = false,
    val created: Long,
    val deletedAt: Long? = null,
) {
    val group: BackupGroup get() = if (kind == FileKind.IMAGE && source == FileSource.IMPORT) BackupGroup.MEDIA else BackupGroup.VAULT
}

@Serializable
data class VaultSettings(
    val clipboardSeconds: Int = 30,
    val travelActive: Boolean = false,
    val decoyConfigured: Boolean = false,
    val folders: List<String> = listOf("General", "Identidad", "Contratos", "Recibos"),
)

@Serializable
data class VaultData(
    val items: List<Item> = emptyList(),
    val files: List<FileMeta> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
)
