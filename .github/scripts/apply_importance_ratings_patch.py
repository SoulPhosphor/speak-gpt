from pathlib import Path


def _nl(data: bytes) -> bytes:
    return b"\r\n" if b"\r\n" in data else b"\n"


def rep(path: str, old: str, new: str, count: int = 1):
    p = Path(path)
    data = p.read_bytes()
    nl = _nl(data)
    old_b = old.replace("\n", nl.decode()).encode()
    new_b = new.replace("\n", nl.decode()).encode()
    actual = data.count(old_b)
    if actual != count:
        raise RuntimeError(f"{path}: expected {count} matches, found {actual} for {old[:80]!r}")
    p.write_bytes(data.replace(old_b, new_b, count))


# Preference is global and enabled by default. Turning it off never rewrites ratings.
rep(
    "app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt",
    'return getGlobalBoolean("use_importance_ratings", false)',
    'return getGlobalBoolean("use_importance_ratings", true)',
)

# A preferences read failure should degrade to the feature's default-On behavior.
rep(
    "app/src/main/java/org/teslasoft/assistant/preferences/memory/librarian/Librarian.kt",
    '''val useImportance = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { false }''',
    '''val useImportance = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { true }''',
    count=2,
)

# Memory Controls master switch.
controls = "app/src/main/java/org/teslasoft/assistant/ui/activities/MemoryControlsActivity.kt"
rep(
    controls,
    '''private var switchCompanionInRoleplay: MaterialSwitch? = null
    private var switchChatListMemoryStatus: MaterialSwitch? = null''',
    '''private var switchCompanionInRoleplay: MaterialSwitch? = null
    private var switchChatListMemoryStatus: MaterialSwitch? = null
    private var switchUseImportanceRatings: MaterialSwitch? = null''',
)
rep(
    controls,
    '''switchCompanionInRoleplay = findViewById(R.id.switch_companion_in_roleplay)
        switchChatListMemoryStatus = findViewById(R.id.switch_chat_list_memory_status)''',
    '''switchCompanionInRoleplay = findViewById(R.id.switch_companion_in_roleplay)
        switchChatListMemoryStatus = findViewById(R.id.switch_chat_list_memory_status)
        switchUseImportanceRatings = findViewById(R.id.switch_use_importance_ratings)''',
)
rep(
    controls,
    '''switchChatListMemoryStatus?.setOnCheckedChangeListener { _, checked ->
            preferences?.setShowMemoryStatusOnChatList(checked)
        }

        /* ---- Memory Engine ---- */''',
    '''switchChatListMemoryStatus?.setOnCheckedChangeListener { _, checked ->
            preferences?.setShowMemoryStatusOnChatList(checked)
        }

        // Importance is global, defaults ON, and is a pure retrieval/UI gate.
        // Switching it off preserves every stored rating for later re-use.
        switchUseImportanceRatings?.isChecked = preferences?.getUseImportanceRatings() ?: true
        switchUseImportanceRatings?.setOnCheckedChangeListener { _, checked ->
            preferences?.setUseImportanceRatings(checked)
        }

        /* ---- Memory Engine ---- */''',
)

controls_xml = "app/src/main/res/layout/activity_memory_controls.xml"
rep(
    controls_xml,
    '''            <!-- ============ Memory Engine ============ -->''',
    '''            <LinearLayout style="@style/Widget.App.Row.Toggle">

                <LinearLayout style="@style/Widget.App.Row.TextColumn">

                    <TextView
                        style="@style/Widget.App.Row.Title"
                        android:text="@string/memory_controls_importance_ratings" />

                    <TextView
                        style="@style/Widget.App.Row.Subtitle"
                        android:maxLines="10"
                        android:text="@string/memory_controls_importance_ratings_hint" />
                </LinearLayout>

                <com.google.android.material.materialswitch.MaterialSwitch
                    android:id="@+id/switch_use_importance_ratings"
                    style="@style/Widget.App.Row.Switch" />
            </LinearLayout>

            <!-- ============ Memory Engine ============ -->''',
)

# Ordinary editor uses the canonical signed values.
editor = "app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryEditorActivity.kt"
rep(editor, "record.importance.coerceIn(0, 5)", "record.importance.coerceIn(-2, 3)")
rep(
    editor,
    '''private fun importanceLabel(i: Int): String = getString(
        when (i) {
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            3 -> R.string.mem_importance_3
            4 -> R.string.mem_importance_4
            else -> R.string.mem_importance_5
        }
    )''',
    '''private fun importanceLabel(i: Int): String = getString(
        when (i.coerceIn(-2, 3)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )''',
)
rep(
    editor,
    '''// 0..5, with 0 · Neutral (§7): 0 is a valid permanent value.
        val labels = (0..5).map { importanceLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, currentImportance.coerceIn(0, 5)) { d, which ->
                currentImportance = which''',
    '''// Signed scale: -2, -1, 0 (neutral), +1, +2, +3 (mandatory when relevant).
        val values = listOf(-2, -1, 0, 1, 2, 3)
        val labels = values.map { importanceLabel(it) }.toTypedArray()
        val selected = values.indexOf(currentImportance.coerceIn(-2, 3)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, selected) { d, which ->
                currentImportance = values[which]''',
)

# Pending card carries + edits importance when the master toggle is on.
adapter = "app/src/main/java/org/teslasoft/assistant/ui/adapters/memory/MemoryRowAdapter.kt"
rep(
    adapter,
    '''val pendingCard: Boolean = false,
    /** Profile Images''',
    '''val pendingCard: Boolean = false,
    /** Stored importance for the inline Pending control. */
    val importance: Int = 0,
    /** Master-toggle presentation gate; hiding never mutates [importance]. */
    val showImportance: Boolean = false,
    /** Profile Images''',
)
rep(
    adapter,
    '''/** Retry tapped after a comparison failure. */
        fun onRetry(row: MemoryRow) {}''',
    '''/** Retry tapped after a comparison failure. */
        fun onRetry(row: MemoryRow) {}

        /** Importance control tapped on an associative Pending card. */
        fun onImportance(row: MemoryRow) {}''',
)
rep(
    adapter,
    '''view.findViewById<TextView>(R.id.pending_content).apply {
            if (row.subtitle.isNullOrBlank()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = row.subtitle }
        }

        val caution''',
    '''view.findViewById<TextView>(R.id.pending_content).apply {
            if (row.subtitle.isNullOrBlank()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = row.subtitle }
        }
        view.findViewById<TextView>(R.id.pending_importance).apply {
            if (!row.showImportance) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                text = context.getString(R.string.mem_importance_value_fmt, importanceLabel(row.importance))
                setOnClickListener { listener?.onImportance(row) }
            }
        }

        val caution''',
)
rep(
    adapter,
    '''        return view
    }
}''',
    '''        return view
    }

    private fun importanceLabel(value: Int): String = context.getString(
        when (value.coerceIn(-2, 3)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )
}''',
)

browser = "app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryBrowserActivity.kt"
rep(
    browser,
    '''pendingCard = usePendingCard,
            // Owner design: roleplay memories additionally get Add to Card''',
    '''pendingCard = usePendingCard,
            importance = m.importance.coerceIn(-2, 3),
            showImportance = usePendingCard && (preferences?.getUseImportanceRatings() ?: true),
            // Owner design: roleplay memories additionally get Add to Card''',
)
rep(
    browser,
    '''override fun onRetry(row: MemoryRow) {
        matchState[row.id] = MemoryRowAdapter.MATCH_LOADING
        currentAdapter?.notifyDataSetChanged()
        detectMatches(row.id)
    }

    /* -------------------- pending actions''',
    '''override fun onRetry(row: MemoryRow) {
        matchState[row.id] = MemoryRowAdapter.MATCH_LOADING
        currentAdapter?.notifyDataSetChanged()
        detectMatches(row.id)
    }

    override fun onImportance(row: MemoryRow) {
        val values = listOf(-2, -1, 0, 1, 2, 3)
        val labels = values.map { importanceLabel(it) }.toTypedArray()
        val selected = values.indexOf(row.importance.coerceIn(-2, 3)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val picked = values[which]
                dialog.dismiss()
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val memory = store.getMemory(row.id) ?: return@runOffThread
                    store.updateMemory(
                        memory.copy(importance = picked),
                        getString(R.string.memory_change_edited)
                    )
                    runOnUiThread { reload() }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun importanceLabel(value: Int): String = getString(
        when (value.coerceIn(-2, 3)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )

    /* -------------------- pending actions''',
)

pending_layout = "app/src/main/res/layout/view_memory_pending_card.xml"
rep(
    pending_layout,
    '''                <TextView
                    android:id="@+id/pending_content"''',
    '''                <com.google.android.material.button.MaterialButton
                    android:id="@+id/pending_importance"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="4dp"
                    android:minHeight="32dp"
                    android:text="@string/mem_importance_value_neutral"
                    android:visibility="gone" />

                <TextView
                    android:id="@+id/pending_content"''',
)

# Possible Match Review: proposal and existing cards expose the same editable rating.
review = "app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryPossibleMatchReviewActivity.kt"
rep(
    review,
    '''import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.textfield.TextInputEditText''',
    '''import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText''',
)
rep(
    review,
    '''card.findViewById<TextView>(R.id.review_content).text = record.content
        card.findViewById<ImageButton>(R.id.review_info).setOnClickListener {''',
    '''card.findViewById<TextView>(R.id.review_content).text = record.content
        card.findViewById<MaterialButton>(R.id.review_importance).apply {
            if (preferences?.getUseImportanceRatings() ?: true) {
                visibility = View.VISIBLE
                text = getString(R.string.mem_importance_value_fmt, importanceLabel(record.importance))
                setOnClickListener { showImportancePicker(record) }
            } else {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
        card.findViewById<ImageButton>(R.id.review_info).setOnClickListener {''',
)
rep(
    review,
    '''    /* ------------------------------ selection ------------------------------ */''',
    '''    private fun showImportancePicker(record: MemoryRecord) {
        val values = listOf(-2, -1, 0, 1, 2, 3)
        val labels = values.map { importanceLabel(it) }.toTypedArray()
        val selected = values.indexOf(record.importance.coerceIn(-2, 3)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val picked = values[which]
                dialog.dismiss()
                runOffThread {
                    val store = MemoryStore.getInstance(this)
                    val live = store.getMemory(record.memoryId) ?: return@runOffThread
                    store.updateMemory(
                        live.copy(importance = picked),
                        getString(R.string.memory_change_edited)
                    )
                    runOnUiThread { load() }
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun importanceLabel(value: Int): String = getString(
        when (value.coerceIn(-2, 3)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )

    /* ------------------------------ selection ------------------------------ */''',
)

review_layout = "app/src/main/res/layout/view_review_memory_card.xml"
rep(
    review_layout,
    '''            <TextView
                android:id="@+id/review_content"''',
    '''            <com.google.android.material.button.MaterialButton
                android:id="@+id/review_importance"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:minHeight="32dp"
                android:text="@string/mem_importance_value_neutral"
                android:visibility="gone" />

            <TextView
                android:id="@+id/review_content"''',
)

# Keep importance through prompt assembly so the post-filter count cap can honor +3.
assembly = "app/src/main/java/org/teslasoft/assistant/preferences/memory/enforcer/AssemblyData.kt"
rep(
    assembly,
    '''val similarity: Float = 0f,
    /** The memory's user-owned Type id''',
    '''val similarity: Float = 0f,
    /** Canonical signed importance; +3 may exceed the normal memory-count cap. */
    val importance: Int = 0,
    /** The memory's user-owned Type id''',
)

backfill = "app/src/main/java/org/teslasoft/assistant/preferences/memory/enforcer/RetrievalBackfill.kt"
rep(
    backfill,
    '''/** Walk [candidates] best-first, keeping survivors of [survives], until
     *  [topK] are kept, the list is exhausted, or [scanCap] candidates were
     *  examined. [survives] records its own removal reason at the call site. */
    fun <T> select(
        candidates: List<T>,
        topK: Int,
        scanCap: Int = scanCap(topK),
        survives: (T) -> Boolean
    ): Selection<T> {
        if (topK <= 0) return Selection(emptyList(), 0, false)
        val kept = ArrayList<T>(minOf(topK, candidates.size))
        var examined = 0
        for (c in candidates) {
            if (kept.size >= topK) break
            if (examined >= scanCap) return Selection(kept, examined, true)
            examined++
            if (survives(c)) kept.add(c)
        }
        return Selection(kept, examined, false)
    }''',
    '''/** Walk [candidates] best-first. Ordinary survivors fill [topK]; a
     *  candidate marked by [isMandatory] is still examined and kept after the
     *  normal count is full. Mandatory candidates may also be examined beyond
     *  [scanCap], because the scan cap is a work bound for ordinary backfill,
     *  not a way to silently drop an explicit +3 rating. [survives] still owns
     *  cooldown, lore-overlap, and character-budget filtering. */
    fun <T> select(
        candidates: List<T>,
        topK: Int,
        scanCap: Int = scanCap(topK),
        isMandatory: (T) -> Boolean = { false },
        survives: (T) -> Boolean
    ): Selection<T> {
        val normalLimit = topK.coerceAtLeast(0)
        val kept = ArrayList<T>(minOf(normalLimit, candidates.size))
        var examined = 0
        var capBlockedOrdinary = false
        for (c in candidates) {
            val mandatory = isMandatory(c)
            if (!mandatory && kept.size >= normalLimit) continue
            if (!mandatory && examined >= scanCap) {
                capBlockedOrdinary = true
                continue
            }
            examined++
            if (survives(c) && (mandatory || kept.size < normalLimit)) kept.add(c)
        }
        return Selection(kept, examined, capBlockedOrdinary && kept.size < normalLimit)
    }''',
)

enforcer = "app/src/main/java/org/teslasoft/assistant/preferences/memory/enforcer/Enforcer.kt"
rep(
    enforcer,
    '''import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.MemoryLog''',
    '''import org.teslasoft.assistant.preferences.memory.MemoryCompanionSync
import org.teslasoft.assistant.preferences.memory.ImportanceRanking
import org.teslasoft.assistant.preferences.memory.MemoryLog''',
)
rep(
    enforcer,
    '''val prefs = Preferences.getPreferences(appContext, input.chatId)

        // §3''',
    '''val prefs = Preferences.getPreferences(appContext, input.chatId)
        val useImportanceRatings = prefs.getUseImportanceRatings()

        // §3''',
)
rep(
    enforcer,
    '''RetrievalBackfill.select(pool, policy.topK, scanCap) { mem ->''',
    '''RetrievalBackfill.select(
                pool,
                policy.topK,
                scanCap,
                isMandatory = {
                    ImportanceRanking.isMandatory(it.importance.toDouble(), useImportanceRatings)
                }
            ) { mem ->''',
)
rep(
    enforcer,
    '''similarity = similarity,
            typeId = m.typeId''',
    '''similarity = similarity,
            importance = m.importance ?: 0,
            typeId = m.typeId''',
)

# User-facing strings.
strings = "app/src/main/res/values/strings.xml"
rep(
    strings,
    '''    <string name="mem_importance_0">0 · Neutral</string>
    <string name="mem_importance_1">1</string>
    <string name="mem_importance_2">2</string>
    <string name="mem_importance_3">3</string>''',
    '''    <string name="mem_importance_minus_2">-2</string>
    <string name="mem_importance_minus_1">-1</string>
    <string name="mem_importance_0">0 · Neutral</string>
    <string name="mem_importance_1">+1</string>
    <string name="mem_importance_2">+2</string>
    <string name="mem_importance_3">+3 · Always include</string>
    <string name="mem_importance_value_fmt">Importance: %1$s</string>
    <string name="mem_importance_value_neutral">Importance: 0 · Neutral</string>''',
)
rep(
    strings,
    '''    <string name="memory_controls_chat_list_status_hint">''',
    '''    <string name="memory_controls_importance_ratings">Use Importance Ratings</string>
    <string name="memory_controls_importance_ratings_hint">Rate memories from -2 to +3. 0 is neutral. +3 is always included when relevant, even beyond the normal memory-count limit. Turning this off keeps saved ratings but ignores them.</string>
    <string name="memory_controls_chat_list_status_hint">''',
)

# Tests: signed ranking, mandatory gate, and downstream count-cap behavior.
importance_test = "app/src/test/java/org/teslasoft/assistant/preferences/memory/ImportanceRankingTest.kt"
rep(importance_test, "importance = 5", "importance = 2")
rep(importance_test, "assertEquals(5, high.importance)", "assertEquals(2, high.importance)")
rep(
    importance_test,
    '''import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue''',
    '''import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue''',
)
rep(
    importance_test,
    '''    @Test
    fun importanceOff_twoMemoriesIdenticalExceptImportanceScoreEqually() {''',
    '''    @Test
    fun signedScaleIsSymmetricAndPlusThreeUsesPlusTwoScore() {
        assertEquals(-1.0, ImportanceRanking.normalizedRankingImportance(-2.0), 0.0)
        assertEquals(-0.5, ImportanceRanking.normalizedRankingImportance(-1.0), 0.0)
        assertEquals(0.0, ImportanceRanking.normalizedRankingImportance(0.0), 0.0)
        assertEquals(0.5, ImportanceRanking.normalizedRankingImportance(1.0), 0.0)
        assertEquals(1.0, ImportanceRanking.normalizedRankingImportance(2.0), 0.0)
        assertEquals(1.0, ImportanceRanking.normalizedRankingImportance(3.0), 0.0)
    }

    @Test
    fun plusThreeIsMandatoryOnlyWhenImportanceIsEnabled() {
        assertTrue(ImportanceRanking.isMandatory(3.0, true))
        assertFalse(ImportanceRanking.isMandatory(3.0, false))
        assertFalse(ImportanceRanking.isMandatory(2.0, true))
    }

    @Test
    fun importanceOff_twoMemoriesIdenticalExceptImportanceScoreEqually() {''',
)

backfill_test = "app/src/test/java/org/teslasoft/assistant/preferences/memory/enforcer/RetrievalBackfillTest.kt"
rep(
    backfill_test,
    '''    @Test
    fun nonPositiveTopKExaminesNothing() {
        var calls = 0
        val selection = RetrievalBackfill.select(listOf("a", "b"), topK = 0) { calls++; true }
        assertTrue(selection.kept.isEmpty())
        assertEquals(0, calls)
    }''',
    '''    @Test
    fun nonPositiveTopKExaminesNothingWithoutMandatoryCandidates() {
        var calls = 0
        val selection = RetrievalBackfill.select(listOf("a", "b"), topK = 0) { calls++; true }
        assertTrue(selection.kept.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun mandatoryCandidateSurvivesBeyondNormalTopK() {
        val candidates = listOf("normal", "also-normal", "must")
        val selection = RetrievalBackfill.select(
            candidates,
            topK = 1,
            isMandatory = { it == "must" }
        ) { true }
        assertEquals(listOf("normal", "must"), selection.kept)
    }

    @Test
    fun mandatoryCandidateIsExaminedBeyondOrdinaryScanCap() {
        val candidates = listOf("drop", "skip", "must")
        val selection = RetrievalBackfill.select(
            candidates,
            topK = 1,
            scanCap = 1,
            isMandatory = { it == "must" }
        ) { it != "drop" }
        assertEquals(listOf("must"), selection.kept)
        assertTrue(selection.scanCapReached)
    }''',
)

# Canonical UI/behavior copy. No legacy-number migration is needed for this app.
doc = "Memory System/memory_controls_and_pending_ui_copy.md"
rep(doc, "Memories can be rated from 0 to 5. Completely neutral is 0. Higher importance may take precedence when multiple memories apply.", "Memories can be rated from -2 to +3. 0 is neutral. -2 through +2 adjust ranking; +3 is always included when relevant, even when that exceeds the normal memory-count limit.")
rep(doc, "Recommended default: **Off**", "Recommended default: **On**")
rep(doc, "Values run from `0` to `5`.", "Values are `-2`, `-1`, `0`, `+1`, `+2`, and `+3`.")
rep(doc, "New memories start at `0`.", "New memories and memories without an assigned value are treated as `0`.")
rep(doc, "Values `1` through `5` are shown as the numbers themselves. Do not add semantic labels such as Low, Medium, or High.", "Values `-2`, `-1`, `+1`, and `+2` are shown as signed numbers. `+3` is shown as `+3 · Always include`. Do not add Low/Medium/High labels.")

# Remove the temporary mechanism from the resulting feature commit.
Path(".github/scripts/apply_importance_ratings_patch.py").unlink(missing_ok=True)
Path(".github/workflows/apply-importance-ratings-patch.yml").unlink(missing_ok=True)
