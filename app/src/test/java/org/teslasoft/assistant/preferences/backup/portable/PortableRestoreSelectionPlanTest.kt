package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test

class PortableRestoreSelectionPlanTest {

    @Test
    fun allRequestedCategoriesProceedWithTheirIndependentModes() {
        val result = PortableRestoreSelectionPlan.inspect(
            requested = listOf(
                selection(PortableRestoreCategory.MEMORIES, PortableRestoreMode.REPLACE),
                selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE)
            ),
            inventory = inventory(
                PortableRestoreCategory.CHATS,
                PortableRestoreCategory.MEMORIES
            )
        )

        assertEquals(
            PortableRestoreSelectionPlan.Result.Ready(
                listOf(
                    selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE),
                    selection(PortableRestoreCategory.MEMORIES, PortableRestoreMode.REPLACE)
                )
            ),
            result
        )
    }

    @Test
    fun missingCategoriesRemainInTheDecisionUntilUserExplicitlyContinues() {
        val result = PortableRestoreSelectionPlan.inspect(
            requested = listOf(
                selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE),
                selection(PortableRestoreCategory.MODEL_RULES, PortableRestoreMode.REPLACE),
                selection(PortableRestoreCategory.LOREBOOKS, PortableRestoreMode.MERGE)
            ),
            inventory = inventory(PortableRestoreCategory.CHATS)
        ) as PortableRestoreSelectionPlan.Result.Missing

        assertEquals(
            listOf(PortableRestoreCategory.MODEL_RULES, PortableRestoreCategory.LOREBOOKS),
            result.missing
        )
        assertEquals(3, result.requested.size)
        assertEquals(
            PortableRestoreSelectionPlan.Result.Ready(
                listOf(selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE))
            ),
            PortableRestoreSelectionPlan.restoreAvailable(result)
        )
    }

    @Test
    fun noSelectedCategoryInBackupCannotContinue() {
        assertEquals(
            PortableRestoreSelectionPlan.Result.NothingAvailable,
            PortableRestoreSelectionPlan.inspect(
                requested = listOf(
                    selection(PortableRestoreCategory.LOREBOOKS, PortableRestoreMode.REPLACE)
                ),
                inventory = inventory(PortableRestoreCategory.CHATS)
            )
        )
    }

    @Test
    fun emptyAndDuplicateRequestsAreRejectedBeforeStaging() {
        val inventory = inventory(PortableRestoreCategory.CHATS)
        assertEquals(
            PortableRestoreSelectionPlan.Result.EmptySelection,
            PortableRestoreSelectionPlan.inspect(emptyList(), inventory)
        )
        assertEquals(
            PortableRestoreSelectionPlan.Result.DuplicateCategory,
            PortableRestoreSelectionPlan.inspect(
                listOf(
                    selection(PortableRestoreCategory.CHATS, PortableRestoreMode.MERGE),
                    selection(PortableRestoreCategory.CHATS, PortableRestoreMode.REPLACE)
                ),
                inventory
            )
        )
    }

    private fun selection(
        category: PortableRestoreCategory,
        mode: PortableRestoreMode
    ) = PortableRestoreSelectionPlan.Selection(category, mode)

    private fun inventory(
        vararg category: PortableRestoreCategory
    ) = PortableRestoreInventory.Inventory(category.toSet())
}
