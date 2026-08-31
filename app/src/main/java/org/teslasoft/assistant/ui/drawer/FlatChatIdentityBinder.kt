package org.teslasoft.assistant.ui.drawer

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.util.ProfileImageBinder
import org.teslasoft.assistant.util.ProfileImageResolver

/** Shared identity/optional-metadata binding for drawer chats and Search results. */
class FlatChatIdentityBinder(
    private val activity: FragmentActivity,
    itemView: View,
    private val displayPreferences: Preferences,
    private val memoryStates: Map<String, String>
) {
    private val title: TextView = itemView.findViewById(R.id.flat_chat_title)
    private val leading: ImageView = itemView.findViewById(R.id.flat_chat_leading_icon)
    private val model: TextView = itemView.findViewById(R.id.flat_chat_model)
    private val memory: TextView = itemView.findViewById(R.id.flat_chat_memory)
    private val imageFrame: View = itemView.findViewById(R.id.flat_chat_image_frame)
    private val image: ImageView = itemView.findViewById(R.id.flat_chat_image)
    private val bookmarkOverlay: ImageView = itemView.findViewById(R.id.flat_chat_bookmark_overlay)

    fun reset() {
        model.visibility = View.GONE
        model.text = ""
        memory.visibility = View.GONE
        memory.text = ""
        imageFrame.visibility = View.GONE
        bookmarkOverlay.visibility = View.GONE
        leading.visibility = View.GONE
        leading.setImageDrawable(null)
        ProfileImageBinder.bind(activity, image, null, "flower") { it.setImageDrawable(null) }
        title.setTextColor(MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface))
        title.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    fun bind(chatId: String, displayTitle: String, pinned: Boolean, current: Boolean) {
        reset()
        title.text = displayTitle
        val chatPreferences = Preferences.getPreferences(activity, chatId)
        if (displayPreferences.getShowCompanionImagesInChatList()) {
            imageFrame.visibility = View.VISIBLE
            val personaId = chatPreferences.getPersonaId()
            val ref = if (personaId.isNotEmpty()) try {
                PersonaPreferences.getPersonaPreferences(activity).getPersona(personaId).avatarRef
            } catch (_: Exception) { "" } else ""
            ProfileImageBinder.bind(
                activity,
                image,
                ProfileImageResolver.resolveAiImageFile(activity, ref),
                GlobalPreferences.getPreferences(activity).getProfileImageShape()
            ) { it.setImageResource(R.drawable.ic_assistant_navigation) }
            bookmarkOverlay.visibility = if (pinned) View.VISIBLE else View.GONE
        } else if (pinned) {
            leading.visibility = View.VISIBLE
            leading.setImageResource(R.drawable.ic_bookmark)
            leading.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(leading, com.google.android.material.R.attr.colorPrimary)
            )
        }
        if (!displayPreferences.getHideModelNames()) {
            model.visibility = View.VISIBLE
            model.text = chatPreferences.getModel()
        }
        if (displayPreferences.getShowMemoryStatusOnChatList()) {
            val state = if (chatPreferences.isChatExcludedFromMemory()) "excluded" else memoryStates[chatId]
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
            title.setTextColor(MaterialColors.getColor(title, com.google.android.material.R.attr.colorPrimary))
            title.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }
}
