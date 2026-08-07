# Troubleshooting Guide (for schema v1.11)

> **⚠️ PRE-REVISION DOCUMENT.** Some workflows below reference screens
> and machinery retired July 6–7 2026 (modes, directives, entities,
> owner profile, the memory hub). The current screens are the Memories
> browser + Pending flow (rules §14) and the roleplay areas in
> `roleplay_cards_and_tags_spec.md`. Symptom-level advice still applies
> where the screens still exist.

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

---

## Phase 7 audit status (July 10 2026)

This appendix is the Phase 7 "end-to-end pass": each symptom above checked
against the CURRENT app UI and against `owner_approved_rules.md`. Verdicts:
**EXISTS** (a real screen/control does the job — path given), **RETIRED** (the
machinery the symptom names was removed by the owner's July 6–8 2026 rulings;
the advice is stale, not a bug), **GAP** (a live concern with no UI home yet —
surfaced to the owner, not silently built). No app code or strings were
changed by this pass.

Screen paths use the current navigation: **Settings → Memory Manager** opens
the hub (Memory Browser · Memory Assistant · Lorebooks · Memory Controls ·
Advanced Memory Settings).

| Symptom (above) | Verdict | Where it lives now / why retired |
|---|---|---|
| A companion forgot something — search, status, change log | **EXISTS** | Memory Browser (search + status badges; superseded/archived reachable via the Filters panel, since Status defaults to Active); per-row **History** shows the change log. |
| …index may be stale → Rebuild index | **EXISTS** | Memory Manager → **Advanced Memory Settings** → Rebuild Index. (Guide says "Settings → Rebuild memory index"; that's the real path.) |
| Untrue statement — check provenance (guessed/tentative vs user_stated) | **GAP** (display) / EXISTS (edit/reject) | Editing/deleting a memory EXISTS (Browser row → Edit / Delete). But a per-memory *confidence* marker is not surfaced — the approved model (§7) shows only **Source** = Entered by hand / Imported / Learned from chat (a Browser filter). Finer "guessed/tentative" is pre-rules; superseded by §7 Source. |
| Wrong tone → mode signals list | **RETIRED** | Modes retired (rules §15). No Modes screen exists; the model-drift remedy is now **Model rules** (AI System Settings). |
| One API plays a companion wrong → model_adaptations note | **RETIRED** | Removed from the companion page (Approved UI decisions — "Companion page surgery"). Use **Model rules** (§11) keyed to the model string instead. |
| Same fact twice / two versions disagree — contradiction flagged in run report | **GAP** | Archiving the worse memory EXISTS (Browser). But the run report (Memory Assistant → Recent Memory Analysis) surfaces counts/status, not contradiction flags or a reconcile action. Pre-rules aspiration; not built. |
| A companion knows another's private business | **EXISTS** (inspect) | Scope + targets are inspectable/editable in the Memory Editor; a genuine leak past that is a code bug to report, as the guide says. |
| Protected topic handled badly → handling / never_assume lists | **RETIRED** | "Protected" retired (addendum §2). No handling/never_assume UI. Care-notes now live inline in the memory's own text. |
| Archivist harvesting weird patterns — reject; harvest_generosity / pattern_harvest dials | **RETIRED** (dials) / EXISTS (reject) | Reject EXISTS (Browser **Pending** mode: Accept/Delete/Edit). The autonomy *dials* were collapsed to toggles — Memory Controls has "Suggest roleplay card updates" + a "Maximum suggestions per conversation" cap; no generosity/harvest-mode dials. |
| No memories being created (pending / excluded / participation / kill switch) | **EXISTS** (all four) | Run the Archivist → Memory Assistant → Analyze. Excluded chats + kill switch → Quick Settings ("Archive this chat" / "Use memory"). memory_participation → Companion detail (full / global_only / none). |
| Fiction bleeding into real life — world_id tag | **EXISTS** | Memory Editor scope picker (World/Campaign/RP-character + target picker); untargeted roleplay drafts are flagged "Needs roleplay target." |
| Slow/battery-hungry after tier 2 → switch quantization, rebuild | **EXISTS** | Advanced Memory Settings → Librarian model rows (download/delete per variant, same flow as Whisper) + Rebuild Index. |
| Personality drifting — proposals / change log / essence / hard limits / mirror drift flags | **MIXED** | Proposals → Pending mode **EXISTS**; change log **EXISTS**. Essence/hard-limits **RETIRED** from the companion page. The standing-packet "mirror drift flags" surface **RETIRED** (StandingPacketManager deleted in Stage 3.4) — no drift-flag view exists. |
| Escalation: Rebuild index | **EXISTS** | Advanced Memory Settings. |
| Escalation: Restore from export | **EXISTS** | Memory Controls → Import (+ rotating auto-backups / manual export). |
| Escalation: Re-derive from transcripts → "re-import your seed" | **PARTIAL** | Import (user's own export) + retained transcripts + rerun Archivist works. But there is **no bundled seed** to re-import (removed by owner decision — law 8, "no AI pre-authors"); the user supplies their own export file. |

**Screens a reader of the old guide would hunt for but that are gone**
(deleted per rules; only dormant tables remain, some still shown as
row-counts in Advanced Memory Settings → System Status, which can mislead):
Modes, Directives, Entities, Owner Profile, Protect/Unprotect, the
companion essence/hard-limits/model_adaptations fields, the
harvest_generosity/pattern_harvest dials, the mirror/drift-flags view, and
the old ten-area Memory hub (now Memory Manager → Memory Browser).
