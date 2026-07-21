package dev.meshaid.core.transport

import dev.meshaid.core.MeshMessage
import dev.meshaid.core.MeshNode
import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.dtn.BundleStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mirrors the real phone+laptop setup exactly: two identity-bearing nodes over TCP,
 * presence with keys both ways, then encrypted DMs in BOTH directions — the field test
 * showed laptop→phone DMs working but phone→laptop reported missing.
 */
class DmOverLanTest {

    private fun await(timeoutMs: Long = 10_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for: $what")
    }

    @Test
    fun `encrypted DMs deliver in both directions over sockets`() {
        val idPhone = Identity.generate()
        val idLaptop = Identity.generate()
        val lanPhone = LanMeshTransport(idPhone.nodeId, enableDiscovery = false)
        val lanLaptop = LanMeshTransport(idLaptop.nodeId, enableDiscovery = false)

        fun mesh(identity: Identity, lan: LanMeshTransport) = MeshNode(
            identity.nodeId,
            System::currentTimeMillis,
            lan,
            bundleStore = BundleStore(System::currentTimeMillis),
            blobStore = BlobStore(Files.createTempDirectory("dm-lan")),
            identity = identity,
        )

        val phone = mesh(idPhone, lanPhone)
        val laptop = mesh(idLaptop, lanLaptop)

        val atLaptop = mutableListOf<MeshMessage>()
        val atPhone = mutableListOf<MeshMessage>()
        laptop.onMessage = { atLaptop.add(it) }
        phone.onMessage = { atPhone.add(it) }

        try {
            phone.start()
            laptop.start()
            lanPhone.connectTo("127.0.0.1", lanLaptop.port)
            await(what = "link up") { lanPhone.peerCount() == 1 && lanLaptop.peerCount() == 1 }

            // Presence with keys, both ways (what the 5s/10s heartbeats do in production).
            phone.sendPresence("MeshAid-test")
            laptop.sendPresence("VAIDZ")
            await(what = "phone learns laptop keys") { phone.directory.get(idLaptop.nodeId) != null }
            await(what = "laptop learns phone keys") { laptop.directory.get(idPhone.nodeId) != null }
            assertTrue(phone.directory.byName("VAIDZ") != null, "byName lookup must work")

            // The reported failure: phone -> laptop DM.
            phone.sendDirectChat(idLaptop.nodeId, "hi from phone")
            await(what = "phone->laptop DM") {
                atLaptop.any { it.direct && String(it.payload) == "hi from phone" }
            }
            assertTrue(atLaptop.single { it.direct }.verified, "DM must arrive verified")

            // And the direction that worked in the field, as a control.
            laptop.sendDirectChat(idPhone.nodeId, "got it")
            await(what = "laptop->phone DM") {
                atPhone.any { it.direct && String(it.payload) == "got it" }
            }

            // Plain broadcast phone -> laptop too, since the report said "messages" generally.
            phone.send(dev.meshaid.core.protocol.PacketType.CHAT, "broadcast from phone".toByteArray())
            await(what = "phone->laptop broadcast") {
                atLaptop.any { !it.direct && String(it.payload) == "broadcast from phone" }
            }
        } finally {
            phone.stop()
            laptop.stop()
        }
    }
}
