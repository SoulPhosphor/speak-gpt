/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.text.DateFormat
import java.util.Date
import org.teslasoft.assistant.R

/**
 * The owner-approved text for a restore's problem lines. The service formats
 * them once, before the mandatory restart, so the Restoration Partly
 * Successful dialog and the Error Log entry always show the same lines.
 */
object PortableRestoreIssueText {
    data class Lines(
        val dialog: List<String>,
        val log: List<String>,
        val missingReferences: Boolean,
        val notRestored: Boolean
    ) {
        val isEmpty: Boolean get() = dialog.isEmpty()
    }

    fun lines(
        context: Context,
        failures: List<UnifiedPortableRestore.CategoryFailure>,
        finalState: PortableRestoreFinalState?
    ): Lines {
        val failureLines = failures.map { categoryFailureLine(context, it) }
        val chatImages = finalState?.missingChatImages.orEmpty()
        val identityImages = finalState?.missingIdentityImages.orEmpty().distinct()
            .map { missingIdentityImageLine(context, it) }
        return Lines(
            dialog = failureLines + chatImages.map { missingChatImageLine(context, it, forLog = false) } +
                identityImages,
            log = failureLines + chatImages.map { missingChatImageLine(context, it, forLog = true) } +
                identityImages,
            missingReferences = chatImages.isNotEmpty() || identityImages.isNotEmpty(),
            notRestored = failureLines.isNotEmpty()
        )
    }

    /** The sentences shown above the problem lines, in the dialog and the log. */
    fun intro(context: Context, missingReferences: Boolean, notRestored: Boolean): List<String> =
        buildList {
            if (notRestored) add(context.getString(R.string.portable_partial_not_restored))
            if (missingReferences) add(context.getString(R.string.portable_partial_missing_references))
        }

    fun categoryFailureLine(
        context: Context,
        failure: UnifiedPortableRestore.CategoryFailure
    ): String = context.getString(
        R.string.portable_restore_category_failure,
        categoryName(context, failure.category),
        failureReason(context, failure)
    )

    fun categoryName(context: Context, category: PortableRestoreCategory): String =
        context.getString(when (category) {
            PortableRestoreCategory.CHATS -> R.string.restore_category_chats
            PortableRestoreCategory.GENERATED_IMAGES -> R.string.restore_category_generated_images
            PortableRestoreCategory.COMPANIONS -> R.string.restore_category_companions
            PortableRestoreCategory.GLAMOURS -> R.string.restore_category_glamours
            PortableRestoreCategory.ROLEPLAY -> R.string.restore_category_roleplay
            PortableRestoreCategory.PROFILE_IMAGES -> R.string.restore_category_profile_images
            PortableRestoreCategory.ACTIVATION_PROMPTS -> R.string.restore_category_activation_prompts
            PortableRestoreCategory.SYSTEM_PROMPTS -> R.string.restore_category_system_prompts
            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS -> R.string.restore_category_model_settings
            PortableRestoreCategory.SETTINGS -> R.string.restore_category_settings
            PortableRestoreCategory.MODEL_RULES -> R.string.restore_category_model_rules
            PortableRestoreCategory.MEMORIES -> R.string.restore_category_memories
            PortableRestoreCategory.LOREBOOKS -> R.string.restore_category_lorebooks
        })

    /** The Chats reader's specific reason, or the generic one when unknown. */
    fun chatReasonMessage(context: Context, reason: PortableChatRestorePlan.Reason?): String =
        context.getString(when (reason) {
            PortableChatRestorePlan.Reason.NOT_A_CHATS_ARTIFACT -> R.string.portable_chat_invalid_file_type
            PortableChatRestorePlan.Reason.UNSUPPORTED_FORMAT -> R.string.portable_chat_unsupported_format
            PortableChatRestorePlan.Reason.INCOMPLETE_ARTIFACT -> R.string.portable_chat_incomplete
            PortableChatRestorePlan.Reason.MALFORMED -> R.string.portable_chat_malformed
            PortableChatRestorePlan.Reason.UNSAFE_CHAT_ID -> R.string.portable_chat_unsafe_id
            PortableChatRestorePlan.Reason.IDENTITY_MISMATCH -> R.string.portable_chat_identity_mismatch
            PortableChatRestorePlan.Reason.DUPLICATE_CHAT_ID -> R.string.portable_chat_duplicate_id
            PortableChatRestorePlan.Reason.MALFORMED_MESSAGE_ID -> R.string.portable_chat_malformed_message_id
            PortableChatRestorePlan.Reason.DUPLICATE_MESSAGE_ID -> R.string.portable_chat_duplicate_message_id
            PortableChatRestorePlan.Reason.MALFORMED_FOLDERS -> R.string.portable_chat_malformed_folders
            PortableChatRestorePlan.Reason.MISSING_FOLDER -> R.string.portable_chat_missing_folder
            PortableChatRestorePlan.Reason.UNREFERENCED_FOLDER -> R.string.portable_chat_unreferenced_folder
            PortableChatRestorePlan.Reason.UNSUPPORTED_SETTING_TYPE -> R.string.portable_chat_unsupported_setting
            PortableChatRestorePlan.Reason.FORBIDDEN_CREDENTIAL -> R.string.portable_chat_forbidden_credential
            null -> R.string.portable_restore_validation_failed
        })

    private fun failureReason(
        context: Context,
        failure: UnifiedPortableRestore.CategoryFailure
    ): String = when (failure.reason) {
        UnifiedPortableRestore.CategoryFailureReason.CHAT_DATA ->
            chatReasonMessage(context, failure.chatReason)
        UnifiedPortableRestore.CategoryFailureReason.MISSING_DATA ->
            context.getString(R.string.portable_restore_reason_missing_artifact)
        UnifiedPortableRestore.CategoryFailureReason.INVALID_DATA,
        UnifiedPortableRestore.CategoryFailureReason.COUNT_MISMATCH ->
            context.getString(R.string.portable_restore_reason_validation_failed)
        UnifiedPortableRestore.CategoryFailureReason.CURRENT_DATA_UNAVAILABLE ->
            context.getString(R.string.portable_restore_reason_staging_failed)
        UnifiedPortableRestore.CategoryFailureReason.PLANNING_FAILED ->
            context.getString(R.string.portable_restore_reason_dependency_validation)
    }

    private fun missingChatImageLine(
        context: Context,
        image: PortableRestoreFinalState.MissingChatImage,
        forLog: Boolean
    ): String {
        val chatName = image.chatName?.takeIf { it.isNotBlank() && !it.trim().contains("_autoname_") }
            ?: context.getString(R.string.label_untitled_chat)
        val generated = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(image.createdAt))
        val prompt = firstWords(image.prompt, PROMPT_WORDS)
        return if (forLog) context.getString(
            R.string.portable_missing_chat_image_log,
            chatName, generated, image.modelId, prompt, image.fileName
        ) else context.getString(
            R.string.portable_missing_chat_image,
            chatName, generated, image.modelId, prompt
        )
    }

    private fun missingIdentityImageLine(
        context: Context,
        reference: PortableRestoreFinalState.IdentityProfileImageReference
    ): String {
        val kind = context.getString(when (reference.category) {
            PortableRestoreCategory.COMPANIONS -> R.string.portable_missing_kind_companion
            PortableRestoreCategory.GLAMOURS -> R.string.portable_missing_kind_glamour
            PortableRestoreCategory.ROLEPLAY -> R.string.portable_missing_kind_roleplay_character
            else -> R.string.profile_image_usage_default_user_image
        })
        val name = reference.name?.takeIf(String::isNotBlank)
        return if (reference.category == null || name == null) {
            context.getString(R.string.portable_missing_unnamed_image, kind)
        } else context.getString(R.string.portable_missing_identity_image, kind, name)
    }

    internal fun firstWords(text: String, count: Int): String {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return if (words.size <= count) words.joinToString(" ")
        else words.take(count).joinToString(" ") + "…"
    }

    private const val PROMPT_WORDS = 10
}
