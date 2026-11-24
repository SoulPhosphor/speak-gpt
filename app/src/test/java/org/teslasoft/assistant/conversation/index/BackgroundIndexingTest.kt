package org.teslasoft.assistant.conversation.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.preferences.MessageCompletionState

/**
 * Step 13 (Owner, 2026-11-24): indexing after the app is closed. The rules
 * and wording choices as plain functions; the controller against a recorded
 * platform; and the whole path through the real coordinator, updater, vector
 * worker, vector passes and Step 12 progress against the in-memory index,
 * with the permit wired in as the runtime wires it. Every interleaving of
 * the main thread and the index's threads that changes behaviour is played
 * out deterministically, one task at a time. The Android
 * service itself is covered by `ConversationIndexingServiceInstrumentedTest`.
 */
class BackgroundIndexingTest {

    // --- The rules, as plain functions ------------------------------------------

    private val on = IndexingConditions(settingOn = true, rebuilding = false, recoveryFailed = false, modelUnavailable = false)
    private val numbers42 = IndexingNumbers(percent = 42, indexedChats = 3, totalChats = 9)

    @Test fun onlyAnUnpausedCatchUpWithWorkLeftIsContinuedWhenTheAppIsLeft() {
        fun decide(
            display: IndexingDisplay,
            conditions: IndexingConditions = on,
            paused: Boolean = false,
            userWork: Boolean = false
        ) = BackgroundIndexingRule.startDecision(display, conditions, paused, userWork)

        assertEquals(BackgroundStartDecision.START, decide(IndexingDisplay.Running(numbers42)))
        assertEquals("counting is a catch-up too", BackgroundStartDecision.START, decide(IndexingDisplay.Running(null)))
        assertEquals("a rebuild is followed by a catch-up", BackgroundStartDecision.START,
            decide(IndexingDisplay.Hidden, on.copy(rebuilding = true)))
        assertEquals("the update of a changed chat is not continued", BackgroundStartDecision.NOT_NEEDED,
            decide(IndexingDisplay.Hidden))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Paused(numbers42), paused = true))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Running(null), paused = true))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Running(null), on.copy(settingOn = false)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Running(null), on.copy(modelUnavailable = true)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Running(null), on.copy(recoveryFailed = true)))
        assertEquals(BackgroundStartDecision.NOT_NEEDED,
            decide(IndexingDisplay.Hidden, on.copy(settingOn = false, rebuilding = true)))
        assertEquals("work the user started comes first", BackgroundStartDecision.YIELD,
            decide(IndexingDisplay.Running(numbers42), userWork = true))
        assertEquals(BackgroundStartDecision.NOT_NEEDED, decide(IndexingDisplay.Hidden, userWork = true))
    }

    @Test fun theNotificationShowsTheRealPercentageAndNoneWhileCountingOrRebuilding() {
        fun next(display: IndexingDisplay, conditions: IndexingConditions = on) =
            BackgroundIndexingRule.next(display, conditions, paused = false, appVisible = false)

        assertEquals(BackgroundIndexingStep.Show(BackgroundIndexingContent.Percent(42)), next(IndexingDisplay.Running(numbers42)))
        assertEquals(BackgroundIndexingStep.Show(BackgroundIndexingContent.Counting), next(IndexingDisplay.Running(null)))
        assertEquals(BackgroundIndexingStep.Show(BackgroundIndexingContent.Rebuilding),
            next(IndexingDisplay.Hidden, on.copy(rebuilding = true)))
        assertEquals(BackgroundIndexingStep.Show(BackgroundIndexingContent.Rebuilding),
            next(IndexingDisplay.Running(numbers42), on.copy(rebuilding = true)))
    }

    @Test fun theServiceStopsForEachConditionAndNamesItsCause() {
        fun stop(display: IndexingDisplay, conditions: IndexingConditions = on, paused: Boolean = false, visible: Boolean = false) =
            (BackgroundIndexingRule.next(display, conditions, paused, visible) as BackgroundIndexingStep.Stop).reason

        val running = IndexingDisplay.Running(numbers42)
        assertEquals(BackgroundStopReason.APP_OPENED, stop(running, visible = true))
        assertEquals(BackgroundStopReason.USER_PAUSED, stop(IndexingDisplay.Paused(numbers42), paused = true))
        assertEquals(BackgroundStopReason.SETTING_OFF, stop(IndexingDisplay.Hidden, on.copy(settingOn = false)))
        assertEquals(BackgroundStopReason.REBUILD_FAILED,
            stop(IndexingDisplay.Hidden, on.copy(settingOn = false, recoveryFailed = true)))
        assertEquals(BackgroundStopReason.MODEL_UNAVAILABLE, stop(IndexingDisplay.Hidden, on.copy(modelUnavailable = true)))
        assertEquals(BackgroundStopReason.CATCH_UP_ENDED, stop(IndexingDisplay.Hidden))
    }

    @Test fun androidsRefusalsAreToldApartAndNothingElseIsTakenForThem() {
        val type = "android.app.ForegroundServiceStartNotAllowedException"
        assertEquals(BackgroundStopReason.TIME_USED_UP_AT_START,
            BackgroundIndexingRule.startFailure(type, "Time limit already exhausted for foreground service type dataSync"))
        assertEquals(BackgroundStopReason.BACKGROUND_START_NOT_ALLOWED,
            BackgroundIndexingRule.startFailure(type, "startForegroundService() not allowed due to mAllowStartForeground false"))
        assertEquals(BackgroundStopReason.UNEXPECTED_FAILURE,
            BackgroundIndexingRule.startFailure("java.lang.SecurityException", "Time limit"))
        assertEquals(BackgroundStopReason.UNEXPECTED_FAILURE, BackgroundIndexingRule.startFailure(IllegalStateException("x")))
        assertTrue(BackgroundStopReason.TIME_LIMIT.isAndroidLimit)
        assertFalse(BackgroundStopReason.MODEL_UNAVAILABLE.isAndroidLimit)
        assertFalse(BackgroundStopReason.UNEXPECTED_FAILURE.isAndroidLimit)
    }

    @Test fun theLogLinesUseTheApprovedWordingAndNeverCallALimitDamage() {
        assertEquals("Conversation indexing background service started.", BackgroundIndexingLog.STARTED)
        assertEquals(
            "Conversation indexing background service stopped: Android's time limit for background data work was reached. " +
                "Completed progress retained.",
            BackgroundIndexingLog.stopped(BackgroundStopReason.TIME_LIMIT)
        )
        assertEquals(
            "Conversation indexing background service failed: java.lang.SecurityException: denied. Effect: e. Retry: r.",
            BackgroundIndexingLog.failed("java.lang.SecurityException: denied", "e", "r")
        )
        for (reason in BackgroundStopReason.entries) {
            assertFalse(reason.text, reason.text.contains("damage", ignoreCase = true))
            assertFalse(reason.text, reason.text.contains("corrupt", ignoreCase = true))
        }
    }

    // --- The permit: the one owner of whether indexing may run --------------------

    @Test fun aProcessStartsClosedAndOnlyBecomingAbleToRunAgainContinuesWaitingWork() {
        val permit = BackgroundIndexingPermit()
        var continued = 0
        permit.setOnMayRunAgain { continued++ }
        assertFalse("a process starts with no screen", permit.mayRun())

        permit.foreground()
        assertEquals(1, continued)
        permit.service()
        assertEquals("foreground to service: it never stopped", 1, continued)
        assertTrue("the app is closed", permit.closedUnlessForeground())
        permit.foreground()
        assertEquals(2, continued)
        assertFalse("a stop arriving after the app opened changes nothing", permit.closedUnlessForeground())
        assertTrue(permit.mayRun())
        permit.closed()
        permit.closed()
        permit.service()
        assertEquals(3, continued)
    }

    // --- The controller against a recorded platform --------------------------------

    private class Platform : BackgroundIndexingPlatform {
        var display: IndexingDisplay = IndexingDisplay.Hidden
        var conditions = IndexingConditions(settingOn = true, rebuilding = false, recoveryFailed = false, modelUnavailable = false)
        var paused = false
        var userWork = false
        var refuseWith: Exception? = null
        val calls = ArrayList<String>()
        val logcat = ArrayList<String>()
        val errors = ArrayList<String>()
        override fun display() = display
        override fun conditions() = conditions
        override fun userPaused() = paused
        override fun userWorkRunning() = userWork
        override fun startService() {
            refuseWith?.let { throw it }
            calls.add("start")
        }
        override fun stopService(reason: BackgroundStopReason) { calls.add("stop:$reason") }
        override fun showStoppedNotice() { calls.add("notice") }
        override fun clearStoppedNotice() { calls.add("clear") }
        override fun logcat(message: String) { logcat.add(message) }
        override fun errorLog(message: String) { errors.add(message) }
    }

    /** Android's refusal: the real class cannot be made on the JVM, so it is classified by its real name. */
    private class AndroidRefusal(message: String) : IllegalStateException(message)

    private fun classify(e: Throwable) = BackgroundIndexingRule.startFailure(
        if (e is AndroidRefusal) BackgroundIndexingRule.START_NOT_ALLOWED else e.javaClass.name, e.message
    )

    private fun controllerFor(platform: BackgroundIndexingPlatform, permit: BackgroundIndexingPermit) =
        BackgroundIndexingController(platform, permit, ::classify)

    @Test fun leavingTheAppDuringACatchUpStartsTheServiceAndOnlyThen() {
        val platform = Platform()
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        controller.appOpened()
        platform.calls.clear()

        controller.appLeft()
        assertTrue("nothing to continue", platform.calls.isEmpty())
        assertFalse(permit.mayRun())

        platform.display = IndexingDisplay.Running(null)
        controller.appOpened()
        controller.appLeft()
        assertEquals(listOf("clear", "start"), platform.calls)
        assertTrue(controller.serviceActive)
        assertEquals(BackgroundIndexingPermit.State.SERVICE, permit.current)
        controller.serviceStarted()
        assertEquals(listOf(BackgroundIndexingLog.STARTED), platform.logcat)

        platform.calls.clear()
        controller.appOpened()
        assertEquals(listOf("stop:APP_OPENED", "clear"), platform.calls)
        assertEquals(BackgroundIndexingPermit.State.FOREGROUND, permit.current)
    }

    @Test fun aPausedIndexIsNeverStartedInTheBackground() {
        val platform = Platform().apply { display = IndexingDisplay.Paused(null); paused = true }
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        controller.appOpened()
        controller.appLeft()
        assertEquals(listOf("clear"), platform.calls)
        assertFalse(permit.mayRun())
    }

    @Test fun workTheUserStartedComesFirstAndIndexingWaitsForTheAppWithoutANotice() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42); userWork = true }
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        controller.appOpened()
        controller.appLeft()
        assertFalse(permit.mayRun())
        assertEquals(listOf("clear"), platform.calls)
        assertTrue(platform.errors.isEmpty())
    }

    @Test fun aRefusedStartClosesIndexingShowsTheApprovedNoticeAndIsNotAnError() {
        for ((message, reason) in listOf(
            "Time limit already exhausted for foreground service type dataSync" to BackgroundStopReason.TIME_USED_UP_AT_START,
            "startForegroundService() not allowed" to BackgroundStopReason.BACKGROUND_START_NOT_ALLOWED
        )) {
            // Refused when asked, and refused in the service's own startForeground.
            for (whenAsked in listOf(true, false)) {
                val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
                if (whenAsked) platform.refuseWith = AndroidRefusal(message)
                val permit = BackgroundIndexingPermit()
                val controller = controllerFor(platform, permit)
                controller.appOpened()
                controller.appLeft()
                if (!whenAsked) controller.startFailed(AndroidRefusal(message))
                assertFalse(permit.mayRun())
                assertFalse(controller.serviceActive)
                assertEquals(reason, controller.lastStop)
                assertTrue("notice" in platform.calls)
                assertEquals(listOf(BackgroundIndexingLog.stopped(reason)), platform.logcat)
                assertTrue("an Android limit is not an error", platform.errors.isEmpty())
            }
        }
    }

    @Test fun anUnexpectedFailureIsWrittenOnceWhileItGoesOnAndClosesWithoutTheNotice() {
        val platform = Platform().apply {
            display = IndexingDisplay.Running(numbers42)
            refuseWith = SecurityException("Starting FGS with type dataSync requires permissions")
        }
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        repeat(3) {
            controller.appOpened()
            controller.appLeft()
        }
        assertFalse(permit.mayRun())
        assertEquals(1, platform.errors.size)
        assertEquals(
            BackgroundIndexingLog.failed(
                "java.lang.SecurityException: Starting FGS with type dataSync requires permissions",
                BackgroundIndexingLog.EFFECT_NOT_STARTED, BackgroundIndexingLog.RETRY
            ),
            platform.errors.single()
        )
        assertFalse("the approved notice is for Android's limits only", "notice" in platform.calls)
        assertEquals(3, platform.logcat.count { it == BackgroundIndexingLog.stopped(BackgroundStopReason.UNEXPECTED_FAILURE) })

        // After a start that works, the same failure is written again when it returns.
        platform.refuseWith = null
        controller.appOpened()
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceFailed(SecurityException("Starting FGS with type dataSync requires permissions"))
        assertEquals(2, platform.errors.size)
        assertTrue(platform.errors.last().contains(BackgroundIndexingLog.EFFECT_STOPPED))
        assertFalse(permit.mayRun())
    }

    @Test fun androidsTimeLimitClosesIndexingAndShowsTheNoticeWhileOtherStopsDoNot() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        controller.appOpened()
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.CATCH_UP_ENDED)
        assertFalse(permit.mayRun())
        assertFalse("notice" in platform.calls)

        controller.appOpened()
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.TIME_LIMIT)
        assertFalse(permit.mayRun())
        assertTrue("notice" in platform.calls)
        assertEquals(BackgroundIndexingLog.stopped(BackgroundStopReason.TIME_LIMIT), platform.logcat.last())
        assertTrue(platform.errors.isEmpty())

        platform.calls.clear()
        controller.appOpened()
        assertEquals("the notice goes", listOf("clear"), platform.calls)
        assertTrue(permit.mayRun())
    }

    @Test fun stopsAndRefusalsThatArriveAfterTheAppOpenedAgainChangeNothing() {
        for (late in listOf<(BackgroundIndexingController) -> Unit>(
            { it.startFailed(AndroidRefusal("Time limit already exhausted")) },
            { it.serviceStopped(BackgroundStopReason.TIME_LIMIT) },
            { it.serviceStopped(BackgroundStopReason.APP_OPENED) },
            { it.serviceFailed(IllegalStateException("late")) }
        )) {
            val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
            val permit = BackgroundIndexingPermit()
            val controller = controllerFor(platform, permit)
            controller.appOpened()
            controller.appLeft()
            controller.appOpened()
            platform.calls.clear()
            late(controller)
            assertTrue("indexing keeps running in the app", permit.mayRun())
            assertFalse("no notice while the app is open", "notice" in platform.calls)
        }
    }

    // --- The whole path: real indexing, the service's steps, Android's stops ----------

    /** Runs handed-in tasks in the order they are due, on a clock that moves to each task's time. */
    private class Tasks : IndexTaskRunner {
        private class Due(val at: Long, val order: Long, val task: () -> Unit)
        var now = 0L
        private var order = 0L
        private val due = java.util.PriorityQueue<Due>(compareBy<Due>({ it.at }, { it.order }))
        override fun run(delayMillis: Long, task: () -> Unit) { due.add(Due(now + delayMillis, order++, task)) }
        fun runOne(): Boolean {
            val next = due.poll() ?: return false
            now = maxOf(now, next.at)
            next.task()
            return true
        }
        fun runAll() {
            var guard = 0
            while (runOne()) check(++guard < 10_000) { "tasks never settle" }
        }
    }

    private class StoredChats : IndexChatSource {
        val chats = LinkedHashMap<String, List<IndexSourceMessage>>()
        val versions = HashMap<String, Int>()
        /** Every chat read to count, compare or cut it, in order. */
        val reads = ArrayList<String>()
        fun put(chatId: String, messages: List<IndexSourceMessage>) {
            chats.remove(chatId)
            chats[chatId] = messages
            versions[chatId] = (versions[chatId] ?: 0) + 1
        }
        override fun read(chatId: String): IndexChatSource.Read {
            reads.add(chatId)
            val stored = chats[chatId] ?: return IndexChatSource.Read.Unavailable
            return IndexChatSource.Read.Messages(chatVersion(chatId), 0L, stored)
        }
        override fun chatVersion(chatId: String) = (versions[chatId] ?: 0).toString()
        override fun changeCount(chatId: String) = 0L
        /** The next read of the chat list fails, as storage briefly unavailable after a restore does. */
        var listFailsOnce = false
        override fun chatIdsNewestFirst(): List<String> {
            if (listFailsOnce) {
                listFailsOnce = false
                throw IllegalStateException("the chat list cannot be read for now")
            }
            return chats.keys.reversed()
        }
        override fun currentMessage(chatId: String, messageId: String) = chats[chatId]?.firstOrNull { it.messageId == messageId }
    }

    /** The on-device model: every exact input it reads, in order. */
    private class Model : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        override val tokenLimit = 400
        val inputs = ArrayList<String>()
        var afterRead: () -> Unit = {}
        /** The next read fails before reading anything, as a model that cannot run for now does. */
        var failNext = false
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
            if (failNext) {
                failNext = false
                throw IllegalStateException("the model could not run for now")
            }
            inputs.add(modelInput)
            afterRead()
            return FloatArray(DIMENSIONS) { (modelInput.hashCode() + it).toFloat() }
        }
    }

    private class SavedPause : EmbeddingPauseState { override var paused = false }

    private class SavedNumbers : SavedIndexingProgress {
        var value: IndexingNumbers? = null
        override fun read() = value
        override fun write(numbers: IndexingNumbers?) { value = numbers }
    }

    // What survives the app's process: the stored chats, the index and the saved pause.
    private val index = MemoryIndex()
    private val chats = StoredChats()
    private val pause = SavedPause()
    private val saved = SavedNumbers()
    private val model = Model()
    /** Runs each time a passage's search text is measured while a chat is cut. */
    private var whileCutting: (String) -> Unit = {}
    private val reader = SearchTextReader(
        vectorModel = MODEL, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = { whileCutting(it); words(it) }
    )
    private var recoveries = 0
    private var settingOn = true
    private var modelInstalled = true
    private var modelProblem = false
    private var rebuilding = false
    private var recoveryFailed = false
    private var readsAtOnce = false
    /** How often the model (its updater) was asked for. */
    private var modelLoads = 0

    /** Runs while a catch-up reads its work, before it is shown. */
    private var whileFindingWork: () -> Unit = {}

    /**
     * Runs each time an indexing thread checks whether it may run, with the
     * answer it got, so a test makes the main thread act at exactly that moment.
     */
    private var onMayRunChecked: (Boolean) -> Unit = {}

    // What one run of the app's process has.
    private lateinit var tasks: Tasks
    private lateinit var tracker: IndexProgressTracker
    private lateinit var coordinator: ConversationIndexCoordinator
    private lateinit var permit: BackgroundIndexingPermit
    private lateinit var platform: Platform
    private lateinit var controller: BackgroundIndexingController

    private val source = object : IndexProgressSource {
        var settingsKey: String? = null
        override fun work(chats: List<String>, settingsKey: String): IndexWork {
            this.settingsKey = settingsKey
            whileFindingWork()
            return IndexWorkFinder.work(
                chats, index.chatSummaries(MODEL, DIMENSIONS), index.waitingPassages(MODEL, DIMENSIONS),
                this@BackgroundIndexingTest.chats::chatVersion, settingsKey
            )
        }
        override fun numbers(counted: Map<String, CountedChat>): IndexingNumbers? {
            val key = settingsKey ?: return null
            return IndexingProgressMath.numbers(
                chats.chatIdsNewestFirst(), index.chatSummaries(MODEL, DIMENSIONS),
                index.messageProgress(MODEL, DIMENSIONS), counted, chats::chatVersion, key
            )
        }
        override fun conditions() = IndexingConditions(settingOn, rebuilding, recoveryFailed, modelProblem)
    }

    private fun mayRun(): Boolean = permit.mayRun().also { onMayRunChecked(it) }

    /** Starts the app's process: indexing as the runtime builds it, with the permit wired in the same way. */
    private fun startProcess() {
        tasks = Tasks()
        permit = BackgroundIndexingPermit()
        // The app reads the numbers on the index's thread while vectors are made on another; [readsAtOnce]
        // reads them the moment they are asked for, as that thread may while a vector pass runs.
        tracker = IndexProgressTracker(source, pause, saved) { delay, task -> if (readsAtOnce) task() else tasks.run(delay, task) }
        val updater = ConversationIndexUpdater(index, reader, 40, OVERLAP)
        val passes = IndexVectorPasses(
            runPass = {
                val catchUp = tracker.vectorPassStarting()
                PassageEmbeddingWorker(index, chats, reader, model, pause).runPass { !settingOn || !mayRun() }.also {
                    if (it is PassageEmbeddingWorker.Outcome.Finished) tracker.vectorPassFinished(catchUp)
                    if (it is PassageEmbeddingWorker.Outcome.Paused) tracker.vectorPassPaused()
                    // As in the runtime: a pass that stopped on a failure leaves the catch-up unable to end by itself.
                    if (it is PassageEmbeddingWorker.Outcome.Interrupted) tracker.vectorPassFailed()
                }
            },
            runner = tasks,
            isOn = { settingOn && mayRun() }
        )
        coordinator = ConversationIndexCoordinator(
            source = chats,
            storage = { index },
            maintenance = { index },
            updaterFor = { modelLoads++; updater.takeIf { modelInstalled } },
            requestVectors = passes::request,
            runner = tasks,
            isOn = { settingOn },
            events = object : IndexEvents {
                override fun modelUnavailable(cause: Exception?) { modelProblem = true; tracker.publish() }
            },
            isPaused = { tracker.isPaused },
            recover = { recoveries++; true },
            progress = tracker,
            mayRun = ::mayRun,
            clock = { tasks.now }
        )
        permit.setOnMayRunAgain { coordinator.mayRunAgain() }
        tracker.publish()
        platform = Platform()
        controller = controllerFor(object : BackgroundIndexingPlatform by platform {
            override fun display() = tracker.display.value
            override fun conditions() = tracker.conditions()
            override fun userPaused() = tracker.isPaused
        }, permit)
    }

    init {
        startProcess()
    }

    /** What the service does at its next update, as it works it out on the index's thread. */
    private fun serviceStep() = BackgroundIndexingRule.next(
        tracker.display.value, tracker.conditions(), tracker.isPaused, controller.appVisible, tracker.isStalled
    )

    private fun shownContent() = (serviceStep() as? BackgroundIndexingStep.Show)?.content

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) = IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)
    private fun chat(prefix: String) = listOf(
        user("$prefix-u1", "Hello $prefix"),
        reply("$prefix-a1", "Hi $prefix"),
        user("$prefix-u2", "Bye $prefix")
    )

    private fun putChats(count: Int) = (1..count).forEach { chats.put("c$it", chat("c$it")) }

    private fun allMade() = index.vectors.isNotEmpty() && index.vectors.values.all { it.state == ConversationIndexStore.STATE_MADE }

    /** Runs [block] with the app closed and no service, and checks that no chat was read and no vector made. */
    private fun assertNothingIndexedWhile(block: () -> Unit) {
        val reads = chats.reads.size
        val inputs = model.inputs.size
        val rows = index.rows.size
        val loads = modelLoads
        block()
        assertEquals("the model is not loaded without the notification", loads, modelLoads)
        assertEquals("no chat counted, compared or cut without the notification", reads, chats.reads.size)
        assertEquals("no vector read without the notification", inputs, model.inputs.size)
        assertEquals("nothing written without the notification", rows, index.rows.size)
    }

    private fun assertNothingReadTwice() =
        assertEquals("no vector is read twice", model.inputs.toSet().size, model.inputs.size)

    /** Every vector of [count] three-message chats is made, each read once. */
    private fun assertFinished(count: Int) {
        assertTrue("every vector is made", allMade())
        assertEquals(count * 3, model.inputs.size)
        assertNothingReadTwice()
    }

    /** Opens the app, turns indexing on and runs until the catch-up shows. */
    private fun startCatchUpWithTheAppOpen() {
        controller.appOpened()
        coordinator.turnedOn()
        while (tracker.display.value !is IndexingDisplay.Running) check(tasks.runOne()) { "the catch-up never showed" }
    }

    /** Opens the app, turns indexing on, then leaves the app while the catch-up runs; the service starts. */
    private fun leaveDuringTheCatchUp() {
        startCatchUpWithTheAppOpen()
        controller.appLeft()
        assertEquals(listOf("clear", "start"), platform.calls)
        controller.serviceStarted()
    }

    @Test fun indexingContinuesWithTheAppClosedAndTheNotificationShowsTheRealPercentageAndNoneWhileCounting() {
        putChats(3)
        leaveDuringTheCatchUp()
        assertEquals("counting: no percentage yet", BackgroundIndexingContent.Counting, shownContent())

        while ((tracker.display.value as? IndexingDisplay.Running)?.numbers == null) check(tasks.runOne())
        val numbers = (tracker.display.value as IndexingDisplay.Running).numbers!!
        assertEquals("the header icon's own numbers", BackgroundIndexingContent.Percent(numbers.percent), shownContent())

        // Part way through the vector pass, the notification follows the vectors actually made.
        readsAtOnce = true
        var partWay: Pair<IndexingDisplay, BackgroundIndexingContent?>? = null
        model.afterRead = {
            if (model.inputs.size == 4) {
                tracker.refresh()
                partWay = tracker.display.value to shownContent()
            }
        }
        tasks.runAll()
        val (display, content) = partWay!!
        assertEquals("three of nine messages have their vector", 33, (display as IndexingDisplay.Running).numbers!!.percent)
        assertEquals(BackgroundIndexingContent.Percent(33), content)

        assertFinished(3)
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED), serviceStep())
    }

    @Test fun androidsTimeLimitLosesNoFinishedWorkAndOpeningTheAppContinuesWithoutReadingAnythingTwice() {
        putChats(3)
        leaveDuringTheCatchUp()
        // The time limit is reached while the third vector is being read.
        model.afterRead = { if (model.inputs.size == 3) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT) }
        tasks.runAll()
        assertFalse(permit.mayRun())
        assertEquals("the read in progress was finished and nothing more was read", 3, model.inputs.size)
        assertEquals("finished vectors are kept", 3, index.vectors.values.count { it.state == ConversationIndexStore.STATE_MADE })
        assertTrue("notice" in platform.calls)

        controller.appOpened()
        tasks.runAll()
        assertFinished(3)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun afterTheProcessEndsTheNextStartContinuesFromTheLastFinishedPoint() {
        putChats(3)
        leaveDuringTheCatchUp()
        model.afterRead = { if (model.inputs.size == 4) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT) }
        tasks.runAll()
        assertEquals(4, model.inputs.size)

        // Android ends the process; the next opening starts a new one.
        model.afterRead = {}
        startProcess()
        coordinator.appStarted()
        controller.appOpened()
        tasks.runAll()
        assertFinished(3)
    }

    @Test fun itNeverStartsWhenTheDaysTimeIsUsedUpAndIndexingWaitsForTheApp() {
        putChats(2)
        startCatchUpWithTheAppOpen()
        platform.refuseWith = AndroidRefusal("Time limit already exhausted for foreground service type dataSync")
        controller.appLeft()
        assertFalse(controller.serviceActive)
        assertFalse(permit.mayRun())
        assertNothingIndexedWhile { tasks.runAll() }
        assertEquals(listOf("clear", "notice"), platform.calls)

        platform.refuseWith = null
        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    // --- Restores ---------------------------------------------------------------------

    @Test fun aRestoreFinishingWhileTheAppIsClosedRunsNothingUntilItOpens() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        controller.appLeft()
        assertEquals("nothing was left to continue", listOf("clear"), platform.calls)

        chats.put("c3", chat("c3"))
        assertNothingIndexedWhile {
            coordinator.chatSetReplaced()
            tasks.runAll()
        }
        assertEquals("no start after the app has left", listOf("clear"), platform.calls)

        controller.appOpened()
        tasks.runAll()
        assertFinished(3)
    }

    @Test fun aRestoreFinishingWhileTheAppIsClosedIsContinuedByTheNextProcess() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        controller.appLeft()
        chats.put("c3", chat("c3"))
        assertNothingIndexedWhile {
            coordinator.chatSetReplaced()
            tasks.runAll()
        }

        startProcess()
        assertNothingIndexedWhile {
            coordinator.appStarted()
            tasks.runAll()
        }
        controller.appOpened()
        tasks.runAll()
        assertFinished(3)
    }

    @Test fun aRestoreWhileTheServiceRunsCountsAgainThenShowsTheRealPercentage() {
        putChats(2)
        leaveDuringTheCatchUp()
        tasks.runAll()
        assertFinished(2)
        chats.put("c3", chat("c3"))
        // The service is still running (its next update has not seen the end yet).
        coordinator.chatSetReplaced()
        tasks.runOne()
        tasks.runOne()
        assertEquals(BackgroundIndexingContent.Counting, shownContent())
        while ((tracker.display.value as? IndexingDisplay.Running)?.numbers == null) check(tasks.runOne())
        assertTrue(shownContent() is BackgroundIndexingContent.Percent)
        tasks.runAll()
        assertFinished(3)
    }

    @Test fun aCatchUpThatEndedInTheBackgroundLeavesALaterRestoreWaitingForTheApp() {
        putChats(2)
        leaveDuringTheCatchUp()
        tasks.runAll()
        controller.serviceStopped(BackgroundStopReason.CATCH_UP_ENDED)
        assertFalse(permit.mayRun())

        chats.put("c3", chat("c3"))
        assertNothingIndexedWhile {
            coordinator.chatSetReplaced()
            tasks.runAll()
        }

        controller.appOpened()
        tasks.runAll()
        assertFinished(3)
    }

    // --- The app opening or closing at the same moment as indexing work -------------------

    @Test fun theAppOpeningAtTheMomentIndexingFindsItMayNotRunIsNeverMissed() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        controller.appLeft()
        chats.put("c3", chat("c3"))
        coordinator.chatSetReplaced()
        // The index's thread finds it may not run, and the app opens before it acts on that.
        var opened = false
        onMayRunChecked = { allowed ->
            if (!allowed && !opened) {
                opened = true
                controller.appOpened()
            }
        }
        tasks.runAll()
        assertTrue(opened)
        assertFinished(3)
    }

    @Test fun theAppOpeningWhileAVectorPassStopsForTheClosingIsNeverMissed() {
        putChats(2)
        leaveDuringTheCatchUp()
        model.afterRead = { if (model.inputs.size == 2) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT) }
        var opened = false
        onMayRunChecked = { allowed ->
            if (!allowed && !opened && model.inputs.size == 2) {
                opened = true
                controller.appOpened()
            }
        }
        tasks.runAll()
        assertTrue(opened)
        assertFinished(2)
    }

    @Test fun leavingWhileIndexingWorksDuringTheDecisionDropsNothingAndTheServiceContinuesIt() {
        putChats(3)
        startCatchUpWithTheAppOpen()
        // While the main thread decides, the index's thread keeps working.
        val deciding = object : BackgroundIndexingPlatform by platform {
            override fun display(): IndexingDisplay {
                repeat(3) { tasks.runOne() }
                return tracker.display.value
            }
            override fun conditions() = tracker.conditions()
            override fun userPaused() = tracker.isPaused
        }
        val leaving = controllerFor(deciding, permit)
        leaving.appLeft()
        assertEquals("start" , platform.calls.last())
        assertTrue(permit.mayRun())
        tasks.runAll()
        assertFinished(3)
    }

    @Test fun leavingWhileACatchUpIsStillBeingAdmittedKeepsItsWorkForTheApp() {
        putChats(2)
        controller.appOpened()
        // The app is left after the catch-up was admitted but before it is shown: no service.
        whileFindingWork = { whileFindingWork = {}; controller.appLeft() }
        coordinator.turnedOn()
        tasks.runAll()
        assertEquals(listOf("clear"), platform.calls)
        assertTrue("nothing compared without the notification", index.rows.isEmpty())
        assertTrue("nothing indexed without the notification", model.inputs.isEmpty())

        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun reopeningBeforeTheServiceStartsAndItsLateStopKeepIndexingRunningInTheApp() {
        putChats(3)
        leaveDuringTheCatchUp()
        tasks.runOne()
        // Reopened before the service reached startForeground: it is asked to stop when it gets there.
        controller.appOpened()
        assertEquals("stop:APP_OPENED", platform.calls[platform.calls.size - 2])
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.APP_OPENED)
        assertTrue(permit.mayRun())
        tasks.runAll()
        assertFinished(3)
        assertFalse("notice" in platform.calls)
    }

    @Test fun aChangedChatSavedJustBeforeClosingWaitsForTheApp() {
        putChats(1)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        chats.put("c2", chat("c2"))
        coordinator.chatChanged("c2")
        controller.appLeft()
        assertNothingIndexedWhile { tasks.runAll() }

        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun aProcessStartedForBackgroundWorkIndexesNothingUntilAScreenOpens() {
        putChats(2)
        assertNothingIndexedWhile {
            coordinator.appStarted()
            tasks.runAll()
        }
        assertTrue(platform.calls.isEmpty())

        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun waitingVectorsAreNotReadWhileTheAppIsClosedWithoutTheService() {
        putChats(2)
        controller.appOpened()
        // An earlier run compared the chats but was stopped before any vector was read.
        model.afterRead = { throw IllegalStateException("not now") }
        coordinator.turnedOn()
        tasks.runAll()
        model.inputs.clear()
        model.afterRead = {}
        assertTrue(index.vectors.values.all { it.state == ConversationIndexStore.STATE_WAITING })

        startProcess()
        controller.appOpened()
        whileFindingWork = { whileFindingWork = {}; controller.appLeft() }
        coordinator.appStarted()
        tasks.runAll()
        assertEquals(listOf("clear"), platform.calls)
        assertTrue("no vector read without the notification", model.inputs.isEmpty())

        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun closingStopsAChatPartWayThroughItsUpdateAndOpeningFinishesItFromThere() {
        chats.put("long", (1..6).flatMap { listOf(user("long-u$it", "Question $it"), reply("long-a$it", "Answer $it")) })
        putChats(1)
        leaveDuringTheCatchUp()
        // Android's time limit arrives while the long chat's second message is being cut.
        whileCutting = { text ->
            if ("Question 2" in text && controller.serviceActive) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT)
        }
        tasks.runAll()
        whileCutting = {}
        val longRows = index.rows.count { it.chatId == "long" }
        assertTrue("the update stopped part way: $longRows of 12 messages", longRows in 1..11)
        assertTrue("nothing read without the notification", model.inputs.isEmpty())

        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(12 + 3, model.inputs.size)
        assertNothingReadTwice()
    }

    @Test fun aCatchUpThatCannotGoOnAfterAFailureStopsTheServiceAndOpeningContinuesIt() {
        putChats(2)
        leaveDuringTheCatchUp()
        // The model cannot run for the third vector of the pass.
        model.afterRead = { if (model.inputs.size == 2) model.failNext = true }
        tasks.runAll()
        model.afterRead = {}
        assertEquals(2, model.inputs.size)
        assertTrue("the catch-up has not ended", tracker.display.value is IndexingDisplay.Running)
        assertEquals("the service never keeps the phone awake for nothing",
            BackgroundIndexingStep.Stop(BackgroundStopReason.INDEXING_FAILED), serviceStep())
        controller.serviceStopped(BackgroundStopReason.INDEXING_FAILED)
        assertFalse("not an Android limit: no notice", "notice" in platform.calls)
        assertFalse(permit.mayRun())

        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun aRecoveryWaitsWhileTheAppIsClosedAndRunsWhenItOpens() {
        putChats(2)
        assertNothingIndexedWhile {
            coordinator.recoverIndex()
            tasks.runAll()
        }
        assertEquals("no rebuild without a screen or the service", 0, recoveries)

        controller.appOpened()
        tasks.runAll()
        assertEquals(1, recoveries)
        assertFinished(2)
    }

    @Test fun openingContinuesWaitingWorkEvenWhenTheWaitingCatchUpCannotStartYet() {
        putChats(1)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        chats.put("c2", chat("c2"))
        coordinator.chatChanged("c2")
        controller.appLeft()
        assertNothingIndexedWhile {
            coordinator.chatSetReplaced()
            tasks.runAll()
        }

        chats.listFailsOnce = true
        controller.appOpened()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun aStartNoLongerWantedEndsWithTheLatestLeavesReason() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
        val permit = BackgroundIndexingPermit()
        val controller = controllerFor(platform, permit)
        controller.appOpened()
        controller.appLeft()
        // Reopened before the service started; then paused and left again.
        controller.appOpened()
        platform.paused = true
        platform.display = IndexingDisplay.Paused(numbers42)
        controller.appLeft()
        assertEquals("stop:USER_PAUSED", platform.calls.last())

        platform.paused = false
        platform.display = IndexingDisplay.Running(numbers42)
        platform.userWork = true
        controller.appOpened()
        controller.appLeft()
        assertEquals("stop:USER_WORK_RUNNING", platform.calls.last())
    }

    // --- The user's pause and the other conditions ------------------------------------------

    @Test fun theUserPausingBeforeClosingStartsNothingAndAPausedIndexNeverRestartsByItself() {
        putChats(2)
        startCatchUpWithTheAppOpen()
        tracker.pause()
        tasks.runAll()
        controller.appLeft()
        assertEquals(listOf("clear"), platform.calls)
        assertTrue(model.inputs.isEmpty())

        // Reopening, a restore while closed, and reopening again: the pause is kept throughout.
        controller.appOpened()
        tasks.runAll()
        controller.appLeft()
        assertNothingIndexedWhile {
            coordinator.chatSetReplaced()
            tasks.runAll()
        }
        controller.appOpened()
        tasks.runAll()
        assertTrue("the user's pause is kept", model.inputs.isEmpty())
        assertTrue(tracker.display.value is IndexingDisplay.Paused)

        tracker.resume()
        coordinator.resumed()
        tasks.runAll()
        assertFinished(2)
    }

    @Test fun turningIndexingOffOrDeletingTheIndexStopsTheService() {
        putChats(2)
        leaveDuringTheCatchUp()
        settingOn = false
        tracker.publish()
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.SETTING_OFF), serviceStep())

        settingOn = true
        coordinator.turnedOn()
        tasks.runOne()
        settingOn = false
        tracker.reset()
        coordinator.deleteIndex()
        tasks.runAll()
        assertEquals(BackgroundStopReason.SETTING_OFF, (serviceStep() as BackgroundIndexingStep.Stop).reason)
    }

    @Test fun aMissingModelStopsTheServiceWithItsOwnCause() {
        putChats(2)
        leaveDuringTheCatchUp()
        modelInstalled = false
        coordinator.resumed()
        tasks.runAll()
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.MODEL_UNAVAILABLE), serviceStep())
    }

    @Test fun aRebuildShowsRebuildingAndAFailedOneStopsTheService() {
        putChats(2)
        leaveDuringTheCatchUp()
        rebuilding = true
        tracker.publish()
        assertEquals(BackgroundIndexingContent.Rebuilding, shownContent())

        rebuilding = false
        recoveryFailed = true
        settingOn = false
        tracker.publish()
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.REBUILD_FAILED), serviceStep())
    }

    @Test fun openingTheAppStopsTheServiceAndForegroundIndexingCarriesOnAlone() {
        putChats(3)
        leaveDuringTheCatchUp()
        while (model.inputs.size < 2) check(tasks.runOne())
        platform.calls.clear()
        controller.appOpened()
        assertEquals(listOf("stop:APP_OPENED", "clear"), platform.calls)
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.APP_OPENED), serviceStep())
        tasks.runAll()
        assertFinished(3)
    }

    companion object {
        const val MODEL = "background-model"
        const val DIMENSIONS = 4
        const val OVERLAP = 5
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
