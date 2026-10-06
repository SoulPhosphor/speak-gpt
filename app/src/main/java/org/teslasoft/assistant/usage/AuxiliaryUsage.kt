/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.usage

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The usage record for one request made outside a chat reply (Condense,
 * Reduce, removal reminders, Summarizer, Compact), built by the same rules as
 * chat replies: counts and charges come only from the provider's response and
 * prices are frozen when the request finishes.
 */
object AuxiliaryUsage {
    /**
     * Null for a failed request whose provider reported nothing; a completed
     * request with no usage report is still a paid request, recorded with
     * unknown counts (shown as Not Reported) rather than estimated.
     */
    suspend fun record(
        attempt: ProviderUsageAttempt,
        pricingDeferred: Deferred<TokenPricingCatalog>,
        succeeded: Boolean,
        reloadPricing: suspend (model: String) -> TokenPricingCatalog?
    ): TurnUsageRecord? {
        val snapshot = attempt.snapshot()
        val reported = snapshot.counts.hasAnyValue() || snapshot.providerCost.hasAnyValue()
        if (!succeeded && !reported) {
            pricingDeferred.cancel()
            return null
        }
        var catalog = withTimeoutOrNull(2000L) { pricingDeferred.await() }
        if (catalog == null) pricingDeferred.cancel()
        if (catalog != null && !catalog.model.equals(snapshot.model, ignoreCase = true)) {
            catalog = withTimeoutOrNull(2000L) { reloadPricing(snapshot.model) }
        }
        return TokenUsageAccounting.createRecord(
            model = snapshot.model,
            provider = snapshot.provider,
            apiEndpoint = snapshot.apiEndpoint,
            counts = snapshot.counts.withDerivedTotal(),
            source = TokenCountSource.PROVIDER_REPORTED,
            pricing = catalog?.pricingFor(snapshot.provider) ?: TokenPricingSnapshot(),
            providerCost = snapshot.providerCost
        )
    }
}
