# Phase 12 Replacement — Backup and Restore Integrity Audit and Repair Plan

**Repository:** `SoulPhosphor/speak-gpt`  
**Branch:** `claude/phase-9-replacement-coordinator-ebnptm`  
**Audited baseline:** `4e6f43e5f05cb82c1588b2bd87c7b6d4dd1cb24a`  
**Governing plan:** `drawer-image-gallery-implementation-plan.md`  
**Status:** Audit complete; implementation not started  
**Purpose:** Replace the original Phase 12 with an executable hardening and validation plan that does not require an AI agent to access the owner's phone.

## 1. Scope and audit method

This audit reviewed the governing plan and followed the complete recovery path rather than limiting the review to the drawer and image-gallery changes. The review covered:

- manual and automatic portable-backup creation;
- package encryption, manifests, hashes, extraction, and inventory;
- category selection, dependency planning, staging, apply, rollback, and process-death recovery;
- chats and folders;
- generated images;
- profile images and image references;
- companions, Glamours, roleplay identities, and settings;
- memories and their tombstones and relationship tables;
- lorebooks and identity-to-lorebook links;
- model endpoints and favorites;
- direct database restore and database repair;
- activity/service lifecycle, post-restore restart behavior, and result reporting;
- existing unit tests, instrumentation tests, and the Android CI workflow.

The branch's current Android Checks run for the audited baseline passed. A local Gradle run could not start because Android Gradle Plugin `8.9.1` was not available in the local offline cache and this environment could not fetch Gradle dependencies. Targeted SQLite/source checks were still run for two database concerns. They confirmed that:

1. reinserting an existing `deleted_ids` row violates that table's primary key; and
2. identity cleanup can delete a memory relationship that restoring the identity row later does not reconstruct.

The findings below distinguish source-confirmed defects from resilience risks that require fault-injection or device verification. No production code was changed during this audit.

## 2. Recovery invariants

Every implementation phase must preserve these invariants. A phase is incomplete if its tests pass while any relevant invariant remains unproven.

1. **A reported successful backup is complete.** Every selected category and every required referenced asset can be read by the restore parser used for that category.
2. **A reported successful restore is durable.** After a process restart, the restored state is still present and stale in-memory state cannot overwrite it.
3. **Failure is atomic.** A failed restore leaves the logical pre-restore state intact. If immediate rollback cannot finish, startup recovery must deterministically finish it before the affected stores are used.
4. **Replace means exact replacement inside the selected scope.** It must not retain selected-scope rows that are absent from the backup.
5. **Merge is idempotent.** Reapplying the same valid backup must not fail on existing primary keys or create duplicates.
6. **Category selection is truthful.** Unselected categories are not rewritten except for explicitly approved reference cleanup needed to keep selected categories valid. Any such cleanup appears in preflight and in the final report.
7. **Dependencies use real references.** Origin metadata, historical ownership, or catalog presence must not substitute for scanning the records that actually reference an asset or related entity.
8. **A package represents one coherent capture.** A backup must not combine mutually inconsistent generations of chats, identities, memories, lorebooks, or image catalogs.
9. **Unreadable dependency data fails closed.** A degraded or inaccessible store must never be interpreted as having no references.
10. **Restore planning is side-effect free.** Preflight must not provision databases, mutate preferences, import files, or depend on live state that can change before apply without detecting that change.
11. **Secrets remain local.** Portable endpoint restore continues to exclude API credentials and must not delete or overwrite local credentials.
12. **Existing package compatibility is explicit.** Any manifest revision must have a documented compatibility path for valid older packages and a clear rejection reason for unsupported ones.

## 3. Findings

Severity definitions:

- **P0:** can lose data, produce a falsely successful recovery, or make rollback unable to restore the original state;
- **P1:** can silently omit relationships/assets, expose a partial result, or leave the app in a state likely to overwrite restored data;
- **P2:** misleading metadata, avoidable availability problems, or validation gaps that do not by themselves mutate data.

| ID | Severity | Finding | Evidence and consequence |
|---|---:|---|---|
| BR-01 | P0 | Memory tombstone replace/merge is incorrect | `MemoryPortableRowFormat.replace` does not delete in-scope `deleted_ids` rows before `insertOrThrow`. Replace can retain tombstones absent from the backup. Merge or rollback can collide with an existing primary key and fail. |
| BR-02 | P0 | Identities and memories share one database but restore as independent participants | Identity replacement cleans memory link tables and mirrored identity fields. If a later participant fails, reverse-order rollback can restore memories against the wrong identity generation, then identity rollback can clean those restored links again. Exact rollback is not guaranteed. |
| BR-03 | P0 | A unified full restore can silently strip valid identity-to-lorebook links | Identity apply occurs before lorebooks. Link validation reads the live lorebook IDs, so a fresh device treats lorebooks contained in the same backup as missing and removes their links. Restoring lorebooks afterward does not recreate them. Reordering alone would create a corresponding rollback problem. |
| BR-04 | P1 | Removed lorebook links are lost from the unified report | The identity participant reduces the structured companion restore result to a Boolean, discarding `removedLinks`. A successful restore can alter identity configuration without reporting the alteration. |
| BR-05 | P0 | Generated-image dependency planning uses `originChatId` instead of actual chat references | A copied image can be referenced by a chat other than its origin. Selecting chats without the full image gallery can omit required bytes. Replacing the gallery while chats are unselected can protect unrelated origin images while deleting actually referenced images whose origin metadata is absent or different. |
| BR-06 | P0 | Profile-image protection is incomplete and does not fail closed | `ProfileImageUsage.computeAll` can return partial usage when the memory database is degraded, but unified restore still proceeds. It also omits roleplay characters not played by the user even though those records can carry `image_ref`. Gallery Replace can therefore delete an image still used by an unselected identity. |
| BR-07 | P1 | Identity backup can report success while omitting an assigned profile-image file | The companion exporter preserves the hash reference but skips a missing source file. Package validation allows that state. The resulting recovery backup can restore an identity whose assigned picture is unavailable. |
| BR-08 | P0 | Direct “Profile Image Database” restore installs only the catalog | The direct database path restores `user_images.db` but does not restore the package's `profile_image_asset` entries. On a fresh or different installation, the catalog can point at files that do not exist. |
| BR-09 | P0 | Unified chat restore does not honor the chat engine's mandatory restart contract | `ChatRestoreManager` requires restart before any later chat read/write. The older chat service relaunches and exits; the unified activity reports success without restarting. A `ChatActivity` already in the back stack can retain old messages and later save them over the restored chat. Other cached stores can also remain stale. |
| BR-10 | P1 | Unified preflight and execution ownership are unsafe across activity lifecycle | Heavy preflight work runs from the UI flow and serializes/parses current chat history. Execution uses a raw thread owned indirectly by the activity. Rotation or destruction can lose results or race staging cleanup, and large backups can freeze the UI. Validation is repeated during execution. |
| BR-11 | P0 | Direct encrypted-database restore has no durable crash journal | `DatabaseRepairManager` changes the database key and swaps files inside an exception-based rollback block. Process death after the key change or during file replacement bypasses the catch block and can leave the active file/key pair mismatched. Copy fallback is also not an atomic publication step. |
| BR-12 | P1 | Model endpoint apply and rollback are internally partial | Endpoint fields are deleted and rewritten through many preference edits. An exception or process death can expose a mixed set. Outer recovery can retry rollback, but rollback uses the same partial mutation pattern. |
| BR-13 | P1 | Finished-package verification proves structure, not restore readiness | Writer verification authenticates and extracts the package but does not run all category logical parsers, asset preparation, or cross-category dependency checks. Several defects above can pass package verification. |
| BR-14 | P1 | Backup capture is not a coherent multi-store snapshot | Categories are collected sequentially while normal app writes may continue. An identity, image assignment, lorebook link, or memory relationship can change between captures, producing individually valid artifacts whose combined state is inconsistent. |
| BR-15 | P1 | Declared category inventory is not tied to an exact category representation | The manifest's declared categories can be treated as explicitly empty merely because no recognized artifacts were found. Validation does not uniformly prove that every declared category has the required container or an explicit empty declaration. |
| BR-16 | P2 | SQLCipher version metadata is stale | The app depends on SQLCipher `4.17.0`, while portable package metadata declares `4.16.0`. This currently appears informational, which makes the manifest misleading and unsuitable as a compatibility gate. |
| BR-17 | P1 | Destination publication can leave an apparently final partial backup | Manual and automatic flows copy directly to the selected/final document. Verification catches ordinary copy errors, but process death during a provider write can leave a truncated `.sgbak` with a final-looking name. Restore should reject it, but its presence can mislead the user into believing a completed backup exists. |
| BR-18 | P2 | Very large logical artifacts can exhaust memory before mutation | Entry limits permit very large JSON files, while several readers load a complete artifact into a `String` and object graph. A valid-but-large package can terminate the process during preflight. The usual outcome is availability loss rather than silent data mutation, but the package limits currently promise more than the readers can safely handle. |

## 4. Required implementation order

The phases below are intentionally narrow and ordered. Each phase should be its own pull request or reviewable commit series. Do not combine later UI/lifecycle work with the database correctness work. A less capable implementation agent should receive only one phase at a time, plus this document and the governing plan.

Before each phase:

1. rebase or merge the latest working branch;
2. record the exact starting commit in the pull request;
3. run the existing unit-test suite for the touched subsystem;
4. add the regression tests listed for that phase in the same change as its production fix;
5. do not change user-visible wording without owner approval, as required by `CLAUDE.md`;
6. do not add new diagnostic logging without owner approval, as required by `CLAUDE.md`.

### Phase 12.1 — Introduce one immutable final-state plan

**Addresses:** BR-03, BR-05, BR-06, BR-13, BR-14, BR-15.

**Goal:** Produce a side-effect-free description of the exact final logical state and all cross-category dependencies before any participant starts staging or mutating data.

#### Work

1. Add a `PortableRestoreFinalState` or equivalently named immutable model. It must contain, at minimum:
   - selected category and mode for every category;
   - final chat IDs and the generated-image IDs actually referenced by final chat messages;
   - final identity IDs and their profile-image and lorebook references;
   - final lorebook IDs;
   - final memory/entity/project IDs and identity relationships;
   - final profile and generated image asset sets;
   - source-generation tokens for every live store read during planning;
   - all planned reference removals or remaps that must be reported.
2. Build the plan from parsed backup objects and read-only current snapshots. Do not allow individual participants to query a different live state during apply.
3. Represent dependency reads with a typed result such as `Available(snapshot)` or `Unavailable(reason)`. Never encode unreadable/degraded as an empty set.
4. Scan the final chat message records for `imageId`. Do not use `originChatId` as a liveness or dependency signal. Preserve origin only as descriptive metadata.
5. Scan all identity records capable of holding a profile image:
   - the default user image;
   - companions;
   - Glamours;
   - every roleplay character, regardless of `playedBy`;
   - any roleplay/session-level image reference supported by the current schema.
6. Resolve identity-to-lorebook links against the planned final lorebook set. For an identities-only restore, use the current lorebook snapshot. For a combined restore, use the backup/merged final lorebook plan. Record every removed link in the immutable plan.
7. Compute image dependencies from the selected categories' final records and the unselected categories' current records. The final gallery set is the union required to keep both groups valid.
8. Capture source generations before and after planning. If any source changes, discard the plan and retry once. If it changes again, return a visible non-mutating failure rather than applying a stale plan.
9. Make `UnifiedPortableRestore.validate()` consume the immutable plan. Execution may verify generation tokens, but must not repeat the expensive full parse or silently build a different plan.

#### Tests

- A copied generated image whose `originChatId` points to Chat A but whose only live reference is in Chat B follows Chat B selection.
- A generated image with a selected-chat origin and no selected-chat references is not treated as required by that chat.
- Gallery Replace preserves an image referenced by an unselected chat even when its origin is null or another chat.
- Profile gallery Replace preserves images used by companion-played and GM-played roleplay characters.
- A degraded or unreadable Memory store blocks profile-image deletion before staging.
- Full restore on an empty installation retains identity links to lorebooks present in the same package.
- Identities-only restore removes only links missing from the current lorebook set and records each removal.
- A source generation change during planning causes a retry; a second change causes a non-mutating failure.
- The same parsed plan drives preflight counts, stage inputs, apply, and the final report.

#### Acceptance gate

No participant may derive cross-category dependencies during `apply` or `rollback`. All dependency sets and planned cleanup must be inspectable from one immutable preflight object.

### Phase 12.2 — Make the shared Memory database one physical transaction participant

**Addresses:** BR-01, BR-02, and the memory/identity part of BR-03.

**Goal:** Treat logical identities and memories as separate user-selectable categories while applying their rows through one physical owner for `companion_memory.db`.

#### Work

1. Introduce one shared-store restore participant for every portable table in `companion_memory.db`. The existing logical identity and memory planners may remain, but neither may write the database independently.
2. During stage, snapshot the exact current rows for all tables the combined operation can affect, including:
   - identity and roleplay tables;
   - memory/entity/project tables;
   - join tables such as `rp_tag_links`;
   - identity mirror/reference columns;
   - transcripts and app-state keys currently changed by identity cleanup;
   - `deleted_ids` for the selected record types.
3. Compose one final row set from the selected-category modes and the immutable final-state plan. Explicitly document which cleanup is permitted when identities are selected and memories are not. Preserve the already approved behavior from the governing companion/roleplay plan unless the owner approves a different product behavior.
4. Apply the final row set in one SQLCipher transaction. Validate foreign keys before commit.
5. Roll back from the exact shared-store snapshot in one SQLCipher transaction. Do not recompute rollback data from the then-current identities or lorebooks.
6. Correct tombstone semantics:
   - before inserting desired tombstones, delete exactly `record_type IN ('memory','entity','project')`;
   - preserve tombstones for every other record type;
   - make both Merge and Replace idempotent;
   - use the same corrected primitive for rollback.
7. Remove the independent identity/memory participant ordering dependency from the outer coordinator. The outer journal must see one started/completed shared-store participant.
8. Ensure process-death recovery can identify and roll back this participant without provisioning a new empty database.

#### Tests

- Replace with an empty memory backup removes memory/entity/project tombstones and preserves unrelated tombstone types.
- Merge with existing and overlapping tombstones succeeds and produces one row per primary key.
- Reapplying the same Merge is a no-op at the logical level.
- Identity Replace followed by an injected later-participant failure restores every original memory link, mirrored field, transcript, and app-state value.
- A combined identity+memory restore with different identity IDs commits valid relationships in one transaction.
- An injected failure before commit changes no rows.
- Process death at each outer journal boundary restores the exact pre-restore shared database.
- SQLCipher integrity and foreign-key checks pass after apply and rollback.

#### Acceptance gate

A byte-for-byte database match is not required because SQLite page layout can change, but a canonical dump of every in-scope table before a failed operation must equal the canonical dump after recovery.

### Phase 12.3 — Repair lorebook resolution and result reporting

**Addresses:** BR-03 and BR-04.

**Goal:** Preserve valid links across combined restores and make every intentional link removal visible in preflight and the final report.

#### Work

1. Change companion/roleplay planning to accept the explicit final lorebook ID set from Phase 12.1. Do not read `LorebookStore` inside apply.
2. Stage original identity data with the original lorebook resolution context and desired identity data with the desired context. This keeps both apply and rollback deterministic.
3. Keep lorebooks as their own physical database participant, but remove any correctness dependency on whether it applies before or after the shared Memory participant.
4. Replace the identity participant's Boolean apply result with a structured outcome that retains removed lorebook links and any other approved cleanup.
5. Add these structured outcomes to `UnifiedPortableRestore.Report`. Reuse approved wording where possible. Obtain owner approval before adding or changing displayed strings.

#### Tests

- Fresh-install full restore preserves all valid identity-to-lorebook links.
- Identities + Lorebooks Merge resolves links against the merged final set.
- Lorebooks-only restore does not rewrite identities.
- Identities-only restore handles absent lorebooks according to the approved policy and reports changes.
- Failure after both participants apply restores the original identity rows, lorebook rows, and links.
- Process-death recovery produces the same result as immediate rollback.

#### Acceptance gate

Participant reordering in a test-only coordinator must not change the planned final links or the rollback result.

### Phase 12.4 — Make image backup and restore dependency-complete

**Addresses:** BR-05, BR-06, BR-07, and BR-08.

**Goal:** Never delete a referenced image and never report a portable identity/gallery backup as successful when a required file is missing or corrupt.

#### Work

1. Replace the generated-image origin filters with the actual-reference sets from Phase 12.1.
2. Replace `ProfileImageUsage.computeAll` in destructive restore planning with the typed, complete dependency collector from Phase 12.1. Keep non-destructive callers separate if they can tolerate partial results.
3. During backup, require every assigned identity image reference to resolve to a readable file whose content hash matches its identity. If it does not, fail the affected recovery backup visibly and retain the previous automatic backup.
4. Validate that a current-format full profile gallery contains every profile asset required by identity artifacts in the same package. During restore of an older compatible package, union identity-carried dependencies into the final gallery plan so a later gallery Replace cannot remove them.
5. Route direct portable “Profile Image Database” restore through the profile image participant in Replace mode so the catalog and assets commit and roll back together.
6. For a legacy standalone `user_images.db`, allow installation only if every referenced file already exists locally and validates. Otherwise reject it without changing the live catalog. Do not create catalog-only portability semantics.
7. Verify post-apply closure before marking the image participants complete:
   - every catalog row has a valid file;
   - every planned identity image reference resolves;
   - every planned chat image reference resolves;
   - no file scheduled for deletion remains referenced.

#### Tests

- Selected copied-chat reference includes the required generated-image bytes.
- Generated gallery Replace preserves all images referenced by unselected chats and deletes only truly unused rows/files.
- Identity export with a missing or hash-mismatched assigned image fails without publishing a recovery package.
- Full profile gallery plus identity restore preserves an identity-carried asset absent from an older gallery artifact.
- Direct profile database restore on an empty installation restores both rows and bytes.
- Legacy catalog-only restore with absent bytes is rejected and leaves the current catalog unchanged.
- Failures after asset import, catalog swap, and deletion each restore the exact prior logical state.

#### Acceptance gate

After every successful test restore, a reference-closure assertion must find zero missing generated/profile files. After every failed test restore, it must find the same closure and gallery membership as before the attempt.

### Phase 12.5 — Move unified restore into a durable coordinator and enforce restart

**Addresses:** BR-09 and BR-10.

**Goal:** Give the operation a lifecycle independent of the settings activity and guarantee a clean process before restored stores can be read or overwritten.

#### Work

1. Move decode, semantic preflight, collision handling, staging, execution, and recovery ownership into a foreground service or another existing project-approved durable coordinator. Do not keep the operation in a raw activity-owned thread.
2. Run all package I/O, current-state serialization, JSON parsing, and database reads off the main thread.
3. Expose immutable progress and collision-request state to the activity. The activity may be recreated without deleting coordinator-owned staging.
4. Persist the terminal outcome and structured report before cleanup/restart. On the next launch, consume and display that result once.
5. After a successful restore that touched any cached or persistent app state, finish the task, relaunch the app's normal entry point, and terminate the old process using the already established restart pattern.
6. Block activity resume/save paths while a completed restore is waiting for restart. In particular, an existing `ChatActivity` must never save its pre-restore in-memory messages after chat replacement.
7. Retain the outer journal until participants are complete and the durable outcome is written. Make cleanup idempotent and owned only by the coordinator.
8. Reuse the immutable validated plan from Phase 12.1. Immediately before apply, compare source-generation tokens; if stale, return to preflight rather than applying.

#### Tests

- Opening a chat, starting restore from settings, and succeeding forces a clean relaunch; the old activity cannot write afterward.
- Rotation/recreation during decode, preflight, folder collision choice, apply, and result display neither cancels nor duplicates the operation.
- Killing the activity while the service runs preserves staging and the eventual result.
- Killing the process at every outer journal boundary recovers before affected stores are opened.
- A large synthetic chat set does not perform parsing or serialization on the main thread.
- The terminal result appears once after relaunch and cleanup completes afterward.

#### Acceptance gate

There must be no code path from unified success back to a live pre-restore activity stack. The restart requirement in `ChatRestoreManager` must be enforced by the coordinator rather than left to the caller.

### Phase 12.6 — Make direct database restore crash-safe

**Addresses:** BR-11 and the remaining direct-restore portion of BR-08.

**Goal:** A process death at any instruction boundary cannot strand an encrypted database with the wrong key or a partially published active file.

#### Work

1. Add a durable per-database restore journal stored outside the database being replaced. At minimum record:
   - database type and source package identity;
   - active, staged, and quarantine paths;
   - original key state and intended key state without exposing key bytes in ordinary text;
   - current phase;
   - hashes/sizes needed to distinguish the original, staged, and installed files.
2. Write and durably flush the journal before changing the stored key or active files.
3. Stage the complete database in the destination database directory, verify it with the intended key, close all handles, and publish with a same-filesystem atomic rename. Remove the delete-then-copy fallback for the active file.
4. Define explicit phases such as `PREPARED`, `KEY_SWITCHED`, `FILE_INSTALLED`, `VERIFIED`, and `COMMITTED`. Startup recovery must either finish installation or restore both original key and quarantine files based only on durable evidence.
5. Include sidecar files in the protocol and ensure WAL/shm files cannot be paired with the wrong main database.
6. Do not clear quarantine or the journal until the installed database has opened through the normal store path and passed integrity checks.
7. Serialize direct repair/restore against portable backup, unified restore, auto-backup, and normal database replacement through a common recovery-operation gate.

#### Tests

- Fault injection/process death after every journal, key, rename, verification, and cleanup step.
- Recovery with original key absent, present, and rotated.
- Recovery with each of the active, staged, or quarantine files missing or truncated.
- WAL and non-WAL starting states.
- Startup always reaches either the exact original logical database or the exact desired logical database; it never provisions over ambiguous evidence.

#### Acceptance gate

For every injected crash point, startup recovery must choose one deterministic state and `PRAGMA integrity_check` plus the normal logical readers must pass before the store becomes available.

### Phase 12.7 — Make model endpoint restore internally recoverable

**Addresses:** BR-12.

**Goal:** Expose either the complete old endpoint configuration or the complete new configuration, never a mix assembled through many preference commits.

#### Work

1. Serialize the portable, non-secret endpoint definition state into one versioned record or generation.
2. Write the desired generation completely, verify it can be decoded, then atomically switch one active-generation pointer. Migrate existing per-field preferences without changing credential storage.
3. Store favorites and rejected voices in the same generation when feasible. If different preference files must remain, add a participant-private journal and make recovery deterministic.
4. Keep credentials outside the portable generation. Deleting or replacing a portable definition must not delete its locally stored secret.
5. Make rollback switch to the staged original generation rather than replaying a loop of setter calls.

#### Tests

- Process death after each write exposes a complete old or new generation.
- Replace, Merge, apply twice, and rollback are idempotent.
- Existing credentials survive endpoint deletion, replacement, ID overlap, and rollback.
- Favorites never refer to an endpoint omitted by the active definition generation unless that is already an explicitly supported state.

#### Acceptance gate

A reader running after any injected interruption must decode one complete endpoint-state generation without observing partially deleted endpoint fields.

### Phase 12.8 — Make backup capture and package verification truthful

**Addresses:** BR-07, BR-13, BR-14, BR-15, BR-16, BR-17, and BR-18.

**Goal:** Publish a recovery package only after proving coherent capture, semantic readability, dependency closure, and complete destination publication.

#### Work

1. Add a common backup/restore operation gate. Automatic and manual backup may not snapshot while a restore/repair is mutating stores.
2. Add mutation-generation tokens for authoritative stores that do not already have them. Read all tokens before capture and after all artifacts are staged. If any token changed, discard the staged package and retry a bounded number of times; then fail visibly while preserving the previous automatic backup.
3. Do not hold a broad database/UI lock across slow ZIP creation or encryption. Use short consistent snapshots plus generation validation.
4. Add a read-only `PortableRecoverySemanticValidator` used by both backup finalization and restore preflight. It must call the same category codecs/preparers used for restore and validate:
   - logical parsing and supported schema versions;
   - declared category representation;
   - record counts or explicit empty states;
   - profile and generated asset hashes and reference closure;
   - identity-to-lorebook dependency rules;
   - chat folder/message/image consistency;
   - model endpoint logical validity;
   - category-specific size limits compatible with the actual reader.
5. Revise the next manifest format to describe each category explicitly as present-with-artifact(s) or explicitly empty, with enough counts to reject contradictory inventory. Continue accepting valid v2 packages through a conservative compatibility adapter; do not infer arbitrary missing v2 artifacts as empty.
6. Update SQLCipher metadata to match the packaged dependency and add a build-time test that prevents drift. Treat it as descriptive unless a real format incompatibility rule is defined.
7. Publish destination documents through an incomplete name/state and rename only after byte-for-byte or digest verification. If a document provider cannot supply the required finalization semantics, fail visibly and leave only an unmistakably incomplete artifact rather than a final-looking `.sgbak`.
8. Set per-category decoded-size limits based on measured safe parser behavior. Prefer streaming parsing where practical. Ensure an over-limit package is rejected before the outer mutation journal begins.
9. Keep the existing previous automatic backup until the new destination has passed package decode and semantic validation.

#### Tests

- Deterministic mutations between each pair of category captures cause retry or refusal, never a successful incoherent package.
- A package with recomputed outer hashes but malformed chat, memory, lorebook, identity, generated-image, profile-image, or endpoint logical data is rejected by the semantic validator.
- Every declared category has exactly the allowed artifact/empty representation; missing, duplicate, and contradictory declarations are rejected.
- Valid v2 fixtures remain restorable, while ambiguous v2 category declarations fail closed.
- The SQLCipher manifest value cannot drift from the build dependency unnoticed.
- Process death during destination copy leaves no final-recognized backup.
- Automatic-backup failure preserves the previously verified backup.
- Boundary-size and over-limit artifacts reject predictably without mutation or process termination.

#### Acceptance gate

The writer's final success signal and the restore preflight must use the same semantic validator. A package reported successful by the writer must pass restore preflight without relying on any source-installation files.

### Phase 12.9 — Automated recovery matrix

**Addresses:** all required findings.

**Goal:** Replace the parts of the former Phase 12 that assumed phone access with repeatable repository and CI evidence.

#### Required test layers

1. **Pure/JVM tests** for merge/replace planners, immutable final-state dependency closure, manifest inventory, codecs, reporting, and state-machine transitions.
2. **SQLite/SQLCipher integration tests** for the shared Memory transaction, tombstones, foreign keys, exact logical rollback, and direct database key/file recovery.
3. **Android instrumentation tests** for SAF/package I/O, service/activity recreation, restart behavior, SharedPreferences endpoint generations, and real image files.
4. **Fault-injection tests** at every participant start/complete marker and at every private database/model journal phase.
5. **Compatibility fixtures** for the oldest supported portable package, the current format, empty categories, maximum practical datasets, and packages containing all twelve categories.

#### Minimum scenario matrix

Run every applicable category through:

- Merge and Replace;
- empty current state, overlapping state, and unrelated current state;
- success, immediate failure, rollback failure followed by startup recovery, and process death;
- category selected alone and with each category sharing a dependency or physical store;
- repeated application of the same package;
- current-format and supported older-format package.

The cross-category combinations that must always run are:

| Combination | Required proof |
|---|---|
| Chats + Generated Images | Actual message references determine assets; copied images survive. |
| Identities + Profile Images | Assigned images exist after apply and rollback. |
| Identities + Lorebooks | Links resolve against the planned final lorebook set. |
| Identities + Memories | One shared database transaction preserves all relationships. |
| All twelve categories | Fresh-install recovery reaches full reference closure and restarts cleanly. |
| Profile Images only | All images used by unselected identity categories are protected. |
| Generated Images only | All images used by unselected chats are protected. |

#### CI gate

The phase is complete only when Android Checks passes from a clean checkout and the required instrumentation/fault suite has an archived result. Tests must assert final logical content, reference closure, durable reports, and journal cleanup; asserting only a success enum is insufficient.

### Phase 12.10 — Owner-run device acceptance

**Goal:** Provide the final real-device evidence without assuming that an AI agent can control or inspect the owner's phone.

The implementation agent prepares a concise checklist and a synthetic fixture/package. The owner runs it and records pass/fail. No private owner data is required for the test fixture.

#### Setup

Create synthetic data containing:

- at least two chats and folders;
- one generated image copied/referenced across chats, plus one gallery-only image;
- default user image, companion, Glamour, user-played roleplay character, and non-user-played roleplay character with shared and distinct images;
- linked and unlinked lorebooks;
- memories/entities/projects with links and tombstones;
- two non-secret model endpoints and favorites;
- enough data to distinguish Merge from Replace.

#### Owner actions

1. Create a manual portable backup and confirm success.
2. Copy it off-device or to a second provider so uninstall/reinstall cannot remove it.
3. Change or delete every category, including moving a generated-image reference to a different chat.
4. Run a selected-category Merge and verify the report and expected preserved data.
5. Run a selected-category Replace and verify unselected categories plus required dependencies remain valid.
6. Open a chat before starting a full restore, complete the restore, and verify the app restarts before the chat can be edited again.
7. Reopen the app and verify all twelve categories, image bytes, identity/lorebook links, and memory relationships.
8. Reapply the same backup and confirm idempotent results.
9. Verify the previous automatic backup remains available after a deliberately induced new-backup failure, using a safe test condition supported by the implementation.

#### Evidence to record

- app version and commit;
- Android version/device model;
- package format version;
- pass/fail for each step;
- the structured restore report;
- any visible recovery-required state.

Phase 12 is complete when the automated gate passes and the owner records a pass for this checklist. The owner remains the only person operating the physical device.

## 5. Implementation safety rules

These rules apply to every repair phase:

1. Do not “fix” rollback by merely changing participant order. Apply and rollback must both use explicit desired/original contexts.
2. Do not convert dependency-read errors into empty lists or warning-only results when deletion is possible.
3. Do not add a catch-all that reports success after skipping a row, asset, or relationship.
4. Do not provision a missing database during preflight or rollback discovery.
5. Do not clear a journal or quarantine because an API call returned; clear it only after normal readers verify the committed state.
6. Do not use origin metadata to infer current references.
7. Do not test recovery only through mocks. At least one SQLCipher/real-filesystem test must cover each atomicity boundary.
8. Do not broaden the portable format to include credentials or other secrets.
9. Keep all new staging and journals inside app-private storage and apply the existing restrictive file permissions.
10. Preserve current approved product behavior and wording unless the owner explicitly approves a change.

## 6. Completion definition

The repair program is complete only when all of the following are true:

- every P0 and P1 finding has a regression test and an implemented repair;
- backup finalization and restore preflight share semantic validation;
- cross-category dependencies are computed from one immutable final-state plan;
- `companion_memory.db` has one physical restore participant;
- image reference closure is proven after successful restore;
- direct database restore survives every injected key/file crash boundary;
- unified success always leads through a clean restart before restored stores can be written;
- all supported formats have explicit category inventory semantics;
- CI and fault-injection results are green from a clean checkout;
- the owner-run device checklist passes.

## 7. Optional user-facing additions

These are useful but are not required to close the defects above. They should be considered only after the required phases pass.

1. **Verify an existing backup:** let the user select a `.sgbak` and run authentication, logical parsing, dependency closure, and compatibility checks without restoring it.
2. **Backup contents report:** show format version, creation time, category counts, encryption status, and semantic verification result before restore.
3. **Scheduled restore drill reminder:** periodically invite the user to verify a recent backup without changing live data.
4. **Exportable recovery report:** save a redacted report containing category outcomes and verification results, excluding chat text, secrets, and private content.
5. **Storage-provider health hint:** identify providers that cannot finalize a document atomically and recommend a safer destination, using owner-approved wording.

## Appendix A — Primary implementation seams

This is a navigation aid, not permission to limit the review to these files. Before changing a seam, search all callers and corresponding tests.

| Area | Primary production files | Existing tests to extend first |
|---|---|---|
| Unified planning and transaction order | `preferences/backup/portable/UnifiedPortableRestore.kt`, `SelectedCategoryRestoreTransaction.kt`, `PortableRestoreRecoveryFlow.kt` | portable coordinator/transaction/recovery tests under `app/src/test` and `app/src/androidTest` |
| Chat logical state and generated-image references | `ChatLogicalSerializer.kt`, `ChatMergePlanner.kt`, `ChatRestoreParticipant.kt`, `GeneratedImageRestoreParticipant.kt`, `GeneratedImageCategoryPlanner.kt` | `GeneratedImageRestoreParticipantTest.kt` plus chat serializer/planner tests |
| Generated-image files/catalog | `GeneratedImagePortableBackup.kt`, `GeneratedImageRestoreTransaction.kt` | generated-image backup and transaction tests |
| Profile image dependencies | `ProfileImageUsage.kt`, `ProfileImageRestoreParticipant.kt`, `ProfileImagePortableBackup.kt`, `ProfileImagePortableRestoreManager.kt` | profile-image backup/restore manager and participant tests |
| Identity export and restore | `CompanionBackupExporter.kt`, `CompanionRestorePlanner.kt`, `CompanionRoleplayRestoreManager.kt`, `CompanionCategoryRestoreParticipant.kt` | `CompanionRestorePlannerTest.kt`, `CompanionCategoryRestoreParticipantTest.kt`, companion backup/restore tests |
| Shared Memory database | `MemoryPortableRows.kt`, `MemoryRowsRestoreParticipant.kt`, `MemoryCategoryPlanner.kt`, `MemoryStore.kt` | memory row-format, planner, participant, and SQLCipher instrumentation tests |
| Lorebooks | `LorebookPortableData.kt`, `LorebookRestoreParticipant.kt` | lorebook portable codec and participant tests |
| Model endpoints | `ModelEndpointPortableRestore.kt`, `ModelEndpointRestoreParticipant.kt`, endpoint preference storage | model endpoint codec/planner/participant tests and new interruption tests |
| Package format and semantics | `PortablePackage.kt`, `PortablePackageFormat.kt`, `PortableRecoveryWriter.kt` | `PortablePackageTest.kt`, `PortablePackageAdversarialTest.kt`, writer tests |
| Automatic/manual publication | `AutomaticPortableBackupWriter.kt`, `AutoBackupController.kt`, `AutoBackupWorker.kt`, `ReadableDataBackup.kt`, `MemoryBackupRestoreActivity.kt` | automatic backup, worker, readable-backup, and SAF instrumentation tests |
| Unified lifecycle and restart | `MemoryBackupRestoreActivity.kt`, `RestoreForegroundService.kt`, `ChatRestoreManager.kt`, `MainApplication.kt` | activity/service lifecycle, recovery-flow, and restart instrumentation tests |
| Direct database restore | `DatabaseRestoreManager.kt`, `DatabaseRepairManager.kt`, `RecoveryBackupManager.kt`, database health/recovery flows | database repair/restore unit tests plus real SQLCipher crash-boundary instrumentation |

All production paths above are relative to `app/src/main/java/org/teslasoft/assistant/`. Test paths should mirror their current package layout rather than creating a parallel test architecture.
