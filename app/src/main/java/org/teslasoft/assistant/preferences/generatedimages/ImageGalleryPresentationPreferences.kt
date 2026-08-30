/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import android.content.SharedPreferences

enum class ImageGallerySortOrder { NEWEST_TO_OLDEST, OLDEST_TO_NEWEST }

data class ImageGalleryPresentation(
    val sortOrder: ImageGallerySortOrder = ImageGallerySortOrder.NEWEST_TO_OLDEST,
    val columns: Int = 3,
    val showLabels: Boolean = true
)

object ImageGallerySorter {
    fun sort(
        records: List<GeneratedImageCatalogRecord>,
        order: ImageGallerySortOrder
    ): List<GeneratedImageCatalogRecord> {
        val ascending = compareBy<GeneratedImageCatalogRecord>({ it.createdAt }, { it.imageId })
        return records.sortedWith(
            if (order == ImageGallerySortOrder.OLDEST_TO_NEWEST) ascending else ascending.reversed()
        )
    }
}

class ImageGalleryPresentationPreferences private constructor(
    private val preferences: SharedPreferences
) {
    fun read(): ImageGalleryPresentation {
        val order = runCatching {
            ImageGallerySortOrder.valueOf(
                preferences.getString(KEY_SORT, null) ?: ImageGallerySortOrder.NEWEST_TO_OLDEST.name
            )
        }.getOrDefault(ImageGallerySortOrder.NEWEST_TO_OLDEST)
        val columns = preferences.getInt(KEY_COLUMNS, 3).takeIf { it in ALLOWED_COLUMNS } ?: 3
        return ImageGalleryPresentation(order, columns, preferences.getBoolean(KEY_LABELS, true))
    }

    fun setSortOrder(value: ImageGallerySortOrder) =
        preferences.edit().putString(KEY_SORT, value.name).apply()

    fun setColumns(value: Int) {
        require(value in ALLOWED_COLUMNS)
        preferences.edit().putInt(KEY_COLUMNS, value).apply()
    }

    fun setShowLabels(value: Boolean) = preferences.edit().putBoolean(KEY_LABELS, value).apply()

    companion object {
        val ALLOWED_COLUMNS = setOf(2, 3, 4)
        private const val KEY_SORT = "image_gallery.sort"
        private const val KEY_COLUMNS = "image_gallery.columns"
        private const val KEY_LABELS = "image_gallery.show_labels"

        fun get(context: Context) = ImageGalleryPresentationPreferences(
            context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        )
    }
}
