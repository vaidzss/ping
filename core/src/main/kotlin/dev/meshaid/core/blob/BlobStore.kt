package dev.meshaid.core.blob

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Content-addressed blob store: media stored at blobs/<sha256-hex>.
 * Hash = identity → automatic dedup across the mesh and tamper-evidence for Phase 6.
 *
 * [seal]/[open] are an optional at-rest encryption hook applied only to the bytes written to
 * and read from disk — the content hash used for addressing (and for wire-protocol dedup and
 * transfer-integrity checks) is always computed over the plaintext [data] passed to [put],
 * never the on-disk form. This keeps the store usable both by callers with no password concept
 * at all (the desktop node — [seal]/[open] simply default to null, today's plaintext behavior)
 * and by the Android app, which can wire in [dev.meshaid.core.crypto.StorageVault] here without
 * touching how blobs are addressed or verified across the mesh.
 */
class BlobStore(
    private val dir: Path,
    private val seal: ((ByteArray) -> ByteArray)? = null,
    private val open: ((ByteArray) -> ByteArray)? = null,
) {
    init {
        Files.createDirectories(dir)
    }

    data class BlobRef(val hashHex: String, val size: Long)

    fun put(data: ByteArray): BlobRef {
        val hash = sha256Hex(data)
        val target = pathFor(hash)
        if (!Files.exists(target)) {
            val tmp = Files.createTempFile(dir, ".incoming", ".tmp")
            try {
                Files.write(tmp, seal?.invoke(data) ?: data)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Same content arrived concurrently — content addressing makes this a success.
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        return BlobRef(hash, data.size.toLong())
    }

    fun has(hashHex: String): Boolean = Files.exists(pathFor(hashHex))

    fun read(hashHex: String): ByteArray {
        val bytes = Files.readAllBytes(pathFor(hashHex))
        return open?.invoke(bytes) ?: bytes
    }

    fun delete(hashHex: String) {
        Files.deleteIfExists(pathFor(hashHex))
    }

    private fun pathFor(hashHex: String): Path {
        require(hashHex.length == 64 && hashHex.all { it in "0123456789abcdef" }) { "invalid blob hash" }
        return dir.resolve(hashHex)
    }

    companion object {
        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        fun sha256Hex(data: ByteArray): String =
            sha256(data).joinToString("") { "%02x".format(it) }
    }
}
