package dev.meshaid.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.meshaid.app.service.MeshForegroundService

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) MeshForegroundService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissionsAndStart()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeshScreen()
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
fun MeshScreen() {
    val messages by MeshRepository.messages.collectAsState()
    val peerCount by MeshRepository.peerCount.collectAsState()
    val running by MeshRepository.meshRunning.collectAsState()
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (running) Color(0xFF1B5E20) else Color(0xFF757575))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MeshAid", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                if (running) "$peerCount peer${if (peerCount == 1) "" else "s"} nearby" else "mesh offline",
                color = Color.White,
            )
        }

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            reverseLayout = true,
        ) {
            items(messages.asReversed()) { msg ->
                MessageBubble(msg)
            }
        }

        // SOS
        Button(
            onClick = { MeshForegroundService.instance?.sendSos("Emergency — need help") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
        ) {
            Text("SOS — broadcast my location")
        }

        // Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message everyone nearby…") },
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        MeshForegroundService.instance?.sendChat(text)
                        draft = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: MeshRepository.ChatMessage) {
    val bg = when {
        msg.isSos -> Color(0xFFFFCDD2)
        msg.mine -> Color(0xFFC8E6C9)
        else -> Color(0xFFEEEEEE)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        contentAlignment = if (msg.mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .background(bg, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!msg.mine) {
                Text(
                    msg.fromId.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF616161),
                )
            }
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
