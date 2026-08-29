# Drawer and Image Gallery Phased Implementation Plan

**Status:** Execution plan derived from the owner-approved specifications on `agent/update-drawer-plan`.

## Authority and execution contract

`drawer-design-spec.md` and `image-gallery-spec.md` are the source of truth for product behavior, hierarchy, exact visible wording, action order, icons, accessibility names, defaults, and acceptance behavior. This plan provides repository-specific sequencing and architecture; it does not supersede or paraphrase those specifications. If this plan conflicts with either specification, follow the specification.

This plan was mapped against:

- specification branch commit `1e48b8dcd57331d01ae7f873ad38fa135f8f886d`;
- current `main` commit `21e1a362778ec88b232ecd13d2213a5deb075e39` (August 29, 2026).

The specification branch intentionally contains an older application snapshot. Each coding session must start from the latest integration commit containing all prior phases, re-read both specifications and `CLAUDE.md`, and re-inspect the named call sites before editing. Do not implement a phase against the stale application files on the specification branch, and do not copy line numbers from this plan. Preserve the specification files unchanged unless the owner separately changes the product decision.

Every phase below is intended to be one independently reviewable coding session/merge. A phase is complete only when its acceptance criteria and checks pass. An intermediate merge may leave a new destination dormant or retain legacy navigation, but it must never introduce a destructive bypass or expose an incomplete destructive action.

## Current repository map

| Concern | Current implementation and constraint |
| --- | --- |
| Launch/navigation | `MainActivity` hosts `ChatsListFragment` and `PlaygroundFragment` through `activity_main.xml` and `bottom_menu.xml`. It normally opens the Chats tab. Keep this as a fallback until Phase 7. |
| Live chat | `ChatActivity` is one activity per chat. `activity_chat.xml` already has the approved double-left-chevron asset through `btn_back`, but it currently finishes the activity. `ChatActivity` owns fragile streaming, voice/hands-free, TTS, composer, Includes, attachment, IME, and configuration-change state. The drawer must overlay this activity rather than rebuild or swap the live chat. |
| Chat index/storage | `ChatPreferences` stores the encrypted `chat_list` metadata JSON and per-chat encrypted history/settings. `getChatListResult(context, includeFirstMessage = false)` is the existing lightweight authoritative read. `storedChatId` preserves legacy ID fallback. All list mutations serialize through `CHAT_LIST_LOCK`; storage health states must never be treated as an empty list. |
| New chats | `AddChatDialogFragment` currently creates a saved placeholder chat immediately, copies settings/defaults, and opens `ChatActivity`. `NewChatProviderRestore` and `NewChatSettingCopyTest` protect provider/settings behavior. Phase 5 must extract and reuse this initialization rather than create a parallel settings lifecycle. |
| Playground | `PlaygroundFragment` currently owns its input/output UI and request construction directly. Its request/provider/tokenize/report semantics must be reused, not reimplemented differently, when hosted as a blank-conversation mode. |
| Generated images | `ImageGenerationJobRegistry` writes completed bytes to `getExternalFilesDir("images")`, constructs `GeneratedImageMetadata`, and delivers the result either to `ChatActivity` or directly to stored history. It currently has no independent gallery catalog. `GeneratedImageFiles.deleteIfUnreferenced` scans authoritative histories and is deliberately conservative. |
| Existing image UI | `ImageBrowserActivity` already provides zooming and `ACTION_CREATE_DOCUMENT`. `ProfileImagesActivity`, `ProfileImageGalleryAdapter`, `ProfileImageFramingActivity`, and `ProfileImageStore` provide the established gallery selection, Show Labels, framing, atomic-file, and catalog patterns to reuse. |
| Existing deletion | `ChatDeleteDialog` is currently a two-action wrapper. Reachable delete paths also exist in `ChatActivity`, `ChatsListFragment`, `ChatListAdapter`/edit flows, swipe, and bulk selection, with direct `ChatPreferences.deleteChat*` calls. They must move together in Phase 3. |
| Shared UI | `ui-style-guide.md` defines shared headers, buttons, rows, dropdowns, and two-action dialogs. There is no shared compact management-popup pattern, shared folder name-entry dialog, or readable three-action dialog yet. Add each once when first needed and record adoption in `ui-style-adoption.md`. Do not extend the parked AMOLED work. |
| Verification | `.github/workflows/android-checks.yml` runs `./gradlew --no-daemon test`, `assembleDebug`, and `assembleDebugAndroidTest`. Instrumentation execution requires an arm64 device; CI compiles those tests but does not run them. |

## Merge order

1. Durable generated-image catalog and safe asset lifecycle.
2. Lightweight chat/folder organization metadata.
3. Unified image-safe chat and folder deletion.
4. Image Gallery and shared compact management UI.
5. Blank-conversation Chat/Playground mode and first-commit lifecycle.
6. Full-width drawer, folders, flat chat rows, and Search.
7. Launcher/navigation cutover, recovery, and end-to-end hardening.

## Changes that must not be split across merges

- Do not register completed images in a catalog without also making message deletion/catalog cleanup preserve active catalog assets. Otherwise deleting a message can silently destroy an image the new catalog promises to retain. Phase 1 owns both changes.
- Do not expose Gallery Delete, bulk delete, chat `Delete All`, or folder image deletion without the same-merge lock re-read and missing-image chat renderer. A stale request must never bypass Lock, and an intentionally removed shared file must never crash or erase historical messages.
- Do not migrate only some currently reachable chat-deletion entry points. The setting, decision policy, three-action dialog, ownership query, lock veto, legacy swipe/bulk/current-chat wiring, and deterministic storage coordinator ship together in Phase 3. Until then, no new `Delete All` action is exposed.
- Do not split nonempty folder deletion between removing the folder record and removing its member chat-list rows. The visible hierarchy mutation must be one committed metadata operation; the deletion journal may finish safe cleanup afterward. If the aggregate image-choice step is cancelled, no mutation may have begun.
- Do not hide the Chat/Playground selector or start a network request before the first user turn and chosen mode have crossed the same durable commit boundary. Typed, voice/hands-free, `/imagine`, and Playground Run entry paths must share that rule.
- Do not change ordinary launch to a blank chat or remove the old bottom navigation until the drawer can reach Image Gallery, folders/chats, Search, Settings, and New Chat, and saved Playground-mode conversations can reopen correctly. Phase 7 is the activation gate.

---

## Phase 1 — Durable generated-image catalog and safe asset lifecycle

### Goal

Make every successfully completed generated image a durable, uniquely indexed asset with conservative legacy backfill, while ensuring existing message cleanup cannot destroy a catalog-owned image and missing files render safely in chat. This is the non-UI foundation for all gallery and image-aware deletion work.

### Dependencies/prerequisites

- No earlier phase.
- Re-read `image-gallery-spec.md` Sections 1, 6-8, 13, and 15 and the image-generation lifecycle rules in `image-generation-rebuild-plan.md`.
- Confirm the current completion paths in `ImageGenerationJobRegistry` and `ChatActivity`; both attached-screen and detached-screen completion must converge on the same registration operation.

### Files/components likely involved

- `app/src/main/java/org/teslasoft/assistant/imagegen/GeneratedImageMetadata.kt`
- `app/src/main/java/org/teslasoft/assistant/imagegen/ImageGenerationJobRegistry.kt`
- `app/src/main/java/org/teslasoft/assistant/imagegen/GeneratedImageFiles.kt`
- `app/src/main/java/org/teslasoft/assistant/util/GeneratedImageStorage.kt`
- `app/src/main/java/org/teslasoft/assistant/util/AtomicFileWriter.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/ChatPreferences.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/adapters/chat/ChatAdapter.kt`
- New package such as `preferences/generatedimages/` containing `GeneratedImageCatalogRecord`, `GeneratedImageCatalogDb`, `GeneratedImageCatalogStore`, `GeneratedImageAssetResolver`, and `GeneratedImageCatalogBackfill`
- `SecurePrefs`, `DatabaseKeys`, `CorruptionErrorHandlers`, `SnapshotRegistry`, and `MainApplication` as required by the selected repository storage-health pattern
- Image-generation/catalog unit and instrumentation tests

### Implementation instructions

1. Add a versioned catalog behind a narrow `GeneratedImageCatalogStore` API. Use an independent SQLCipher database/key following the existing `MemoryStore`/`DatabaseKeys` health conventions because the required origin/last-known chat name is user data. Do not put full prompts, message bodies, or image bytes in the index. Treat locked, corrupt, or unavailable catalog storage as an explicit failure state, never as an empty gallery.
2. Store the fields required by `image-gallery-spec.md` Section 8. Make `imageId` the primary identity, index `fileHash`, `createdAt`, and `originChatId`, and allow origin/message identity to be null when legacy provenance is genuinely unknown. Enforce idempotent upsert by `imageId`. For legacy records without a usable ID, deduplicate conservatively by the strongest available tuple (file identity plus stored creation/provenance), not by whichever chat is currently open. Two separate generations that happen to produce identical bytes must not be collapsed merely because the content hash matches. The store may retain an internal non-active deletion tombstone/journal keyed by `imageId` so chat rendering can distinguish an explicitly deleted catalog image from never-cataloged legacy metadata; tombstones are not active gallery items.
3. Stop treating the content hash as the sole physical identity for new outputs. Give each new stable `imageId` its own canonical catalog file path (while retaining `fileHash` for integrity/legacy lookup), or provide an equivalent reference-counted asset layer with the same per-`imageId` deletion semantics. Add one `GeneratedImageAssetResolver` used by chat, gallery, viewer, and deletion: resolve catalog-managed metadata by `imageId`/catalog path first and use the existing hash-named path only as a legacy fallback. This prevents two independently generated but byte-identical images from becoming destructively coupled, while copied messages carrying the same `imageId` still share one item.
4. Change successful generation finalization into one operation with this order: allocate the stable `imageId`; validate/decode metadata; atomically write a temporary file and rename it into the generated-images directory using the existing atomic-file utility and canonical identity above; synchronously insert/upsert the catalog record; only then publish `Terminal.Complete` to the visible or detached chat path. Capture stable origin chat ID/name when the job starts, not from mutable current-screen state when it finishes. Failed, cancelled, or catalog-failed jobs never create an active record. If catalog insertion fails, remove only a newly created unshared file and surface a terminal failure; never delete a pre-existing legacy/shared file another record/reference may use.
5. Keep `GeneratedImageMetadata` on the chat message. The catalog supplements it; it does not replace it. Use the generated `imageId` consistently across the terminal result, catalog record, resolver, and message metadata. Preserve the current one-terminal-state and screen-detach behavior.
6. Add a catalog rename hook to the successful stable-ID chat rename transaction. Update `origin/last-known chat name` only for records whose `originChatId` matches the renamed chat. A copied/reference-only chat must not change ownership or labels. A failed chat rename must not update catalog labels.
7. Make `GeneratedImageFiles.deleteIfUnreferenced` remain conservative and additionally refuse to remove a file represented by any active catalog item. It may continue cleaning truly unregistered legacy or failed-job orphans. Do not weaken its authoritative-history checks and do not turn it into explicit Gallery Delete.
8. Render the specification's missing-image state in `ChatAdapter` when the shared resolver reports an explicit-deletion tombstone or when complete generated-image metadata resolves to absent bytes. An active catalog record is authoritative for catalog-managed `imageId`; a tombstoned ID must not fall back to still-present shared/legacy bytes. Use hash-path fallback only when no catalog/tombstone knowledge exists for genuine legacy data. Reuse the existing missing/cancel icon resource only if it matches the specified X-circle treatment; otherwise add the correct Material asset. Preserve the message and metadata and expose the exact accessibility state from the specification. This renderer must not reuse failure/retry UI.
9. Implement a resumable, idempotent legacy backfill off the main thread. Scan only authoritative chat histories, accept only complete metadata with an existing file, prefer stored `imageId`/`fileHash`/`createdAt`, default to unlocked, and record ownership only when the source chat can be established. Skip a locked/corrupt chat without guessing. Multiple legacy records may temporarily share a hash-named physical file, but remain distinct when their stable IDs prove they were distinct generations. Record migration progress/version so startup or first-gallery access can resume after interruption without duplicating items. Do not block normal chat startup on a full-history scan.
10. Add recovery/reconciliation for `catalog row -> missing file`, tombstone/shared-file reference counts, and `uncommitted temp file` states. Reconciliation may remove an invalid active gallery row or finish a journaled operation, but must not delete a locked valid asset or manufacture ownership.

### Explicit non-goals

- No Image Gallery activity, grid, controls, viewer changes, lock UI, or gallery delete action.
- No chat-delete setting or `Delete All` choice.
- No change to provider adapters, prompt behavior, image summaries, or user-uploaded attachments.
- No broad backup/export redesign and no copying full generated-image bytes into chat backups as incidental work.

### Acceptance criteria

- A completed attached-screen or detached-screen generation produces one durable file, one catalog record, and one message metadata record sharing the same stable `imageId`.
- Failed, cancelled, file-write-failed, or catalog-write-failed generation produces no active gallery record or phantom completion.
- Copying a chat/message and rerunning backfill does not duplicate the catalog item or transfer ownership.
- Renaming the origin chat updates its catalog label; deleting or renaming a referencing copy does not.
- Deleting a message cannot remove an active catalog asset; unregistered orphan cleanup remains fail-safe.
- Independently generated byte-identical images remain independently manageable, while copied references with the same `imageId` remain one item.
- A missing referenced file produces the specified chat placeholder without deleting metadata or crashing.
- Legacy backfill is idempotent, skips absent files, leaves ambiguous ownership null, and never treats unreadable storage as empty.

### Tests/build checks

- Add focused tests such as `GeneratedImageCatalogIdentityTest`, `GeneratedImageCatalogBackfillTest`, `GeneratedImageRegistrationTest`, `GeneratedImageCatalogRenameTest`, and `GeneratedImageFilesCatalogProtectionTest`.
- Add an arm64 instrumentation suite for SQLCipher create/reopen/migration, interrupted registration/reconciliation, and lock/corruption health behavior; CI must at least compile it.
- Extend `GeneratedImageMetadataTest`, `GeneratedImageStorageTest`, and source/adapter contracts for the placeholder.
- Run focused JVM tests during development, then the phase gate: `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.
- Manually verify one attached completion, one completion after leaving the chat, a missing-file message, and a process restart during/after catalog insertion on an arm64 device.

---

## Phase 2 — Lightweight chat and folder organization metadata

### Goal

Create the stable, encrypted, lightweight navigation model used by the drawer, Search, and coordinated folder deletion without changing visible navigation yet.

### Dependencies/prerequisites

- Phase 1 merged.
- Re-read `drawer-design-spec.md` Sections 4 and 7 and its existing safety rules.
- Preserve the current stable chat-ID and storage-health work in `ChatPreferences`; legacy entries without `id` still use `storedChatId` without destructive eager rewriting.

### Files/components likely involved

- `app/src/main/java/org/teslasoft/assistant/preferences/ChatPreferences.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt` and/or `GlobalPreferences.kt`
- New `preferences/chatnavigation/` models and `ChatNavigationRepository`
- `ChatStorageHealth`, `SecurePrefs`, `SnapshotRegistry`, and existing preference test helpers
- New pure sort/name-policy/index-projection tests

### Implementation instructions

1. Define typed lightweight models for `FolderRecord`, `ChatNavigationItem`, and a complete `ChatNavigationSnapshot`. Include only stable identity and display/order metadata required by the specifications; never include full messages or previews.
2. Store folder records as versioned JSON in the same encrypted `chat_list` preferences file as the chat-list `data` key. Store nullable `folder_id` on each existing chat metadata map. This allows a future folder-plus-member-list removal to update the folder catalog and chat-list rows in one synchronous `SharedPreferences.Editor.commit()` while holding `CHAT_LIST_LOCK`. Existing rows with no `folder_id` are naturally unfiled.
3. Give each folder a UUID independent of its name. Centralize trimmed, blank, and case-insensitive duplicate validation in a pure `FolderNamePolicy`; UI dialogs in Phase 6 must call this policy rather than duplicate it. New folders are empty and unpinned. Never key membership by display name.
4. Add repository mutations for create, rename, folder pin/unpin, chat move/unfile, and chat pin/unpin. Hold `CHAT_LIST_LOCK`, perform authoritative reads, use synchronous commits for user-visible metadata mutation, and return typed success/failure results. Moving or pinning must not touch history, settings, timestamps, memory, Includes, or generated-image data.
5. Persist the top-level Folders expansion flag and per-folder expansion flags separately as presentation preferences keyed by stable folder ID. Removing a folder should clean its expansion key after the durable organization mutation. Missing expansion keys receive the specification defaults; never encode expansion in chat content.
6. Build the full hierarchy projection from `getChatListResult(context, includeFirstMessage = false)` plus folder records. Implement deterministic locale-aware/case-insensitive folder ordering, existing timestamp ordering for chats, pinned-chat de-duplication from folder/unfiled rows, and preservation of retained folder membership while pinned. Return the complete accessible set rather than an arbitrary page.
7. Preserve explicit storage health. Corrupt folder JSON must be backed up/reported using the repository's encrypted corruption pattern and must block folder mutation; it must not cause chat-list data to be overwritten or silently interpreted as “no folders.” Provide an idempotent schema migration/version marker, but do not guess assignments.
8. Provide a batch metadata primitive for Phase 3 that can remove a supplied set of chat rows and, optionally, one validated folder record in a single `chat_list` preferences commit. The primitive must verify the folder's current membership under the lock instead of trusting a stale UI list.

### Explicit non-goals

- No drawer, folder accordion, folder dialogs, Search screen, or flat row UI.
- No chat history scan, preview/snippet derivation, memory processing, or companion-image decoding.
- No Projects, nested folders, folder-specific AI settings, or altered chat semantics.
- No deletion confirmation UI or image deletion yet.

### Acceptance criteria

- Existing chats appear in a complete snapshot as unfiled with their existing stable IDs, order, and pin state.
- Empty folders persist; folder rename preserves ID and membership; one chat can have at most one folder ID.
- Folder and chat pin states remain independent, and a pinned folder never changes child chat pin state.
- A pinned assigned chat occurs only in the pinned projection and returns to its retained folder after unpin.
- Create/rename/move/pin operations neither read nor rewrite a conversation history nor change last-used timestamps.
- Folder and chat-list metadata can be removed together in one guarded commit, and a failed/non-authoritative read causes no write.

### Tests/build checks

- Add `FolderNamePolicyTest`, `ChatNavigationRepositoryTest`, `ChatNavigationProjectionTest`, `ChatFolderMigrationTest`, and storage-health/failed-commit tests using `FakeSharedPreferences` or Robolectric as appropriate.
- Cover pinned-first alphabetical folder ordering, ordinary chat ordering, no duplicate pinned rows, stable-ID rename, empty folders, unfiled legacy rows, stale folder membership, and corrupted folder JSON.
- Add a hot-path/source contract proving the snapshot calls `includeFirstMessage = false` and does not call `getChatByIdResult`.
- Run `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.

---

## Phase 3 — Unified image-safe chat and folder deletion

### Goal

Replace every currently reachable chat deletion path with one catalog-backed decision and execution coordinator, add the app-wide image choice setting and shared three-action dialog, and make lock/ownership enforcement impossible to bypass. This phase deliberately ships as one safety boundary.

### Dependencies/prerequisites

- Phases 1-2 merged.
- Re-read `image-gallery-spec.md` Sections 5.4, 6, and 9-12 and `drawer-design-spec.md` Section 4.8. Use the specification files directly for every message and action label.
- Before editing, run `rg` for every `deleteChat`, `deleteChatById`, swipe delete, bulk delete, and `ChatDeleteDialog` call site on the current integration commit.

### Files/components likely involved

- `app/src/main/java/org/teslasoft/assistant/ui/util/ChatDeleteDialog.kt`
- New `ChatDeletionPolicy`, `ChatDeletionCoordinator`, `ChatDeletionJournal`, and deletion result models
- `ChatPreferences.kt` and Phase 2 `ChatNavigationRepository`
- Phase 1 `GeneratedImageCatalogStore` plus a narrow explicit asset-deletion operation
- `ChatActivity.kt`, `ChatsListFragment.kt`, `ChatListAdapter.kt`, `EditChatTitleDialog.kt`, and any other current delete callers found by audit
- `ImageGenerationSettingsActivity.kt` and `activity_image_generation_settings.xml`
- New shared three-action dialog layout/style, strings, `ui-style-guide.md`, and `ui-style-adoption.md`

### Implementation instructions

1. Add the exact app-wide setting at the bottom of Image Generation settings using the existing shared toggle-row pattern. Default it safely for old installs and new installs. Its value changes the confirmation choices only; no low-level observer may auto-delete images when the toggle flips.
2. Separate pure policy from UI and storage. `ChatDeletionPolicy` should accept a stable target set, setting value, and catalog-derived ownership/lock counts and return the required dialog variant and allowed decisions. `ChatDeletionCoordinator` should perform authoritative preflight, request the UI decision, and execute it. `ChatPreferences` and catalog/file stores remain deterministic and show no dialogs.
3. Query owned images by `originChatId` from the catalog. Do not scan chat messages to infer ownership and do not include images merely referenced by the target chats. Unknown-origin legacy records are never claimed for destructive `Delete All`.
4. Extend the shared dialog system with a readable adaptive/stacked three-action variant that preserves the specification's exact cancel-first action order. Update `ChatDeleteDialog` to render ordinary, keep-images, and three-choice states from the policy. Document the shared variant in `ui-style-guide.md` and its consumers in `ui-style-adoption.md`.
5. Implement single-chat, multi-chat, and folder deletion targets. Resolve every confirmation, including the folder's initial warning and any aggregate image choice, before writing a deletion journal or changing metadata. Re-read target membership and image lock state immediately before commit.
6. For a folder target, use Phase 2's single metadata commit to remove the validated folder record and all current member chat-list rows together. For any target, write a recoverable journal first, remove the visible chat-list rows (and folder when applicable) synchronously, then perform per-chat encrypted history/settings/summarizer/Includes cleanup and the chosen image cleanup off the UI thread. A cleanup failure keeps the journal and logs/surfaces failure; it must not recreate a partially removed visible hierarchy. Extra retained bytes are the safe failure mode.
7. For `Delete Chat Only` or the setting-Off path, leave catalog records/assets intact and retain their last-known origin chat names. For `Delete All`, call an explicit catalog deletion operation that re-reads each row and Lock flag under the catalog transaction, deletes only unlocked records owned by the target IDs, and never calls or weakens `deleteIfUnreferenced`. Mark the deleted `imageId` non-active/tombstoned before or as part of the journaled operation so its historical messages resolve to the placeholder. Remove physical bytes only when no other active catalog identity shares that legacy/reference-counted file. If a file delete fails, keep/reconcile the operation and report incomplete cleanup without exposing a phantom active gallery item. If bytes disappear before row cleanup finishes, the Phase 1 resolver/placeholder and journal recovery keep the app coherent.
8. Route all reachable current paths through the coordinator in this same merge: current-chat overflow, legacy swipe, legacy bulk selection, edit-dialog delete, and any other UI call found by the audit. Remove or make internal/deprecated UI-facing direct deletion helpers so new callers cannot casually bypass policy. Add a source-contract test that permits direct low-level deletion only inside the coordinator/storage layer.
9. Add dynamic Pin/Unpin at the top of the current saved chat overflow using the existing `ChatPreferences`/Phase 2 pin state. Omit it for an unsaved chat. Keep all existing overflow actions and order beneath it as required by the specification.
10. Reconcile incomplete journals at safe startup/resume points. Recovery must re-check Lock before any image removal, must be idempotent, and must never interpret inaccessible catalog/chat storage as permission to delete.

### Explicit non-goals

- No Image Gallery screen or gallery selection UI.
- No drawer/folder management UI; only the batch/folder target and tests needed for the later surface.
- No change to explicit message deletion beyond the catalog protection already completed in Phase 1.
- No automatic deletion triggered merely by enabling the setting.

### Acceptance criteria

- The complete setting/owned-images/locked-images policy matrix matches `image-gallery-spec.md` Sections 9-10, including exact wording and action order.
- A chat with no owned images receives the ordinary confirmation; reference-only images do not create ownership warnings.
- Cancel performs no mutation. `Delete Chat Only` preserves every catalog asset. `Delete All` removes only currently unlocked assets originally owned by the target chats.
- Lock wins over stale single, multi, folder, and journaled deletion requests.
- Every currently reachable legacy and current delete UI uses the same coordinator; no swipe or bulk bypass remains.
- Folder metadata and all member chat rows disappear in one visible metadata commit only after all required choices; cancelling the aggregate image step leaves everything untouched.
- Interrupted cleanup is idempotently recoverable and fails toward retained images/data rather than unconfirmed destruction.
- Current-chat Pin/Unpin is saved-only, first in the overflow, and consistent with the lightweight index.

### Tests/build checks

- Add table-driven `ChatDeletionPolicyTest` for setting Off/On, zero/owned/reference-only images, some/all locked, single/multi/folder targets, and every decision.
- Add `ChatDeletionCoordinatorTest`, `ChatDeletionJournalTest`, `ImageLockDeletionVetoTest`, `FolderDeletionAtomicMetadataTest`, and `ChatDeletionCallSiteContractTest`.
- Add arm64 instrumentation coverage for real encrypted chat-list commits plus SQLCipher catalog lock re-check and interrupted journal recovery.
- Manually exercise every reachable delete path on the legacy screen, current overflow, and a test-created folder; verify another chat's shared reference becomes the specified placeholder only after explicit image deletion.
- Run `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.

---

## Phase 4 — Image Gallery and shared compact management UI

### Goal

Build the complete generated-image gallery on the catalog and deletion primitives, including presentation preferences, viewer/save flow, avatar derivation, lock management, and selection/bulk deletion. Register the destination but do not add a temporary non-spec navigation entry; the drawer connects it in Phase 6.

### Dependencies/prerequisites

- Phases 1-3 merged and their migration/journal recovery green.
- Re-read all of `image-gallery-spec.md`, especially Sections 3-6, 13-15.
- Inspect the current `ProfileImagesActivity`, `ProfileImageGalleryAdapter`, `ProfileImageFramingActivity`, `ProfileImageStore`, and `ImageBrowserActivity` immediately before implementation; reuse their current patterns rather than this plan's snapshot.

### Files/components likely involved

- New `ImageGalleryActivity`, `GeneratedImageGalleryAdapter`, gallery view-state/presentation-preferences classes, and layouts
- `AndroidManifest.xml`, `strings.xml`, styles, dimensions, and Material icon resources
- Phase 1 `GeneratedImageCatalogStore` and Phase 3 explicit image-deletion API
- `ImageBrowserActivity.kt` / `activity_imageview.xml`
- `ProfileImageFramingActivity.kt`, `ProfileImageStore.kt`, and framing-session utilities
- New shared `CompactActionPopup`/theme overlay
- `ui-style-guide.md` and `ui-style-adoption.md`

### Implementation instructions

1. Create a dedicated full-screen gallery activity using the shared action-bar family. Build its normal/selection top-bar and bottom action behavior from the current Profile Images management pattern. Add only the exact sort, columns, labels, Select, and deletion controls specified; read all strings/defaults/order directly from the specification.
2. Persist sort, 2/3/4 column choice, and Show Labels app-wide in a typed presentation-preferences class. Preserve them independently of activity state. Save/restore the `GridLayoutManager` position/anchor around viewer launches and recreation so returning does not jump to the top.
3. Query the lightweight catalog off the main thread and submit stable-ID rows to a `RecyclerView`/`GridLayoutManager`. Sort only by catalog `createdAt` with an `imageId` tie-breaker for deterministic equal timestamps. Use Glide or the repository's current image loader with bounded thumbnail requests; full-resolution decode belongs only in the viewer. Reset every image, label, lock, selection, enabled, alpha, listener, tint, and visibility field on bind/recycle.
4. Add one centrally styleable `CompactActionPopup`/theme pattern and use it for the gallery long-press menu. Document it in `ui-style-guide.md`; Phase 6 must reuse the same component for chat/folder menus. Do not create screen-local popup colors/backgrounds.
5. Implement short press by extending `ImageBrowserActivity` with a catalog-backed generated-image input. Preserve existing viewer actions. Add/use the specified lower-right Save Image action and existing `ACTION_CREATE_DOCUMENT` path without passing raw private paths to another app.
6. Implement `Go to Chat` from durable `originChatId`, verifying the chat still exists in the authoritative lightweight index. Pass `imageId`/origin message identity for best-effort positioning, but opening the correct chat is the minimum. Do not guess by name when the origin is gone.
7. Implement `Add to Avatar Gallery` as an internal app-private source session for `ProfileImageFramingActivity`. Resolve the selected catalog asset, copy/read it into the existing framing temporary workflow without a document picker, and persist only the framed derivative through `ProfileImageStore`. Never give the editor permission to overwrite the generated original. Reject stale/missing catalog assets safely.
8. Implement durable Lock/Unlock and a lock badge independent of labels. All selectability and menu availability are projections of the latest catalog state. Single and bulk delete must use Phase 3's explicit asset operation and re-read Lock at execution; they leave all messages/metadata untouched so Phase 1 renders placeholders.
9. Implement selection mode exactly from the specification: locked items remain visible but unselectable, count/action state stays live, confirmation occurs before deletion, and normal long-press behavior is suspended. Refresh the catalog after completion and exit selection mode without retaining stale IDs.
10. Register the activity in `AndroidManifest.xml`. Do not add it to Settings or legacy bottom navigation as a temporary shortcut; Phase 6 supplies the approved drawer placement.

### Explicit non-goals

- No drawer row or navigation restructuring.
- No change to generated-image providers, chat message content, profile-image deletion rules, or uploaded image handling.
- No five-column option, paging/Load More UI, folders/tags for images, or gallery search.
- No exact-message scrolling if it would require risky chat lifecycle changes; retain the durable identity for a later safe enhancement.

### Acceptance criteria

- Every control, default, menu order, dialog, label placement, Lock rule, and accessibility name matches `image-gallery-spec.md` Sections 3-6 and 14.
- Gallery startup queries only the catalog; it does not parse all histories or decode full-resolution images.
- Presentation settings and the prior visible position survive viewer round trips and recreation.
- Duplicated references display one item; missing active files are reconciled out of the grid.
- Go to Chat uses stable origin identity and is unavailable after origin deletion.
- Avatar framing creates a separate normal profile-image asset and leaves the generated source byte-for-byte unchanged.
- Single and bulk deletion re-read Lock, remove only eligible assets/catalog items, and preserve historical messages.
- Fast recycling cannot leak thumbnails, labels, badges, selection, or enabled state.

### Tests/build checks

- Add `ImageGalleryPresentationPreferencesTest`, `ImageGallerySortTest`, `ImageGallerySelectionTest`, `ImageGalleryActionPolicyTest`, and adapter bind/reset contracts.
- Add Robolectric/activity tests for normal-selection transitions, persisted controls, viewer result/scroll restoration, locked stale selection, and unavailable origin chat.
- Add framing integration tests proving source immutability and ordinary `ProfileImageStore` output.
- Manually test large collections at 2/3/4 columns, labels on/off, fast scroll, rotation/recreation, viewer save, avatar framing, locking, and stale deletion.
- Run `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.

---

## Phase 5 — Blank-conversation Chat/Playground mode and first-commit lifecycle

### Goal

Introduce one reusable pending/new-conversation lifecycle that defaults to Chat, supports the specification's pre-send Chat/Playground selector without losing composer state, and saves the chosen mode only when the first user action is durably committed. Existing launcher/navigation remains in place until Phase 7.

### Dependencies/prerequisites

- Phases 1-4 merged.
- Re-read `drawer-design-spec.md` Sections 3, 8, and 13 plus the gallery specification's saved/unsaved overflow rules.
- Inspect the latest `AddChatDialogFragment`, `ChatActivity` typed/voice/image send paths, `NewChatProviderRestore`, `Preferences`, and `PlaygroundFragment`. Preserve recent send-preparation, Includes, voice, and provider-restore fixes.

### Files/components likely involved

- New `NewConversationCoordinator`, `PendingConversationState`, `ConversationMode`, and first-commit transaction/journal
- `AddChatDialogFragment.kt`, `ChatPreferences.kt`, `Preferences.kt`, and `NewChatProviderRestore.kt`
- `ChatActivity.kt` and `activity_chat.xml`
- `PlaygroundFragment.kt` plus an extracted reusable request/controller/state component
- New shared segmented-selector view/style/resources
- `ui-style-guide.md` and `ui-style-adoption.md`
- Existing new-chat/provider/send-ordering tests plus new lifecycle tests

### Implementation instructions

1. Extract the current new-chat initialization (stable ID allocation, default setting copy, provider restore, persona/activation/lore defaults, placeholder naming) from `AddChatDialogFragment` into `NewConversationCoordinator`. Both the legacy Add Chat entry and later drawer/launcher entries must call the same coordinator. Do not introduce a second provider/settings initialization path.
2. Represent a blank conversation with a stable provisional UUID and the existing per-chat `settings.<id>` / `chat_<id>` stores, but do not insert a saved row into `chat_list` until the first user turn commits. Persist only enough pending-session identity/mode/panel state to survive normal activity recreation/process restoration, and clean abandoned provisional stores through an explicit idempotent cleanup path. Opening/closing navigation must never create another pending ID by itself.
3. Add a versioned `ConversationMode` metadata field. Missing mode on every legacy saved chat means Chat. A provisional new conversation always starts Chat regardless of previous global or last-chat mode. Once saved, the selected mode is durable and controls how that chat reopens.
4. Add the shared accessible segmented selector beneath the existing chat header and above the content host, following `drawer-design-spec.md` Section 3 exactly. Centralize styles/dimensions/animation and document the pattern. Animation is visual only; selection state changes immediately.
5. Keep the Chat panel and Playground panel in the same `ChatActivity`/`FragmentActivity` host. Add/hide existing view/fragment instances rather than destroying/recreating them on each pre-send switch, so draft text, pending attachments, Includes, model/provider, composer expansion, Playground input/output, and panel state remain intact. Do not add another IME/inset owner to `ChatActivity`.
6. Extract Playground request construction/execution from `PlaygroundFragment` into a reusable controller/runner used by both the legacy host and the new panel. Preserve its current provider/model, system message, logit-bias, tokenize, stop, report, streaming-output, and error semantics. Add only the callbacks/state persistence needed to participate in the pending-conversation commit; do not fork a second request implementation.
7. Refactor the first-user-turn boundary so all entry paths use one `commitPendingConversation` result before clearing UI or starting network work. This includes normal prepared typed turns, voice/hands-free submissions, `/imagine`/tool-triggering user turns, and Playground Run. The transaction must synchronously validate storage health, persist the chosen mode and first user payload/state, and add the chat-list row under `CHAT_LIST_LOCK` with a journal/rollback strategy. Only `OK` hides the selector, clears committed draft/includes, updates timestamps, and dispatches the request. A pre-commit failure leaves the selector and user state intact and must not send or duplicate the turn.
8. Preserve the current prepared-turn capacity/capability checks: they still happen before commit where they currently protect a send. Ensure `saveSettings()` failure is no longer ignored for a pending first turn. Repeat/recovery must not append the same first message twice.
9. Omit saved-chat-only actions, including Pin/Unpin and drawer-row management, while provisional. After commit, refresh the activity's durable identity/state without recreating it or interrupting an already-started stream.

### Explicit non-goals

- No ordinary-launch cutover and no removal of the legacy Playground/Chats tabs.
- No drawer UI; Phase 6 wires New Chat to this coordinator.
- No new Playground tools, changed request payloads, chat-style Playground transcript, or provider behavior.
- No in-place swapping between two saved chats.

### Acceptance criteria

- Every genuinely new blank session defaults to Chat, shows the selector, and has no saved chat row or Pin/Unpin action.
- Switching modes preserves all state listed in `drawer-design-spec.md` Section 3.3 and survives recreation.
- Every first-user entry path hides/locks the selector only after durable commit and starts no request on pre-commit failure.
- The first commit creates exactly one saved chat with one stable ID and durable mode; retry/recovery creates no duplicate row or message.
- Saved legacy chats open as Chat without migration prompts. Saved Playground-mode chats reopen in Playground without showing the selector.
- Existing Chat and Playground request/provider semantics and new-chat setting-copy/provider-restore behavior remain green.

### Tests/build checks

- Add `PendingConversationCommitTest`, `ConversationModeMigrationTest`, `NewConversationCoordinatorTest`, `FirstTurnEntryPointContractTest`, and transaction/journal recovery tests.
- Extend `NewChatSettingCopyTest`, `NewChatProviderRestoreTest`, `ChatRequestOrderingSourceTest`, and relevant Includes/voice/image-generation tests.
- Add Robolectric tests for selector state, view preservation across Chat/Playground switching, recreation, pre-commit failure, saved-mode reopen, and unsaved overflow omission.
- Manually test typed text with attachments/Includes, voice/hands-free, `/imagine`, Playground Run/Stop, configuration changes, process restoration, and a forced encrypted-preferences commit failure.
- Run `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.

---

## Phase 6 — Full-width drawer, folders, flat chat rows, and Search

### Goal

Build the complete drawer/search navigation surface over the live `ChatActivity`, including all folder management and image-safe deletion integrations, while deliberately retaining the legacy `MainActivity` navigation as a fallback until the final activation phase.

### Dependencies/prerequisites

- Phases 1-5 merged.
- Re-read all of `drawer-design-spec.md` and the gallery specification's drawer/menu/deletion sections.
- Confirm Image Gallery, New Conversation, deletion, and navigation repositories are independently functional before adding their drawer entry points.

### Files/components likely involved

- `ChatActivity.kt` and `activity_chat.xml`
- New drawer layout(s), `DrawerHierarchyAdapter`, stable row models, and row view holders/layouts
- Phase 2 `ChatNavigationRepository` and Phase 3 `ChatDeletionCoordinator`
- Phase 4 `CompactActionPopup` and `ImageGalleryActivity`
- Phase 5 `NewConversationCoordinator`
- New `SearchActivity`/layout and a shared flat `ChatRowAdapter`/binder
- `ChatSettingsActivity.kt` / `activity_chat_settings.xml` for the companion-image toggle
- Shared folder name-entry dialog layout/controller, folder chooser, strings, icons, styles
- `AndroidManifest.xml`, `ui-style-guide.md`, and `ui-style-adoption.md`

### Implementation instructions

1. Wrap/host `activity_chat.xml` in a full-screen `DrawerLayout` or equivalent overlay whose drawer child explicitly measures to 100% of available width. Disable edge-drag/open gestures with the drawer lock/gesture API while retaining programmatic animation. Change the existing top double-left-chevron from `finishActivity()` to open; add the specified right double-chevron to close. System Back closes the drawer before other navigation. Do not recreate `ChatActivity`, reparent the composer, or add competing IME/inset listeners.
2. Implement the three fixed/scroll/fixed zones and exact hierarchy from `drawer-design-spec.md` Section 2. The middle is one virtualized RecyclerView. Use shared theme/inset roles and verify status/navigation bars without accepting the framework's default partial-width drawer margin.
3. Build one typed hierarchy projection and stable-ID adapter for Image Gallery, Folders, folder rows, pinned section/rows, folder child rows, and unfiled rows. Use DiffUtil or equivalent. Every bind must reset indentation, companion image, bookmark/overlay, selection/current marker, metadata visibility, chevrons, icon/tint, enabled state, and listeners.
4. Implement Folders and per-folder accordion persistence, pinned-first alphabetical folder ordering, empty folders, subtle one-level indentation, and independent chat/folder pinning from the Phase 2 repository. Pinning an assigned chat removes only its duplicate visual occurrence; it retains folder ID.
5. Reuse Phase 4's `CompactActionPopup` for the top-level Folders menu, folder menu, and saved-chat menu. Add one shared name-entry dialog/controller for Add/Rename Folder, backed by `FolderNamePolicy`, and document it in the shared style files. Read exact titles, field labels, actions, menu order, and validation behavior from the specification.
6. Implement Move to Folder with No Folder and the same folder ordering as the hierarchy. Choose popup versus shared scrollable selector based on current list size/available space, without clipping. Mutation is metadata-only and refreshes by stable ID.
7. Implement empty and nonempty folder deletion through Phase 3. The nonempty path presents the folder warning first, then one aggregate image decision if required; neither step writes early. Pass the stable folder target to the coordinator so it re-reads membership and performs the atomic folder/chat-list commit. Never loop per-chat dialogs.
8. Add a reusable flat chat-row binder for drawer and Search. Do not reuse the old `ChatListAdapter` card/preview layout. Preserve model-name and memory-status preferences; memory work remains asynchronous, off-main, enabled-only, and keyed to stable chat ID. Add the exact companion-image toggle in Chat Settings with default Off; when Off, inflate/reserve no image column. Reuse current `ProfileImageResolver`/binder behavior only for visible rows.
9. Add full-screen Search with the shared action bar, no visual header buttons, the existing search-box visual assets, and existing chat matching semantics. Search the complete lightweight snapshot independently of expansion/folder visibility; folder names are not new targets. Use the same flat row binder and stable saved-chat navigation.
10. Wire destinations: Image Gallery opens Phase 4; Settings opens the current Settings activity; New Chat calls Phase 5; Search opens the new screen. Selecting a saved chat uses the existing one-chat-per-`ChatActivity` intent/lifecycle rather than mutating the live activity in place. Re-check the current activity's stop/finish conventions so a navigation tap does not leak voice/TTS resources.
11. Refresh the snapshot when the drawer opens and when returning/resuming so auto-name, rename, timestamps, pin state, folder changes, origin label updates, current selection, and deletion are never stale. A failed/non-authoritative index read shows the established unavailable/error state and offers no destructive actions.
12. Keep `MainActivity`, `ChatsListFragment`, and the bottom navigation reachable in this phase. They are the rollback path until Phase 7 proves destination reachability and startup behavior.

### Explicit non-goals

- No ordinary-launch cutover or deletion of legacy navigation classes/resources.
- No edge swipe, partial-width drawer, hamburger, inline drawer search, Playground drawer row, Projects, nested folders, or chat preview snippets.
- No in-place live-chat switching and no folder-induced changes to memory, model/provider, Includes, image generation, Summarizer, or Compact behavior.
- No unrelated Settings transition/animation or AMOLED expansion.

### Acceptance criteria

- Drawer geometry, controls, fixed zones, exact hierarchy, and Back behavior match `drawer-design-spec.md` Sections 1-2; open/close preserves every listed live-chat state.
- Folder creation/rename/pin/expand/move/delete behavior, ordering, wording, and validation match Section 4, including aggregate image-safe deletion and cancellation atomicity.
- Pinned/current/folder/unfiled chat projections are complete, correctly ordered, never duplicated, and update after all relevant mutations.
- Drawer and Search use the approved flat row, no previews, correct optional metadata, companion-image preference, and recycling resets.
- Search sees all chats regardless of collapsed state and preserves current matching semantics.
- Image Gallery, New Chat, Search, Settings, and saved chats all open their correct destinations; Playground is absent from the drawer.
- Drawer construction does not open full histories, tokenize/summarize, decode full generated images, or block the UI on memory work.

### Tests/build checks

- Add `DrawerHierarchyProjectionTest`, `FolderInteractionPolicyTest`, `FolderDeleteFlowTest`, `FlatChatRowBindingTest`, `SearchProjectionTest`, and drawer destination/source contracts.
- Add Robolectric/activity tests for full-width measurement, disabled edge gesture, Back-first close, fixed-zone scrolling, current selection, state refresh, dialog validation, folder chooser, and adapter recycling.
- Add a lifecycle regression harness/manual checklist for streaming, TTS, mic/hands-free, draft, open IME, pending attachments, Includes, selected model/provider, and blank mode while opening/closing the drawer.
- Load-test hundreds of chats/folders with slow memory metadata and companion images both Off and On; assert no full-history reads on the drawer/Search hot path.
- Run `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`.

---

## Phase 7 — Launcher/navigation cutover, recovery, and end-to-end hardening

### Goal

Activate the new navigation as the ordinary app experience, retire the old lower navigation only after reachability is proven, and close cross-feature migration/recovery/performance gaps under realistic lifecycle and legacy data conditions.

### Dependencies/prerequisites

- Phases 1-6 merged and individually green.
- Create a reachability checklist from both specifications before removing any route.
- Re-inspect onboarding, startup storage-health/reconciliation, activity task flags, and current `MainActivity` Back/task behavior on the integration commit.

### Files/components likely involved

- `MainActivity.kt`, `activity_main.xml`, `bottom_menu.xml`, and `AndroidManifest.xml`
- Phase 5 `NewConversationCoordinator` and pending-session journal
- `ChatsListFragment`, `PlaygroundFragment`, and legacy resources only where navigation wiring can now be removed safely
- Startup hooks for generated catalog backfill, chat-deletion recovery, pending-conversation recovery, and folder metadata health
- Cross-feature unit, Robolectric, instrumentation, source-contract, and performance tests
- `ui-style-adoption.md` completion entries

### Implementation instructions

1. Turn `MainActivity` into the smallest safe startup/router layer that preserves current onboarding, permissions, storage-health, and recovery gates, then opens a Phase 5 blank conversation in Chat mode and finishes/does not display the old tab UI. Keep `MainActivity` as launcher unless a fresh inspection proves changing the exported launcher is safer; do not duplicate startup checks in `ChatActivity`.
2. Make ordinary cold/warm launch deterministic: it creates or restores the one appropriate blank provisional session, never reopens the last saved chat, never flashes the Chats tab, and never creates duplicate blank chat rows. Existing saved chats remain reachable only through the drawer/Search.
3. Remove the bottom-navigation wiring and permanent Playground/Chats destinations only after automated/manual reachability proves New Chat, saved chats, Search, Image Gallery, Settings, and blank Playground selection. Retain reusable `PlaygroundFragment`/controller code needed by Phase 5. Delete old adapters/resources only when `rg` proves they have no remaining compatibility/test caller; unreachable code cleanup must not reopen a deletion bypass.
4. Establish deterministic Back/task behavior across drawer, selection/bulk modes, viewer, Search, Settings, saved chats, and the blank startup chat. Drawer closes first. Do not accidentally reveal the retired `MainActivity` tab host beneath `ChatActivity`.
5. Run all idempotent recovery in a safe order before enabling destructive actions: storage outage reconciliation; generated catalog/open/backfill; deletion journals; pending first-commit journal; folder/index health. A locked/corrupt dependency disables the affected mutation and surfaces established error handling; it never becomes empty state.
6. Test and tune scale paths with realistic large datasets. Drawer/Search reads the complete lightweight index, Gallery reads only its catalog, adapters decode thumbnails only, and optional memory/companion work remains cancellable/stable-ID keyed. Add instrumentation/counters or source contracts rather than relying only on visual impressions.
7. Complete accessibility, localization, theme, and state-restoration review against every numbered acceptance item in both specifications. Verify Title Caps and exact owner-approved strings directly from the spec files. Update `ui-style-adoption.md` for the new shared compact popup, name-entry dialog, three-action dialog, segmented selector, drawer, Search, Gallery, and flat rows.
8. Preserve compatibility fallbacks: missing chat mode means Chat; missing folder assignment means unfiled; missing expansion/settings keys use specification defaults; legacy generated-image ownership remains conservative; last-known gallery labels survive origin deletion; old copied references never gain ownership. Do not add an eager destructive migration or delete legacy metadata merely because the new path is active.

### Explicit non-goals

- No new destinations, Projects, Companions/Characters relocation, gallery paging UI, new Playground features, or unrelated visual redesign.
- No removal of legacy data fields or compatibility readers without a separate measured deprecation plan.
- No redesign of voice, streaming, Summarizer/Compact, Includes, memory, provider, or token-accounting semantics.
- No expansion of parked AMOLED/custom-theme decisions.

### Acceptance criteria

- Ordinary launch immediately shows one blank Chat-mode conversation with the selector and no old-tab flash or last-chat reopen.
- Every approved destination remains reachable after bottom-navigation retirement; Playground exists only through the blank-mode selector.
- Back/task navigation cannot reveal or recreate the retired tab surface and still honors drawer-first close behavior.
- All legacy/default/migration cases above work without guessed ownership/folder assignment or destructive reset.
- Recovery after process death at image registration, chat/folder deletion, first-chat commit, and catalog backfill is idempotent and safe.
- The full acceptance lists in `drawer-design-spec.md` Section 14 and `image-gallery-spec.md` Section 15 pass, including live-chat preservation and large-list recycling/performance.

### Tests/build checks

- Add launcher/navigation reachability and task-stack source/Robolectric tests, plus cold/warm/process-restored startup tests.
- Add an end-to-end policy matrix covering legacy chats/images, copied references, renamed/deleted origins, pinned assigned chats, folders with mixed locked/unlocked ownership, and cancellation at every dialog boundary.
- Run instrumentation on an arm64 device for SQLCipher catalog migration, encrypted metadata commits, journal recovery, configuration/process restoration, and large RecyclerViews.
- Manually execute every numbered acceptance item from both specifications on light/dark themes and with accessibility services/large text where practical. Record failures against the specification item number, not a paraphrased checklist.
- Required final gate: `./gradlew --no-daemon test assembleDebug assembleDebugAndroidTest`, followed by a green `Android Checks` workflow on the merged integration commit.

## Final release rule

The feature is not product-complete merely because the drawer or gallery renders. It is complete only after Phase 7, when all specification acceptance items pass and no reachable deletion path can bypass ownership, explicit choice, Lock, storage-health, or missing-image safety.
