package dev.meshaid.app.service

import android.util.Base64
import dev.meshaid.app.MeshRepository.ChatMessage
import dev.meshaid.core.crypto.StorageVault
import org.json.JSONObject
import java.io.File

/**
 * Append-only, encrypted-at-rest JSONL message log so chat history survives app/service
 * restarts without sitting in the clear on disk. Each line is
 * Base64(StorageVault.seal(jsonBytes, key)) — sealed rather than raw JSON, and Base64 rather
 * than raw ciphertext, so the file stays line-based (raw ciphertext can otherwise contain a
 * stray newline byte and corrupt `readLines()`).
 *
 * (SQLite/SQLDelight is the Phase-1 upgrade; this keeps the DTN kill-the-app test honest.)
 */
class MessageLog(private val file: File, private val key: ByteArray) {

    companion object {
        private const val MAX_LOADED = 500
    }

    @Synchronized
    fun append(message: ChatMessage) {
        runCatching {
            val json = JSONObject()
                .put("from", message.fromId)
                .put("text", message.text)
                .put("ts", message.timestampMs)
                .put("mine", message.mine)
                .put("sos", message.isSos)
                .put("image", message.imageHash ?: JSONObject.NULL)
                .put("video", message.videoHash ?: JSONObject.NULL)
                .put("verified", message.verified)
                .put("direct", message.direct)
                .put("peer", message.peerId ?: JSONObject.NULL)
            val sealed = StorageVault.seal(json.toString().toByteArray(Charsets.UTF_8), key)
            file.appendText(Base64.encodeToString(sealed, Base64.NO_WRAP) + "\n")
        }
    }

    @Synchronized
    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(MAX_LOADED).mapNotNull { line ->
                runCatching {
                    val sealed = Base64.decode(line, Base64.NO_WRAP)
                    val json = JSONObject(String(StorageVault.open(sealed, key), Charsets.UTF_8))
                    ChatMessage(
                        fromId = json.getString("from"),
                        text = json.getString("text"),
                        timestampMs = json.getLong("ts"),
                        mine = json.getBoolean("mine"),
                        isSos = json.optBoolean("sos"),
                        imageHash = json.optString("image").takeIf { it.isNotEmpty() && it != "null" },
                        videoHash = json.optString("video").takeIf { it.isNotEmpty() && it != "null" },
                        verified = json.optBoolean("verified"),
                        direct = json.optBoolean("direct"),
                        peerId = json.optString("peer").takeIf { it.isNotEmpty() && it != "null" },
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
