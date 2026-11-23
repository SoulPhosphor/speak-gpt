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
import org.teslasoft.assistant.usage.UsageLogEntry
import org.teslasoft.assistant.usage.UsageLogStore
import org.teslasoft.assistant.ui.chat.setup.SetupModelResolver
import java.util.UUID
import kotlin.coroutines.resume

/** Screen glue only: coordinator owns requests; this controller owns composer and branch interaction. */
internal class ParallelChatController(private val activity: FragmentActivity, private val host: Host,
    private val titleUsageWriter: ((UsageLogEntry) -> Boolean)? = null,
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
        fun canonicalEdited() {}
        fun settled()
        suspend fun nameTurn(group: ResponseGroup, retainUsage: (UsageLogEntry) -> Unit) {}
        /** A deleted message's response group reached its final state; its
         *  usage belongs in the chat's usage log. */
        fun deletedGroupSettled(row: HashMap<String, Any>) {}
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = false
    private var selectingBranch = false
    private var pendingSave = false
    private data class PendingTitleReceipt(val assistantMessageId: String, val entry: UsageLogEntry)
    private val pendingTitleReceipts = linkedMapOf<String, PendingTitleReceipt>()
    private var closed = false
    private data class PendingTurn(val raw: String, val base: ParallelTurnBase, val groupId: String)
    private var pendingTurn: PendingTurn? = null
    private var preparing = false
    private var operationJob: Job? = null
    private var branchJob: Job? = null
    private var mutating = false
    private var stopping = false
    // Bumped when a message deletion abandons the running operation, so that
    // operation's late cleanup cannot reset a newer turn.
    private var operationToken = 0
    // The Stop task waiting for an interrupted preparation to wind down.
    private var stopJob: Job? = null
    /** Groups of deleted messages whose requests may still report back. */
    private class DeletedGroup(var row: HashMap<String, Any>, var group: ResponseGroup)
    private val deletedGroups = HashMap<String, DeletedGroup>()
    val busy get() = active || pendingSave || mutating || stopping
    private val prefs get() = Preferences.getPreferences(activity, host.chatId)
    private val chatScope by lazy { ParallelChatScope(host.chatId) }
    private val detachedPersistence by lazy { StoredParallelGroups(activity.applicationContext, chatScope) {} }
    private val persistence = object : ParallelGroupPersistence {
        override fun read(target: ResponseGroupTarget): ResponseGroup = runBlocking(Dispatchers.Main.immediate) {
            deletedGroups[target.assistantMessageId]?.let { return@runBlocking it.group }
            owned(target).second
        }
        override fun update(target: ResponseGroupTarget, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup =
            runBlocking(Dispatchers.Main.immediate) {
                // A deleted message's group never returns to the chat.
                deletedGroups[target.assistantMessageId]?.let { return@runBlocking keepDeleted(it, transform) }
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
                // Only this owned assistant row changed. Bound controls on every other row
                // must keep their live source object until their own notification/rebind.
                host.messages[index] = HashMap(change.after)
                if (!host.save()) {
                    host.messages.clear(); host.messages.addAll(original)
                    host.save() // Restore a failed SharedPreferences process-local write, without resending.
                    throw ParallelPersistenceException("write_failed")
                }
                host.changed(index, change.canonicalChanged)
                after
            }
    }
    private val coordinatorDelegate = lazy { coordinatorFactory?.invoke(scope, persistence) ?: ParallelRuntime.forChat(activity, host.chatId, scope,
        canonicalSourcesChanged = {}, confirmImage = { request, prompt ->
            confirm("${request.assignment.label}\n\n$prompt", R.string.image_gen_action_create)
        }, persistence = persistence) }
    private val coordinator by coordinatorDelegate

    fun configured(): Boolean = prefs.readExperimentalSetup()?.mode == ExperimentalSetup.Mode.PARALLEL

    /** Also runs for a subsequent ordinary send after switching Parallel Responses off. */
    fun beforeSend(continueSend: () -> Unit): Boolean {
        if (busy || selectingBranch) return true
        val pending = ParallelResponseSelection.pending(host.messages) ?: return false
        selectingBranch = true
        val token = operationToken
        branchJob = scope.launch {
            var adopted = false
            try {
                if (!pending.hasDescendants || confirm(activity.getString(R.string.make_current_body), R.string.make_current_confirm))
                    adopted = host.adopt(pending.position, pending.hasDescendants)
            } finally { if (token == operationToken) selectingBranch = false }
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
        val token = operationToken
        operationJob = scope.launch {
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
                if (!closed && !pendingSave) {
                    val settled = persistence.read(ResponseGroupTarget(host.chatId, group.assistantMessageId, group.groupId))
                    if (settled.slots.any { slot -> slot.versions.any { it.fields["state"] == MessageCompletionState.DONE } })
                        host.nameTurn(settled) { receipt ->
                            pendingTitleReceipts[receipt.id] = PendingTitleReceipt(settled.assistantMessageId, receipt)
                            if (closed) pendingSave = true else refreshSaveState()
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ParallelPreparationRefused) {
                notice(e.text)
            } catch (_: OutOfMemoryError) {
                notice(activity.getString(R.string.request_prepare_failed_body))
            } catch (_: Exception) {
                notice(activity.getString(R.string.parallel_prepare_failed))
            } finally {
                if (token == operationToken) {
                    pendingTurn = null
                    preparing = false
                    active = false
                    if (!closed) { host.busy(busy); if (!mutating && !stopping) host.settled() }
                }
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

    fun applyImageSummary(imageId: String, prompt: String, summary: String): Boolean {
        if (closed || host.unavailable) return false
        val index = host.messages.indexOfFirst { row ->
            ResponseGroupImages.pendingSummaries(row).any { it.imageId == imageId && it.prompt == prompt }
        }
        if (index < 0) return false
        val row = host.messages[index]
        val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return false
        val after = ResponseGroupImages.summarize(group, imageId, prompt, summary)
        if (after == group) return false
        host.messages[index] = ResponseGroupEdits.install(row, after)
        if (!host.save()) { host.messages[index] = row; host.save(); return false }
        val primary = group.slots.single { it.slotId == group.primarySlotId }
        val canonical = primary.versions.single { it.versionId == primary.canonicalVersionId }
        host.changed(index, canonical != after.slots.single { it.slotId == primary.slotId }
            .versions.single { it.versionId == primary.canonicalVersionId })
        return true
    }

    fun stopAll() {
        if (!preparing) { host.messages.indices.toList().forEach { stop(it, null) }; return }
        if (stopping || closed) return
        stopping = true
        host.busy(true)
        val token = operationToken
        val preparingJob = operationJob
        preparingJob?.cancel(ParallelUserStop())
        stopJob = scope.launch {
            try {
                // A blocking durable commit may finish while preparation is being cancelled.
                // Drain that owner before finding every newly registered request chain.
                preparingJob?.join()
                // A message deletion abandoned this stop meanwhile; a newer turn
                // may own the requests now, and it already stopped the old ones.
                if (token != operationToken) return@launch
                coordinator.cancelOwned()
                flushSavedEvidence()
                refreshSaveState()
            } finally {
                if (token == operationToken) {
                    stopping = false
                    if (!closed) { host.busy(busy); if (!busy) host.settled() }
                }
            }
        }
    }

    fun stop(position: Int, slotId: String?) {
        val group = (host.messages.getOrNull(position)?.let(ResponseGroupCodec::read) as? ResponseGroupRead.Valid)?.group ?: return
        scope.launch {
            group.slots.filter { slotId == null || it.slotId == slotId }.flatMap { it.versions }.flatMap { it.attempts }
                .filter { it.state == MessageCompletionState.STREAMING }.map { it.requestId }.forEach(coordinator::signalCancel)
            // A terminal tool response can still own a live continuation/usage chain.
            coordinator.cancelOwned(ResponseGroupTarget(host.chatId, group.assistantMessageId, group.groupId), slotId)
        }
    }

    fun regenerate(position: Int): Boolean {
        // The ordinary fallback may truncate this owner's live group too.
        if (busy || selectingBranch || host.unavailable) return true
        val row = host.messages.getOrNull(position) ?: return false
        val read = ResponseGroupCodec.read(row)
        val group = (read as? ResponseGroupRead.Valid)?.group ?: run {
            if (read == ResponseGroupRead.Absent) return false
            notice(activity.getString(if (read is ResponseGroupRead.Unsupported) R.string.parallel_responses_unsupported else R.string.parallel_responses_unreadable))
            return true
        }
        selectingBranch = true
        val token = operationToken
        operationJob = scope.launch {
            try { host.beforeRegenerate(position) {
                active = true; host.busy(true)
                try {
                    coordinator.regenerate(ResponseGroupTarget(host.chatId, group.assistantMessageId, group.groupId), group.foregroundSlotId).await()
                    offerSaveRetry()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice(activity.getString(R.string.parallel_prepare_failed)) }
                finally {
                    if (token == operationToken) { active = false; if (!closed) { host.busy(busy); if (!mutating && !stopping) host.settled() } }
                }
            } } finally { if (token == operationToken) selectingBranch = false }
        }
        return true
    }

    fun editReply(target: ParallelReplyEditTarget, text: String) {
        if (closed || mutating || stopping || host.unavailable) return
        mutating = true
        host.busy(true)
        scope.launch {
            try {
                coordinator.cancelOwned(ResponseGroupTarget(host.chatId, target.assistantMessageId, target.groupId),
                    target.slotId, target.versionId)
                flushSavedEvidence()
                refreshSaveState()
                if (pendingSave) return@launch
                val index = host.messages.indexOfFirst { it["message_id"] == target.assistantMessageId }
                val row = host.messages.getOrNull(index) ?: return@launch
                val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return@launch
                val edited = ParallelReplyEditing.apply(group, target, text)
                host.messages[index] = ResponseGroupEdits.install(row, edited)
                if (!host.save()) {
                    host.messages[index] = row; host.save()
                    notice(activity.getString(R.string.message_save_failed))
                    return@launch
                }
                val canonical = ParallelReplyEditing.canonical(group, target)
                if (canonical) host.canonicalEdited()
                host.changed(index, canonical)
            } finally {
                mutating = false
                if (!closed) { host.busy(busy); if (!busy) host.settled() }
            }
        }
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
        return pendingTitleReceipts.values.any { it.assistantMessageId == group.assistantMessageId } ||
            group.slots.flatMap { it.versions }.flatMap { it.attempts }.any { it.requestId in coordinator.unsavedRequestIds() }
    }

    /**
     * Message deletion: stops every request and any send or branch choice in
     * progress at once, without waiting for them, so a new message can be
     * sent immediately. Requests keep winding down in the background; what
     * they report for surviving messages is saved as usual, and the deletion
     * passes deleted groups to [keepDeletedGroups].
     */
    fun abandonForDeletion() {
        if (closed) return
        operationToken += 1
        operationJob?.cancel(ParallelUserStop())
        branchJob?.cancel(ParallelUserStop())
        stopJob?.cancel()
        operationJob = null
        branchJob = null
        stopJob = null
        pendingTurn = null
        preparing = false
        active = false
        stopping = false
        selectingBranch = false
        if (coordinatorDelegate.isInitialized()) coordinator.signalCancelAll()
        host.busy(busy)
    }

    /** The deleted rows' groups: late results go only to the usage log. */
    fun keepDeletedGroups(rows: List<Map<String, Any>>) {
        rows.forEach { row ->
            val group = (ResponseGroupCodec.read(row) as? ResponseGroupRead.Valid)?.group ?: return@forEach
            deletedGroups[group.assistantMessageId] = DeletedGroup(HashMap(row), group)
        }
        if (closed || !coordinatorDelegate.isInitialized()) return
        pendingSave = unsavedOutsideDeleted()
        host.busy(busy)
        // Results held back by an earlier failed save now go to the usage log.
        if (coordinator.unsavedTargets().any { it.assistantMessageId in deletedGroups }) scope.launch {
            coordinator.flush()
            refreshSaveState()
            if (!closed) host.busy(busy)
        }
    }

    /** Unsaved evidence that still belongs to the chat (a deleted group's is
     *  routed to the usage log instead). */
    private fun unsavedOutsideDeleted(): Boolean =
        coordinator.unsavedTargets().any { it.assistantMessageId !in deletedGroups } ||
            pendingTitleReceipts.isNotEmpty()

    private fun keepDeleted(kept: DeletedGroup, transform: (ResponseGroup?) -> ResponseGroup): ResponseGroup {
        val after = transform(kept.group)
        kept.row = ResponseGroupEdits.install(kept.row, after)
        kept.group = after
        val settled = after.slots.none { slot ->
            slot.versions.any { version -> version.attempts.any { it.state == MessageCompletionState.STREAMING } }
        }
        if (settled) host.deletedGroupSettled(HashMap(kept.row))
        return after
    }

    fun retrySave() {
        if (active || closed || mutating || stopping || !pendingSave) return
        val token = operationToken
        scope.launch {
            active = true; host.busy(true)
            try { flushSavedEvidence(); refreshSaveState() }
            finally { if (token == operationToken) { active = false; host.busy(busy); if (!busy) host.settled() } }
        }
    }

    private suspend fun flushSavedEvidence() {
        coordinator.flush()
        for ((id, pending) in pendingTitleReceipts.toMap()) {
            val saved = withContext(Dispatchers.IO) {
                try { titleUsageWriter?.invoke(pending.entry) ?: UsageLogStore.putRequest(prefs, pending.entry) }
                catch (_: Exception) { false }
            }
            if (saved && pendingTitleReceipts[id] == pending) pendingTitleReceipts.remove(id)
        }
    }

    private fun refreshSaveState() {
        pendingSave = unsavedOutsideDeleted()
        host.messages.indices.forEach { position ->
            if (host.messages[position].containsKey(ResponseGroupCodec.KEY)) host.changed(position, false)
        }
    }

    private suspend fun offerSaveRetry() {
        refreshSaveState()
        if (!pendingSave || closed) return
        if (confirm(activity.getString(R.string.parallel_save_failed), R.string.parallel_retry_save)) {
            flushSavedEvidence()
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
