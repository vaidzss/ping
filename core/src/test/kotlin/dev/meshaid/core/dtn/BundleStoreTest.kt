package dev.meshaid.core.dtn

import dev.meshaid.core.mesh.FakeClock
import dev.meshaid.core.protocol.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleStoreTest {

    private val clock = FakeClock()
    private val store = BundleStore({ clock.now })

    private var seq = 0
    private fun bundle(
        priority: Int = BundlePriority.NORMAL,
        lifetimeMs: Long = Bundle.DEFAULT_LIFETIME_MS,
        copyBudget: Int = Bundle.DEFAULT_COPY_BUDGET,
    ): Bundle {
        val id = MessageId(ByteArray(16).also { it[0] = (seq++).toByte(); it[1] = (seq shr 8).toByte() })
        return Bundle(id, ByteArray(32), clock.now, lifetimeMs, priority, copyBudget)
    }

    @Test
    fun `bundles expire after their lifetime`() {
        val b = bundle(lifetimeMs = 1000)
        store.add(b)
        assertNotNull(store.get(b.id))
        clock.advance(1001)
        assertNull(store.get(b.id))
        assertEquals(0, store.size())
    }

    @Test
    fun `copy budget stops spraying after eight handoffs`() {
        val b = bundle(copyBudget = 8)
        store.add(b)
        repeat(8) {
            assertEquals(1, store.takeForSpray(listOf(b.id)).size, "handoff ${it + 1} should succeed")
        }
        assertTrue(store.takeForSpray(listOf(b.id)).isEmpty(), "budget exhausted, must stop spraying")
        // Bundle is still carried locally (it may reach its destination through us).
        assertNotNull(store.get(b.id))
    }

    @Test
    fun `summary vector sync converges two stores`() {
        val remoteStore = BundleStore({ clock.now })
        val a = bundle(); val b = bundle(); val c = bundle()
        store.add(a); store.add(b)
        remoteStore.add(b); remoteStore.add(c)

        // Contact: each side learns what it's missing and pulls it.
        val weWant = store.missingFrom(remoteStore.summaryVector())
        val theyWant = remoteStore.missingFrom(store.summaryVector())
        assertEquals(listOf(c.id), weWant)
        assertEquals(listOf(a.id), theyWant)

        store.takeForSpray(theyWant).forEach { remoteStore.add(Bundle(it.id, it.encodedPacket, clock.now)) }
        remoteStore.takeForSpray(weWant).forEach { store.add(Bundle(it.id, it.encodedPacket, clock.now)) }

        assertEquals(store.summaryVector().toSet(), remoteStore.summaryVector().toSet())
        assertEquals(3, store.size())
    }

    @Test
    fun `duplicate add is rejected`() {
        val b = bundle()
        assertTrue(store.add(b))
        assertFalse(store.add(b))
    }

    @Test
    fun `eviction protects SOS bundles from normal traffic`() {
        val tiny = BundleStore({ clock.now }, maxBundles = 2)
        val sos = bundle(priority = BundlePriority.SOS)
        val normal = bundle()
        tiny.add(sos)
        clock.advance(10)
        tiny.add(normal)

        // Store full: a new normal bundle must evict the old NORMAL one, never the SOS.
        val newcomer = bundle()
        assertTrue(tiny.add(newcomer))
        assertNotNull(tiny.get(sos.id), "SOS bundle must survive eviction")
        assertNull(tiny.get(normal.id))
    }
}
