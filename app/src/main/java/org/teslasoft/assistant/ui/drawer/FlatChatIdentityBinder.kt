package org.teslasoft.assistant.ui.drawer

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.chatnavigation.ChatListIndex
import org.teslasoft.assistant.preferences.profileimages.ProfileImageShape
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.ProfileImageResolver

/** Shared identity/optional-metadata binding for drawer chats and Search results. */
class FlatChatIdentityBinder(
    private val activity: FragmentActivity,
    itemView: View,
    private val displayPreferences: Preferences,
    private val memoryStates: Map<String, String>,
    private val companionImages: CompanionImageLookup
) {
    private val title: TextView = itemView.findViewById(R.id.flat_chat_title)
    private val leading: ImageView = itemView.findViewById(R.id.flat_chat_leading_icon)
    private val model: TextView = itemView.findViewById(R.id.flat_chat_model)
    private val memory: TextView = itemView.findViewById(R.id.flat_chat_memory)
    private val imageFrame: View = itemView.findViewById(R.id.flat_chat_image_frame)
    private val image: ImageView = itemView.findViewById(R.id.flat_chat_image)
    private val bookmarkIndicator: ImageView = itemView.findViewById(R.id.flat_chat_bookmark_indicator)
    private val bookmarkOverlay: ImageView = itemView.findViewById(R.id.flat_chat_bookmark_overlay)

    fun reset() {
        model.visibility = View.GONE
        model.text = ""
        memory.visibility = View.GONE
        memory.text = ""
        imageFrame.visibility = View.GONE
        bookmarkOverlay.visibility = View.GONE
        bookmarkIndicator.visibility = View.GONE
        leading.visibility = View.GONE
        leading.setImageDrawable(null)
        ProfileImageBinder.bind(activity, image, null, ProfileImageShape.DEFAULT) {
            it.setImageDrawable(null)
        }
        title.setTextColor(MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface))
        title.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    /**
     * Draws a chat row from the chat list alone; no chat file is opened here.
     * [index] is null until the row's details have been filled in, and only the
     * information already known is shown until then.
     */
    fun bind(chatId: String, displayTitle: String, pinned: Boolean, current: Boolean,
        index: ChatListIndex.Entry?, startedAt: Long?, showBookmarkIndicator: Boolean = true) {
        reset()
        title.text = displayTitle
        if (displayPreferences.getShowCompanionImagesInChatList()) {
            imageFrame.visibility = View.VISIBLE
            if (index != null) {
                ProfileImageBinder.bind(
                    activity,
                    image,
                    companionImages.imageFor(index.companionId),
                    GlobalPreferences.getPreferences(activity).getProfileImageShape()
                ) { it.setImageResource(R.drawable.ic_assistant_navigation) }
            }
            bookmarkOverlay.visibility = if (pinned) View.VISIBLE else View.GONE
        } else if (pinned) {
            leading.visibility = View.VISIBLE
            leading.setImageResource(R.drawable.ic_bookmark)
            leading.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(leading, androidx.appcompat.R.attr.colorPrimary)
            )
        }
        bookmarkIndicator.visibility = if (showBookmarkIndicator && displayPreferences.getShowBookmarkIndicatorInChatList() &&
            index?.hasBookmarks == true) View.VISIBLE else View.GONE
        val metadata = ChatListDetails.metadata(
            if (displayPreferences.getShowChatStartDateInChatList()) startedAt else null,
            if (displayPreferences.getShowModelNamesInChatList()) index?.displayModel else null
        )
        model.text = metadata
        model.visibility = if (metadata.isEmpty()) View.GONE else View.VISIBLE
        if (displayPreferences.getShowMemoryStatusOnChatList() && index != null) {
            val state = if (index.memoryExcluded) "excluded" else memoryStates[chatId]
            if (state != null) {
                memory.visibility = View.VISIBLE
                memory.text = activity.getString(when (state) {
                    "pending" -> R.string.memory_marker_pending
                    "partial" -> R.string.memory_marker_partial
                    "processed" -> R.string.memory_marker_processed
                    else -> R.string.memory_marker_excluded
                })
            }
        }
        if (current) {
            title.setTextColor(MaterialColors.getColor(title, androidx.appcompat.R.attr.colorPrimary))
            title.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }
}

/** Each companion's picture is looked up once per list refresh, not once per row. */
class CompanionImageLookup(private val activity: FragmentActivity) {
    private val files = HashMap<String, File?>()

    fun clear() = files.clear()

    fun imageFor(companionId: String): File? {
        if (files.containsKey(companionId)) return files[companionId]
        val ref = if (companionId.isNotEmpty()) try {
            PersonaPreferences.getPersonaPreferences(activity).getPersona(companionId).avatarRef
        } catch (_: Exception) { "" } else ""
        return ProfileImageResolver.resolveAiImageFile(activity, ref).also { files[companionId] = it }
    }
}
