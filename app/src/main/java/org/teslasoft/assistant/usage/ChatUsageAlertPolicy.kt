package org.teslasoft.assistant.usage

import java.math.BigDecimal

/** A conversation's warning is permanent; only a higher limit can arm another warning. */
object ChatUsageAlertPolicy {
    fun parseAmount(value: String): BigDecimal? =
        value.trim().replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    fun shouldAlert(
        enabled: Boolean,
        amount: String,
        totalCost: Double,
        lastAlertedAmount: String,
        amountChangedAt: Long,
        latestUsageAt: Long
    ): Boolean {
        if (!enabled || !totalCost.isFinite()) return false
        val limit = parseAmount(amount) ?: return false
        val previous = parseAmount(lastAlertedAmount)
        if (previous != null && (limit <= previous || latestUsageAt <= amountChangedAt)) return false
        return BigDecimal.valueOf(totalCost) >= limit
    }
}
