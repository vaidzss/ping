package dev.meshaid.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun `sign and verify round trip`() {
        val identity = Identity.generate()
        val data = "flood water rising near bridge".toByteArray()
        val sig = identity.sign(data)
        assertTrue(Identity.verify(identity.signingPublic.encoded, data, sig))
    }

    @Test
    fun `tampered data fails verification`() {
        val identity = Identity.generate()
        val data = "original".toByteArray()
        val sig = identity.sign(data)
        assertFalse(Identity.verify(identity.signingPublic.encoded, "originaX".toByteArray(), sig))
    }

    @Test
    fun `impersonation without the private key fails`() {
        val victim = Identity.generate()
        val attacker = Identity.generate()
        val data = "I am the victim".toByteArray()
        val forged = attacker.sign(data)
        assertFalse(Identity.verify(victim.signingPublic.encoded, data, forged))
    }

    @Test
    fun `identity survives export and import`() {
        val identity = Identity.generate()
        val restored = Identity.importPrivate(identity.exportPrivate())
        assertEquals(identity.nodeId, restored.nodeId)
        val data = "persisted".toByteArray()
        assertTrue(Identity.verify(identity.signingPublic.encoded, data, restored.sign(data)))
    }

    @Test
    fun `sealed box round trips to the right recipient`() {
        val recipient = Identity.generate()
        val plaintext = "meet at the school shelter".toByteArray()
        val sealed = SealedBox.seal(recipient.dhPublic.encoded, plaintext)
        assertContentEquals(plaintext, SealedBox.open(recipient, sealed))
    }

    @Test
    fun `wrong recipient cannot open a sealed box`() {
        val recipient = Identity.generate()
        val eavesdropper = Identity.generate()
        val sealed = SealedBox.seal(recipient.dhPublic.encoded, "secret".toByteArray())
        assertFailsWith<CryptoException> { SealedBox.open(eavesdropper, sealed) }
    }

    @Test
    fun `tampered sealed box fails authentication`() {
        val recipient = Identity.generate()
        val sealed = SealedBox.seal(recipient.dhPublic.encoded, "secret".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertFailsWith<CryptoException> { SealedBox.open(recipient, sealed) }
    }

    @Test
    fun `same plaintext seals to different ciphertexts`() {
        val recipient = Identity.generate()
        val a = SealedBox.seal(recipient.dhPublic.encoded, "hello".toByteArray())
        val b = SealedBox.seal(recipient.dhPublic.encoded, "hello".toByteArray())
        assertFalse(a.contentEquals(b), "ephemeral keys must differ per seal")
    }

    @Test
    fun `contact card round trips through its QR uri`() {
        val identity = Identity.generate()
        val card = ContactCard.of(identity, "Vaidic S / जयपुर")
        val parsed = ContactCard.parse(card.toUri())
        assertEquals(card.name, parsed.name)
        assertContentEquals(card.signingPublic, parsed.signingPublic)
        assertContentEquals(card.dhPublic, parsed.dhPublic)
        assertEquals(identity.nodeId, parsed.nodeId)
    }
}
