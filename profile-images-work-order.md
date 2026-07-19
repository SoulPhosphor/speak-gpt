PROFILE IMAGES — IMPLEMENTATION WORK ORDER

STATUS

Awaiting the owner's approval of this work order. After approval, this
file and profile-images-plan.md go to main, and each phase runs in a
FRESH session so the context window is never a problem.

This document is the HOW. `profile-images-plan.md` is the WHAT and is
authoritative wherever the two differ. Do not re-decide anything the
plan decides. Do not reopen owner rulings recorded in either document.

HOW TO RUN A PHASE (every session, every phase)

1. Read, in this order: CLAUDE.md, profile-images-plan.md, this file,
   then this phase's "Read first" list. Do not start editing before
   finishing the reading.
2. Verify the current state of main before trusting any file path or
   line reference — earlier phases and unrelated work will have moved
   things. The plan's behavior is mandatory; its line numbers are not.
3. Work on ONE branch per phase: `claude/profile-images-phase-<N>-<suffix>`.
   One phase per session. Do not start the next phase in the same
   session even if the current one finishes early.
4. There is no local Android SDK. CI is the compile gate: statically
   verify everything (every R.* reference resolves, imports exist, XML
   well-formed, signatures match), push, then confirm the Android
   Checks run for your commit is GREEN before reporting the phase done.
5. Report to the owner in user terms (the owner is not a coder): what
   they will see changed, what is not yet visible, and that CI is
   green. Never report device behavior as confirmed — only the owner
   can confirm on-device.
6. Merging to main: ask the owner in a plain chat message before
   merging (pushes to main publish a debug test build). Do not create
   a pull request unless the owner asks for one.

RULES THAT BIND EVERY PHASE

- Never use the AskUserQuestion pop-up tool. Ask in plain chat and
  wait.
- Never spawn a Fable-tier subagent. Haiku for mechanical work, Sonnet
  by default, Opus for the fragile parts. The "Model" line on each
  phase is the recommendation for the SESSION running it.
- No new Toast or Snackbar anywhere in this feature. Persistent inline
  text, dialogs, or visible empty/error states only.
- All user-visible wording comes from the plan VERBATIM and lives in
  res/values/strings.xml. If a needed string is not in the plan, STOP
  and ask the owner — do not invent user-facing words (owner approval
  gate).
- Styles: obey the plan's XML STYLE AND THEME INTEGRATION REQUIREMENTS
  in every phase that touches a layout. Every new activity calls
  ThemeManager.getThemeManager().applyPalette(this) in onCreate BEFORE
  setContentView.
- Touch nothing outside the phase's scope. Especially: never modify the
  voice/VAD pipeline, prompt assembly in regularGPTResponse, chat
  storage/encryption paths, or rename transactions. If a phase's work
  seems to require it, STOP and ask.
- Copyright header on every new file; comments explain constraints and
  why, matching the codebase's documentation style.
- Each phase leaves main shippable: no half-wired UI reachable by the
  user, no dead buttons. A screen ships in the phase that makes it
  reachable.

PHASE SEQUENCE AND DEPENDENCIES

Run in numeric order. Strict dependencies: 2 before 7 and 8; 1 before
3, 4, 5; 6 before 7 (Phase 7's assignment flow needs the gallery entry
points); 5 before 6, 7, 8 (the gallery is the shared assignment
surface). 0 must be first. 9 is last.

----------------------------------------------------------------------
PHASE 0 — Repository validation and legacy avatar repair
----------------------------------------------------------------------

Model: Sonnet
Goal: prove the ground truth, fix the one existing bug the feature
depends on, and lock the cropper decision.

Read first: CustomizeAssistantDialog.kt, ChatAdapter.kt (avatar
display around the readAndDisplay call), ChatListAdapter.kt (same),
Preferences.kt avatar getters, the plan's PREREQUISITE REPAIR and
CROPPER IMPLEMENTATION DECISION sections.

Steps:
1. Re-verify the plan's repository claims against current main (paths,
   class names, MemoryStore version — it must still be 14; if it moved,
   STOP and tell the owner before proceeding).
2. Create the shared legacy-avatar resolver (one small utility): given
   the avatar id, check `avatar_<id>.png` then `avatar_<id>.jpg` in
   getExternalFilesDir("images"); return the first existing file, else
   null. CORRECTED July 19 2026: the literal extension actually written
   by CustomizeAssistantDialog.writeImageToCache is "jpg" (from the
   selectedImageType field), not "jpeg" — the plan's original ".jpeg"
   claim confused that field with an unrelated same-purpose-sounding
   local variable used only for the in-memory Base64 data URI. No
   ".jpeg" file has ever been written per the history visible in this
   (shallow) checkout, so do not add a ".jpeg" fallback speculatively.
3. Use the resolver at all three display sites: ChatAdapter,
   ChatListAdapter, CustomizeAssistantDialog's preview. Change only the
   file-path construction — no other behavior, no preference keys.
4. Unit-test the resolver's pure decision logic (extension order,
   missing file) in app/src/test with the filesystem abstracted.
5. Evaluate crop libraries per the plan's criteria (resolvable from
   already-configured repositories, CI-compilable, all ten framing
   requirements without forking). Record the decision — library name
   and version, or "custom Matrix implementation" — in a short
   DECISION note appended to this file, with one paragraph of
   reasoning. The plan expects the custom outcome; do not force a
   library that almost fits.

Must not: rewrite the avatar feature, touch avatar preference keys,
add any UI.

Done when: all three display sites resolve both extensions (.png and
.jpg), tests pass, CI green, the cropper decision is recorded, and the
owner has been told (in user terms) that old JPG avatars will reappear.

DECISION (Phase 0, recorded July 19 2026): custom Matrix implementation.

The repository already resolves com.github.* artifacts through JitPack
(settings.gradle) alongside google() and mavenCentral(), and the app
already depends on PhotoView (com.github.chrisbanes.photoview) the same
way, so a maintained crop library was genuinely reachable if one fit.
The two maintained candidates considered were uCrop (Yalantis) and
Android-Image-Cropper (CanHub, the maintained fork of ArthurHub's
library). Neither satisfies the plan's full requirement set without
forking or relying on private/internal APIs: uCrop exposes no public
horizontal-flip API on its gesture-crop view (req. 3 fails without
reaching into internals), and both libraries expose rotation only as
their own gesture/90-degree-step controls rather than a value a host
Activity can drive from an external dial widget and a numeric-entry
dialog while the library recomputes minimum scale and edge coverage for
that exact combined state (req. 4 and req. 10 fail together — neither
library's public API guarantees no uncovered crop edges for an
arbitrary fine angle combined with flip, zoom, and pan at once). Both
also ship their own crop-screen chrome, which does not match the plan's
exact top-right Flip/Rotate, degree-readout, and tick-dial layout (req.
6) without substantial fighting-the-library rework. Since forking or
using private APIs is explicitly disallowed by the plan, and the plan's
own text already anticipates this outcome, Phase 4 will build the pure
transform-math core plus a custom view as specified in FRAMING SCREEN
through CUSTOM MATRIX REQUIREMENTS, using AndroidX ExifInterface for
orientation. No crop library dependency will be added.

----------------------------------------------------------------------
PHASE 1 — Storage and catalog (no UI)
----------------------------------------------------------------------

Model: Sonnet
Goal: the permanent image store, hash, catalog database, atomic save,
reconciliation, and framing-cache management — all headless.

Read first: the plan's SHARED STORAGE ARCHITECTURE, CATALOG DATABASE,
RECONCILIATION, TEMPORARY FILE CLEANUP, STORAGE PRIVACY DECISION;
Hash.kt; LoreBookStore.kt (for the getInstance(applicationContext)
singleton pattern only — NOT its encryption).

Steps:
1. Add byte SHA-256 (Hash.hash(bytes: ByteArray) or a Sha256 utility;
   do not change the existing string hash). Unit-test against known
   SHA-256 vectors.
2. Create preferences/profileimages/: ProfileImageDb (plain
   SQLiteOpenHelper, schema v1 exactly as the plan writes it) and
   ProfileImageStore (save/list/delete/reconcile + the framing session
   cache directory management with the 24-hour stale threshold).
3. Implement the atomic save exactly in the plan's numbered order
   (encode once → hash the encoded bytes → temp file → rename →
   insertOrIgnore → return hash). Dedup keeps the original created_at.
4. Implement reconciliation exactly per the plan's validation list
   (name pattern, decodes, 512×512, hash matches). Runs only when the
   gallery opens — never at app startup. Nothing in this phase may
   touch MainApplication.
5. Unit-test every pure decision: filename validation, hash/dedup
   logic, stale-session selection, reconciliation accept/reject —
   filesystem and DB abstracted where needed.

Must not: touch companion_memory.db, lorebook.db, SecurePrefs, or any
UI file. Nothing user-visible changes in this phase.

Done when: store + catalog + tests exist, CI green, and the owner is
told this phase is invisible plumbing.

----------------------------------------------------------------------
PHASE 2 — Identity data model and migrations
----------------------------------------------------------------------

Model: Opus (encrypted-database migration = fragile area)
Goal: every identity can hold an image reference; backups carry it.

Read first: MemoryStore.kt header + the v14 migration block + meta
handling, MemoryData.kt records, MemorySeedCodec.kt and its tests,
PersonaPreferences.kt (getPersona/writePersona/removePersonaKeys),
PersonaObject.kt, the plan's REFERENCE STORAGE, MEMORY DATABASE
MIGRATION, BACKUP CODEC sections.

Steps:
1. MemoryStore: DATABASE_VERSION 14 → 15; new `if (oldVersion < 15)`
   block adding image_ref TEXT to user_personas and
   roleplay_characters; update meta.db_migration to "15" inside that
   block; add the columns to the fresh-install CREATE TABLE statements.
   Never edit an older migration block.
2. UserPersonaRecord / RoleplayCharacterRecord: nullable imageRef,
   defaulted null; thread through readUserPersonas / upsertUserPersona /
   readRoleplayCharacters / upsertRoleplayCharacter and every
   constructor/copy call site.
3. PersonaObject.avatarRef (String, default "") + PersonaPreferences
   plumbing (getPersona/writePersona/removePersonaKeys/list). Verify by
   reading the edit/rename path that avatarRef survives the
   delete-and-recreate rename (it must ride the PersonaObject like
   every other field).
4. GlobalPreferences: default_user_image_ref ("" default) and
   profile_image_shape ("flower" default, values flower|circle|square).
5. MemorySeedCodec: optional image_ref export/import for both record
   types via the existing tolerant optional-string helper. Update the
   codec unit tests per the plan's list. Update the schema
   documentation (or document image_ref as an approved extension).
6. Verify import of a pre-image_ref backup fixture still round-trips.

Must not: build any UI, alter any other table, touch memories rows,
change existing codec fields, or edit old migration blocks.

Done when: migration + records + codec + tests done, CI green, owner
told (invisible plumbing; existing data untouched; old backups still
import).

----------------------------------------------------------------------
PHASE 3 — Shared shape rendering
----------------------------------------------------------------------

Model: Sonnet
Goal: one ProfileShapeTransformation that renders Flower, Circle, and
true Square.

Read first: the plan's PROFILE IMAGE SHAPE RENDERING section;
res/drawable/mtrl_shape_clover.xml; how Glide is used today
(readAndDisplay sites).

Steps:
1. Implement ProfileShapeTransformation(shape) as a Glide
   BitmapTransformation: clover vector path as alpha mask for Flower
   (scale the 184×184 path space to the bitmap), circular mask for
   Circle, no-op mask for Square. Shape name in the cache key.
2. A tiny shared helper that binds a profile image into an ImageView:
   clears the previous request, resets tint/scale/background, applies
   the transformation for the CURRENT profile_image_shape, or applies
   the complete fallback state. Every later phase binds through this
   helper — write it once here.
3. Unit-test what is pure (cache-key composition, shape selection).
   The mask rendering itself is verified visually in later phases.

Must not: touch existing glyph avatars, backgrounds, or the generic
user icon; create any second clipping system; add UI.

Done when: transformation + binding helper + tests exist, CI green.

----------------------------------------------------------------------
PHASE 4 — Framing editor
----------------------------------------------------------------------

Model: Opus (transform math and no-empty-edges correctness)
Goal: the full Framing screen producing the 512×512 master image.

Read first: the plan's FRAMING SCREEN through FRAMING OUTPUT sections
(all of them), the Phase 0 cropper DECISION note, the plan's
ACCESSIBILITY AND LAYOUT section.

Steps:
1. Build the pure transform core FIRST as a plain Kotlin class (no
   Android views): pan/zoom/flip/quarter-turns/fine-angle composition,
   inverse-mapped corner coverage validation, minimum-scale
   computation. Unit-test it hard: every transform combination the
   plan lists, corners at extremes, the 90°-plus-fine-angle example.
   This class is the source of truth; the view is a shell around it.
2. ProfileImageFramingActivity: exact control order from the plan
   (scrim, square window, corner guides; Flip/Rotate top-right;
   readout with degree symbol; dial; Cancel/Framing/Done bottom bar).
   applyPalette before setContentView.
3. Source handling: copy to the session cache dir off-main; bounded
   decode (~2048px); EXIF applied once via AndroidX ExifInterface.
4. Fine Rotation dialog per the plan (range, decimals, Reset to 0°,
   validation message — plan wording verbatim).
5. State restoration per the plan's list; missing-source shows the
   load error and returns safely.
6. Output: exactly one 512×512 JPEG q92 in the session dir; transparent
   pixels composited over white BEFORE encoding.
7. Accessibility: content descriptions, 48dp targets, announced
   fine-rotation value.

Must not: write to permanent storage (that is the gallery's job in
Phase 5 — this activity returns the temp output path), show any shape
mask, allow a non-square crop.

Done when: math class fully unit-tested, screen complete per the
plan's exact orders, CI green. The screen is NOT yet reachable by
users (no entry point until Phase 5) — say so in the report.

----------------------------------------------------------------------
PHASE 5 — Profile Images gallery
----------------------------------------------------------------------

Model: Sonnet
Goal: the gallery activity — both modes, filters, selection, deletion,
detail sheet, upload flow — wired to Phases 1, 3, 4.

Read first: the plan from PROFILE IMAGES GALLERY through CONFIRMATION
WORDING, plus ERROR PRESENTATION, FINAL UX CLARIFICATIONS, USAGE
RESOLUTION, and the XML style rules.

Steps:
1. ProfileImagesActivity: RecyclerView + GridLayoutManager, dynamic
   span count, newest-first, plain square thumbnails, applyPalette
   before setContentView.
2. Assignment mode and management mode exactly per the plan's EXACT
   GALLERY CONTROL ORDER — including what assignment mode must NOT
   show, the Select availability rules, filter locking in selection
   mode, and the sticky bottom button (AppButton.Primary /
   AppButton.Destructive) respecting insets.
3. ProfileImageUsage: live usage computation off-main (MemoryStore
   only if isProvisioned; never provisions), identity names included.
4. Upload flow: ACTION_OPEN_DOCUMENT → Framing → ProfileImageStore
   permanent save → return hash (assignment) or refresh (management).
   Reconciliation + stale-session cleanup run on gallery open.
5. Detail/View Usage bottom sheet; deletion with the authoritative
   recheck, file-then-record order, and the plan's confirmation
   wording verbatim; the "Some images were not deleted…" persistent
   notice.
6. Empty states per the plan (no duplicate Upload New). Unavailable
   placeholder tiles. All states carry non-color indicators and
   screen-reader announcements per the accessibility section.
7. One shared gallery tile style/drawable (the plan's rule 13/12).

Must not: know or edit destination profiles (bare-hash result
contract only), add management controls to assignment mode, introduce
any Toast/Snackbar.

Done when: both modes work end-to-end against the store, CI green.
Still not user-reachable (entry points come in Phase 6) — say so.

----------------------------------------------------------------------
PHASE 6 — Profile Image Settings screen
----------------------------------------------------------------------

Model: Sonnet
Goal: the feature becomes reachable — Settings tile, the settings
screen, Default User Image, Default Shape, and the new row style.

Read first: the plan's PROFILE IMAGE SETTINGS SCREEN section
(including ROW-STYLE APPROVAL), the XML style rules, activity_settings.xml
and how the existing Settings tiles register, values/themes.xml row
styles, values/dimens.xml.

Steps:
1. Create Widget.App.Row.ProfileImage in values/themes.xml — child of
   Widget.App.Row.Icon overriding ONLY layout_width/layout_height with
   the new @dimen/row_profile_image_size (48dp) in values/dimens.xml.
2. ProfileImageSettingsActivity with the three rows in the plan's
   order, built from the shared row pieces (WithSubtitle + TextColumn +
   Title + Subtitle + Chevron; the Default User Image row leads with
   the ProfileImage style; the Remove Picture action row is TitleOnly).
   applyPalette before setContentView.
3. Default Shape chooser (Material single-choice dialog/sheet with
   small previews) writing profile_image_shape; the row subtitle shows
   the current value.
4. Shape Change Refresh: previews on this screen update immediately.
5. One normal Settings TILE on the main screen (TileFragment pattern —
   NOT a row) opening this activity.
6. Default User Image assignment through the gallery (assignment mode,
   RESULT_OK → default_user_image_ref) and Remove Picture clearing
   only the reference.

Must not: convert any other screen's tiles/rows, restyle anything
outside this feature, put three permanent shape controls on the
settings screen.

Done when: full flow works — open settings → tile → set/remove the
Default User Image, change shape — CI green, and this is the phase to
offer the owner a first test build.

----------------------------------------------------------------------
PHASE 7 — Companion integration
----------------------------------------------------------------------

Model: Opus (edits inside ChatActivity/ChatAdapter — the most fragile
neighborhood in the app; changes must be surgical and display-only)
Goal: Companions get pictures; chat and chat-list show them.

Read first: the plan's EDITOR INTEGRATION (Companion Editor), CHAT AND
CHAT-LIST DISPLAY, INTERACTION WITH EXISTING PER-CHAT AVATAR
CUSTOMIZATION, COMPANION SELECTION LIST sections; EditPersonaDialogFragment
and fragment_edit_persona.xml; the ChatActivity sections that own
adapter setup and Quick-Settings refresh (read the file's structure
before editing; do not stray into voice or generation code);
ChatAdapter avatar binding; ChatListAdapter; the persona list rows.

Steps:
1. Companion editor: shaped preview + Add/Change/Remove Picture,
   gallery in assignment mode, lifecycle-safe result handling (no
   transient-field-only listener), avatarRef in the final
   PersonaObject. Verify rename survival by reading the save path.
2. ChatActivity: resolve the assistant-side image off-main, hand
   resolved values to ChatAdapter, refresh on Quick Settings changes
   and onResume. Display-only additions; nothing else in the file
   moves.
3. ChatAdapter: bind via the Phase 3 helper with the full assistant
   precedence (Companion image → legacy resolver avatar → built-in).
   Complete fallback state on every bind.
4. ChatListAdapter: same precedence when the chat's Companion is
   known.
5. Companion selection rows: leading image per the plan (ProfileImage
   geometry; existing glyph unchanged when unassigned).
6. The per-chat avatar explanation text (plan wording verbatim,
   persistent, shown only while a Companion picture is active).

Must not: touch generation, voice, TTS, or prompt code in
ChatActivity; block row binding on database work; change the legacy
avatar feature beyond displaying through the resolver.

Done when: assistant-side pictures render everywhere listed with
correct fallbacks, CI green, owner offered a test build.

----------------------------------------------------------------------
PHASE 8 — My Persona and Roleplay Character integration
----------------------------------------------------------------------

Model: Sonnet
Goal: user-side pictures and the full user-side precedence chain.

Read first: the plan's My Persona Editor / Roleplay Character Editor /
Default User Image subsections and CHAT AND CHAT-LIST DISPLAY;
EditUserPersonaDialogFragment; MemoryUserPersonasActivity;
CharacterCardActivity (isParty discrimination).

Steps:
1. My Persona editor: imageRef in/out, lifecycle-safe result path,
   preserved when editing other fields.
2. CharacterCardActivity, user mode only (isParty == false): image
   controls, imageRef in saved state, preserved on save unless
   explicitly changed.
3. ChatActivity/ChatAdapter user side: active RP character → active My
   Persona → Default User Image → generic ic_user, resolved off-main,
   bound through the Phase 3 helper, refreshed on Quick Settings
   change and onResume.

Must not: show image controls in party-member mode; add images to
memory-side list rows (not approved); touch anything beyond these
call sites.

Done when: user-side precedence works and changes with identity
selection, CI green, owner offered a test build.

----------------------------------------------------------------------
PHASE 9 — Style review gate, documentation, and final report
----------------------------------------------------------------------

Model: Sonnet
Goal: verify everything, correct the docs, deliver the owner's test
build.

Steps:
1. Run the plan's STYLE REVIEW GATE checklist item by item across
   every layout and Kotlin file this feature added or touched. Fix
   violations. The report names every new layout and the shared
   styles it uses.
2. Walk the plan's VERIFICATION section; confirm each line or fix it.
3. Update CLAUDE.md: the Profile Images feature (storage, screens,
   styles including Widget.App.Row.ProfileImage, the legacy-avatar
   resolver), and correct the stale AppButton.Secondary claim. Update
   the memory schema documentation for image_ref.
4. Confirm all unit tests pass in CI; final Android Checks green.
5. Deliver the owner's test-build checklist from the plan's OWNER TEST
   BUILD section, in user terms, as the report.

Done when: gate passed and reported, docs updated, CI green, owner has
the test build and the checklist. The FEATURE is done only when the
owner approves the test build — no phase report may claim otherwise.
