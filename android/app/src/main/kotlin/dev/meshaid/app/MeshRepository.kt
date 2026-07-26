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
        /** Content hash of an image in the blob store, for photo messages. */
        val imageHash: String? = null,
        /** Content hash of a video in the blob store, for video messages. */
        val videoHash: String? = null,
        /** Signature verified against the sender's announced key. */
        val verified: Boolean = false,
        /** Encrypted 1:1 message. */
        val direct: Boolean = false,
        /** App-generated notice (e.g. "no peer named X") — never sent over the mesh. */
        val system: Boolean = false,
        /** The other party's NodeId for a direct message, regardless of direction — threads the conversation. */
        val peerId: String? = null,
    )

    data class PeerInfo(
        val id: String,
        val name: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val lastSeenMs: Long = 0,
        val verified: Boolean = false,
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
    val peers: StateFlow<Map<String, PeerInfo>> = _peers.asStateFlow()

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val _meshRunning = MutableStateFlow(false)
    val meshRunning: StateFlow<Boolean> = _meshRunning.asStateFlow()

    private val _selfCallsign = MutableStateFlow("—")
    val selfCallsign: StateFlow<String> = _selfCallsign.asStateFlow()

    /** id -> name. The privacy boundary for the Roster — see [dev.meshaid.app.service.FriendStore]. */
    private val _friends = MutableStateFlow<Map<String, String>>(emptyMap())
    val friends: StateFlow<Map<String, String>> = _friends.asStateFlow()

    /** Result of the most recent QR scan attempt, shown inline on MyQrScreen. */
    private val _qrScanMessage = MutableStateFlow<String?>(null)
    val qrScanMessage: StateFlow<String?> = _qrScanMessage.asStateFlow()

    fun setQrScanMessage(message: String?) {
        _qrScanMessage.value = message
    }

    fun setFriends(map: Map<String, String>) {
        _friends.value = map
    }

    /** Bundles this phone is carrying for other people (the data-mule stat). */
    private val _carryingCount = MutableStateFlow(0)
    val carryingCount: StateFlow<Int> = _carryingCount.asStateFlow()

    fun setSelfCallsign(name: String) {
        _selfCallsign.value = name
    }

    fun setCarryingCount(count: Int) {
        _carryingCount.value = count
    }

    private val _selfLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val selfLocation: StateFlow<Pair<Double, Double>?> = _selfLocation.asStateFlow()

    fun setSelfLocation(lat: Double, lon: Double) {
        _selfLocation.value = lat to lon
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    /** Seed history from the persisted log (only when nothing is loaded yet). */
    fun seedHistory(history: List<ChatMessage>) {
        if (_messages.value.isEmpty() && history.isNotEmpty()) {
            _messages.value = history
        }
    }

    fun updatePeer(id: String, update: (PeerInfo) -> PeerInfo) {
        val current = _peers.value[id] ?: PeerInfo(id)
        _peers.value = _peers.value + (id to update(current))
    }

    fun displayName(id: String): String = _peers.value[id]?.name ?: id.take(8)

    fun setPeerCount(count: Int) {
        _peerCount.value = count
    }

    fun setMeshRunning(running: Boolean) {
        _meshRunning.value = running
    }
}
