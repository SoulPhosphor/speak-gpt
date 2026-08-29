package org.teslasoft.assistant.preferences.chatnavigation

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.FakeSharedPreferences

internal fun chatRow(
    id: String,
    name: String,
    timestamp: Long,
    pinned: Boolean = false,
    folderId: String? = null
): HashMap<String, String> = hashMapOf(
    "id" to id,
    "name" to name,
    "timestamp" to timestamp.toString(),
    "pinned" to pinned.toString()
).apply { if (folderId != null) put(ChatNavigationRepository.FOLDER_ID_KEY, folderId) }

internal fun repository(
    rows: List<HashMap<String, String>> = emptyList(),
    chatStore: SharedPreferences = FakeSharedPreferences(),
    presentationStore: SharedPreferences = FakeSharedPreferences(),
    state: ChatStorageHealth.ReadState? = null,
    ids: Iterator<String> = generateSequence { java.util.UUID.randomUUID().toString() }.iterator(),
    onCorrupt: (String) -> Unit = {}
): ChatNavigationRepository {
    chatStore.edit().putString("data", Gson().toJson(rows)).commit()
    return ChatNavigationRepository(
        chatListPreferences = chatStore,
        presentationPreferences = presentationStore,
        readChatList = {
            val type = TypeToken.getParameterized(ArrayList::class.java, HashMap::class.java).type
            @Suppress("UNCHECKED_CAST")
            val current = Gson().fromJson<Any>(chatStore.getString("data", "[]"), type)
                as ArrayList<HashMap<String, String>>
            ChatPreferences.ChatListResult(
                state ?: if (current.isEmpty()) ChatStorageHealth.ReadState.EMPTY
                else ChatStorageHealth.ReadState.OK,
                current
            )
        },
        preserveCorruptFolders = onCorrupt,
        idFactory = { ids.next() }
    )
}

internal class CommitFailingPreferences(
    private val delegate: FakeSharedPreferences = FakeSharedPreferences()
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean = false
            override fun apply() = Unit
        }
    }
}
