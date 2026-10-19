package me.rerere.rikkahub.data.memory

import kotlin.math.pow

/** History alone ages; long-term facts and their saved source text are never deleted here. */
data class MemoryRetentionPolicy(
    val nowMillis: Long, val enabled: Boolean = true, val days: Int = 30, val halfLifeDays: Int = 7,
) {
    val cutoffMillis: Long? get() = if (enabled) nowMillis - days.coerceIn(1, 365) * 86_400_000L else null
    fun allows(createdAt: String): Boolean = cutoffMillis?.let { cutoff ->
        organizationSourceMillis(createdAt)?.let { it >= cutoff && it <= nowMillis } == true
    } ?: true
    fun weight(createdAt: String): Double {
        if (!enabled) return 1.0
        if (!allows(createdAt)) return 0.0
        val age = (nowMillis - requireNotNull(organizationSourceMillis(createdAt))).coerceAtLeast(0)
        return 0.5.pow(age.toDouble() / (halfLifeDays.coerceIn(1, 365) * 86_400_000.0))
    }
}
