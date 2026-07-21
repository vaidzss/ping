package dev.meshaid.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.meshaid.app.service.MeshForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------- design tokens
// "Field radio" identity: emergencies mean blackouts and night — a dark panel is an
// OLED-battery decision, not a style. Three signal colors, each with one meaning.
private val Night = Color(0xFF0C1116) // instrument panel background
private val Panel = Color(0xFF131A21) // raised strips
private val Inkwell = Color(0xFF223039) // hairlines
private val Chalk = Color(0xFFE8E6DF) // primary text
private val Slate = Color(0xFF77828C) // secondary text
private val MeshGreen = Color(0xFF62D98A) // mesh status · verified traffic
private val DmCyan = Color(0xFF57C7E3) // encrypted direct messages
private val RescueOrange = Color(0xFFFF5A2D) // SOS. Nothing else is orange.

private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) MeshForegroundService.start(this)
        }

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { MeshForegroundService.instance?.sendImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissionsAndStart()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Night)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Night) {
                    MeshScreen(
                        onPickPhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            MeshForegroundService.start(this)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun MeshScreen(onPickPhoto: () -> Unit) {
    val messages by MeshRepository.messages.collectAsState()
    val running by MeshRepository.meshRunning.collectAsState()
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(Night)) {
        InstrumentPanel()
        TransmissionLog(
            messages = messages,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            onPickPhoto = onPickPhoto,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    MeshForegroundService.instance?.sendChat(text)
                    draft = ""
                }
            },
            enabled = running,
        )
        SosBar(enabled = running)
    }
}

// ---------------------------------------------------------------------------- instrument panel

@Composable
private fun InstrumentPanel() {
    val running by MeshRepository.meshRunning.collectAsState()
    val peerCount by MeshRepository.peerCount.collectAsState()
    val peers by MeshRepository.peers.collectAsState()
    val callsign by MeshRepository.selfCallsign.collectAsState()
    val carrying by MeshRepository.carryingCount.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "MESHAID",
                color = Chalk,
                fontFamily = Mono,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                letterSpacing = 4.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLamp(running)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (running) "MESH LIVE" else "MESH OFF",
                    color = if (running) MeshGreen else Slate,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "CALLSIGN $callsign",
                color = Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
            Text(
                if (peerCount == 1) "1 PEER IN RANGE" else "$peerCount PEERS IN RANGE",
                color = if (peerCount > 0) MeshGreen else Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
        }
        val named = peers.values.mapNotNull { it.name }
        if (named.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "IN RANGE: " + named.joinToString(" · "),
                color = Chalk,
                fontFamily = Mono,
                fontSize = 11.sp,
            )
        }
        if (carrying > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                // The stat no ordinary chat app can show: this phone is a courier.
                "carrying $carrying message${if (carrying == 1) "" else "s"} for others",
                color = MeshGreen.copy(alpha = 0.75f),
                fontFamily = Mono,
                fontSize = 11.sp,
            )
        }
    }
    HairLine()
}

@Composable
private fun StatusLamp(running: Boolean) {
    val alpha = if (running) {
        val transition = rememberInfiniteTransition(label = "lamp")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "lampAlpha",
        ).value
    } else 1f
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(if (running) MeshGreen else Slate, CircleShape),
    )
}

@Composable
private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Inkwell))
}

// ---------------------------------------------------------------------------- transmission log
// No chat bubbles: entries read as a radio operator's log. The colored left rule is the
// message's signal class — green verified traffic, cyan encrypted DM, orange SOS.

@Composable
private fun TransmissionLog(messages: List<MeshRepository.ChatMessage>, modifier: Modifier = Modifier) {
    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "NO TRAFFIC YET",
                    color = Slate,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your radio is listening.\nAnyone in range appears here — no internet needed.",
                    color = Slate.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
    ) {
        items(messages.asReversed()) { msg ->
            LogEntry(msg)
        }
    }
}

@Composable
private fun LogEntry(msg: MeshRepository.ChatMessage) {
    val rule = when {
        msg.isSos -> RescueOrange
        msg.direct -> DmCyan
        msg.mine -> Slate
        else -> MeshGreen
    }
    val time = remember(msg.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(msg.timestampMs))
    }
    val sender = if (msg.mine) "YOU" else MeshRepository.displayName(msg.fromId).uppercase()
    val marks = buildString {
        if (msg.direct) append("  DM")
        if (msg.verified && !msg.mine) append("  ✓")
    }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 34.dp)
                .background(rule, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = Slate, fontFamily = Mono, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    sender + marks,
                    color = when {
                        msg.isSos -> RescueOrange
                        msg.direct -> DmCyan
                        msg.mine -> Slate
                        else -> Chalk
                    },
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
            msg.imageHash?.let { hash ->
                Spacer(Modifier.height(4.dp))
                val bitmap = rememberBlobImage(hash)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "photo",
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("photo unavailable", color = Slate, fontFamily = Mono, fontSize = 12.sp)
                }
            }
            if (msg.text.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    msg.text,
                    color = if (msg.isSos) Chalk else Chalk.copy(alpha = if (msg.mine) 0.85f else 1f),
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = if (msg.isSos) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- composer + SOS

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    HairLine()
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPickPhoto,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Inkwell, contentColor = Chalk),
            shape = RoundedCornerShape(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            Text("IMG", fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("message all · @name for private", color = Slate, fontSize = 13.sp)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Chalk,
                unfocusedTextColor = Chalk,
                focusedContainerColor = Night,
                unfocusedContainerColor = Night,
                focusedBorderColor = MeshGreen.copy(alpha = 0.6f),
                unfocusedBorderColor = Inkwell,
                cursorColor = MeshGreen,
            ),
            shape = RoundedCornerShape(6.dp),
            maxLines = 3,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSend,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MeshGreen,
                contentColor = Night,
                disabledContainerColor = Inkwell,
                disabledContentColor = Slate,
            ),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text("SEND", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun SosBar(enabled: Boolean) {
    Button(
        onClick = { MeshForegroundService.instance?.sendSos("Emergency — need help") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RescueOrange,
            contentColor = Color(0xFF140904),
            disabledContainerColor = Panel,
            disabledContentColor = Slate,
        ),
        shape = RoundedCornerShape(0.dp),
    ) {
        Text(
            "SOS — BROADCAST MY LOCATION",
            fontFamily = Mono,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun rememberBlobImage(hashHex: String): ImageBitmap? =
    remember(hashHex) {
        runCatching {
            val bytes = MeshForegroundService.instance?.blobStore?.read(hashHex) ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
