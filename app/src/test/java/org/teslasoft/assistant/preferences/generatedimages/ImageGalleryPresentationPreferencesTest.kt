package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageGalleryPresentationPreferencesTest {
    private lateinit var context: Context

    @Before fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsAndAllowedChoicesPersistAppWide() {
        val preferences = ImageGalleryPresentationPreferences.get(context)
        assertEquals(ImageGallerySortOrder.NEWEST_TO_OLDEST, preferences.read().sortOrder)
        assertEquals(3, preferences.read().columns)
        assertTrue(preferences.read().showLabels)

        preferences.setSortOrder(ImageGallerySortOrder.OLDEST_TO_NEWEST)
        preferences.setColumns(4)
        preferences.setShowLabels(false)
        assertEquals(
            ImageGalleryPresentation(ImageGallerySortOrder.OLDEST_TO_NEWEST, 4, false),
            ImageGalleryPresentationPreferences.get(context).read()
        )
    }
}
