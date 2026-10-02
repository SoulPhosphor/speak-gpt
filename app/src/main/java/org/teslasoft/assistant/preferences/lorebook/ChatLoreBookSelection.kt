/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.preferences.lorebook

import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.dto.PersonaObject

/**
 * Reconciles a chat's selectable lorebooks with the currently selected
 * companion. The first time a companion is used, every linked book defaults on.
 * Later edits preserve explicit off choices while newly linked books default on.
 */
object ChatLoreBookSelection {

    data class State(
        val companionBooks: List<LoreBook>,
        val extraBooks: List<LoreBook>,
        val activeIds: LinkedHashSet<String>
    ) {
        val displayedBooks: List<LoreBook> = companionBooks + extraBooks
    }

    fun reconcile(
        preferences: Preferences,
        persona: PersonaObject,
        store: LoreBookStore
    ): State {
        val linkedIds = (
            listOfNotNull(persona.coreLoreBookId.takeIf { it.isNotBlank() }) +
                persona.additionalLoreBookIdList()
            ).distinct()
        val companionBooks = linkedIds.mapNotNull(store::getBook)
        val companionIds = companionBooks.map { it.id }
        val extraBooks = preferences.getChatExtraLoreBookIds()
            .filterNot(companionIds::contains)
            .distinct()
            .mapNotNull(store::getBook)
        val extraIds = extraBooks.map { it.id }
        if (extraIds != preferences.getChatExtraLoreBookIds()) {
            preferences.setChatExtraLoreBookIds(extraIds)
        }
        val displayedIds = companionIds + extraIds
        val previousPersonaId = preferences.getLoreBookSelectionPersonaId()
        val previousKnown = preferences.getLoreBookSelectionKnownIds().toSet()
        val previousActive = preferences.getActiveLoreBookIds().toSet()

        val active = reconcileActiveIds(
            personaId = persona.id,
            previousPersonaId = previousPersonaId,
            companionIds = companionIds,
            extraIds = extraIds,
            previousKnownIds = previousKnown,
            previousActiveIds = previousActive
        )

        if (previousPersonaId != persona.id ||
            previousKnown != displayedIds.toSet() ||
            previousActive != active
        ) {
            preferences.setActiveLoreBookIds(active.toList())
            preferences.setLoreBookSelectionPersonaId(persona.id)
            preferences.setLoreBookSelectionKnownIds(displayedIds)
        }

        return State(companionBooks, extraBooks, active)
    }

    internal fun reconcileActiveIds(
        personaId: String,
        previousPersonaId: String,
        companionIds: List<String>,
        extraIds: List<String>,
        previousKnownIds: Set<String>,
        previousActiveIds: Set<String>
    ): LinkedHashSet<String> {
        val displayedIds = companionIds + extraIds
        if (previousPersonaId != personaId) {
            return LinkedHashSet<String>().apply {
                addAll(companionIds)
                addAll(extraIds.filter(previousActiveIds::contains))
            }
        }
        return LinkedHashSet<String>().apply {
            addAll(displayedIds.filter(previousActiveIds::contains))
            addAll(displayedIds.filterNot(previousKnownIds::contains))
        }
    }
}
