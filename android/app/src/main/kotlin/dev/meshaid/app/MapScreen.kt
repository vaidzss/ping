package dev.meshaid.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------- mesh map
// No tile server, no downloaded map data — a real map needs internet or a bundled tile
// pack, and this app's whole pitch is zero infrastructure. Instead: a relative-position
// radar, you at the center, everyone with a GPS fix plotted by bearing and distance from
// you. Reads less like Google Maps and more like an aircraft's traffic display — which is
// honestly the more honest representation of what a GPS_BEACON packet actually gives you.

private data class MapContact(
    val id: String,
    val name: String,
    val distanceKm: Double,
    val bearingDeg: Double,
    val isFriend: Boolean,
    val fresh: Boolean,
    val verified: Boolean,
)

@Composable
internal fun MapScreen(onBack: () -> Unit) {
    val peers by MeshRepository.peers.collectAsState()
    val friends by MeshRepository.friends.collectAsState()
    val self by MeshRepository.selfLocation.collectAsState()
    val now by androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    val contacts = remember(peers, friends, self, now) {
        val (selfLat, selfLon) = self ?: return@remember emptyList()
        peers.values
            .mapNotNull { p ->
                val lat = p.lat ?: return@mapNotNull null
                val lon = p.lon ?: return@mapNotNull null
                val ageSec = ((now - p.lastSeenMs) / 1000).coerceAtLeast(0)
                MapContact(
                    id = p.id,
                    name = p.name ?: friends[p.id] ?: p.id.take(6),
                    distanceKm = haversineKm(selfLat, selfLon, lat, lon),
                    bearingDeg = bearingDeg(selfLat, selfLon, lat, lon),
                    isFriend = p.id in friends,
                    fresh = ageSec < 40,
                    verified = p.verified,
                )
            }
            .sortedBy { it.distanceKm }
    }

    Column(Modifier.fillMaxSize()) {
        ThreadHeaderRow(title = "MESH MAP", online = contacts.isNotEmpty(), onBack = onBack)
        when {
            self == null -> EmptyMapNotice(
                "WAITING FOR YOUR GPS FIX",
                "Move somewhere with a clear sky view, or send an SOS to force a location read.",
            )
            contacts.isEmpty() -> EmptyMapNotice(
                "NO FIXES ON THE MESH YET",
                "This fills in as soon as someone nearby's phone reports a GPS_BEACON or SOS location.",
            )
            else -> Column(Modifier.fillMaxSize()) {
                RadarView(contacts, modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(20.dp))
                HairLine()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts) { contact -> MapContactRow(contact) }
                }
            }
        }
    }
}

@Composable
private fun EmptyMapNotice(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Slate, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = Slate.copy(alpha = 0.7f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun RadarView(contacts: List<MapContact>, modifier: Modifier = Modifier) {
    val maxDistanceKm = remember(contacts) { (contacts.maxOf { it.distanceKm }).coerceAtLeast(0.05) }
    val ringLabelColor = Slate.copy(alpha = 0.55f).toArgb()
    val meshGreenArgb = MeshGreen.toArgb()
    val dmCyanArgb = DmCyan.toArgb()
    val slateArgb = Slate.toArgb()
    val chalkArgb = Chalk.toArgb()

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadiusPx = min(size.width, size.height) / 2f * 0.82f

        // Range rings at 1/3, 2/3, and full scale.
        listOf(1f / 3f, 2f / 3f, 1f).forEach { fraction ->
            drawCircle(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                radius = maxRadiusPx * fraction,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatDistance(maxDistanceKm * fraction),
                center.x + 4.dp.toPx(),
                center.y - maxRadiusPx * fraction + 12.dp.toPx(),
                android.graphics.Paint().apply {
                    color = ringLabelColor
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                },
            )
        }

        // Self, dead center.
        drawCircle(color = androidx.compose.ui.graphics.Color(meshGreenArgb), radius = 7.dp.toPx(), center = center)
        drawContext.canvas.nativeCanvas.drawText(
            "YOU",
            center.x,
            center.y + 22.dp.toPx(),
            android.graphics.Paint().apply {
                color = chalkArgb
                textSize = 11.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                isFakeBoldText = true
            },
        )

        contacts.forEach { contact ->
            val fraction = (contact.distanceKm / maxDistanceKm).toFloat().coerceIn(0.06f, 1f)
            val angleRad = Math.toRadians(contact.bearingDeg).let { it }
            val x = center.x + (sin(angleRad) * maxRadiusPx * fraction).toFloat()
            val y = center.y - (cos(angleRad) * maxRadiusPx * fraction).toFloat()
            val dotColor = when {
                !contact.fresh -> androidx.compose.ui.graphics.Color(slateArgb)
                contact.isFriend -> androidx.compose.ui.graphics.Color(dmCyanArgb)
                else -> androidx.compose.ui.graphics.Color(meshGreenArgb)
            }
            drawCircle(color = dotColor, radius = 6.dp.toPx(), center = Offset(x, y))
            drawContext.canvas.nativeCanvas.drawText(
                contact.name.uppercase().take(10),
                x,
                y + 18.dp.toPx(),
                android.graphics.Paint().apply {
                    color = dotColor.toArgb()
                    textSize = 10.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                },
            )
        }
    }
}

@Composable
private fun MapContactRow(contact: MapContact) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).background(
                if (!contact.fresh) Slate else if (contact.isFriend) DmCyan else MeshGreen,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    contact.name.uppercase(),
                    color = Chalk,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                if (contact.verified) {
                    Spacer(Modifier.width(6.dp))
                    Text("✓", color = MeshGreen, fontFamily = Mono, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                compassLabel(contact.bearingDeg) + " · " + (if (contact.isFriend) "friend" else "not added"),
                color = Slate,
                fontFamily = Mono,
                fontSize = 11.sp,
            )
        }
        Text(
            formatDistance(contact.distanceKm),
            color = if (contact.fresh) MeshGreen else Slate,
            fontFamily = Mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    HairLine()
}

private fun compassLabel(bearingDeg: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = (((bearingDeg % 360) + 360) % 360 / 45.0).let { Math.round(it).toInt() % 8 }
    return points[index]
}

/** Initial bearing from (lat1,lon1) to (lat2,lon2), in degrees, 0 = north. */
private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
