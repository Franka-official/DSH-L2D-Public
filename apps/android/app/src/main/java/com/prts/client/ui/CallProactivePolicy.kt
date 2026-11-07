package com.prts.client.ui

/** Single foreground proactive turn; human input replenishes the quiet-turn budget. */
internal class CallProactivePolicy(private val clock: () -> Long, private val quietMillis: Long) {
    private var nextAt = Long.MAX_VALUE
    private var consecutive = 0
    private var active = false

    fun resumed() { nextAt = clock() + quietMillis }
    fun canStart(): Boolean = !active && consecutive < 2 && clock() >= nextAt
    fun started() { active = true; consecutive++ }
    fun finished() {
        if (active) nextAt = clock() + FOLLOW_UP_MS
        active = false
    }
    val isActive: Boolean get() = active

    fun finishAfterPlayback(completed: Boolean, busy: Boolean): Boolean {
        if (!active || !completed || busy) return false
        finished()
        return true
    }

    fun userInput(): Boolean {
        val wasActive = active
        active = false
        consecutive = 0
        nextAt = clock() + quietMillis
        return wasActive
    }
    private companion object {
        const val FOLLOW_UP_MS = 90_000L
    }
}
