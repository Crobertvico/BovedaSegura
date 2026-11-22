@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pe.bovedasegura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.delay
import pe.bovedasegura.AutoLock
import pe.bovedasegura.data.Item
import pe.bovedasegura.data.ItemType
import pe.bovedasegura.data.Schemas
import pe.bovedasegura.data.Vault
import pe.bovedasegura.util.Domains
import pe.bovedasegura.util.GenOptions
import pe.bovedasegura.util.Health
import pe.bovedasegura.util.PasswordGen
import pe.bovedasegura.util.Totp

fun typeIcon(t: ItemType): ImageVector = when (t) {
    ItemType.LOGIN -> Icons.Filled.Language
    ItemType.CARD -> Icons.Filled.CreditCard
    ItemType.BANK -> Icons.Filled.AccountBalance
    ItemType.IDENTITY -> Icons.Filled.Badge
    ItemType.NOTE -> Icons.Filled.Description
    ItemType.WIFI -> Icons.Filled.Wifi
    ItemType.SERVER -> Icons.Filled.Dns
    ItemType.SSH_API -> Icons.Filled.VpnKey
    ItemType.LICENSE -> Icons.Filled.Key
}

private val avatarColors = listOf(Color(0xFFE6F1FB) to Color(0xFF0C447C), Color(0xFFFAECE7) to Color(0xFF712B13), Color(0xFFEEEDFE) to Color(0xFF3C3489), Color(0xFFE1F5EE) to Color(0xFF085041), Color(0xFFFBEAF0) to Color(0xFF72243E))

@Composable
fun ItemAvatar(item: Item) {
    val (bg, fg) = avatarColors[(item.title.firstOrNull()?.code ?: 0) % avatarColors.size]
    Box(Modifier.size(40.dp).background(bg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (item.type == ItemType.LOGIN && item.title.isNotEmpty()) Text(item.title.first().uppercase(), color = fg, fontWeight = FontWeight.Medium)
        else Icon(typeIcon(item.type), null, tint = fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ItemRow(item: Item, warn: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemAvatar(item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title.ifEmpty { "Sin nombre" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(item.username.ifEmpty { item.type.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (warn) Icon(Icons.Filled.Warning, "Revisar", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        if (item.password.isNotEmpty()) IconButton(onClick = { copySecret(ctx, "Contraseña", item.password); Vault.markUsed(item.id) }) {
            Icon(Icons.Filled.ContentCopy, "Copiar contraseña", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun VaultListScreen(nav: NavHostController) {
    val data by Vault.data.collectAsState()
    val d = data ?: return
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<ItemType?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val report = remember(d.items) { Health.analyze(d.items) }
    val risky = remember(report) { (report.weak + report.reused).map { it.id }.toSet() }
    val list = d.items.filter { it.deletedAt == null }
        .filter { cat == null || it.category == cat }
        .filter { !favOnly || it.favorite }
        .filter { typeFilter == null || it.type == typeFilter }
        .filter { q.isBlank() || it.title.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true) || it.notes.contains(q, true) }
        .sortedWith(compareByDescending<Item> { it.favorite }.thenBy { it.title.lowercase() })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(if (Vault.isDecoy) "Mi bóveda" else "Bóveda") })
            OutlinedTextField(q, { q = it }, placeholder = { Text("Buscar cuenta") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Todas", cat == null && !favOnly && typeFilter == null) { cat = null; favOnly = false; typeFilter = null }
                Chip("★ Favoritas", favOnly) { favOnly = !favOnly }
                Schemas.categories.forEach { c -> Chip(c, cat == c) { cat = if (cat == c) null else c } }
                listOf(ItemType.CARD, ItemType.BANK, ItemType.IDENTITY, ItemType.SERVER, ItemType.NOTE, ItemType.WIFI).forEach { t ->
                    Chip(t.label, typeFilter == t) { typeFilter = if (typeFilter == t) null else t }
                }
            }
            if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (d.items.none { it.deletedAt == null }) "Agrega tu primera cuenta" else "Sin resultados", style = MaterialTheme.typography.titleMedium)
                    Text("Toca + o importa desde Chrome u otro gestor en Más › Importar.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { item -> ItemRow(item, item.id in risky) { nav.go("item/${item.id}") } }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { addMenu = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "Agregar", tint = MaterialTheme.colorScheme.onPrimary) }
            DropdownMenu(addMenu, { addMenu = false }) {
                ItemType.entries.forEach { t ->
                    DropdownMenuItem(text = { Text(t.label) }, leadingIcon = { Icon(typeIcon(t), null) }, onClick = { addMenu = false; nav.go("edit/new?type=${t.name}") })
                }
            }
        }
    }
}

@Composable
fun TotpBox(spec: String) {
    val ctx = LocalContext.current
    val parsed = remember(spec) { Totp.parse(spec) } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(spec) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val code = Totp.code(parsed, now)
    val left = Totp.secondsLeft(parsed, now)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).clickable { copySecret(ctx, "Código 2FA", code) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Código 2FA · toca para copiar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("${left}s", color = if (left <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun ItemDetailScreen(nav: NavHostController, id: String) {
    val data by Vault.data.collectAsState()
    val item = data?.items?.find { it.id == id } ?: run { BackBar("Cuenta", { nav.popBackStack() }); return }
    var showHistory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val files = data?.files?.filter { it.itemId == id && it.deletedAt == null }.orEmpty()
    Column(Modifier.fillMaxSize()) {
        BackBar(item.title.ifEmpty { item.type.label }, { nav.popBackStack() }) {
            IconButton(onClick = { Vault.upsertItem(item.copy(favorite = !item.favorite)) }) { Icon(if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorita") }
            IconButton(onClick = { nav.go("edit/${item.id}?type=${item.type.name}") }) { Icon(Icons.Filled.Edit, "Editar") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemAvatar(item); Spacer(Modifier.width(10.dp))
                Column {
                    Text(item.type.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.category + (if (item.travelSafe) " · Segura para viaje" else ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            InfoCard {
                Schemas.fields(item.type).forEach { f -> FieldRow(f.label, item.fields[f.key].orEmpty(), f.secret, mono = f.key in setOf("host", "port", "number", "account", "cci")) }
                if (item.packages.isNotEmpty()) FieldRow("Apps vinculadas", item.packages.joinToString(", "), false)
            }
            if (item.totp.isNotBlank()) TotpBox(item.totp)
            if (item.password.isNotEmpty()) StrengthBar(item.password)
            if (item.notes.isNotBlank()) InfoCard { Text("Notas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(item.notes) }
            if (files.isNotEmpty()) {
                SectionTitle("Archivos adjuntos")
                files.forEach { f -> MenuRow(Icons.Filled.Description, f.name, formatBytes(f.size)) { nav.go("file/${f.id}") } }
            }
            OutlinedButton(onClick = { nav.go("camera?itemId=${item.id}") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Adjuntar foto con cámara segura") }
            if (item.history.isNotEmpty()) MenuRow(Icons.Filled.History, "Historial de contraseñas", "${item.history.size} versiones anteriores") { showHistory = true }
            Text("Creada: ${formatDate(item.created)} · Modificada: ${formatDate(item.updated)}\nContraseña cambiada: ${formatDate(item.pwChanged)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text("Contraseñas anteriores") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { item.history.forEach { h -> FieldRow(formatDate(h.changedAt), h.value, true) } } },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Cerrar") } }
    )
    if (confirmDelete) ConfirmDialog("Mover a la papelera", "Podrás restaurarla durante 30 días desde Más › Papelera.", "Mover", true, { confirmDelete = false }) {
        Vault.trashItem(item.id); confirmDelete = false; nav.popBackStack()
    }
}

@Composable
fun ItemEditScreen(nav: NavHostController, id: String, typeName: String) {
    val existing = remember(id) { Vault.current().items.find { it.id == id } }
    val base = remember(id) { existing ?: Vault.newItem(runCatching { ItemType.valueOf(typeName) }.getOrDefault(ItemType.LOGIN)) }
    var title by remember { mutableStateOf(base.title) }
    var fields by remember { mutableStateOf(base.fields) }
    var notes by remember { mutableStateOf(base.notes) }
    var category by remember { mutableStateOf(base.category) }
    var totp by remember { mutableStateOf(base.totp) }
    var travel by remember { mutableStateOf(base.travelSafe) }
    var packages by remember { mutableStateOf(base.packages.joinToString(", ")) }
    var genFor by remember { mutableStateOf<String?>(null) }
    var dupWarning by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> AutoLock.suspended = false; r.contents?.let { totp = it } }

    fun build(): Item = base.copy(
        title = title.trim().ifEmpty { Domains.registrable(fields["url"]).ifEmpty { base.type.label } },
        fields = fields.filterValues { it.isNotEmpty() }, notes = notes, category = category, totp = totp.trim(), travelSafe = travel,
        packages = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    )

    fun save(force: Boolean = false) {
        if (totp.isNotBlank() && Totp.parse(totp) == null) { error = "El código 2FA no es válido"; return }
        val item = build()
        if (!force && existing == null && item.type == ItemType.LOGIN) {
            val dup = Vault.current().items.firstOrNull { it.deletedAt == null && it.type == ItemType.LOGIN && it.username.equals(item.username, true) && item.username.isNotEmpty() && Domains.matches(it.url, item.url) }
            if (dup != null) { dupWarning = dup; return }
        }
        Vault.upsertItem(item)
        nav.popBackStack()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(if (existing == null) "Nuevo: ${base.type.label}" else "Editar", { nav.popBackStack() }) {
            TextButton(onClick = { save() }) { Text("Guardar", fontWeight = FontWeight.Medium) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Schemas.fields(base.type).forEach { f ->
                val v = fields[f.key].orEmpty()
                if (f.secret && !f.multiline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PasswordInput(v, { fields = fields + (f.key to it) }, f.label, modifier = Modifier.weight(1f))
                        if (f.key == "password" || f.key == "passphrase") IconButton(onClick = { genFor = f.key }) { Icon(Icons.Filled.AutoAwesome, "Generar") }
                    }
                    if (f.key == "password") StrengthBar(v)
                } else {
                    OutlinedTextField(
                        v, { fields = fields + (f.key to it) }, label = { Text(f.label) }, singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                        keyboardOptions = KeyboardOptions(keyboardType = when (f.keyboard) { "email" -> KeyboardType.Email; "uri" -> KeyboardType.Uri; "number" -> KeyboardType.Number; "phone" -> KeyboardType.Phone; else -> KeyboardType.Text }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (base.type == ItemType.LOGIN || base.type == ItemType.BANK || base.type == ItemType.SERVER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(totp, { totp = it; error = null }, label = { Text("Código 2FA (secreto u otpauth://)") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { AutoLock.suspended = true; scanner.launch(qrOptions("Escanea el QR de 2FA del sitio")) }) { Icon(Icons.Filled.QrCodeScanner, "Escanear") }
                }
                if (totp.isNotBlank() && Totp.parse(totp) != null) TotpBox(totp)
            }
            if (base.type == ItemType.LOGIN) OutlinedTextField(packages, { packages = it }, label = { Text("Apps vinculadas (paquetes, separados por coma)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text("Categoría", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Schemas.categories.forEach { c -> Chip(c, category == c) { category = c } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FlightTakeoff, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Segura para viaje")
                    Text("Seguirá visible cuando actives el modo viaje", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(travel, { travel = it })
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Guardar") }
            Spacer(Modifier.height(40.dp))
        }
    }

    genFor?.let { key ->
        GeneratorDialog(onDismiss = { genFor = null }) { pw -> fields = fields + (key to pw); genFor = null }
    }
    dupWarning?.let { dup ->
        AlertDialog(
            onDismissRequest = { dupWarning = null },
            title = { Text("Ya tienes esta cuenta") },
            text = { Text("\"${dup.title}\" usa el mismo sitio y usuario. ¿Quieres actualizarla en lugar de crear un duplicado?") },
            confirmButton = { TextButton(onClick = {
                Vault.upsertItem(dup.copy(fields = dup.fields + build().fields, notes = notes.ifBlank { dup.notes }, totp = totp.ifBlank { dup.totp }))
                dupWarning = null; nav.popBackStack()
            }) { Text("Actualizar existente") } },
            dismissButton = { TextButton(onClick = { dupWarning = null; save(force = true) }) { Text("Crear nueva") } }
        )
    }
}

@Composable
fun GeneratorPanel(onUse: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    var o by remember { mutableStateOf(GenOptions()) }
    var pw by remember(o) { mutableStateOf(PasswordGen.generate(o)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(14.dp)) {
            Text(pw, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
        }
        StrengthBar(pw)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Frase de paso", Modifier.weight(1f)); Switch(o.passphrase, { o = o.copy(passphrase = it) }) }
        if (o.passphrase) {
            Text("Palabras: ${o.words}")
            androidx.compose.material3.Slider(o.words.toFloat(), { o = o.copy(words = it.toInt()) }, valueRange = 3f..8f, steps = 4)
        } else {
            Text("Longitud: ${o.length}")
            androidx.compose.material3.Slider(o.length.toFloat(), { o = o.copy(length = it.toInt()) }, valueRange = 8f..64f)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Mayúsculas", Modifier.weight(1f)); Switch(o.upper, { o = o.copy(upper = it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Números", Modifier.weight(1f)); Switch(o.digits, { o = o.copy(digits = it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Símbolos", Modifier.weight(1f)); Switch(o.symbols, { o = o.copy(symbols = it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Evitar 0/O y l/1", Modifier.weight(1f)); Switch(o.noAmbiguous, { o = o.copy(noAmbiguous = it) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pw = PasswordGen.generate(o) }, modifier = Modifier.weight(1f)) { Text("Otra") }
            if (onUse != null) Button(onClick = { onUse(pw) }, modifier = Modifier.weight(1f)) { Text("Usar") }
            else Button(onClick = { copySecret(ctx, "Contraseña", pw) }, modifier = Modifier.weight(1f)) { Text("Copiar") }
        }
    }
}

@Composable
fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Generar contraseña") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { GeneratorPanel(onUse) } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun GeneratorScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        BackBar("Generador", { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { GeneratorPanel(null) }
    }
}

@Composable
fun TotpScreen(nav: NavHostController) {
    val data by Vault.data.collectAsState()
    val list = data?.items?.filter { it.deletedAt == null && it.totp.isNotBlank() }.orEmpty()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Códigos 2FA") })
        if (list.isEmpty()) Column(Modifier.padding(24.dp)) {
            Text("Aún no tienes códigos 2FA", style = MaterialTheme.typography.titleMedium)
            Text("Edita una cuenta y escanea el QR que te muestra el sitio al activar la verificación en dos pasos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { item ->
                Column(Modifier.clickable { nav.go("item/${item.id}") }) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.username, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    TotpBox(item.totp)
                }
            }
        }
    }
}

@Composable
fun HealthListScreen(nav: NavHostController, kind: String) {
    val data by Vault.data.collectAsState()
    val r = remember(data) { Health.analyze(data?.items.orEmpty()) }
    val (title, list) = when (kind) {
        "reused" -> "Repetidas" to r.reused.sortedBy { it.password }
        "old" -> "Sin cambiar hace más de un año" to r.old
        "no2fa" -> "Sin 2FA" to r.no2fa
        else -> "Débiles" to r.weak
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(title, { nav.popBackStack() })
        if (list.isEmpty()) Text("Nada por aquí. ¡Bien!", Modifier.padding(24.dp))
        LazyColumn { items(list, key = { it.id }) { ItemRow(it) { nav.go("edit/${it.id}?type=${it.type.name}") } } }
    }
}

@Composable
fun TrashScreen(nav: NavHostController) {
    val data by Vault.data.collectAsState()
    val items = data?.items?.filter { it.deletedAt != null }.orEmpty()
    val files = data?.files?.filter { it.deletedAt != null }.orEmpty()
    Column(Modifier.fillMaxSize()) {
        BackBar("Papelera", { nav.popBackStack() })
        Text("Se borra definitivamente a los 30 días.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        LazyColumn {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ItemAvatar(item); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(item.title); Text("Eliminada ${ago(item.deletedAt ?: 0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { Vault.restoreItem(item.id) }) { Icon(Icons.Filled.RestoreFromTrash, "Restaurar") }
                    IconButton(onClick = { Vault.deleteItemForever(item.id) }) { Icon(Icons.Filled.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error) }
                }
            }
            items(files, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, null); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(f.name, maxLines = 1); Text("Eliminado ${ago(f.deletedAt ?: 0)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { pe.bovedasegura.data.FileVault.restore(f.id) }) { Icon(Icons.Filled.RestoreFromTrash, "Restaurar") }
                    IconButton(onClick = { pe.bovedasegura.data.FileVault.deleteForever(f) }) { Icon(Icons.Filled.Delete, "Eliminar", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (items.isEmpty() && files.isEmpty()) Text("La papelera está vacía", Modifier.padding(24.dp))
        HorizontalDivider()
    }
}
