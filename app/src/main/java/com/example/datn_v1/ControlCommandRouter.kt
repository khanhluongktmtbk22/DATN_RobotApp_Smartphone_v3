package com.example.datn_v1

/** Source selection. Access on the control thread; generation may be read by inference. */
internal class ControlCommandRouter {
    enum class Mode { WAITING, AUTO_FOLLOW, REMOTE }

    var mode = Mode.WAITING
        private set
    @Volatile var generation = 0L
        private set
    private var awaitingRemoteBaseline = true
    private var lastRemoteTimestamp: Long? = null

    fun select(enabled: Boolean?): Boolean {
        val next = when (enabled) {
            true -> Mode.AUTO_FOLLOW
            false -> Mode.REMOTE
            null -> Mode.WAITING
        }
        if (next == mode) return false
        mode = next
        generation++
        awaitingRemoteBaseline = true
        lastRemoteTimestamp = null
        return true
    }

    fun acceptsLocal(token: Long, createdAt: Long, now: Long, maxAge: Long): Boolean =
        mode == Mode.AUTO_FOLLOW && token == generation &&
            now >= createdAt && now - createdAt <= maxAge

    /** The initial snapshot is persisted state, not a new joystick action. */
    fun acceptsRemote(token: Long, timestamp: Long?, now: Long?, maxAge: Long, futureSkew: Long): Boolean {
        if (mode != Mode.REMOTE || token != generation) return false
        if (awaitingRemoteBaseline) {
            awaitingRemoteBaseline = false
            lastRemoteTimestamp = if (now == null) null
            else timestamp?.takeIf { it in (now - maxAge)..(now + futureSkew) }
            return false
        }
        if (now == null) return false
        if (timestamp == null || timestamp < now - maxAge || timestamp > now + futureSkew) return false
        if (lastRemoteTimestamp?.let { timestamp <= it } == true) return false
        lastRemoteTimestamp = timestamp
        return true
    }
}
