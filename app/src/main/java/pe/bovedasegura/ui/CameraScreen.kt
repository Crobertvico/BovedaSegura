@file:OptIn(ExperimentalLayoutApi::class)

package pe.bovedasegura.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bovedasegura.data.FileSource
import pe.bovedasegura.data.FileVault
import pe.bovedasegura.data.Vault
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun scaleDown(src: Bitmap, max: Int): Bitmap {
    val s = max.toFloat() / maxOf(src.width, src.height)
    return if (s >= 1f) src else Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
}

private fun jpeg(b: Bitmap, q: Int = 82): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

/** PDF con una página A4 por foto. */
private fun pagesToPdf(pages: List<Bitmap>): ByteArray {
    val doc = PdfDocument()
    pages.forEachIndexed { i, bmp ->
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
        val margin = 18f
        val area = RectF(margin, margin, 595 - margin, 842 - margin)
        val s = minOf(area.width() / bmp.width, area.height() / bmp.height)
        val w = bmp.width * s; val h = bmp.height * s
        val dst = RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2)
        page.canvas.drawBitmap(bmp, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
    }
    val out = ByteArrayOutputStream()
    doc.writeTo(out); doc.close()
    return out.toByteArray()
}

/**
 * Cámara segura: la foto se toma en memoria y se cifra directamente. Nunca pasa por la galería
 * ni por Google Fotos. Modo documento: varias páginas en un solo PDF.
 */
@Composable
fun CameraScreen(nav: NavHostController, itemId: String?) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var docMode by remember { mutableStateOf(false) }
    val pages = remember { mutableStateListOf<Bitmap>() }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(if (itemId != null) "General" else "Identidad") }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember { PreviewView(ctx) }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(granted) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val pv = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, imageCapture)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose { provider?.unbindAll() }
    }

    fun capture() {
        busy = true
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val raw = image.toBitmap()
                image.close()
                scope.launch {
                    val bmp = withContext(Dispatchers.Default) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                        scaleDown(r, 2200)
                    }
                    busy = false
                    if (docMode) pages += bmp else preview = bmp
                }
            }
            override fun onError(exception: ImageCaptureException) { busy = false }
        })
    }

    fun save() {
        busy = true
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        scope.launch {
            if (docMode) {
                val pdf = withContext(Dispatchers.Default) { pagesToPdf(pages.toList()) }
                FileVault.importBytes(pdf, (name.ifBlank { "Documento_$stamp" }) + ".pdf", "application/pdf", FileSource.SCAN, folder, itemId)
                pdf.fill(0)
                pages.forEach { it.recycle() }; pages.clear()
            } else {
                val b = preview ?: return@launch
                val bytes = withContext(Dispatchers.Default) { jpeg(b) }
                FileVault.importBytes(bytes, (name.ifBlank { "Foto_$stamp" }) + ".jpg", "image/jpeg", FileSource.CAMERA, folder, itemId)
                bytes.fill(0)
                b.recycle(); preview = null
            }
            busy = false
            saveDialog = false
            nav.popBackStack()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BackBar(if (docMode) "Escanear documento (${pages.size} pág.)" else "Cámara segura", { nav.popBackStack() })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val p = preview
            if (!granted) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Se necesita permiso de cámara", color = Color.White)
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Dar permiso") }
                }
            } else if (p != null) {
                Image(p.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                AndroidView({ previewView }, Modifier.fillMaxSize())
            }
        }
        if (pages.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pages.forEachIndexed { i, b ->
                Image(b.asImageBitmap(), "Página ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp, 76.dp).clickable { pages.removeAt(i) })
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (preview != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { preview?.recycle(); preview = null }) { Text("Repetir", color = Color.White) }
                    Button(onClick = { saveDialog = true }) { Text("Guardar cifrada") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Foto", !docMode) { docMode = false }
                    Chip("Documento (PDF)", docMode) { docMode = true }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.size(72.dp).background(if (busy) Color.Gray else Color.White, CircleShape).clickable(enabled = granted && !busy) { capture() })
                    if (docMode && pages.isNotEmpty()) Button(onClick = { saveDialog = true }) { Text("Guardar PDF") }
                }
                if (docMode) Text("Toca una miniatura para quitar esa página", color = Color.LightGray, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    if (saveDialog) AlertDialog(
        onDismissRequest = { if (!busy) saveDialog = false },
        title = { Text("Guardar en el cofre") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre (opcional)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Vault.current().settings.folders.forEach { f -> Chip(f, folder == f) { folder = f } }
                }
                Text("Va en el respaldo de la bóveda.", style = MaterialTheme.typography.bodySmall)
                if (busy) Busy("Cifrando…")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { save() }) { Text("Guardar") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { saveDialog = false }) { Text("Cancelar") } }
    )
}
