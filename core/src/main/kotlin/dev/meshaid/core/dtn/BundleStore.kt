package dev.meshaid.core.dtn

import dev.meshaid.core.protocol.MessageId

object BundlePriority {
    const val NORMAL = 0
    const val SOS = 10
}

/**
 * A store-carry-forward bundle: one encoded packet being carried for later delivery
 * (DTN / RFC 9171 model — every phone is a data mule).
 */
class Bundle(
    val id: MessageId,
    val encodedPacket: ByteArray,
    val createdAtMs: Long,
    val lifetimeMs: Long = DEFAULT_LIFETIME_MS,
    val priority: Int = BundlePriority.NORMAL,
    copyBudget: Int = DEFAULT_COPY_BUDGET,
) {
    var copiesLeft: Int = copyBudget
        internal set

    fun isExpired(nowMs: Long): Boolean = nowMs - createdAtMs >= lifetimeMs

    companion object {
        const val DEFAULT_LIFETIME_MS = 24 * 60 * 60 * 1000L
        /** Spray-and-Wait style bound on how many peers we hand each bundle to. */
        const val DEFAULT_COPY_BUDGET = 8
    }
}

/**
 * In-memory bundle store with expiry, capacity eviction (SOS protected), and
 * summary-vector sync: on peer contact, exchange held ids and pull what's missing.
 */
class BundleStore(
    private val clock: () -> Long,
    private val maxBundles: Int = 1000,
) {
    private val bundles = LinkedHashMap<MessageId, Bundle>()

    @Synchronized
    fun add(bundle: Bundle): Boolean {
        expireSweep()
        if (bundles.containsKey(bundle.id)) return false
        if (bundles.size >= maxBundles && !evictOne(bundle.priority)) return false
        bundles[bundle.id] = bundle
        return true
    }

    @Synchronized
    fun get(id: MessageId): Bundle? {
        expireSweep()
        return bundles[id]
    }

    @Synchronized
    fun size(): Int {
        expireSweep()
        return bundles.size
    }

    /** Ids we hold — sent to a newly contacted peer. */
    @Synchronized
    fun summaryVector(): List<MessageId> {
        expireSweep()
        return bundles.keys.toList()
    }

    /** Of the ids a peer holds, the ones we are missing (and should pull). */
    @Synchronized
    fun missingFrom(remote: Collection<MessageId>): List<MessageId> {
        expireSweep()
        return remote.filter { it !in bundles }
    }

    /**
     * Bundles to hand over for a pull request, consuming copy budget.
     * A bundle whose budget is exhausted is kept locally (it may still reach its
     * destination through us) but no longer sprayed.
     */
    @Synchronized
    fun takeForSpray(requested: Collection<MessageId>): List<Bundle> {
        expireSweep()
        val out = ArrayList<Bundle>()
        for (id in requested) {
            val b = bundles[id] ?: continue
            if (b.copiesLeft <= 0) continue
            b.copiesLeft--
            out.add(b)
        }
        return out
    }

    @Synchronized
    fun remove(id: MessageId) {
        bundles.remove(id)
    }

    private fun expireSweep() {
        val now = clock()
        bundles.entries.removeIf { it.value.isExpired(now) }
    }

    /** Evict the oldest lowest-priority bundle that is not higher priority than the newcomer. */
    private fun evictOne(newcomerPriority: Int): Boolean {
        val victim = bundles.values
            .filter { it.priority <= newcomerPriority || it.priority < BundlePriority.SOS }
            .minWithOrNull(compareBy({ it.priority }, { it.createdAtMs }))
            ?: return false
        bundles.remove(victim.id)
        return true
    }
}
