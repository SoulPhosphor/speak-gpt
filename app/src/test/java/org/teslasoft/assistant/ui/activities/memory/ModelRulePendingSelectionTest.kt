package org.teslasoft.assistant.ui.activities.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulePendingSelectionTest {

    @Test
    fun `endpoint and model together are an unadded selection`() {
        assertTrue(hasUnaddedModelSelection("endpoint-1", "provider/model-1"))
    }

    @Test
    fun `endpoint without model is not an unadded selection`() {
        assertFalse(hasUnaddedModelSelection("endpoint-1", ""))
    }

    @Test
    fun `model without endpoint is not an unadded selection`() {
        assertFalse(hasUnaddedModelSelection("", "provider/model-1"))
    }
}
