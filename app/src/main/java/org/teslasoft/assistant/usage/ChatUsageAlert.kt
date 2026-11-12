package org.teslasoft.assistant.usage

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.SecurePrefs

/** App-wide settings; the dismissed warning itself is stored with each encrypted chat. */
class ChatUsageAlertSettings(context: Context) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = settings.getBoolean("chat_usage_alert_enabled", false)
        set(value) { settings.edit().putBoolean("chat_usage_alert_enabled", value).apply() }

    var amount: String
        get() = settings.getString("chat_usage_alert_amount", "").orEmpty()
        set(value) {
            if (value == amount) return
            val oldAmount = ChatUsageAlertPolicy.parseAmount(amount)
            val newAmount = ChatUsageAlertPolicy.parseAmount(value)
            val changed = if (oldAmount != null && newAmount != null) oldAmount.compareTo(newAmount) != 0
                else oldAmount != newAmount
            settings.edit().apply {
                putString("chat_usage_alert_amount", value)
                if (changed) putLong("chat_usage_alert_amount_changed_at", System.currentTimeMillis())
            }.apply()
        }

    val amountChangedAt: Long get() = settings.getLong("chat_usage_alert_amount_changed_at", 0L)
}

/** Observes usage receipts so all models and usage categories share one warning. */
class ChatUsageAlert(
    private val activity: FragmentActivity,
    private val refreshUsage: () -> Unit
) : DefaultLifecycleObserver {
    private val settings = ChatUsageAlertSettings(activity)
    private val mutex = Mutex()
    private var watchedChatId: String? = null
    private var watched: SharedPreferences? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "usage_log" && settings.enabled &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            refreshUsage()
        }
    }

    init { activity.lifecycle.addObserver(this) }

    override fun onResume(owner: LifecycleOwner) {
        // Also catches receipts that arrived while a settings screen covered the chat.
        if (settings.enabled) refreshUsage()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        watched?.unregisterOnSharedPreferenceChangeListener(listener)
        dialog?.dismiss()
    }

    suspend fun onSummary(chatId: String, totalCost: Double, latestUsageAt: Long) = mutex.withLock {
        if (!settings.enabled || chatId.isBlank() || activity.isFinishing || activity.isDestroyed) return@withLock
        if (watchedChatId != chatId) {
            watched?.unregisterOnSharedPreferenceChangeListener(listener)
            dialog?.dismiss()
            watchedChatId = chatId
            watched = SecurePrefs.get(activity, "settings.$chatId").also {
                it.registerOnSharedPreferenceChangeListener(listener)
            }
        }
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || dialog?.isShowing == true) {
            return@withLock
        }
        val prefs = watched ?: return@withLock
        val amount = settings.amount.trim()
        if (!ChatUsageAlertPolicy.shouldAlert(
                settings.enabled, amount, totalCost,
                prefs.getString(LAST_ALERTED_AMOUNT, "").orEmpty(), settings.amountChangedAt, latestUsageAt
            )) return@withLock
        // Persist before showing so returning to the chat or restarting cannot repeat it.
        val saved = withContext(Dispatchers.IO) {
            prefs.edit().putString(LAST_ALERTED_AMOUNT, amount).commit()
        }
        if (!saved || activity.isFinishing || activity.isDestroyed) return@withLock
        val actions = activity.layoutInflater.inflate(R.layout.dialog_single_action, null)
        val warning = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_usage_alert_title)
            .setMessage(activity.getString(R.string.chat_usage_alert_message, amount))
            .setView(actions)
            .create()
        actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { warning.dismiss() }
        }
        dialog = warning
        warning.setOnDismissListener {
            dialog = null
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshUsage()
        }
        warning.show()
    }

    private companion object {
        const val LAST_ALERTED_AMOUNT = "chat_usage_alert_last_amount"
    }
}
