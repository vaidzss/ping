package dev.meshaid.app.service

import dev.meshaid.app.MeshRepository.ChatMessage
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSONL message log so chat history survives app/service restarts.
 * (SQLite/SQLDelight is the Phase-1 upgrade; this keeps the DTN kill-the-app test honest.)
 */
class MessageLog(private val file: File) {

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
                .put("verified", message.verified)
                .put("direct", message.direct)
            file.appendText(json.toString() + "\n")
        }
    }

    @Synchronized
    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(MAX_LOADED).mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    ChatMessage(
                        fromId = json.getString("from"),
                        text = json.getString("text"),
                        timestampMs = json.getLong("ts"),
                        mine = json.getBoolean("mine"),
                        isSos = json.optBoolean("sos"),
                        imageHash = json.optString("image").takeIf { it.isNotEmpty() && it != "null" },
                        verified = json.optBoolean("verified"),
                        direct = json.optBoolean("direct"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
