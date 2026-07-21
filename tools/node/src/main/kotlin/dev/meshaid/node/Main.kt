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
    )

    val names = HashMap<String, String>()
    fun label(id: Any): String = names[id.toString()] ?: id.toString().take(8)

    node.onMessage = { packet ->
        when (packet.type) {
            PacketType.CHAT -> println("\n[${label(packet.senderId)}] ${String(packet.payload)}")
            PacketType.SOS -> {
                val beacon = runCatching { GpsBeacon.decode(packet.payload) }.getOrNull()
                val note = if (packet.payload.size > GpsBeacon.SIZE) {
                    String(packet.payload, GpsBeacon.SIZE, packet.payload.size - GpsBeacon.SIZE)
                } else ""
                val fix = beacon?.takeIf { it.latE7 != 0 || it.lonE7 != 0 }
                    ?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "no fix"
                println("\n!!! SOS from ${label(packet.senderId)}: $note ($fix) !!!")
            }
            PacketType.GPS_BEACON -> {
                runCatching { GpsBeacon.decode(packet.payload) }.getOrNull()?.let {
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
    println("MeshAid node '$name' up — id ${identity.nodeId}, LAN port ${transport.port}")
    println("Join the phone's hotspot (or same Wi-Fi). Commands: /sos <note>, /photo <path>, /peers, /quit")

    thread(isDaemon = true) {
        while (true) {
            node.sendPresence(name)
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
                node.send(PacketType.SOS, GpsBeacon(0, 0, 0, 100).encode() + note.toByteArray(), sign = identity::sign)
                println("SOS broadcast sent")
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
                node.send(PacketType.CHAT, input.toByteArray(), sign = identity::sign)
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
