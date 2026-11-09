package com.rw3.directsync.ble.parser

/** A pushed file must never consume a different outstanding download request. */
class XiaomiActivityRequestQueue {
    private val pending = ArrayDeque<ByteArray>()
    private val seen = mutableSetOf<String>()
    private var current: ByteArray? = null
    private var attempts = 0
    val active: ByteArray? @Synchronized get() = current?.copyOf()

    @Synchronized fun offer(id: ByteArray, priority: Boolean = false): Boolean {
        require(id.size == 7)
        if (!seen.add(key(id))) return false
        if (priority) pending.addFirst(id.copyOf()) else pending.addLast(id.copyOf())
        return true
    }

    @Synchronized fun next(): ByteArray? {
        if (current != null) return null
        current = pending.removeFirstOrNull()
        attempts = if (current == null) 0 else 1
        return current?.copyOf()
    }

    @Synchronized fun complete(id: ByteArray) {
        seen.add(key(id))
        pending.removeAll { it.contentEquals(id) }
        if (current?.contentEquals(id) == true) { current = null; attempts = 0 }
    }

    /** Returns the same identity for at most three attempts, then permits the next request. */
    @Synchronized fun retry(): ByteArray? {
        val id = current ?: return null
        if (attempts++ < 3) return id.copyOf()
        current = null
        return null
    }

    @Synchronized fun reset() { pending.clear(); seen.clear(); current = null; attempts = 0 }
    private fun key(id: ByteArray) = id.joinToString("") { "%02x".format(it.toInt() and 255) }
}
