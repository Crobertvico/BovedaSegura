@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pe.bovedasegura.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pe.bovedasegura.AutoLock
import pe.bovedasegura.backup.BackupScheduler
import pe.bovedasegura.data.BackupGroup
import pe.bovedasegura.data.ExpiredException
import pe.bovedasegura.data.FileKind
import pe.bovedasegura.data.FileMeta
import pe.bovedasegura.data.FileSource
import pe.bovedasegura.data.FileVault
import pe.bovedasegura.data.Vault
import pe.bovedasegura.data.unb64
import java.io.File

@Composable
fun FileThumb(meta: FileMeta, modifier: Modifier = Modifier) {
    val bmp = remember(meta.id, meta.thumb) { meta.thumb?.let { runCatching { val b = it.unb64(); BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() } }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(when { FileVault.isPdf(meta.mime) -> Icons.Filled.PictureAsPdf; meta.kind == FileKind.IMAGE -> Icons.Filled.Image; else -> Icons.Filled.Description }, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        if (!FileVault.exists(meta)) Text("Pendiente de restaurar", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(2.dp))
    }
}

@Composable
fun FilesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Vault.data.collectAsState()
    val d = data ?: return
    var folder by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val files = d.files.filter { it.deletedAt == null }
        .filter { folder == null || it.folder == folder }
        .filter { q.isBlank() || it.name.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        AutoLock.suspended = false
        if (uris.isNotEmpty()) pickedUris = uris
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Archivos seguros") })
            OutlinedTextField(q, { q = it }, placeholder = { Text("Buscar archivo") }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Todos", folder == null) { folder = null }
                (d.settings.folders + d.files.map { it.folder }).distinct().forEach { f -> Chip(f, folder == f) { folder = if (folder == f) null else f } }
            }
            val totalSize = d.files.filter { it.deletedAt == null }.sumOf { it.size }
            Text("${files.size} archivo(s) · ${formatBytes(totalSize)} cifrados", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (files.isEmpty()) Column(Modifier.padding(24.dp)) {
                Text("Guarda aquí tu DNI, contratos y recibos", style = MaterialTheme.typography.titleMedium)
                Text("Solo podrán verse desde esta app. Usa la cámara segura para que nunca pasen por tu galería.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.id }) { f ->
                    Column(Modifier.clickable { nav.go("file/${f.id}") }) {
                        FileThumb(f, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(f.name, maxLines = 1, fontSize = 12.sp)
                        Text(formatBytes(f.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { menu = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "Agregar", tint = MaterialTheme.colorScheme.onPrimary) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Cámara segura") }, leadingIcon = { Icon(Icons.Filled.CameraAlt, null) }, onClick = { menu = false; nav.go("camera") })
                DropdownMenuItem(text = { Text("Importar archivos") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; AutoLock.suspended = true; picker.launch(arrayOf("*/*")) })
            }
        }
    }
    if (pickedUris.isNotEmpty()) ImportFlow(pickedUris, allowDeleteOriginal = true) { pickedUris = emptyList() }
}

/** Flujo de importación: carpeta → cifrado → (opcional) borrar originales. Detecta archivos .bsf compartidos. */
@Composable
fun ImportFlow(uris: List<Uri>, allowDeleteOriginal: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders = Vault.current().settings.folders
    var stage by remember { mutableStateOf("folder") }
    var folder by remember { mutableStateOf("General") }
    var newFolder by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    val imported = remember { mutableStateListOf<Uri>() }
    var sharedUri by remember { mutableStateOf<Uri?>(null) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    fun runImport() {
        stage = "busy"
        scope.launch {
            if (newFolder.isNotBlank() && newFolder.trim() !in folders) Vault.updateSettings { it.copy(folders = it.folders + newFolder.trim()) }
            val target = newFolder.trim().ifEmpty { folder }
            var images = 0
            uris.forEachIndexed { i, u ->
                progress = "Cifrando ${i + 1} de ${uris.size}…"
                if (FileVault.isShared(ctx, u)) { sharedUri = u; return@forEachIndexed }
                runCatching { FileVault.importUri(ctx, u, target) }.onSuccess { m -> imported += u; if (m.group == BackupGroup.MEDIA) images++ }
            }
            if (images > 0) BackupScheduler.noteMediaAdded(ctx)
            stage = if (sharedUri != null) "code" else if (allowDeleteOriginal && imported.isNotEmpty()) "delete" else "done"
        }
    }

    when (stage) {
        "folder" -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Guardar ${uris.size} archivo(s) en") },
            text = {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { folders.forEach { f -> Chip(f, folder == f && newFolder.isBlank()) { folder = f; newFolder = "" } } }
                    OutlinedTextField(newFolder, { newFolder = it }, label = { Text("O crea una carpeta nueva") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { runImport() }) { Text("Cifrar y guardar") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
        )
        "busy" -> AlertDialog(onDismissRequest = {}, title = { Text("Importando") }, text = { Busy(progress) }, confirmButton = {})
        "code" -> AlertDialog(
            onDismissRequest = { stage = if (imported.isNotEmpty() && allowDeleteOriginal) "delete" else "done" },
            title = { Text("Archivo compartido protegido") },
            text = {
                Column {
                    Text("Escribe el código de 8 caracteres que te dio quien te lo envió.")
                    OutlinedTextField(code, { code = it; msg = null }, label = { Text("XXXX-XXXX") }, singleLine = true)
                    msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val u = sharedUri ?: return@TextButton
                scope.launch {
                    try { FileVault.importShared(ctx, u, code); sharedUri = null; stage = "done"; msg = null }
                    catch (e: ExpiredException) { msg = "Este archivo ya venció" }
                    catch (e: Exception) { msg = "Código incorrecto o archivo dañado" }
                }
            }) { Text("Abrir") } },
            dismissButton = { TextButton(onClick = { stage = "done" }) { Text("Cancelar") } }
        )
        "delete" -> AlertDialog(
            onDismissRequest = { stage = "done" },
            title = { Text("¿Borrar los originales?") },
            text = { Text("Los archivos ya están cifrados en el cofre. Si los borras de la galería o de Descargas, solo existirán aquí.\n\nRecuerda: si ya se subieron a Google Fotos o se enviaron por WhatsApp, bórralos también allí. En la memoria del teléfono pueden quedar rastros que un laboratorio forense podría recuperar.") },
            confirmButton = { TextButton(onClick = {
                val ok = imported.count { FileVault.deleteOriginal(ctx, it) }
                msg = if (ok == imported.size) "Originales borrados" else "Se borraron $ok de ${imported.size}. Borra el resto desde la app de origen."
                stage = "done"
            }) { Text("Borrar originales", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { stage = "done" }) { Text("Conservar") } }
        )
        else -> {
            if (msg != null) AlertDialog(onDismissRequest = onDone, text = { Text(msg!!) }, confirmButton = { TextButton(onClick = onDone) { Text("Aceptar") } })
            else LaunchedEffect(Unit) { onDone() }
        }
    }
}

@Composable
fun PendingShareImport() {
    val pending = MainActivity.pendingShares
    if (pending.isEmpty()) return
    val list = remember(pending.size) { pending.toList() }
    ImportFlow(list, allowDeleteOriginal = false) { pending.clear() }
}

@Composable
fun FileViewerScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Vault.data.collectAsState()
    val meta = data?.files?.find { it.id == id } ?: run { BackBar("Archivo", { nav.popBackStack() }); return }
    var reauthFor by remember { mutableStateOf<String?>(null) }
    var shareDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var externalWarn by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(meta.mime)) { uri ->
        AutoLock.suspended = false
        if (uri != null) scope.launch {
            info = try { withContext(Dispatchers.IO) { FileVault.exportTo(ctx, meta, uri) }; "Archivo exportado sin cifrar" } catch (e: Exception) { "No se pudo exportar: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(meta.name, { nav.popBackStack() }) {
            IconButton(onClick = { reauthFor = "export" }) { Icon(Icons.Filled.FileDownload, "Exportar") }
            IconButton(onClick = { reauthFor = "share" }) { Icon(Icons.Filled.Share, "Compartir cifrado") }
            IconButton(onClick = { moveDialog = true }) { Icon(Icons.Filled.Folder, "Mover") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar") }
        }
        Text("${meta.folder} · ${formatBytes(meta.size)} · ${formatDate(meta.created)} · " + when (meta.source) { FileSource.CAMERA -> "Cámara segura"; FileSource.SCAN -> "Escaneo"; else -> "Importado" } +
            " · Respaldo: " + if (meta.group == BackupGroup.MEDIA) "multimedia" else "bóveda", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            when {
                !FileVault.exists(meta) -> Banner("Este archivo está pendiente de restaurar desde tu respaldo multimedia (Más › Respaldos).", Icons.Filled.Warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                meta.kind == FileKind.IMAGE -> ImageViewer(meta)
                FileVault.isPdf(meta.mime) -> PdfViewer(meta)
                FileVault.isText(meta.mime) -> TextViewer(meta)
                else -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    Text(meta.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (FileVault.isWord(meta.mime)) "El lector interno de Word llegará en la fase 2. Por ahora puedes exportarlo o abrirlo temporalmente con otra app." else "Este tipo de archivo no tiene visor interno.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { externalWarn = true }) { Icon(Icons.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Abrir con otra app") }
                }
            }
        }
    }

    reauthFor?.let { what ->
        ReauthDialog(if (what == "export") "Vas a guardar una copia SIN cifrar fuera del cofre." else "Vas a crear una copia cifrada para compartir.", { reauthFor = null }) {
            reauthFor = null
            if (what == "export") { AutoLock.suspended = true; exporter.launch(meta.name) } else shareDialog = true
        }
    }
    if (shareDialog) ShareDialog(meta) { shareDialog = false }
    if (moveDialog) {
        val folders = Vault.current().settings.folders
        AlertDialog(onDismissRequest = { moveDialog = false }, title = { Text("Mover a carpeta") }, text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { folders.forEach { f -> Chip(f, meta.folder == f) { FileVault.updateMeta(meta.copy(folder = f)); moveDialog = false } } }
        }, confirmButton = { TextButton(onClick = { moveDialog = false }) { Text("Cerrar") } })
    }
    if (externalWarn) ConfirmDialog("Abrir fuera del cofre", "Se creará una copia temporal descifrada para la otra app. Mientras esté abierta allí, queda fuera de la protección del cofre. Bóveda Segura la borra al volver.", "Abrir", false, { externalWarn = false }) {
        externalWarn = false
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { FileVault.tempForExternal(ctx, meta) }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, meta.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir con"))
            } catch (e: Exception) { info = "No hay una app para abrir este archivo" }
        }
    }
    if (confirmDelete) ConfirmDialog("Mover a la papelera", "Podrás restaurarlo durante 30 días.", "Mover", true, { confirmDelete = false }) { FileVault.trash(meta.id); confirmDelete = false; nav.popBackStack() }
    info?.let { AlertDialog(onDismissRequest = { info = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { info = null }) { Text("Aceptar") } }) }
}

@Composable
private fun ImageViewer(meta: FileMeta) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(meta.id) {
        bmp = withContext(Dispatchers.IO) { val b = FileVault.decryptBytes(meta); FileVault.decodeImage(b).also { b.fill(0) } }
    }
    DisposableEffect(meta.id) { onDispose { bmp?.recycle(); bmp = null } }
    val b = bmp
    if (b == null) { Busy("Descifrando…"); return }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        }) {
            Image(b.asImageBitmap(), meta.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y, rotationZ = rotation))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { rotation = (rotation + 90f) % 360f }) { Text("Rotar") }
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Ajustar") }
        }
    }
}

@Composable
private fun PdfViewer(meta: FileMeta) {
    val ctx = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var tmp by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val mutex = remember { Mutex() }
    LaunchedEffect(meta.id) {
        try {
            val (pfd, t) = withContext(Dispatchers.IO) { FileVault.openPdfDescriptor(ctx, meta) }
            tmp = t
            renderer = PdfRenderer(pfd)
        } catch (e: Exception) { error = "No se pudo abrir el PDF" }
    }
    DisposableEffect(meta.id) { onDispose { runCatching { renderer?.close() }; tmp?.delete() } }
    val r = renderer
    if (error != null) { Text(error!!, Modifier.padding(24.dp)); return }
    if (r == null) { Busy("Descifrando…"); return }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(r.pageCount) { i ->
            var page by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(i) {
                page = withContext(Dispatchers.Default) {
                    mutex.withLock {
                        r.openPage(i).use { p ->
                            val w = 1400
                            val h = (w.toFloat() * p.height / p.width).toInt()
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bm -> bm.eraseColor(AColor.WHITE); p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        }
                    }
                }
            }
            val pb = page
            if (pb != null) Image(pb.asImageBitmap(), "Página ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            else Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) { Text("Página ${i + 1}…") }
        }
    }
}

@Composable
private fun TextViewer(meta: FileMeta) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(meta.id) { text = withContext(Dispatchers.IO) { String(FileVault.decryptBytes(meta)).take(500_000) } }
    val t = text ?: run { Busy("Descifrando…"); return }
    Text(t, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

@Composable
private fun ShareDialog(meta: FileMeta, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember { FileVault.newShareCode() }
    var hours by remember { mutableStateOf(24) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Compartir cifrado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Se enviará un archivo .bsf que solo se abre con Bóveda Segura y este código. Díctalo por otro medio (llamada, en persona), nunca en el mismo mensaje.")
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
                }
                Text("Vence en:")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "1 h", 24 to "24 h", 168 to "7 días").forEach { (h, l) -> Chip(l, hours == h) { hours = h } }
                }
                Text("El vencimiento lo aplica Bóveda Segura al abrir el archivo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) Busy("Cifrando…")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val f = FileVault.createShared(ctx, meta, code, System.currentTimeMillis() + hours * 3_600_000L, true)
                busy = false
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Enviar archivo cifrado"))
                onDismiss()
            }
        }) { Text("Crear y enviar") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } }
    )
}
