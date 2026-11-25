/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.recall.eval

import org.teslasoft.assistant.conversation.recall.RecallTuning

/** Values measured beside the approved starting values, for the baseline record. */
object RecallTuningCandidates {
    val all: List<Pair<String, RecallTuning>> = listOf(
        "approved starting values" to RecallTuning.APPROVED_STARTING,
        "starting values with floor 0.40" to RecallTuning.APPROVED_STARTING.copy(similarityFloor = 0.40),
        "starting values with keyword rarity 10%" to RecallTuning.APPROVED_STARTING.copy(keywordMaxShare = 0.10),
        "working values (floor 0.40, keyword rarity 10%)" to RecallTuning(),
        "working values with certain 0.65" to RecallTuning(certainSimilarity = 0.65),
        "working values with keyword rarity 5%" to RecallTuning(keywordMaxShare = 0.05)
    )
}
