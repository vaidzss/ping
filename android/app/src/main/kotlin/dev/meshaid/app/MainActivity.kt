package dev.meshaid.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.meshaid.app.service.IdentityStore
import dev.meshaid.app.service.MeshForegroundService
import dev.meshaid.core.crypto.ContactCard
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.protocol.NodeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------- design tokens
// "Field radio" identity: emergencies mean blackouts and night — a dark panel is an
// OLED-battery decision, not a style. Three signal colors, each with one meaning.
internal val Night = Color(0xFF0C1116) // instrument panel background
internal val Panel = Color(0xFF131A21) // raised strips
internal val Inkwell = Color(0xFF223039) // hairlines
internal val Chalk = Color(0xFFE8E6DF) // primary text
internal val Slate = Color(0xFF77828C) // secondary text
internal val MeshGreen = Color(0xFF62D98A) // mesh status · verified traffic
internal val DmCyan = Color(0xFF57C7E3) // encrypted direct messages
internal val RescueOrange = Color(0xFFFF5A2D) // SOS. Nothing else is orange.
internal val SystemAmber = Color(0xFFE0A800) // app-generated notices — never sent over the mesh

internal val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {

    // Unlocked in-process by the login/signup screen; never round-tripped through an
    // Intent, so the private key never leaves this process's memory.
    private var unlockedIdentity: Identity? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val denied = grants.filterValues { !it }.keys
            if (denied.isEmpty()) {
                unlockedIdentity?.let { startMeshService(it) }
            } else {
                // Starting the service anyway would crash it — BleMeshTransport's BLE calls
                // throw SecurityException without these — so this used to just go quiet with
                // no mesh and no explanation at all.
                MeshRepository.addMessage(
                    MeshRepository.ChatMessage(
                        fromId = "system",
                        text = "Mesh can't start — denied: ${denied.joinToString { it.substringAfterLast('.') }}. " +
                            "Grant them from the phone's app settings, then reopen Ping.",
                        timestampMs = System.currentTimeMillis(),
                        mine = false,
                        system = true,
                    ),
                )
            }
        }

    // The result is ignored deliberately: whether the user allows or dismisses this, we still
    // start the service either way — BleMeshTransport now retries on its own tick loop, so
    // declining here just means BLE comes up later if they enable Bluetooth manually instead.
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            unlockedIdentity?.let { checkBluetoothAndStart(it) }
        }

    // Same reasoning — declining just means the OEM battery manager may still kill BLE in the
    // background later; the mesh still starts either way rather than being blocked on this.
    private val batteryExemptionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            unlockedIdentity?.let { checkBluetoothAndStart(it) }
        }

    private fun startMeshService(identity: Identity) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // ColorOS/OxygenOS/MIUI and friends are known to kill BLE scanning/advertising in a
            // background foreground service anyway unless explicitly exempted — this is exactly
            // the "phones never see each other" failure mode, not a bug in the BLE code itself.
            runCatching {
                batteryExemptionLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }.onFailure { checkBluetoothAndStart(identity) }
        } else {
            checkBluetoothAndStart(identity)
        }
    }

    private fun checkBluetoothAndStart(identity: Identity) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val canRequestEnable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (adapter != null && !adapter.isEnabled && canRequestEnable) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            MeshForegroundService.start(this, identity)
        }
    }

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { MeshForegroundService.instance?.sendImage(it) }
        }

    // CaptureActivity (from zxing-android-embedded) requests CAMERA itself if not yet
    // granted — no separate runtime-permission plumbing needed here.
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@registerForActivityResult
        runCatching { ContactCard.parse(scanned) }
            .onSuccess { card ->
                MeshForegroundService.instance?.addVerifiedContact(card)
                MeshRepository.setQrScanMessage("Added ${card.name.uppercase()} as a verified contact.")
            }
            .onFailure {
                MeshRepository.setQrScanMessage("That QR code isn't a Ping contact card.")
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Night, surface = Night)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Night) {
                    App(
                        onUnlocked = { identity ->
                            unlockedIdentity = identity
                            ensurePermissionsAndStart()
                        },
                        onPickPhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onScanContact = {
                            MeshRepository.setQrScanMessage(null)
                            qrScanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(true),
                            )
                        },
                    )
                }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val identity = unlockedIdentity ?: return
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
            startMeshService(identity)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/** Top-level screen switch: sign-up or login gates the mesh screen behind an unlocked identity. */
@Composable
private fun App(onUnlocked: (Identity) -> Unit, onPickPhoto: () -> Unit, onScanContact: () -> Unit) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    var hasAccount by remember { mutableStateOf<Boolean?>(null) }
    var signUpError by remember { mutableStateOf<String?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { hasAccount = IdentityStore.hasAccount(context) }

    when {
        unlocked -> MeshScreen(onPickPhoto = onPickPhoto, onScanContact = onScanContact)
        hasAccount == null -> Box(Modifier.fillMaxSize().background(Night))
        hasAccount == false -> SignUpScreen(
            error = signUpError,
            onSubmit = { username, password ->
                runCatching { IdentityStore.signUp(context, username, password) }
                    .onSuccess { identity ->
                        signUpError = null
                        unlocked = true
                        onUnlocked(identity)
                    }
                    .onFailure { e -> signUpError = e.message ?: "Could not create account." }
            },
        )
        else -> LoginScreen(
            username = remember { IdentityStore.username(context).orEmpty() },
            error = loginError,
            onSubmit = { password ->
                runCatching { IdentityStore.login(context, password) }
                    .onSuccess { identity ->
                        loginError = null
                        unlocked = true
                        onUnlocked(identity)
                    }
                    .onFailure { loginError = "Incorrect password." }
            },
        )
    }
}

/**
 * Which screen is showing. The default (and only "back" destination) is [ChatsList] — a list
 * of conversations, like any normal chat app. There is deliberately no screen that shows every
 * message ever received in one merged view; [Broadcast] and [Thread] are each scoped to one
 * conversation, opened by tapping into it.
 */
private sealed class Screen {
    data object ChatsList : Screen()
    data object Broadcast : Screen()
    data object MyQr : Screen()
    data object MeshMap : Screen()
    data class Thread(val peerId: String) : Screen()
}

@Composable
fun MeshScreen(onPickPhoto: () -> Unit, onScanContact: () -> Unit) {
    val running by MeshRepository.meshRunning.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.ChatsList) }

    // imePadding matters here: targetSdk 36 makes edge-to-edge mandatory, and without it the
    // keyboard covers the composer and SOS bar instead of pushing them up above it.
    Column(
        modifier = Modifier.fillMaxSize().background(Night)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (val s = screen) {
                Screen.ChatsList -> Column(Modifier.fillMaxSize()) {
                    InstrumentPanel()
                    ChatsList(
                        onOpenBroadcast = { screen = Screen.Broadcast },
                        onOpenThread = { id -> screen = Screen.Thread(id) },
                        onOpenMyQr = { screen = Screen.MyQr },
                        onOpenMap = { screen = Screen.MeshMap },
                    )
                }
                Screen.MyQr -> {
                    val service = MeshForegroundService.instance
                    val scanMessage by MeshRepository.qrScanMessage.collectAsState()
                    if (service != null) {
                        MyQrScreen(
                            myCard = service.myContactCard(),
                            onBack = { screen = Screen.ChatsList },
                            onScan = onScanContact,
                            scanResult = scanMessage,
                        )
                    }
                }
                Screen.MeshMap -> MapScreen(onBack = { screen = Screen.ChatsList })
                Screen.Broadcast -> BroadcastScreen(
                    onBack = { screen = Screen.ChatsList },
                    onPickPhoto = onPickPhoto,
                    enabled = running,
                )
                is Screen.Thread -> ThreadScreen(
                    peerId = s.peerId,
                    onBack = { screen = Screen.ChatsList },
                    enabled = running,
                )
            }
        }
        // A hairline seam keeps the emergency action visually distinct from whatever's above it
        // (composer, chats list) — previously there was zero separation between them.
        HairLine()
        // Emergency broadcast is always one tap away, regardless of which conversation is open.
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
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PING",
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
internal fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Inkwell))
}

// ---------------------------------------------------------------------------- chats list
// The main screen — like any chat app's home screen, this is a list of conversations, never
// a merged view of every message. One pinned row for the public mesh channel, then friends
// (each a real 1:1 thread), then anyone nearby who isn't a friend yet.

@Composable
private fun ChatsList(
    modifier: Modifier = Modifier,
    onOpenBroadcast: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenMyQr: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val messages by MeshRepository.messages.collectAsState()
    val peers by MeshRepository.peers.collectAsState()
    val friends by MeshRepository.friends.collectAsState()
    val self by MeshRepository.selfLocation.collectAsState()
    val now = System.currentTimeMillis()
    val fixCount = remember(peers) { peers.values.count { it.lat != null && it.lon != null } }

    val lastBroadcast = remember(messages) { messages.lastOrNull { !it.direct && !it.system } }
    val friendRows = friends.entries
        .map { (id, name) ->
            val lastMsg = messages.lastOrNull { it.direct && it.peerId == id }
            Triple(id, name, peers[id]) to lastMsg
        }
        .sortedByDescending { (row, lastMsg) -> lastMsg?.timestampMs ?: row.third?.lastSeenMs ?: 0L }
    val nearby = peers.entries
        .filter { (id, info) -> info.name != null && id !in friends }
        .sortedByDescending { it.value.lastSeenMs }

    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
        item { BroadcastRow(lastMessage = lastBroadcast, onClick = onOpenBroadcast) }
        item { MyQrRow(onClick = onOpenMyQr) }
        item { MapRow(fixCount = fixCount, onClick = onOpenMap) }
        if (friendRows.isNotEmpty()) {
            item { SectionLabel("FRIENDS") }
            items(friendRows) { (row, lastMsg) ->
                val (id, name, live) = row
                FriendRow(
                    id, name, live, lastMsg, self, now,
                    onClick = { onOpenThread(id) },
                    onRemove = { MeshForegroundService.instance?.removeFriend(id) },
                )
            }
        }
        if (nearby.isNotEmpty()) {
            item { SectionLabel("NEARBY — NOT YET ADDED") }
            items(nearby) { (id, info) ->
                NearbyRow(
                    name = info.name!!,
                    onAdd = { MeshForegroundService.instance?.addFriend(id, info.name) },
                )
            }
        }
        if (friendRows.isEmpty() && nearby.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "NO ONE ON THE MESH YET",
                        color = Slate,
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Wait for someone to come into range, then add them here to start chatting.",
                        color = Slate.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun BroadcastRow(lastMessage: MeshRepository.ChatMessage?, onClick: () -> Unit) {
    val running by MeshRepository.meshRunning.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLamp(running)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "MESH BROADCAST",
                color = Chalk,
                fontFamily = Mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                lastMessage?.let { "${MeshRepository.displayName(it.fromId)}: ${it.text}".take(48) }
                    ?: "public channel · everyone in range",
                color = Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        lastMessage?.let {
            Text(
                remember(it.timestampMs) { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.timestampMs)) },
                color = Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = Slate, fontFamily = Mono, fontSize = 16.sp)
    }
    HairLine()
}

@Composable
private fun MyQrRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(DmCyan, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "MY CONTACT CARD",
                color = DmCyan,
                fontFamily = Mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text("show your QR · scan someone else's", color = Slate, fontFamily = Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = Slate, fontFamily = Mono, fontSize = 16.sp)
    }
    HairLine()
}

@Composable
private fun MapRow(fixCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (fixCount > 0) MeshGreen else Slate, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "MESH MAP",
                color = if (fixCount > 0) MeshGreen else Chalk,
                fontFamily = Mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (fixCount == 0) "no GPS fixes yet"
                else if (fixCount == 1) "1 device with a fix" else "$fixCount devices with a fix",
                color = Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", color = Slate, fontFamily = Mono, fontSize = 16.sp)
    }
    HairLine()
}

// ---------------------------------------------------------------------------- broadcast screen
// The public mesh channel, on its own screen — opened from the pinned row in ChatsList, never
// shown by default. No chat bubbles here: entries read as a radio operator's log, since this
// is genuinely a shared channel everyone posts to, not a 1:1 conversation.

@Composable
private fun BroadcastScreen(onBack: () -> Unit, onPickPhoto: () -> Unit, enabled: Boolean) {
    val messages by MeshRepository.messages.collectAsState()
    val running by MeshRepository.meshRunning.collectAsState()
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ThreadHeaderRow(title = "MESH BROADCAST", online = running, onBack = onBack)
        TransmissionLog(
            messages = messages.filter { !it.direct },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            onPickPhoto = onPickPhoto,
            showPhotoButton = true,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    val service = MeshForegroundService.instance
                    if (service == null) {
                        // Rules out "the tap did nothing because the service died" —
                        // this can never be silent now.
                        MeshRepository.addMessage(
                            MeshRepository.ChatMessage(
                                fromId = "system",
                                text = "SEND FAILED - mesh service is not running. Restart the app.",
                                timestampMs = System.currentTimeMillis(),
                                mine = false,
                                system = true,
                            ),
                        )
                    } else {
                        service.sendChat(text)
                    }
                    draft = ""
                }
            },
            enabled = enabled,
        )
    }
}

// ---------------------------------------------------------------------------- transmission log
// No chat bubbles: entries read as a radio operator's log. The colored left rule is the
// message's signal class — green verified traffic, orange SOS.

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
        msg.system -> SystemAmber
        msg.isSos -> RescueOrange
        msg.mine -> Slate
        else -> MeshGreen
    }
    val time = remember(msg.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(msg.timestampMs))
    }
    val sender = if (msg.system) "PING" else if (msg.mine) "YOU" else MeshRepository.displayName(msg.fromId).uppercase()
    val marks = if (msg.verified && !msg.mine) "  ✓" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Panel, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Left accent strip stays the marker for message type (green=peer, orange=SOS,
        // amber=system, slate=you) — a "channel log" reads by that color rail, not by bubble
        // side, so every entry stays left-aligned regardless of who sent it.
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
                        msg.system -> SystemAmber
                        msg.isSos -> RescueOrange
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

// ---------------------------------------------------------------------------- friend threads
// A dedicated 1:1 view per friend, opened by tapping their row in ChatsList — ordinary
// chat-app shape (header + scrollback + composer), never mixed with anyone else's messages.

@Composable
private fun ThreadScreen(peerId: String, onBack: () -> Unit, enabled: Boolean) {
    val messages by MeshRepository.messages.collectAsState()
    val peers by MeshRepository.peers.collectAsState()
    val friends by MeshRepository.friends.collectAsState()
    var draft by remember { mutableStateOf("") }

    val name = friends[peerId] ?: MeshRepository.displayName(peerId)
    val liveInfo = peers[peerId]
    val online = liveInfo != null && (System.currentTimeMillis() - liveInfo.lastSeenMs) < 40_000
    val thread = remember(messages, peerId) {
        messages.filter { it.direct && it.peerId == peerId }
    }

    Column(Modifier.fillMaxSize()) {
        ThreadHeaderRow(title = name.uppercase(), online = online, onBack = onBack)
        ThreadLog(thread, modifier = Modifier.weight(1f).fillMaxWidth())
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            onPickPhoto = {},
            showPhotoButton = false,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    val service = MeshForegroundService.instance
                    if (service == null) {
                        MeshRepository.addMessage(
                            MeshRepository.ChatMessage(
                                fromId = "system",
                                text = "SEND FAILED - mesh service is not running. Restart the app.",
                                timestampMs = System.currentTimeMillis(),
                                mine = false,
                                system = true,
                            ),
                        )
                    } else {
                        service.sendDirectMessage(NodeId.parse(peerId), text)
                    }
                    draft = ""
                }
            },
            enabled = enabled,
        )
    }
}

/** Shared header for any single-conversation screen (broadcast or a friend's thread). */
@Composable
internal fun ThreadHeaderRow(title: String, online: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats", tint = Chalk)
        }
        HeaderAvatar(title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Chalk,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Text(
                if (online) "online — mesh live" else "not in range",
                color = if (online) MeshGreen else Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
            )
        }
    }
    HairLine()
}

/** A colored initial in a circle — the closest thing to a photo a mesh identity has. */
@Composable
private fun HeaderAvatar(title: String) {
    Box(
        modifier = Modifier.size(38.dp).background(Inkwell, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).uppercase(),
            color = Chalk,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun ThreadLog(messages: List<MeshRepository.ChatMessage>, modifier: Modifier = Modifier) {
    if (messages.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "NO MESSAGES YET — SAY HI",
                color = Slate,
                fontFamily = Mono,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        reverseLayout = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 12.dp),
    ) {
        items(messages.asReversed()) { msg -> ThreadBubble(msg) }
    }
}

@Composable
private fun ThreadBubble(msg: MeshRepository.ChatMessage) {
    val time = remember(msg.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(msg.timestampMs))
    }
    // Asymmetric corners with a tight "tail" corner pointing at whoever sent it — the same
    // shape language Signal/Telegram use to make a bubble read as coming from a direction,
    // not just a rounded rectangle floating in space.
    val shape = if (msg.mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (msg.mine) DmCyan.copy(alpha = 0.20f) else Panel, shape)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(msg.text, color = Chalk, fontSize = 15.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = Slate, fontFamily = Mono, fontSize = 10.sp)
                if (msg.verified && !msg.mine) {
                    Spacer(Modifier.width(6.dp))
                    Text("✓", color = MeshGreen, fontFamily = Mono, fontSize = 10.sp)
                }
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
    showPhotoButton: Boolean = true,
) {
    HairLine()
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showPhotoButton) {
            IconButton(onClick = onPickPhoto, enabled = enabled, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.ImageIcon,
                    contentDescription = "Attach a photo",
                    tint = if (enabled) Slate else Slate.copy(alpha = 0.4f),
                )
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (showPhotoButton) "Message everyone…" else "Message…",
                    color = Slate,
                    fontSize = 14.sp,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Chalk,
                unfocusedTextColor = Chalk,
                focusedContainerColor = Inkwell,
                unfocusedContainerColor = Inkwell,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = MeshGreen,
            ),
            shape = RoundedCornerShape(22.dp),
            maxLines = 4,
            // Without this, the keyboard's Enter key inserts a newline instead of
            // sending — easy to hit by habit and silent (no packet, no on-screen sign).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(6.dp))
        val canSend = enabled && draft.isNotBlank()
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(if (canSend) MeshGreen else Inkwell),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (canSend) Night else Slate,
                modifier = Modifier.size(20.dp),
            )
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

// ---------------------------------------------------------------------------- roster rows
// Privacy boundary: only friends (explicitly added, see FriendStore) get a persistent row
// with their location history and a chat thread. Everyone else nearby shows up in a
// separate "not yet added" section with a name and nothing else, until you choose to add
// them — a stranger's exact location is never surfaced just because they're in radio range.

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Slate,
        fontFamily = Mono,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FriendRow(
    id: String,
    name: String,
    live: MeshRepository.PeerInfo?,
    lastMessage: MeshRepository.ChatMessage?,
    self: Pair<Double, Double>?,
    now: Long,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val ageSec = live?.let { ((now - it.lastSeenMs) / 1000).coerceAtLeast(0) }
    val fresh = ageSec != null && ageSec < 40 // within the presence-expiry window
    var confirmingRemove by remember { mutableStateOf(false) }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove ${name.uppercase()}?", fontFamily = Mono, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Their chat history stays on this phone — this only removes them from " +
                        "your Roster. You can add them again later.",
                    color = Slate,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingRemove = false; onRemove() }) {
                    Text("REMOVE", color = RescueOrange, fontFamily = Mono, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) {
                    Text("CANCEL", color = Slate, fontFamily = Mono)
                }
            },
            containerColor = Panel,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { confirmingRemove = true })
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (fresh) MeshGreen else Slate, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name.uppercase(),
                    color = Chalk,
                    fontFamily = Mono,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                if (live?.verified == true) {
                    Spacer(Modifier.width(8.dp))
                    Text("✓", color = MeshGreen, fontFamily = Mono, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            val preview = when {
                lastMessage != null -> (if (lastMessage.mine) "You: " else "") + lastMessage.text
                live?.lat != null && live.lon != null && self != null -> {
                    val km = haversineKm(self.first, self.second, live.lat, live.lon)
                    "%.5f, %.5f  ·  %s away".format(live.lat, live.lon, formatDistance(km))
                }
                fresh -> "in range — say hi"
                else -> "not in range"
            }
            Text(preview, color = Slate, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            when {
                lastMessage != null -> SimpleDateFormat("HH:mm", Locale.US).format(Date(lastMessage.timestampMs))
                ageSec == null -> "—"
                ageSec < 5 -> "now"
                ageSec < 90 -> "${ageSec}s ago"
                else -> "${ageSec / 60}m ago"
            },
            color = if (fresh) Slate else Slate.copy(alpha = 0.6f),
            fontFamily = Mono,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text("›", color = Slate, fontFamily = Mono, fontSize = 16.sp)
    }
    HairLine()
}

@Composable
private fun NearbyRow(name: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(MeshGreen, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name.uppercase(),
                color = Chalk,
                fontFamily = Mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text("in range · not a friend yet", color = Slate, fontFamily = Mono, fontSize = 11.sp)
        }
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = MeshGreen, contentColor = Night),
            shape = RoundedCornerShape(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("ADD", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        }
    }
    HairLine()
}

internal fun formatDistance(km: Double): String =
    if (km < 1.0) "${(km * 1000).toInt()} m" else "%.1f km".format(km)

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

@Composable
private fun rememberBlobImage(hashHex: String): ImageBitmap? =
    remember(hashHex) {
        runCatching {
            val bytes = MeshForegroundService.instance?.blobStore?.read(hashHex) ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
