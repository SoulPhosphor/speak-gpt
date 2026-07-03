# Troubleshooting Guide (for schema v1.11)

Written for the system's owner, not the coder. You've only used frontier models with built-in memory, so here is what problems actually look like in a system you own — symptom first, then where to look, then the fix. None of this requires reading code; everything routes through the memory editor, run reports, and settings.

## The two habits that prevent most problems

Read the **run report** after each Archivist run — it's short on purpose. If it makes sense, the system is healthy. If something in it surprises you, that's your early warning, and the one-tap undo is right there. Second, once in a while confirm a **backup export** exists and is recent. Those two habits are ninety percent of system care.

## Symptoms and fixes

**A companion forgot something it definitely knew.** Search the memory editor for it. If the memory shows status superseded or archived, read its change log to see why — undo if wrong. If it's active but never surfaces, the index may be stale: Settings → Rebuild memory index. If it's scoped to a different companion, that's not forgetting — that's privacy working; re-scope it if you want it shared.

**A companion states something untrue about you.** Find the memory; check its provenance. If it's marked guessed/tentative, this is the harvest system being wrong, which is allowed — correct it in conversation (the Archivist will fix it on next run) or edit/reject it directly. If it's marked user_stated but you never said it, that's a provenance violation worth flagging in the repo as a bug.

**Wrong tone in a moment that mattered** (e.g., got task-mode when you needed presence). Check whether the message resembled the mode's stored signals; add the phrasing that failed to the mode's signals list. If the run report noted the failure, the Archivist may have already proposed the fix — check proposals.

**One API plays a companion wrong, others don't.** That's model drift, not memory: add or sharpen a model_adaptations note for that model on the companion. The Archivist proposes these on its own when the pattern is consistent.

**The same fact shows up twice, or two versions disagree.** If one source is a lore book, lore wins by design and the contradiction should appear flagged in the next run report — reconcile by editing whichever is wrong. If both are memories, archive the worse one; the Archivist should have superseded it and didn't, so expect it in a future run report.

**A companion knows another companion's private business.** Serious — scope isolation should make this impossible. Check the memory's scope and companion list; if they're correct and it still leaked, that's a code bug to report, not a memory to edit.

**A protected topic got handled badly anyway.** Read that memory's handling and never_assume lists — the model can only follow what's written. Vague handling produces vague behavior; sharpen the instructions with the exact thing that went wrong.

**The Archivist is harvesting weird or off-base patterns.** Reject them from the run report (rejections are remembered and teach it). If it keeps happening, lower harvest_generosity or flip pattern_harvest to propose in the dials. Wrong reads are tolerable; annoying volume is a dial.

**No memories are being created at all.** Check, in order: conversations sitting at pending (run the Archivist — it's manual); conversations marked excluded; the companion's memory_participation set to global_only or none; the memory kill switch left on from an experiment.

**Fiction is bleeding into real life** (a companion treats roleplay events as biography). Check whether the session's memories carry a world_id — if emergence failed to tag them, tag or archive them by hand, and flag it; the firewall depends on that tagging.

**App got slow or battery-hungry after enabling tier 2.** The embedding model variant is too big for the phone — switch to a smaller quantization in the model manager (same flow as Whisper) and rebuild the index.

**Personality feels like it's drifting over weeks.** Check proposals and the change log on that companion. Essence and hard limits can only change through your approvals — if the drift isn't in the records, it's a model problem (see model adaptations), not a memory problem. The mirror lets the Archivist notice this too; look for its drift flags.

## Escalation ladder (worst cases)

1. **Rebuild memory index** — fixes anything retrieval-shaped; costs minutes; loses nothing.
2. **Restore from export** — the rotating backups exist exactly for database corruption; you lose at most the time since the last export.
3. **Re-derive from transcripts** — the nuclear option: on a fresh store, re-import your seed, then re-run the Archivist over the retained transcript archive. Slow, but the raw material of nearly everything survives in the transcripts.

If a fix requires editing code rather than records, it's a repo issue — capture the run report and the specific record IDs involved; those two things are what an AI debugging the code will need from you.
