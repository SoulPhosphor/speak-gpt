from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    found = text.count(old)
    if found < count:
        raise SystemExit(
            f"{path}: expected at least {count} occurrence(s), found {found}: {old[:120]!r}"
        )
    p.write_text(text.replace(old, new, count))


# Preference is global and ON by default. The setter's default must match so
# explicitly turning it off actually persists false.
pref = "app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt"
replace(
    pref,
    'return getGlobalBoolean("use_importance_ratings", false)',
    'return getGlobalBoolean("use_importance_ratings", true)',
)
replace(
    pref,
    'putGlobalBoolean("use_importance_ratings", enabled, false)',
    'putGlobalBoolean("use_importance_ratings", enabled, true)',
)

# Librarian: signed ranking plus a separate +3 mandatory-inclusion pass after
# relevance eligibility and before the normal count cap.
lib = "app/src/main/java/org/teslasoft/assistant/preferences/memory/librarian/Librarian.kt"
replace(
    lib,
    """internal fun reconciliationWeights(useImportance: Boolean): Weights = Weights(
            similarity = 1.0,
            importance = if (useImportance) RECONCILIATION_IMPORTANCE_WEIGHT else 0.0,
            recency = RECONCILIATION_RECENCY_WEIGHT
        )""",
    """internal fun reconciliationWeights(useImportance: Boolean): Weights = Weights(
            similarity = 1.0,
            importance = if (useImportance) RECONCILIATION_IMPORTANCE_WEIGHT else 0.0,
            recency = RECONCILIATION_RECENCY_WEIGHT,
            useImportanceRatings = useImportance
        )""",
)
replace(
    lib,
    "score = w_sim·cosine + w_imp·(importance/5) + w_rec·recency +",
    "score = w_sim·cosine + w_imp·signedImportance + w_rec·recency +",
)
replace(
    lib,
    "weights.importance * (c.memory.importance / 5.0) +",
    "weights.importance * ImportanceRanking.normalizedRankingValue(c.memory.importance) +",
)
replace(
    lib,
    "weights.importance * (mem.importance / 5.0) +",
    "weights.importance * ImportanceRanking.normalizedRankingValue(mem.importance) +",
)
replace(
    lib,
    "return scored.sortedByDescending { it.score }.take(topK)",
    "return ImportanceRanking.selectWithMandatory(scored, topK, weights.useImportanceRatings)",
    count=2,
)
replace(
    lib,
    "data class Weights(val similarity: Double, val importance: Double, val recency: Double)",
    """data class Weights(
        val similarity: Double,
        val importance: Double,
        val recency: Double,
        val useImportanceRatings: Boolean = true
    )""",
)
replace(
    lib,
    """} catch (_: Exception) { false }
        val importanceWeight = ImportanceRanking.effectiveImportanceWeight(bounded.value[1], useImportance)
        return Weights(bounded.value[0], importanceWeight, bounded.value[2])""",
    """} catch (_: Exception) { true }
        val importanceWeight = ImportanceRanking.effectiveImportanceWeight(bounded.value[1], useImportance)
        return Weights(bounded.value[0], importanceWeight, bounded.value[2], useImportance)""",
)
replace(
    lib,
    """} catch (_: Exception) { false }
        return reconciliationWeights(useImportance)""",
    """} catch (_: Exception) { true }
        return reconciliationWeights(useImportance)""",
)

# Ordinary memory editor: six-value signed picker, neutral default, and toggle
# visibility defaults to ON.
editor = "app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryEditorActivity.kt"
replace(
    editor,
    "import org.teslasoft.assistant.preferences.memory.CardType\n",
    "import org.teslasoft.assistant.preferences.memory.CardType\nimport org.teslasoft.assistant.preferences.memory.ImportanceRanking\n",
)
replace(editor, "an Importance dropdown (five steps),", "an Importance dropdown (six steps),")
replace(
    editor,
    """// (§7.1). When Off (the default), a new memory keeps the neutral 0 and
        // an edit preserves its stored value without exposing the control.
        val importanceOn = preferences?.getUseImportanceRatings() == true""",
    """// (§7.1). When Off, a new memory keeps the neutral 0 and an edit
        // preserves its stored value without exposing the control.
        val importanceOn = preferences?.getUseImportanceRatings() ?: true""",
)
replace(
    editor,
    """// Preserve the stored importance unchanged; range is 0..5 (§7).
                    currentImportance = record.importance.coerceIn(0, 5)""",
    """// Preserve the stored importance; sanitize only unsupported values.
                    currentImportance = ImportanceRanking.sanitizeImportance(record.importance)""",
)
replace(
    editor,
    """    private fun importanceLabel(i: Int): String = getString(
        when (i) {
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            3 -> R.string.mem_importance_3
            4 -> R.string.mem_importance_4
            else -> R.string.mem_importance_5
        }
    )
""",
    """    private fun importanceLabel(i: Int): String = getString(
        when (ImportanceRanking.sanitizeImportance(i)) {
            -2 -> R.string.mem_importance_minus_2
            -1 -> R.string.mem_importance_minus_1
            0 -> R.string.mem_importance_0
            1 -> R.string.mem_importance_1
            2 -> R.string.mem_importance_2
            else -> R.string.mem_importance_3
        }
    )
""",
)
replace(
    editor,
    """    private fun showImportancePicker() {
        // 0..5, with 0 · Neutral (§7): 0 is a valid permanent value.
        val labels = (0..5).map { importanceLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, currentImportance.coerceIn(0, 5)) { d, which ->
                currentImportance = which
                refreshImportance()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
""",
    """    private fun showImportancePicker() {
        val values = listOf(-2, -1, 0, 1, 2, 3)
        val labels = values.map { importanceLabel(it) }.toTypedArray()
        val current = values.indexOf(ImportanceRanking.sanitizeImportance(currentImportance))
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_edit_label_importance)
            .setSingleChoiceItems(labels, current) { d, which ->
                currentImportance = values[which]
                refreshImportance()
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }
""",
)

# Memory Controls: put the master switch where users can actually use it.
controls = "app/src/main/java/org/teslasoft/assistant/ui/activities/MemoryControlsActivity.kt"
replace(
    controls,
    "private var switchChatListMemoryStatus: MaterialSwitch? = null\n\n\n    private var textMemoryEngineValue",
    "private var switchChatListMemoryStatus: MaterialSwitch? = null\n    private var switchUseImportanceRatings: MaterialSwitch? = null\n\n    private var textMemoryEngineValue",
)
replace(
    controls,
    "switchChatListMemoryStatus = findViewById(R.id.switch_chat_list_memory_status)\n        textMemoryEngineValue",
    "switchChatListMemoryStatus = findViewById(R.id.switch_chat_list_memory_status)\n        switchUseImportanceRatings = findViewById(R.id.switch_use_importance_ratings)\n        textMemoryEngineValue",
)
replace(
    controls,
    """        switchChatListMemoryStatus?.setOnCheckedChangeListener { _, checked ->
            preferences?.setShowMemoryStatusOnChatList(checked)
        }

        /* ---- Memory Engine ---- */""",
    """        switchChatListMemoryStatus?.setOnCheckedChangeListener { _, checked ->
            preferences?.setShowMemoryStatusOnChatList(checked)
        }

        // User-owned signed importance ratings are enabled by default. Turning
        // this off only gates ranking/+3 inclusion; stored values stay intact.
        switchUseImportanceRatings?.isChecked = preferences?.getUseImportanceRatings() ?: true
        switchUseImportanceRatings?.setOnCheckedChangeListener { _, checked ->
            preferences?.setUseImportanceRatings(checked)
        }

        /* ---- Memory Engine ---- */""",
)

layout = "app/src/main/res/layout/activity_memory_controls.xml"
marker = "            <!-- ============ Memory Engine ============ -->"
importance_ui = """            <!-- ============ Importance Ratings ============ -->
            <TextView
                style="@style/Widget.App.Section.Title"
                android:text="@string/memory_controls_section_importance" />

            <LinearLayout style="@style/Widget.App.Row.Toggle">

                <LinearLayout style="@style/Widget.App.Row.TextColumn">

                    <TextView
                        style="@style/Widget.App.Row.Title"
                        android:text="@string/memory_controls_use_importance" />

                    <TextView
                        style="@style/Widget.App.Row.Subtitle"
                        android:maxLines="10"
                        android:text="@string/memory_controls_use_importance_hint" />
                </LinearLayout>

                <com.google.android.material.materialswitch.MaterialSwitch
                    android:id="@+id/switch_use_importance_ratings"
                    style="@style/Widget.App.Row.Switch"
                    android:checked="true" />
            </LinearLayout>

"""
replace(layout, marker, importance_ui + marker)

strings = "app/src/main/res/values/strings.xml"
replace(
    strings,
    '<string name="mem_importance_0">0 · Neutral</string>',
    '<string name="mem_importance_minus_2">-2</string>\n    <string name="mem_importance_minus_1">-1</string>\n    <string name="mem_importance_0">0 · Neutral</string>',
)
replace(strings, '<string name="mem_importance_1">1</string>', '<string name="mem_importance_1">+1</string>')
replace(strings, '<string name="mem_importance_2">2</string>', '<string name="mem_importance_2">+2</string>')
replace(strings, '<string name="mem_importance_3">3</string>', '<string name="mem_importance_3">+3 · Always include</string>')
replace(strings, '    <string name="mem_importance_4">4</string>\n', "")
replace(strings, '    <string name="mem_importance_5">5</string>\n', "")
replace(
    strings,
    '<string name="mem_importance_dialog_title">Importance</string>',
    '<string name="mem_importance_dialog_title">Importance</string>\n    <string name="memory_controls_section_importance">Importance Ratings</string>\n    <string name="memory_controls_use_importance">Use Importance Ratings</string>\n    <string name="memory_controls_use_importance_hint">Rate memories from -2 to +3. 0 is neutral. +3 memories are always included when relevant, even if that exceeds the normal memory limit.</string>',
)

# Canonical user-facing contract replaces the abandoned 0..5/off design.
spec = "Memory System/memory_controls_and_pending_ui_copy.md"
replace(
    spec,
    """> Memories can be rated from 0 to 5. Completely neutral is 0. Higher importance may take precedence when multiple memories apply.

**Recommended Default:** Off.
""",
    """> Memories can be rated from -2 to +3. Completely neutral is 0. Negative ratings reduce priority, positive ratings increase it, and +3 is always included when the memory is relevant.

**Recommended Default:** On.
""",
)
replace(
    spec,
    """When On:

- importance controls appear in Pending, Possible Match Review, and ordinary memory editing;
- allowed values are 0 through 5;
- new memories begin at 0;
- stored ratings reappear.

**Neutral Value:** `0 · Neutral`

Values 1 through 5 are displayed as numbers. Do not invent semantic labels such as `Critical`, `Minor`, or `Essential` unless the owner later approves them.

Importance is considered only after scope and semantic relevance have already made a memory eligible. It may help choose among multiple applicable memories, but it cannot make an irrelevant memory apply.
""",
    """When On:

- importance controls appear in Pending, Possible Match Review, and ordinary memory editing;
- allowed values are -2, -1, 0, +1, +2, and +3;
- new memories begin at 0;
- stored ratings reappear.

**Neutral Value:** `0 · Neutral`

Values -2 through +2 are ranking preferences around neutral. `+3 · Always include` is special: after scope and semantic/lexical relevance make the memory eligible, it must be included even when doing so exceeds the normal memory-count maximum. The normal maximum is therefore a soft cap only for eligible +3 memories.

Do not invent additional semantic labels such as `Critical`, `Minor`, or `Essential` unless the owner later approves them.

Importance is considered only after scope and relevance have already made a memory eligible. Negative and positive ratings may reorder applicable memories, but no importance value can make an irrelevant or out-of-scope memory apply.
""",
)

# Ranking tests used 3 as the old midpoint. Neutral is now 0.
ranking_test = "app/src/test/java/org/teslasoft/assistant/preferences/memory/librarian/LibrarianRankingTest.kt"
replace(ranking_test, "importance: Int = 3,", "importance: Int = 0,")
replace(ranking_test, 'cand(mem("high", importance = 5)', 'cand(mem("high", importance = 2)')
replace(ranking_test, "importance 5 and", "importance +2 and")
replace(ranking_test, 'mem("irrelevant-important", importance = 5)', 'mem("irrelevant-important", importance = 2)')
replace(ranking_test, 'mem("weaker", importance = 5, scope = "campaign")', 'mem("weaker", importance = 2, scope = "campaign")')
