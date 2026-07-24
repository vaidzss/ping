package dev.meshaid.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.meshaid.core.crypto.ContactCard

// ---------------------------------------------------------------------------- QR contact exchange
// In-person only, by design: a QR code has no range beyond arm's length, so scanning one is
// itself the proof of physical presence SECURITY.md calls out as the missing piece — presence
// broadcasts prove a key hashes to a NodeId, nothing proves the human across from you is the
// one holding that phone. This is that proof.

@Composable
internal fun MyQrScreen(myCard: ContactCard, onBack: () -> Unit, onScan: () -> Unit, scanResult: String?) {
    Column(Modifier.fillMaxSize()) {
        ThreadHeaderRow(title = "MY CONTACT CARD", online = true, onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val qr = rememberQrBitmap(myCard.toUri(), sizePx = 720)
            Box(
                modifier = Modifier
                    .background(Chalk, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = "Your contact QR code")
                } else {
                    Box(Modifier.height(280.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                myCard.name.uppercase(),
                color = Chalk,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Have someone scan this in person to add you as a verified contact — " +
                    "stronger than the usual \"seen nearby\" add, since it proves they're " +
                    "actually the one holding this key.",
                color = Slate,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MeshGreen, contentColor = Night),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("SCAN A CONTACT", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
            }
            scanResult?.let {
                Spacer(Modifier.height(18.dp))
                Text(it, color = MeshGreen, fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.5.sp)
            }
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String, sizePx: Int): ImageBitmap? {
    var bitmap by remember(content) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(content) {
        bitmap = runCatching {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) NIGHT_ARGB else CHALK_ARGB)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    return bitmap
}

private val NIGHT_ARGB = Color(0xFF0C1116).toArgb()
private val CHALK_ARGB = Color(0xFFE8E6DF).toArgb()

private fun Color.toArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
