/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.teslasoft.assistant.preferences.backup.companion.RemovedLorebookLink
import org.teslasoft.assistant.preferences.backup.RecoveryOperationGate

/**
 * Process-lifetime owner of unified portable restore work. The foreground
 * service is the only caller of [advance]; activities only observe immutable
 * state and submit explicit user decisions. Decode staging, the validated
 * plan, collision state, transaction staging, and cleanup never belong to an
 * Activity instance.
 */
object UnifiedPortableRestoreCoordinator {
    enum class ProgressPhase { DECODING, PREFLIGHT, APPLYING, RESTARTING }

    sealed interface State {
        val version: Long

        data class Idle(override val version: Long) : State
        data class Progress(
            override val version: Long,
            val phase: ProgressPhase
        ) : State
        data class UnlockFailed(
            override val version: Long,
            val error: PortablePackageFormat.RestoreError,
            val inspection: PortablePackage.Inspection
        ) : State
        data class MissingCategories(
            override val version: Long,
            val missing: List<PortableRestoreCategory>
        ) : State
        data class FolderDecision(
            override val version: Long,
            val collision: ChatMergePlanner.FolderCollision
        ) : State
        data class Confirmation(
            override val version: Long,
            val selections: List<PortableRestoreSelectionPlan.Selection>
        ) : State
        data class Terminal(
            override val version: Long,
            val outcome: PortableRestoreOutcome
        ) : State
    }

    sealed interface UnlockMaterial {
        class Secret(value: ByteArray) : UnlockMaterial {
            internal val value = value.copyOf()
        }
        class Password(value: CharArray) : UnlockMaterial {
            internal val value = value.copyOf()
        }
    }

    fun interface Observer { fun onStateChanged(state: State) }

    enum class AdvanceResult { WAITING_FOR_USER, TERMINAL, RESTART_REQUIRED, NO_WORK }

    private enum class Next { DECODE, PREFLIGHT, APPLY }

    private data class Session(
        val packageFile: File,
        val decodeRoot: File,
        val requested: List<PortableRestoreSelectionPlan.Selection>,
        val inspection: PortablePackage.Inspection,
        var unlock: UnlockMaterial?,
        var artifacts: List<PortablePackage.ValidatedArtifact> = emptyList(),
        var explicitlyEmpty: Set<PortableRestoreCategory> = emptySet(),
        var selected: List<PortableRestoreSelectionPlan.Selection> = emptyList(),
        val folderResolutions: MutableMap<String, ChatMergePlanner.FolderResolution> = LinkedHashMap(),
        var missingResult: PortableRestoreSelectionPlan.Result.Missing? = null,
        var transactionRoot: File? = null,
        var ready: UnifiedPortableRestore.BuildResult.Ready? = null,
        var next: Next = Next.DECODE,
        var stalePlanRetries: Int = 0
    )

    private val lock = Any()
    private val advancing = AtomicBoolean(false)
    private val recoveryWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "portable-restore-recovery")
    }
    private val observers = Collections.newSetFromMap(WeakHashMap<Observer, Boolean>())
    private var version = 0L
    private var state: State = State.Idle(version)
    private var session: Session? = null

    fun begin(
        packageFile: File,
        decodeRoot: File,
        requested: List<PortableRestoreSelectionPlan.Selection>,
        inspection: PortablePackage.Inspection,
        unlock: UnlockMaterial
    ): Boolean = synchronized(lock) {
        if (session != null || requested.isEmpty() || !packageFile.isFile || !decodeRoot.isDirectory) {
            wipe(unlock)
            return@synchronized false
        }
        session = Session(
            packageFile,
            decodeRoot,
            requested.toList(),
            copyInspection(inspection),
            unlock
        )
        publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.DECODING))
        true
    }

    fun retryUnlock(unlock: UnlockMaterial): Boolean = synchronized(lock) {
        val current = session
        if (current == null || state !is State.UnlockFailed) {
            wipe(unlock)
            return@synchronized false
        }
        current.unlock = unlock
        current.next = Next.DECODE
        publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.DECODING))
        true
    }

    fun restoreAvailableCategories(): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        val missing = current.missingResult ?: return@synchronized false
        if (state !is State.MissingCategories) return@synchronized false
        current.selected = PortableRestoreSelectionPlan.restoreAvailable(missing).selections
        current.missingResult = null
        current.next = Next.PREFLIGHT
        publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.PREFLIGHT))
        true
    }

    fun resolveFolder(
        sourceFolderId: String,
        resolution: ChatMergePlanner.FolderResolution
    ): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        val pending = state as? State.FolderDecision ?: return@synchronized false
        if (pending.collision.backupFolder.id != sourceFolderId) return@synchronized false
        current.folderResolutions[sourceFolderId] = resolution
        current.next = Next.PREFLIGHT
        publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.PREFLIGHT))
        true
    }

    fun confirm(): Boolean = synchronized(lock) {
        val current = session ?: return@synchronized false
        if (state !is State.Confirmation || current.ready == null) return@synchronized false
        current.next = Next.APPLY
        publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.APPLYING))
        true
    }

    fun cancel(): Boolean {
        val abandoned = synchronized(lock) {
            val current = session ?: return false
            val phase = (state as? State.Progress)?.phase
            if (phase == ProgressPhase.RESTARTING ||
                (phase == ProgressPhase.APPLYING && advancing.get())
            ) return false
            session = null
            publishLocked(State.Idle(nextVersionLocked()))
            current
        }
        cleanup(abandoned, keepTransactionForRecovery = false)
        return true
    }

    fun addObserver(observer: Observer): State = synchronized(lock) {
        observers.add(observer)
        state
    }

    fun removeObserver(observer: Observer) = synchronized(lock) { observers.remove(observer) }

    fun snapshot(): State = synchronized(lock) { state }

    fun isActive(): Boolean = synchronized(lock) { session != null }

    /** Startup/manual recovery enters through the same operation owner. */
    @Synchronized
    fun recoverPending(context: Context): Boolean =
        RecoveryOperationGate.runExclusive {
            UnifiedPortableRestore.recoverPending(context.applicationContext)
        }

    /** Recovery execution remains process-owned even if its requesting Activity is destroyed. */
    fun recoverPendingAsync(context: Context, complete: (Boolean) -> Unit) {
        val app = context.applicationContext
        recoveryWorker.execute {
            val recovered = try { recoverPending(app) } catch (_: Exception) { false }
            complete(recovered)
        }
    }

    fun inspection(): PortablePackage.Inspection? = synchronized(lock) {
        session?.inspection?.let(::copyInspection)
    }

    fun folderResolutions(): Map<String, ChatMergePlanner.FolderResolution> = synchronized(lock) {
        Collections.unmodifiableMap(LinkedHashMap(session?.folderResolutions.orEmpty()))
    }

    /** Called on the foreground service's single worker, never the main thread. */
    fun advance(context: Context): AdvanceResult {
        if (!advancing.compareAndSet(false, true)) return AdvanceResult.NO_WORK
        try {
            while (true) {
                val next = synchronized(lock) { session?.next } ?: return AdvanceResult.NO_WORK
                when (next) {
                    Next.DECODE -> when (decode(context.applicationContext)) {
                        Step.CONTINUE -> continue
                        Step.WAIT -> return AdvanceResult.WAITING_FOR_USER
                        Step.TERMINAL -> return AdvanceResult.TERMINAL
                        Step.RESTART -> return AdvanceResult.RESTART_REQUIRED
                    }
                    Next.PREFLIGHT -> when (preflight(context.applicationContext)) {
                        Step.CONTINUE -> continue
                        Step.WAIT -> return AdvanceResult.WAITING_FOR_USER
                        Step.TERMINAL -> return AdvanceResult.TERMINAL
                        Step.RESTART -> return AdvanceResult.RESTART_REQUIRED
                    }
                    Next.APPLY -> when (apply(context.applicationContext)) {
                        Step.CONTINUE -> continue
                        Step.WAIT -> return AdvanceResult.WAITING_FOR_USER
                        Step.TERMINAL -> return AdvanceResult.TERMINAL
                        Step.RESTART -> return AdvanceResult.RESTART_REQUIRED
                    }
                }
            }
        } finally {
            advancing.set(false)
        }
        @Suppress("UNREACHABLE_CODE")
        return AdvanceResult.NO_WORK
    }

    private enum class Step { CONTINUE, WAIT, TERMINAL, RESTART }

    private fun decode(context: Context): Step {
        val current: Session
        val unlock: UnlockMaterial
        synchronized(lock) {
            current = session ?: return Step.TERMINAL
            unlock = current.unlock ?: return Step.WAIT
            current.unlock = null
        }
        val decoded = try {
            when (unlock) {
                is UnlockMaterial.Password -> PortablePackage.decodeWithPassword(
                    current.packageFile, unlock.value, current.decodeRoot
                )
                is UnlockMaterial.Secret -> PortablePackage.decodeWithSecret(
                    current.packageFile, unlock.value, current.decodeRoot
                )
            }
        } finally {
            wipe(unlock)
        }
        if (decoded !is PortablePackage.DecodeResult.Ok) {
            val error = (decoded as PortablePackage.DecodeResult.Failed).error
            if (current.inspection.protection != PortablePackageFormat.PROTECTION_NONE) {
                synchronized(lock) {
                    publishLocked(State.UnlockFailed(
                        nextVersionLocked(), error, copyInspection(current.inspection)
                    ))
                }
                return Step.WAIT
            }
            return terminal(context, PortableRestoreOutcome.PackageFailure(error), current)
        }
        val artifactRoot = File(current.decodeRoot, "artifacts")
        if (!artifactRoot.mkdirs()) {
            return terminal(
                context,
                PortableRestoreOutcome.PackageFailure(
                    PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED
                ),
                current
            )
        }
        val validated = PortablePackage.validateAndExtract(decoded.innerZip, artifactRoot)
        if (validated !is PortablePackage.ValidateResult.Ok) {
            return terminal(
                context,
                PortableRestoreOutcome.PackageFailure(
                    (validated as PortablePackage.ValidateResult.Failed).error
                ),
                current
            )
        }
        val inventory = PortableRestoreInventory.from(
            validated.artifacts, validated.declaredCategories
        )
        when (val selected = PortableRestoreSelectionPlan.inspect(current.requested, inventory)) {
            is PortableRestoreSelectionPlan.Result.Ready -> synchronized(lock) {
                current.artifacts = validated.artifacts.toList()
                current.explicitlyEmpty = inventory.explicitlyEmpty.toSet()
                current.selected = selected.selections.toList()
                current.next = Next.PREFLIGHT
                publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.PREFLIGHT))
            }
            is PortableRestoreSelectionPlan.Result.Missing -> synchronized(lock) {
                current.artifacts = validated.artifacts.toList()
                current.explicitlyEmpty = inventory.explicitlyEmpty.toSet()
                current.missingResult = selected
                publishLocked(State.MissingCategories(
                    nextVersionLocked(), selected.missing.toList()
                ))
                return Step.WAIT
            }
            PortableRestoreSelectionPlan.Result.NothingAvailable ->
                return terminal(context, PortableRestoreOutcome.NothingAvailable, current)
            else -> return terminal(
                context,
                PortableRestoreOutcome.BuildFailure(
                    UnifiedPortableRestore.BuildFailure.EMPTY_SELECTION, null
                ),
                current
            )
        }
        return Step.CONTINUE
    }

    private fun preflight(context: Context): Step {
        val current = synchronized(lock) { session } ?: return Step.TERMINAL
        val transactionRoot = current.transactionRoot ?: UnifiedPortableRestore
            .newTransactionStaging(context)?.also { current.transactionRoot = it }
            ?: return terminal(
                context,
                PortableRestoreOutcome.TransactionFailure(
                    SelectedCategoryRestoreTransaction.Failure.PENDING_RECOVERY,
                    null,
                    SelectedCategoryRestoreTransaction.DataState.UNCHANGED,
                    null
                ),
                current,
                keepTransactionForRecovery = UnifiedPortableRestore.journalRoot(context).exists()
            )
        val built = UnifiedPortableRestore.build(
            context,
            current.artifacts,
            UnifiedPortableRestore.Request(
                current.selected,
                current.folderResolutions.toMap(),
                current.explicitlyEmpty
            ),
            transactionRoot
        )
        synchronized(lock) {
            when (built) {
                is UnifiedPortableRestore.BuildResult.NeedsFolderDecisions -> {
                    val collision = built.collisions.firstOrNull()
                    if (collision != null) {
                        publishLocked(State.FolderDecision(nextVersionLocked(), collision))
                        return Step.WAIT
                    }
                }
                is UnifiedPortableRestore.BuildResult.Failed ->
                    return terminal(context, PortableRestoreOutcome.BuildFailure(
                        built.reason, built.category
                    ), current)
                is UnifiedPortableRestore.BuildResult.Ready -> {
                    current.ready = built
                    publishLocked(State.Confirmation(
                        nextVersionLocked(), current.selected.toList()
                    ))
                    return Step.WAIT
                }
            }
        }
        return terminal(
            context,
            PortableRestoreOutcome.BuildFailure(
                UnifiedPortableRestore.BuildFailure.DEPENDENCY_VALIDATION_FAILED, null
            ),
            current
        )
    }

    private fun apply(context: Context): Step = RecoveryOperationGate.runExclusive {
        applyLocked(context)
    }

    private fun applyLocked(context: Context): Step {
        val current = synchronized(lock) { session } ?: return Step.TERMINAL
        val ready = current.ready ?: return terminal(
            context,
            PortableRestoreOutcome.BuildFailure(
                UnifiedPortableRestore.BuildFailure.DEPENDENCY_VALIDATION_FAILED, null
            ),
            current
        )
        val transactionRoot = current.transactionRoot ?: return terminal(
            context,
            PortableRestoreOutcome.TransactionFailure(
                SelectedCategoryRestoreTransaction.Failure.STAGING_FAILED,
                null,
                SelectedCategoryRestoreTransaction.DataState.UNCHANGED,
                null
            ),
            current
        )

        if (!UnifiedPortableRestore.sourceGenerationsMatch(context, ready, transactionRoot)) {
            if (current.stalePlanRetries++ == 0) {
                synchronized(lock) {
                    current.ready = null
                    current.next = Next.PREFLIGHT
                    publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.PREFLIGHT))
                }
                return Step.CONTINUE
            }
            return terminal(
                context,
                PortableRestoreOutcome.BuildFailure(
                    UnifiedPortableRestore.BuildFailure.DEPENDENCY_VALIDATION_FAILED, null
                ),
                current
            )
        }
        if (!PortableRestoreProcessGate.mark(context, PortableRestoreProcessGate.Phase.APPLYING)) {
            return terminal(
                context,
                PortableRestoreOutcome.TransactionFailure(
                    SelectedCategoryRestoreTransaction.Failure.JOURNAL_FAILED,
                    null,
                    SelectedCategoryRestoreTransaction.DataState.UNCHANGED,
                    null
                ),
                current
            )
        }

        val success = PortableRestoreOutcome.Success(report(ready))
        val result = UnifiedPortableRestore.execute(
            UnifiedPortableRestore.journalRoot(context),
            ready,
            beforeCleanup = {
                val written = PortableRestoreOutcomeStore.persist(context, success)
                if (written) {
                    PortableRestoreProcessGate.mark(
                        context, PortableRestoreProcessGate.Phase.RESTART_PENDING
                    )
                }
                written
            }
        )
        if (result is SelectedCategoryRestoreTransaction.Result.Success) {
            synchronized(lock) {
                publishLocked(State.Progress(nextVersionLocked(), ProgressPhase.RESTARTING))
                session = null
            }
            cleanup(current, keepTransactionForRecovery = false)
            return Step.RESTART
        }

        val failed = result as SelectedCategoryRestoreTransaction.Result.Failed
        val outcome = PortableRestoreOutcome.TransactionFailure(
            failed.reason,
            failed.categoryKey,
            failed.dataState,
            ready.chatParticipant?.validationFailure
        )
        if (!UnifiedPortableRestore.journalRoot(context).exists()) {
            PortableRestoreProcessGate.clear(context)
        }
        return terminal(
            context,
            outcome,
            current,
            keepTransactionForRecovery = UnifiedPortableRestore.journalRoot(context).exists()
        )
    }

    private fun terminal(
        context: Context,
        outcome: PortableRestoreOutcome,
        current: Session,
        keepTransactionForRecovery: Boolean = false
    ): Step {
        val persisted = PortableRestoreOutcomeStore.persist(context, outcome)
        synchronized(lock) {
            // If publication itself fails, retain the entire session. Cleanup
            // must never overtake the durable terminal record.
            if (persisted && session === current) session = null
            publishLocked(State.Terminal(nextVersionLocked(), outcome))
        }
        if (persisted) cleanup(current, keepTransactionForRecovery)
        return Step.TERMINAL
    }

    private fun report(
        ready: UnifiedPortableRestore.BuildResult.Ready
    ): PortableRestoreOutcome.Report {
        val lines = ArrayList<PortableRestoreOutcome.Report.Line>()
        ready.chatParticipant?.mergeReport?.let { report ->
            if (report.conflicts.isNotEmpty()) lines.add(
                PortableRestoreOutcome.Report.Line.ConflictCount(
                    PortableRestoreCategory.CHATS, report.conflicts.size
                )
            )
            if (report.longerHistoriesUsed > 0) lines.add(
                PortableRestoreOutcome.Report.Line.LongerChats(report.longerHistoriesUsed)
            )
        }
        val removedLinks = ArrayList<RemovedLorebookLink>()
        ready.participants.forEach { participant ->
            when (participant) {
                is GeneratedImageRestoreParticipant -> {
                    val protected = participant.report?.protectedCurrentImageIds?.size ?: 0
                    if (protected > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ProtectedImages(protected)
                    )
                    val conflicts = participant.report?.merge?.conflicts?.size ?: 0
                    if (conflicts > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ConflictCount(
                            PortableRestoreCategory.GENERATED_IMAGES, conflicts
                        )
                    )
                }
                is ProfileImageRestoreParticipant -> {
                    val protected = participant.report?.protectedCurrentHashes?.size ?: 0
                    if (protected > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ProtectedImages(protected)
                    )
                }
                is CompanionCategoryRestoreParticipant -> {
                    participant.report?.conflicts.orEmpty().groupBy { it.category }
                        .forEach { (category, conflicts) ->
                            lines.add(PortableRestoreOutcome.Report.Line.NamedConflicts(
                                category,
                                conflicts.map { it.displayName }
                                    .filter(String::isNotBlank).distinct(),
                                conflicts.size
                            ))
                        }
                    removedLinks.addAll(participant.removedLorebookLinks)
                }
                is ModelEndpointRestoreParticipant -> {
                    val count = participant.mergeReport?.conflicts?.size ?: 0
                    if (count > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ConflictCount(
                            PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS, count
                        )
                    )
                }
                is CompanionMemoryRestoreParticipant -> {
                    val memory = participant.memoryReport?.conflicts?.size ?: 0
                    if (memory > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ConflictCount(
                            PortableRestoreCategory.MEMORIES, memory
                        )
                    )
                    val rules = participant.modelRulesReport?.conflicts?.size ?: 0
                    if (rules > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ConflictCount(
                            PortableRestoreCategory.MODEL_RULES, rules
                        )
                    )
                }
                is LorebookRestoreParticipant -> {
                    val count = participant.report?.conflicts?.size ?: 0
                    if (count > 0) lines.add(
                        PortableRestoreOutcome.Report.Line.ConflictCount(
                            PortableRestoreCategory.LOREBOOKS, count
                        )
                    )
                }
            }
        }
        return PortableRestoreOutcome.Report(lines.toList(), removedLinks.toList())
    }

    private fun cleanup(current: Session, keepTransactionForRecovery: Boolean) {
        wipe(current.unlock)
        current.unlock = null
        PortableStaging.delete(current.decodeRoot)
        if (!keepTransactionForRecovery) current.transactionRoot?.deleteRecursively()
    }

    private fun wipe(material: UnlockMaterial?) {
        when (material) {
            is UnlockMaterial.Secret -> material.value.fill(0)
            is UnlockMaterial.Password -> material.value.fill('\u0000')
            null -> Unit
        }
    }

    private fun copyInspection(source: PortablePackage.Inspection) = PortablePackage.Inspection(
        source.protection,
        source.createdAtIso,
        source.appVersion,
        source.producerAppId,
        source.producerDisplayName,
        source.keyFingerprint?.copyOf(),
        source.hasPasswordSlot
    )

    private fun nextVersionLocked(): Long = ++version

    private fun publishLocked(next: State) {
        state = next
        observers.toList().forEach { observer ->
            runCatching { observer.onStateChanged(next) }
        }
    }
}
