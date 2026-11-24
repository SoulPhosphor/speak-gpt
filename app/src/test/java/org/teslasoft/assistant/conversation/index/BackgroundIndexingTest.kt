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
 * with the background hold wired in as the runtime wires it. The Android
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
        var onResume: () -> Unit = {}
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
        override fun resumeIndexing() { calls.add("resume"); onResume() }
        override fun logcat(message: String) { logcat.add(message) }
        override fun errorLog(message: String) { errors.add(message) }
    }

    /** Android's refusal: the real class cannot be made on the JVM, so it is classified by its real name. */
    private class AndroidRefusal(message: String) : IllegalStateException(message)

    private fun classify(e: Throwable) = BackgroundIndexingRule.startFailure(
        if (e is AndroidRefusal) BackgroundIndexingRule.START_NOT_ALLOWED else e.javaClass.name, e.message
    )

    private fun controllerFor(platform: BackgroundIndexingPlatform, hold: BackgroundIndexingHold) =
        BackgroundIndexingController(platform, hold, ::classify)

    @Test fun leavingTheAppDuringACatchUpStartsTheServiceAndOnlyThen() {
        val platform = Platform()
        val controller = controllerFor(platform, BackgroundIndexingHold())
        controller.appOpened()
        platform.calls.clear()

        controller.appLeft()
        assertTrue("nothing to continue", platform.calls.isEmpty())

        platform.display = IndexingDisplay.Running(null)
        controller.appOpened()
        controller.appLeft()
        assertEquals(listOf("clear", "start"), platform.calls)
        assertTrue(controller.serviceActive)
        controller.serviceStarted()
        assertEquals(listOf(BackgroundIndexingLog.STARTED), platform.logcat)

        platform.calls.clear()
        controller.appOpened()
        assertEquals(listOf("stop:APP_OPENED", "clear"), platform.calls)
    }

    @Test fun aPausedIndexIsNeverStartedInTheBackground() {
        val platform = Platform().apply { display = IndexingDisplay.Paused(null); paused = true }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        controller.appLeft()
        assertTrue(platform.calls.isEmpty())
        assertFalse(hold.held)
    }

    @Test fun workTheUserStartedComesFirstAndIndexingWaitsForTheAppWithoutANotice() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42); userWork = true }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        controller.appLeft()
        assertTrue(hold.held)
        assertTrue(platform.calls.isEmpty())
        assertTrue(platform.errors.isEmpty())

        controller.appOpened()
        assertFalse(hold.held)
        assertEquals(listOf("clear", "resume"), platform.calls)
    }

    @Test fun aRefusedStartHoldsIndexingShowsTheApprovedNoticeAndIsNotAnError() {
        for ((message, reason) in listOf(
            "Time limit already exhausted for foreground service type dataSync" to BackgroundStopReason.TIME_USED_UP_AT_START,
            "startForegroundService() not allowed" to BackgroundStopReason.BACKGROUND_START_NOT_ALLOWED
        )) {
            val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
            val hold = BackgroundIndexingHold()
            val controller = controllerFor(platform, hold)
            controller.appLeft()
            controller.startFailed(AndroidRefusal(message))
            assertTrue(hold.held)
            assertFalse(controller.serviceActive)
            assertEquals(listOf("start", "notice"), platform.calls)
            assertEquals(listOf(BackgroundIndexingLog.stopped(reason)), platform.logcat)
            assertTrue("an Android limit is not an error", platform.errors.isEmpty())
        }
    }

    @Test fun aRefusalWhenAskedToStartIsHandledTheSameWay() {
        val platform = Platform().apply {
            display = IndexingDisplay.Running(numbers42)
            refuseWith = AndroidRefusal("Time limit already exhausted for foreground service type dataSync")
        }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        controller.appLeft()
        assertTrue(hold.held)
        assertEquals(listOf("notice"), platform.calls)
        assertEquals(listOf(BackgroundIndexingLog.stopped(BackgroundStopReason.TIME_USED_UP_AT_START)), platform.logcat)
        assertTrue(platform.errors.isEmpty())
    }

    @Test fun anUnexpectedFailureIsWrittenOnceWhileItGoesOnAndHoldsWithoutTheNotice() {
        val platform = Platform().apply {
            display = IndexingDisplay.Running(numbers42)
            refuseWith = SecurityException("Starting FGS with type dataSync requires permissions")
        }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        repeat(3) {
            controller.appOpened()
            controller.appLeft()
        }
        assertTrue(hold.held)
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
    }

    @Test fun androidsTimeLimitHoldsIndexingAndShowsTheNoticeWhileOtherStopsDoNot() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.CATCH_UP_ENDED)
        assertFalse(hold.held)
        assertFalse("notice" in platform.calls)

        controller.appOpened()
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.TIME_LIMIT)
        assertTrue(hold.held)
        assertTrue("notice" in platform.calls)
        assertEquals(BackgroundIndexingLog.stopped(BackgroundStopReason.TIME_LIMIT), platform.logcat.last())
        assertTrue(platform.errors.isEmpty())

        platform.calls.clear()
        controller.appOpened()
        assertEquals("the notice goes and indexing continues", listOf("clear", "resume"), platform.calls)
    }

    @Test fun aRefusalArrivingAfterTheAppOpenedAgainChangesNothing() {
        val platform = Platform().apply { display = IndexingDisplay.Running(numbers42) }
        val hold = BackgroundIndexingHold()
        val controller = controllerFor(platform, hold)
        controller.appLeft()
        controller.appOpened()
        platform.calls.clear()
        controller.startFailed(AndroidRefusal("Time limit already exhausted"))
        assertFalse(hold.held)
        assertTrue(platform.calls.isEmpty())
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
        fun runUntil(condition: () -> Boolean) {
            var guard = 0
            while (!condition()) {
                check(runOne()) { "nothing left to run, and the condition never held" }
                check(++guard < 10_000) { "tasks never settle" }
            }
        }
    }

    private class StoredChats : IndexChatSource {
        val chats = LinkedHashMap<String, List<IndexSourceMessage>>()
        val versions = HashMap<String, Int>()
        fun put(chatId: String, messages: List<IndexSourceMessage>) {
            chats.remove(chatId)
            chats[chatId] = messages
            versions[chatId] = (versions[chatId] ?: 0) + 1
        }
        override fun read(chatId: String): IndexChatSource.Read {
            val stored = chats[chatId] ?: return IndexChatSource.Read.Unavailable
            return IndexChatSource.Read.Messages(chatVersion(chatId), 0L, stored)
        }
        override fun chatVersion(chatId: String) = (versions[chatId] ?: 0).toString()
        override fun changeCount(chatId: String) = 0L
        override fun chatIdsNewestFirst() = chats.keys.reversed()
        override fun currentMessage(chatId: String, messageId: String) = chats[chatId]?.firstOrNull { it.messageId == messageId }
    }

    /** The on-device model: every exact input it reads, in order. */
    private class Model : PassageVectorReader {
        override val vectorModel = MODEL
        override val vectorDimensions = DIMENSIONS
        override val tokenLimit = 400
        val inputs = ArrayList<String>()
        var afterRead: () -> Unit = {}
        override fun countTokens(modelInput: String) = words(modelInput)
        override fun read(modelInput: String): FloatArray {
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
    private val reader = SearchTextReader(
        vectorModel = MODEL, vectorDimensions = DIMENSIONS, hardLimit = 400,
        prefixFor = { "title: $it | text: " }, countModelInput = ::words
    )
    private var settingOn = true
    private var modelInstalled = true
    private var modelProblem = false
    private var rebuilding = false
    private var recoveryFailed = false

    private var readsAtOnce = false

    // What one run of the app's process has.
    private lateinit var tasks: Tasks
    private lateinit var tracker: IndexProgressTracker
    private lateinit var coordinator: ConversationIndexCoordinator
    private lateinit var hold: BackgroundIndexingHold
    private lateinit var platform: Platform
    private lateinit var controller: BackgroundIndexingController

    /** Runs while a catch-up reads its work, before it is shown. */
    private var whileFindingWork: () -> Unit = {}

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

    /** Starts the app's process: indexing as the runtime builds it, with the background hold wired in the same way. */
    private fun startProcess() {
        tasks = Tasks()
        hold = BackgroundIndexingHold()
        // The app reads the numbers on the index's thread while vectors are made on another; [readsAtOnce]
        // reads them the moment they are asked for, as that thread may while a vector pass runs.
        tracker = IndexProgressTracker(source, pause, saved) { delay, task -> if (readsAtOnce) task() else tasks.run(delay, task) }
        val updater = ConversationIndexUpdater(index, reader, 40, OVERLAP)
        val passes = IndexVectorPasses(
            runPass = {
                val catchUp = tracker.vectorPassStarting()
                PassageEmbeddingWorker(index, chats, reader, model, pause).runPass { !settingOn || hold.held }.also {
                    if (it is PassageEmbeddingWorker.Outcome.Finished) tracker.vectorPassFinished(catchUp)
                    if (it is PassageEmbeddingWorker.Outcome.Paused) tracker.vectorPassPaused()
                }
            },
            runner = tasks,
            isOn = { settingOn }
        )
        coordinator = ConversationIndexCoordinator(
            source = chats,
            storage = { index },
            maintenance = { index },
            updaterFor = { updater.takeIf { modelInstalled } },
            requestVectors = passes::request,
            runner = tasks,
            isOn = { settingOn },
            events = object : IndexEvents {
                override fun modelUnavailable(cause: Exception?) { modelProblem = true; tracker.publish() }
            },
            isPaused = { tracker.isPaused || hold.held },
            progress = tracker,
            catchUpWaits = hold::catchUpMustWait,
            clock = { tasks.now }
        )
        tracker.publish()
        platform = Platform().apply { onResume = { if (settingOn) coordinator.resumed() } }
        controller = controllerFor(object : BackgroundIndexingPlatform by platform {
            override fun display() = tracker.display.value
            override fun conditions() = tracker.conditions()
            override fun userPaused() = tracker.isPaused
        }, hold)
    }

    init {
        startProcess()
    }

    /** What the service does at its next update, as it works it out on the index's thread. */
    private fun serviceStep() =
        BackgroundIndexingRule.next(tracker.display.value, tracker.conditions(), tracker.isPaused, controller.appVisible)

    private fun shownContent() = (serviceStep() as? BackgroundIndexingStep.Show)?.content

    private fun user(id: String, text: String) = IndexSourceMessage(id, false, text, false, null, 100L, null)
    private fun reply(id: String, text: String) = IndexSourceMessage(id, true, text, false, MessageCompletionState.DONE, 200L, null)
    private fun chat(prefix: String) = listOf(
        user("$prefix-u1", "Hello $prefix"),
        reply("$prefix-a1", "Hi $prefix"),
        user("$prefix-u2", "Bye $prefix")
    )

    private fun putChats(count: Int) = (1..count).forEach { chats.put("c$it", chat("c$it")) }

    private fun allMade() = index.vectors.values.all { it.state == ConversationIndexStore.STATE_MADE } && index.vectors.isNotEmpty()

    private fun assertNothingReadTwice() =
        assertEquals("no vector is read twice", model.inputs.toSet().size, model.inputs.size)

    /** Turns indexing on with the app open, then leaves the app while the catch-up runs. */
    private fun leaveDuringTheCatchUp() {
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runOne()
        assertTrue(tracker.display.value is IndexingDisplay.Running)
        controller.appLeft()
        assertEquals(listOf("clear", "start"), platform.calls)
        controller.serviceStarted()
    }

    @Test fun indexingContinuesWithTheAppClosedAndTheNotificationShowsTheRealPercentageAndNoneWhileCounting() {
        putChats(3)
        leaveDuringTheCatchUp()
        assertEquals("counting: no percentage yet", BackgroundIndexingContent.Counting, shownContent())

        tasks.runUntil { (tracker.display.value as? IndexingDisplay.Running)?.numbers != null }
        val numbers = (tracker.display.value as IndexingDisplay.Running).numbers!!
        assertEquals("the header icon's own numbers", BackgroundIndexingContent.Percent(numbers.percent), shownContent())

        // Part way through, the notification follows the vectors actually made.
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
        val percent = (display as IndexingDisplay.Running).numbers!!.percent
        assertEquals("three of nine messages have their vector", 33, percent)
        assertEquals(BackgroundIndexingContent.Percent(33), content)

        assertTrue(allMade())
        assertEquals(9, model.inputs.size)
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.CATCH_UP_ENDED), serviceStep())
        assertNothingReadTwice()
    }

    @Test fun androidsTimeLimitLosesNoFinishedWorkAndOpeningTheAppContinuesWithoutReadingAnythingTwice() {
        putChats(3)
        leaveDuringTheCatchUp()
        // The time limit is reached while the third vector is being read.
        model.afterRead = { if (model.inputs.size == 3) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT) }
        tasks.runAll()
        assertTrue(hold.held)
        assertEquals("the read in progress was finished and nothing more was read", 3, model.inputs.size)
        assertEquals("finished vectors are kept", 3,
            index.vectors.values.count { it.state == ConversationIndexStore.STATE_MADE })
        assertTrue("notice" in platform.calls)

        // A change saved while held waits too.
        chats.put("c4", chat("c4"))
        coordinator.chatChanged("c4")
        tasks.runAll()
        assertEquals(3, model.inputs.size)

        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(12, model.inputs.size)
        assertNothingReadTwice()
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)
    }

    @Test fun afterTheProcessEndsTheNextStartContinuesFromTheLastFinishedPoint() {
        putChats(3)
        leaveDuringTheCatchUp()
        model.afterRead = { if (model.inputs.size == 4) controller.serviceStopped(BackgroundStopReason.TIME_LIMIT) }
        tasks.runAll()
        val before = model.inputs.size

        // Android ends the process; the next opening starts a new one.
        model.afterRead = {}
        startProcess()
        assertFalse("nothing held in a new process", hold.held)
        controller.appOpened()
        coordinator.appStarted()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(9, model.inputs.size)
        assertTrue(before < 9)
        assertNothingReadTwice()
    }

    @Test fun itNeverStartsWhenTheDaysTimeIsUsedUpAndIndexingWaitsForTheApp() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runOne()
        platform.refuseWith = AndroidRefusal("Time limit already exhausted for foreground service type dataSync")
        controller.appLeft()
        assertFalse(controller.serviceActive)
        assertTrue(hold.held)
        tasks.runAll()
        assertTrue("nothing runs without the service", model.inputs.isEmpty())
        assertEquals(listOf("clear", "notice"), platform.calls)

        platform.refuseWith = null
        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertNothingReadTwice()
    }

    @Test fun theUserPausingBeforeClosingStartsNothingAndAHeldPausedIndexNeverRestartsByItself() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runOne()
        tracker.pause()
        tasks.runAll()
        controller.appLeft()
        assertEquals(listOf("clear"), platform.calls)
        assertTrue(model.inputs.isEmpty())

        // Paused and held (another visit left during a catch-up that the time limit stopped).
        tracker.resume()
        coordinator.resumed()
        tasks.runOne()
        controller.appLeft()
        controller.serviceStarted()
        controller.serviceStopped(BackgroundStopReason.TIME_LIMIT)
        tracker.pause()
        tasks.runAll()
        val read = model.inputs.size
        controller.appOpened()
        tasks.runAll()
        assertEquals("the user's pause is kept", read, model.inputs.size)
        assertTrue(tracker.display.value is IndexingDisplay.Paused)
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
        chats.put("c3", chat("c3"))
        coordinator.chatChanged("c3")
        coordinator.resumed()
        tasks.runAll()
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.MODEL_UNAVAILABLE), serviceStep())
        assertFalse("not an Android limit: no hold", hold.held)
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

    @Test fun aRestoreWhileTheServiceRunsCountsAgainThenShowsTheRealPercentage() {
        putChats(2)
        leaveDuringTheCatchUp()
        tasks.runAll()
        chats.put("c3", chat("c3"))
        coordinator.chatSetReplaced()
        tasks.runOne()
        tasks.runOne()
        assertEquals(BackgroundIndexingContent.Counting, shownContent())
        tasks.runUntil { (tracker.display.value as? IndexingDisplay.Running)?.numbers != null }
        assertTrue(shownContent() is BackgroundIndexingContent.Percent)
        tasks.runAll()
        assertTrue(allMade())
        assertNothingReadTwice()
    }

    @Test fun aRestoreFinishingWithTheAppAlreadyInTheBackgroundStartsNoServiceAndKeepsItsWork() {
        putChats(2)
        controller.appOpened()
        coordinator.turnedOn()
        tasks.runAll()
        assertTrue(allMade())
        controller.appLeft()
        assertEquals("nothing was left to continue", listOf("clear"), platform.calls)

        chats.put("c3", chat("c3"))
        coordinator.chatSetReplaced()
        tasks.runAll()
        assertEquals("no start after the app has left", listOf("clear"), platform.calls)
        assertEquals("the catch-up waits for the app: nothing read without the notification", 6, model.inputs.size)
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)

        // A changed chat's own update is not held back.
        chats.put("c1", chat("c1") + user("c1-u3", "Again c1"))
        coordinator.chatChanged("c1")
        tasks.runAll()
        assertEquals(7, model.inputs.size)

        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(10, model.inputs.size)
        assertNothingReadTwice()

        // The same after the process ends instead: the next opening continues it.
        controller.appLeft()
        chats.put("c4", chat("c4"))
        coordinator.chatSetReplaced()
        tasks.runAll()
        assertEquals(10, model.inputs.size)
        startProcess()
        controller.appOpened()
        coordinator.appStarted()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(13, model.inputs.size)
        assertNothingReadTwice()
    }

    @Test fun aProcessStartedForBackgroundWorkIndexesNothingUntilAScreenOpens() {
        putChats(2)
        // Android starts the process for other background work: the startup catch-up runs before any screen.
        coordinator.appStarted()
        tasks.runAll()
        assertTrue("nothing indexed without a screen or the notification", model.inputs.isEmpty())
        assertTrue(platform.calls.isEmpty())

        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(6, model.inputs.size)
        assertNothingReadTwice()
    }

    @Test fun leavingWhileACatchUpIsStillBeingAdmittedLeavesItWaitingForTheApp() {
        putChats(2)
        controller.appOpened()
        // The app is left after the catch-up passed its check but before it is shown.
        whileFindingWork = { whileFindingWork = {}; controller.appLeft() }
        coordinator.turnedOn()
        tasks.runAll()
        assertEquals("not shown yet, so no service", listOf("clear"), platform.calls)
        assertTrue("nothing indexed without the notification", model.inputs.isEmpty())
        assertEquals(IndexingDisplay.Hidden, tracker.display.value)

        controller.appOpened()
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(6, model.inputs.size)
        assertNothingReadTwice()
    }

    @Test fun aCatchUpThatStopsInTheBackgroundLeavesLaterCatchUpsWaitingForTheApp() {
        putChats(2)
        leaveDuringTheCatchUp()
        tasks.runAll()
        controller.serviceStopped(BackgroundStopReason.CATCH_UP_ENDED)
        assertFalse(hold.held)

        chats.put("c3", chat("c3"))
        coordinator.chatSetReplaced()
        tasks.runAll()
        assertEquals(6, model.inputs.size)

        controller.appOpened()
        tasks.runAll()
        assertEquals(9, model.inputs.size)
        assertNothingReadTwice()
    }

    @Test fun openingTheAppStopsTheServiceAndForegroundIndexingCarriesOnAlone() {
        putChats(3)
        leaveDuringTheCatchUp()
        tasks.runUntil { model.inputs.size >= 2 }
        platform.calls.clear()
        controller.appOpened()
        assertEquals(listOf("stop:APP_OPENED", "clear"), platform.calls)
        assertEquals(BackgroundIndexingStep.Stop(BackgroundStopReason.APP_OPENED), serviceStep())
        assertFalse(hold.held)
        tasks.runAll()
        assertTrue(allMade())
        assertEquals(9, model.inputs.size)
        assertNothingReadTwice()
        assertNull(tracker.display.value as? IndexingDisplay.Running)
    }

    companion object {
        const val MODEL = "background-model"
        const val DIMENSIONS = 4
        const val OVERLAP = 5
        fun words(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() } + 2
    }
}
