package dev.meshaid.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresencePayloadTest {

    @Test
    fun `round trips with keys`() {
        val signing = Random(1).nextBytes(32)
        val dh = Random(2).nextBytes(32)
        val presence = PresencePayload("Vaidic", signing, dh)
        val decoded = PresencePayload.decode(presence.encode())

        assertEquals("Vaidic", decoded.name)
        assertTrue(decoded.hasKeys)
        assertContentEquals(signing, decoded.signingPublic)
        assertContentEquals(dh, decoded.dhPublic)
    }

    @Test
    fun `round trips name-only presence`() {
        val presence = PresencePayload("Vaidic")
        val decoded = PresencePayload.decode(presence.encode())

        assertEquals("Vaidic", decoded.name)
        assertFalse(decoded.hasKeys)
        assertNull(decoded.signingPublic)
        assertNull(decoded.dhPublic)
    }

    @Test
    fun `rejects empty or truncated payloads`() {
        assertFailsWith<ProtocolException> { PresencePayload.decode(ByteArray(0)) }
        // Declares a 10-byte name but supplies none of it.
        assertFailsWith<ProtocolException> { PresencePayload.decode(byteArrayOf(10)) }
        // Declares a 5-byte name with only 3 bytes actually present.
        assertFailsWith<ProtocolException> { PresencePayload.decode(byteArrayOf(5, 1, 2, 3)) }
        // Zero-length name is invalid on the wire (encode() never produces it).
        assertFailsWith<ProtocolException> { PresencePayload.decode(byteArrayOf(0)) }
    }

    @Test
    fun `falls back to name-only when the trailing key material is short`() {
        val nameBytes = "Vaidic".toByteArray()
        // 40 trailing bytes is neither 0 nor the required 64 for both keys — decode()
        // treats anything under 64 as "no keys" rather than guessing at a partial key.
        val payload = byteArrayOf(nameBytes.size.toByte()) + nameBytes + ByteArray(40)
        val decoded = PresencePayload.decode(payload)
        assertEquals("Vaidic", decoded.name)
        assertFalse(decoded.hasKeys)
    }

    @Test
    fun `constructor rejects an oversized name`() {
        assertFailsWith<IllegalArgumentException> { PresencePayload("x".repeat(65)) }
    }

    @Test
    fun `constructor rejects announcing only one key`() {
        val key = Random(3).nextBytes(32)
        assertFailsWith<IllegalArgumentException> { PresencePayload("Vaidic", signingPublic = key) }
        assertFailsWith<IllegalArgumentException> { PresencePayload("Vaidic", dhPublic = key) }
    }

    @Test
    fun `constructor rejects wrong-length keys`() {
        assertFailsWith<IllegalArgumentException> {
            PresencePayload("Vaidic", signingPublic = ByteArray(31), dhPublic = ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            PresencePayload("Vaidic", signingPublic = ByteArray(32), dhPublic = ByteArray(33))
        }
    }
}
