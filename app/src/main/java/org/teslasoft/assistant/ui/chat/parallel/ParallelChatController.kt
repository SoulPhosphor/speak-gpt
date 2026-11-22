package org.teslasoft.assistant.ui.chat.parallel

import android.view.View
import android.widget.ImageButton
import androidx.fragment.app.FragmentActivity
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.conversation.setup.ExperimentalSetup
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.ui.chat.setup.SetupModelResolver
import java.util.UUID
import kotlin.coroutines.resume

/** Screen glue only: coordinator owns requests; this controller owns composer and branch interaction. */
internal class ParallelChatController(private val activity: FragmentActivity, private val host: Host,
    private val coordinatorFactory: ((CoroutineScope, ParallelGroupPersistence) -> ParallelRequestCoordinator)? = null) {
    interface Host {
        val chatId: String
        val messages: MutableList<HashMap<String, Any>>
        val rawInput: String
        val includes: List<ChatInclude>
        val manualMemoryIds: List<String>
        val unavailable: Boolean
        val imagineCommandEnabled: Boolean get() = false
        suspend fun prepareBase(raw: String, setup: ExperimentalSetup): ParallelTurnBase
        /** Persist both message rows before composer/attachment transfer. */
        fun commitTurn(raw: String, base: ParallelTurnBase, group: ResponseGroup): Boolean
        fun save(): Boolean
        fun changed(position: Int, canonical: Boolean)
        fun busy(value: Boolean)
        suspend fun adopt(position: Int, discardDescendants: Boolean): Boolean
        suspend fun beforeRegenerate(position: Int, action: suspend () -> Unit)
        fun settled()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = false
    private var selectingBranch = false
    private var pendingSave = false
    private var closed = false
    private data class PendingTurn(val raw: String, val base: ParallelTurnBase, val groupId: String)
    private var pendingTurn: PendingTurn? = null
    private var preparing = false
    private var sendJob: Job? = null
    val busy get() = active || pendingSave
    private val prefs get() = Preferences.getPreferences(activity, host.chatId)
    private val chatScope by lazy { ParallelChatScope(host.chatId) }
    private val detachedPersistence by lazy { StoredParallelGroups(activity.applicationContext, chatScope) {} }
    private val persistence = object : ParallelGroupPersistence {
        override fun read(target: ResponseGroupTarget): ResponseGroup = runBlocking(Dispatchers.Main.immediate) {
            owned(target).second
        }
        override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup =
            runBlocking(Dispatchers.Main.immediate) {
                if (closed) return@runBlocking detachedPersistence.update(target, transform)
                val actual = chatScope.target(target)
                val index = host.messages.indexOfFirst { it["message_id"] == actual.assistantMessageId }
                check(actual.chatId == host.chatId)
                if (index < 0) {
                    val pending = requireNotNull(pendingTurn)
                    check(pending.groupId == target.groupId)
                    val group = transform(null)
                    check(host.messages.none { it["message_id"] == group.userMessageId || it["message_id"] == group.assistantMessageId })
                    if (!host.commitTurn(pending.raw, pending.base, group)) throw ParallelPersistenceException("turn_commit_failed")
                    pendingTurn = null
                    preparing = false
                    return@runBlocking group
                }
                val row = host.messages[index]
                val before = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group
                check(before == null || before.groupId == target.groupId)
                val after = transform(before)
                val change = ResponseGroupHistory.replace(host.chatId, host.messages, actual,
                    row[ResponseGroupCodec.KEY] as? String, after)
                val original = host.messages.toList()
                host.messages.clear(); host.messages.addAll(change.messages)
                if (!host.save()) {
                    host.messages.clear(); host.messages.addAll(original)
                    host.save() // Restore a failed SharedPreferences process-local write, without resending.
                    throw ParallelPersistenceException("write_failed")
                }
                host.changed(index, change.canonicalChanged)
                after
            }
    }
    private val coordinator by lazy { coordinatorFactory?.invoke(scope, persistence) ?: ParallelRuntime.forChat(activity, host.chatId, scope,
        canonicalSourcesChanged = {}, confirmImage = { request, prompt ->
            confirm("${request.assignment.label}\n\n$prompt", R.string.image_gen_action_create)
        }, persistence = persistence) }

    fun configured(): Boolean = prefs.readExperimentalSetup()?.mode == ExperimentalSetup.Mode.PARALLEL

    /** Also runs for a subsequent ordinary send after switching Parallel Responses off. */
    fun beforeSend(continueSend: () -> Unit): Boolean {
        if (busy || selectingBranch) return true
        val pending = ParallelResponseSelection.pending(host.messages) ?: return false
        selectingBranch = true
        scope.launch {
            var adopted = false
            try {
                if (!pending.hasDescendants || confirm(activity.getString(R.string.make_current_body), R.string.make_current_confirm))
                    adopted = host.adopt(pending.position, pending.hasDescendants)
            } finally { selectingBranch = false }
            if (adopted) beforeSendOrContinue(continueSend)
        }
        return true
    }
    private fun beforeSendOrContinue(continueSend: () -> Unit) {
        if (!beforeSend(continueSend)) continueSend()
    }

    fun send(raw: String): Boolean {
        if (!configured()) return false
        if (busy || host.unavailable || raw.isEmpty()) return true
        val setup = prefs.readExperimentalSetup() ?: return true
        if (SetupModelResolver.unavailable(activity, setup).isNotEmpty()) {
            notice(activity.getString(R.string.experimental_setup_unavailable,
                SetupModelResolver.unavailable(activity, setup).joinToString { it.modelId }))
            return true
        }
        if ((host.imagineCommandEnabled && org.teslasoft.assistant.imagegen.ImagineCommand.isImagineAttempt(raw)) || setup.models.any { it.modelId.contains(":ft") || it.modelId.contains("ft:") }) {
            notice(activity.getString(R.string.parallel_single_required)); return true
        }
        if (beforeSend { send(raw) }) return true
        val inputIncludes = host.includes.toList()
        val manual = host.manualMemoryIds.toList()
        active = true; preparing = true; host.busy(true)
        sendJob = scope.launch {
            try {
                val base = host.prepareBase(raw, setup)
                val prepared = ParallelTurnPreparation.prepare(activity, host.chatId, setup, base) { confirm(it) }
                if (host.rawInput != raw || host.includes != inputIncludes || host.manualMemoryIds != manual ||
                    prefs.readExperimentalSetup() != setup || SetupModelResolver.unavailable(activity, setup).isNotEmpty()) return@launch
                val userId = UUID.randomUUID().toString()
                val assistantId = UUID.randomUUID().toString()
                val group = ParallelInput.freeze(userId, assistantId,
                    if (setup.lastMessageOnly) "last_message_only" else "full_history",
                    prepared.context, prepared.current, prepared.slots, setup.models.indexOfFirst { it.id == setup.primaryId }, System.currentTimeMillis())
                pendingTurn = PendingTurn(raw, base, group.groupId)
                coordinator.dispatch(host.chatId, group, prepared.commitContext).await()
                offerSaveRetry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ParallelPreparationRefused) {
                notice(e.text)
            } catch (_: OutOfMemoryError) {
                notice(activity.getString(R.string.request_prepare_failed_body))
            } catch (_: Exception) {
                notice(activity.getString(R.string.parallel_prepare_failed))
            } finally {
                pendingTurn = null
                preparing = false
                active = false
                if (!closed) { host.busy(false); host.settled() }
            }
        }
        return true
    }

    fun browse(position: Int, slot: String, version: String) {
        if (closed || host.unavailable) return
        val row = host.messages.getOrNull(position) ?: return
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return
        val after = ResponseGroupEdits.browse(group, slot, version)
        val replacement = ResponseGroupEdits.install(row, after)
        host.messages[position] = replacement
        if (!host.save()) { host.messages[position] = row; host.save(); notice(activity.getString(R.string.message_save_failed)) }
        host.changed(position, false)
    }

    fun editImageSummary(position: Int, text: String?): Boolean {
        val row = host.messages.getOrNull(position) ?: return false
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return false
        val selected = group.slots.single { it.slotId == group.foregroundSlotId }
        val after = group.copy(slots = group.slots.map { slot ->
            if (slot.slotId != selected.slotId) slot else slot.copy(versions = slot.versions.map { version ->
                if (version.versionId != slot.foregroundVersionId) version else version.copy(imageSummaryEdited = text)
            })
        })
        host.messages[position] = ResponseGroupEdits.install(row, after)
        if (!host.save()) { host.messages[position] = row; host.save(); notice(activity.getString(R.string.message_save_failed)) }
        host.changed(position, group.primarySlotId == selected.slotId && selected.canonicalVersionId == selected.foregroundVersionId)
        return true
    }

    fun stopAll() {
        if (preparing) sendJob?.cancel(ParallelUserStop())
        else host.messages.indices.toList().forEach { stop(it, null) }
    }

    fun stop(position: Int, slotId: String?) {
        val group = (host.messages.getOrNull(position)?.let(ResponseGroupCodec::read) as? ResponseGroupRead.Valid)?.group ?: return
        scope.launch {
            group.slots.filter { slotId == null || it.slotId == slotId }.flatMap { it.versions }.flatMap { it.attempts }
                .filter { it.state == MessageCompletionState.STREAMING }.map { it.requestId }.forEach { coordinator.cancel(it) }
        }
    }

    fun regenerate(position: Int): Boolean {
        val row = host.messages.getOrNull(position) ?: return false
        val read = ResponseGroupCodec.read(row)
        val group = (read as? ResponseGroupRead.Valid)?.group ?: run {
            if (read == ResponseGroupRead.Absent) return false
            notice(activity.getString(if (read is ResponseGroupRead.Unsupported) R.string.parallel_responses_unsupported else R.string.parallel_responses_unreadable))
            return true
        }
        if (busy || selectingBranch || host.unavailable) return true
        selectingBranch = true
        scope.launch {
            try { host.beforeRegenerate(position) {
                active = true; host.busy(true)
                try {
                    coordinator.regenerate(ResponseGroupTarget(host.chatId, group.assistantMessageId, group.groupId), group.foregroundSlotId).await()
                    offerSaveRetry()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice(activity.getString(R.string.parallel_prepare_failed)) }
                finally { active = false; if (!closed) { host.busy(false); host.settled() } }
            } } finally { selectingBranch = false }
        }
        return true
    }

    private fun owned(target: ResponseGroupTarget): Pair<Int, ResponseGroup> {
        check(host.chatId == chatScope.target(target).chatId)
        val index = host.messages.indexOfFirst { it["message_id"] == target.assistantMessageId }
        val group = (ResponseGroupCodec.read(host.messages[index]) as ResponseGroupRead.Valid).group
        check(group.groupId == target.groupId)
        return index to group
    }

    fun unsaved(position: Int): Boolean {
        if (!pendingSave) return false
        val group = (host.messages.getOrNull(position)?.let(ResponseGroupCodec::read) as? ResponseGroupRead.Valid)?.group ?: return false
        return group.slots.flatMap { it.versions }.flatMap { it.attempts }.any { it.requestId in coordinator.unsavedRequestIds() }
    }

    fun retrySave() {
        if (active || closed || !pendingSave) return
        scope.launch {
            active = true; host.busy(true)
            try { coordinator.flush(); refreshSaveState() }
            finally { active = false; host.busy(false); if (!pendingSave) host.settled() }
        }
    }

    private fun refreshSaveState() {
        pendingSave = coordinator.unsavedRequestIds().isNotEmpty()
        host.messages.indices.forEach { position ->
            if (host.messages[position].containsKey(ResponseGroupCodec.KEY)) host.changed(position, false)
        }
    }

    private suspend fun offerSaveRetry() {
        refreshSaveState()
        if (!pendingSave || closed) return
        if (confirm(activity.getString(R.string.parallel_save_failed), R.string.parallel_retry_save)) {
            coordinator.flush()
            refreshSaveState()
        }
    }

    suspend fun confirm(message: String, positive: Int = R.string.send_anyway): Boolean = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext false
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
                .setMessage(message).setPositiveButton(positive) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }.create()
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            dialog.show()
        }
    }
    private fun notice(message: String) {
        if (!closed) MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog).setMessage(message).setPositiveButton(R.string.okay, null).show()
    }

    fun close() {
        // Terminal checkpoints use guarded storage after teardown, never a stale screen snapshot.
        closed = true
        scope.cancel()
    }

    companion object {
        fun bindBusy(send: ImageButton?, progress: View?, busy: Boolean) {
            if (!busy) return
            progress?.visibility = View.GONE
            send?.apply {
                visibility = View.VISIBLE
                isEnabled = false
                setImageResource(R.drawable.ic_send)
                setColorFilter(MaterialColors.getColor(this, R.attr.appRowSubtitleColor))
            }
        }
    }
}
