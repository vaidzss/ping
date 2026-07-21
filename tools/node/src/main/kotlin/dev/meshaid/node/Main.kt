package dev.meshaid.node

import dev.meshaid.core.MeshNode
import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.dtn.BundleStore
import dev.meshaid.core.media.MimeTag
import dev.meshaid.core.protocol.GpsBeacon
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.transport.LanMeshTransport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * MeshAid desktop node: the same mesh core the Android app runs, over the LAN lane.
 * Lets a laptop join the mesh so the app can be exercised with a single phone
 * (phone hotspot + laptop = a real two-node mesh with zero internet).
 *
 * Usage: gradlew :tools:node:run --console=plain -q [--args="--name Laptop"]
 * Commands: plain text = broadcast chat · /sos <note> · /photo <path> · /peers · /quit
 */
fun main(args: Array<String>) {
    val name = args.toList().windowed(2).firstOrNull { it[0] == "--name" }?.get(1)
        ?: (System.getenv("COMPUTERNAME") ?: "Laptop")

    val home = Paths.get(System.getProperty("user.home"), ".meshaid")
    Files.createDirectories(home)
    val identity = loadOrCreateIdentity(home)
    val transport = LanMeshTransport(identity.nodeId)
    val blobDir = home.resolve("blobs")
    val blobStore = BlobStore(blobDir)
    val node = MeshNode(
        selfId = identity.nodeId,
        clock = System::currentTimeMillis,
        transport = transport,
        bundleStore = BundleStore(System::currentTimeMillis),
        blobStore = blobStore,
        identity = identity,
    )

    val names = HashMap<String, String>()
    fun label(id: Any): String = names[id.toString()] ?: id.toString().take(8)

    node.onMessage = { message ->
        val packet = message.packet
        val payload = message.payload
        // ASCII only: Windows consoles often aren't UTF-8 and garble fancy glyphs.
        val tag = buildString {
            if (message.direct) append(" [encrypted DM]")
            if (message.verified) append(" [verified]")
        }
        when (packet.type) {
            PacketType.CHAT -> println("\n[${label(packet.senderId)}]$tag ${String(payload)}")
            PacketType.SOS -> {
                val beacon = runCatching { GpsBeacon.decode(payload) }.getOrNull()
                val note = if (payload.size > GpsBeacon.SIZE) {
                    String(payload, GpsBeacon.SIZE, payload.size - GpsBeacon.SIZE)
                } else ""
                val fix = beacon?.takeIf { it.latE7 != 0 || it.lonE7 != 0 }
                    ?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "no fix"
                println("\n!!! SOS from ${label(packet.senderId)}$tag: $note ($fix) !!!")
            }
            PacketType.GPS_BEACON -> {
                runCatching { GpsBeacon.decode(payload) }.getOrNull()?.let {
                    println("\n[${label(packet.senderId)}] location: %.5f, %.5f".format(it.lat, it.lon))
                }
            }
            else -> Unit
        }
        print("> ")
    }
    node.onPeerPresence = { peer, peerName ->
        if (names.put(peer.toString(), peerName) != peerName) {
            println("\n* $peerName joined the mesh ($peer)")
            print("> ")
        }
    }
    node.onNeighborUp = {
        // Answer a new link immediately so key exchange doesn't wait for the heartbeat.
        node.sendPresence(name)
        node.sendAnnounce(name)
    }
    node.onMediaOffer = { offer, from ->
        println("\n* incoming media from ${label(from)} (${offer.totalSize / 1024} KB) — fetching…")
        true
    }
    node.onMediaReceived = { hash, mime, from ->
        val ext = when (mime) {
            MimeTag.JPEG -> "jpg"; MimeTag.PNG -> "png"; MimeTag.MP4 -> "mp4"; else -> "bin"
        }
        val outFile = home.resolve("received").also { Files.createDirectories(it) }
            .resolve("${hash.take(12)}.$ext")
        Files.write(outFile, blobStore.read(hash))
        println("\n* media from ${label(from)} saved: $outFile")
        print("> ")
    }

    node.start()
    println("MeshAid node '$name' up - id ${identity.nodeId}, LAN port ${transport.port}")
    println("Local addresses: ${transport.localAddresses().joinToString().ifEmpty { "NONE - not connected to any network?" }}")
    println("Join the phone's hotspot (or same Wi-Fi).")
    println("Commands: @name <msg> (encrypted DM), /sos <note>, /photo <path>, /loc <lat> <lon>, /peers, /quit")

    thread(isDaemon = true) {
        var beat = 0
        while (true) {
            node.sendPresence(name)
            if (beat++ % 6 == 0) node.sendAnnounce(name)
            node.tick()
            Thread.sleep(5_000)
        }
    }

    print("> ")
    generateSequence(::readLine).forEach { line ->
        val input = line.trim()
        when {
            input.isEmpty() -> Unit
            input == "/quit" -> {
                node.stop()
                exitProcess(0)
            }
            input == "/peers" -> {
                println("direct links: ${transport.peerCount()}, known names: ${names.values.joinToString().ifEmpty { "none yet" }}")
            }
            input.startsWith("/sos") -> {
                val note = input.removePrefix("/sos").trim().ifEmpty { "Emergency — need help" }
                node.send(PacketType.SOS, GpsBeacon(0, 0, 0, 100).encode() + note.toByteArray())
                println("SOS broadcast sent")
            }
            input.startsWith("/loc") -> {
                val parts = input.removePrefix("/loc").trim().split(Regex("\\s+"))
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lon == null) {
                    println("usage: /loc 26.9124 75.7873")
                } else {
                    node.send(PacketType.GPS_BEACON, GpsBeacon.of(lat, lon, 10, 100).encode())
                    println("location beacon sent: $lat, $lon")
                }
            }
            input.startsWith("@") -> {
                val space = input.indexOf(' ')
                val target = if (space > 1) input.substring(1, space) else ""
                val body = if (space > 1) input.substring(space + 1).trim() else ""
                val match = node.directory.byName(target)
                when {
                    body.isEmpty() -> println("usage: @name message")
                    match == null -> println("no peer named '$target' known yet (known: ${names.values.joinToString().ifEmpty { "none" }})")
                    else -> {
                        node.sendDirectChat(match.first, body)
                        println("(encrypted DM sent to ${match.second.name})")
                    }
                }
            }
            input.startsWith("/photo") -> {
                val path = input.removePrefix("/photo").trim().trim('"')
                runCatching {
                    val bytes = Files.readAllBytes(Paths.get(path))
                    val mime = if (path.lowercase().endsWith(".png")) MimeTag.PNG else MimeTag.JPEG
                    val hash = node.offerMedia(bytes, mime)
                    println("offered ${bytes.size / 1024} KB to the mesh ($hash)")
                }.onFailure { println("cannot offer '$path': ${it.message}") }
            }
            else -> {
                node.send(PacketType.CHAT, input.toByteArray())
            }
        }
        print("> ")
    }
    node.stop()
}

private fun loadOrCreateIdentity(home: Path): Identity {
    val file = home.resolve("identity")
    if (Files.exists(file)) {
        runCatching {
            return Identity.importPrivate(Base64.getDecoder().decode(Files.readAllLines(file).first().trim()))
        }
    }
    val identity = Identity.generate()
    Files.write(file, listOf(Base64.getEncoder().encodeToString(identity.exportPrivate())))
    return identity
}
