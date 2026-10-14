package org.teslasoft.assistant.preferences.backup.readable

/** Independently selected readable exports; no recovery or restore state. */
enum class ReadableBackupCategory {
    ACTIVATION_PROMPTS, PROFILE_IMAGES, CHATS, COMPANIONS, GENERATED_IMAGES,
    GLAMOURS, LOREBOOKS, MEMORIES, MODEL_SETTINGS, MODEL_RULES, ROLEPLAY,
    SETTINGS, SYSTEM_PROMPTS;

    companion object {
        /** Old combined identities selections never implicitly enable the new toggles. */
        fun fromStored(names: Set<String>?): Set<ReadableBackupCategory> = names.orEmpty()
            .mapNotNullTo(LinkedHashSet()) { name -> entries.firstOrNull { it.name == name } }
    }
}

/** Keep the same companion/glamour/roleplay boundaries used by restore. */
internal object ReadableIdentitySelection {
    private val companions = setOf("companions", "companion_name_history")
    private val glamours = setOf("user_personas")

    fun tables(
        rows: Map<String, List<Map<String, Any?>>>,
        category: ReadableBackupCategory
    ): Map<String, List<Map<String, Any?>>> = rows.filterKeys { table ->
        when (category) {
            ReadableBackupCategory.COMPANIONS -> table in companions
            ReadableBackupCategory.GLAMOURS -> table in glamours
            ReadableBackupCategory.ROLEPLAY -> table !in companions && table !in glamours
            else -> false
        }
    }
}
