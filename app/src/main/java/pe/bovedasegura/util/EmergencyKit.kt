package pe.bovedasegura.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Qr {
    fun bitmap(text: String, size: Int = 512): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}

/** Kit de emergencia en PDF: clave secreta (texto + QR) e instrucciones de recuperación. */
object EmergencyKit {
    fun write(ctx: Context, uri: Uri, secretFormatted: String) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(15, 110, 86); isAntiAlias = true }
        val h2 = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; color = Color.DKGRAY; isAntiAlias = true }
        val mono = Paint().apply { textSize = 16f; typeface = Typeface.MONOSPACE; color = Color.BLACK; isAntiAlias = true }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
        var y = 60f
        c.drawText("Bóveda Segura · Kit de emergencia", 40f, y, title); y += 22
        c.drawText("Creado el " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "PE")).format(Date()), 40f, y, body); y += 30

        c.drawText("1. Tu clave secreta", 40f, y, h2); y += 22
        c.drawText(secretFormatted, 40f, y, mono); y += 14
        c.drawBitmap(Bitmap.createScaledBitmap(Qr.bitmap(secretFormatted, 360), 170, 170, false), 385f, 95f, null)
        y += 10
        wrap(c, "Esta clave, junto con tu contraseña maestra, abre tu bóveda y tus respaldos. No está guardada en ningún respaldo: solo en tu teléfono y en este documento.", 40f, y, 330f, body).also { y = it + 16 }

        c.drawText("2. Tu contraseña maestra (opcional, a mano)", 40f, y.coerceAtLeast(290f).also { y = it }, h2); y += 34
        c.drawLine(40f, y, 555f, y, line); y += 30

        c.drawText("3. Cómo recuperar tu bóveda en otro teléfono", 40f, y, h2); y += 20
        val steps = listOf(
            "Instala Bóveda Segura y elige \"Restaurar desde respaldo\".",
            "Selecciona tu archivo .vault más reciente (en Drive, tu PC o USB).",
            "Escanea el código QR de este documento o escribe la clave secreta.",
            "Escribe tu contraseña maestra. Tus cuentas y documentos vuelven al instante.",
            "Luego, si quieres, elige la carpeta BovedaSegura_multimedia para recuperar tus imágenes."
        )
        steps.forEachIndexed { i, s -> y = wrap(c, "${i + 1}) $s", 50f, y, 500f, body) + 6 }
        y += 16
        c.drawText("Importante", 40f, y, h2); y += 18
        listOf(
            "Guarda este documento impreso en un lugar físico seguro, separado de tu teléfono.",
            "No lo guardes en la misma carpeta que tus respaldos.",
            "Si olvidas tu contraseña maestra, nadie puede recuperar la bóveda: ni siquiera con este kit.",
            "Si generas una clave secreta nueva desde la app, este kit deja de servir para los respaldos nuevos."
        ).forEach { y = wrap(c, "• $it", 50f, y, 500f, body) + 4 }
        doc.finishPage(page)
        ctx.contentResolver.openOutputStream(uri, "w")!!.use { doc.writeTo(it) }
        doc.close()
    }

    private fun wrap(c: android.graphics.Canvas, text: String, x: Float, y0: Float, width: Float, p: Paint): Float {
        var y = y0
        var line = ""
        for (w in text.split(" ")) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) > width) { c.drawText(line, x, y, p); y += p.textSize + 4; line = w } else line = t
        }
        if (line.isNotEmpty()) { c.drawText(line, x, y, p); y += p.textSize + 4 }
        return y
    }
}
