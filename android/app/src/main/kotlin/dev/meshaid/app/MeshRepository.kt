package dev.meshaid.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing state, written by the mesh service, observed by Compose.
 */
object MeshRepository {

    data class ChatMessage(
        val fromId: String,
        val text: String,
        val timestampMs: Long,
        val mine: Boolean,
        val isSos: Boolean = false,
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _meshRunning = MutableStateFlow(false)
    val meshRunning: StateFlow<Boolean> = _meshRunning.asStateFlow()

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun setPeerCount(count: Int) {
        _peerCount.value = count
    }

    fun setMeshRunning(running: Boolean) {
        _meshRunning.value = running
    }
}
