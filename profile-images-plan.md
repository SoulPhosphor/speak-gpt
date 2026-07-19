PROFILE IMAGES
Repository-Grounded Implementation Plan for SoulPhosphor/speak-gpt

STATUS

Planning only. Awaiting the owner's final approval in chat. Do not modify
code until the owner approves this complete plan.

Repository:
SoulPhosphor/speak-gpt
Base branch:
main

This plan completely replaces every earlier Profile Images plan. Do not reuse
rejected architecture or wording from previous versions.

Before implementation, recheck every named path and line against the current
main branch. Repository structure is authoritative when a line number has
moved, but the user experience and behavior defined here are mandatory.

Whether this work starts immediately or waits behind other open work is a
scheduling decision the owner makes separately, outside this document. This
plan carries no priority claim of its own.

GOAL

Create one shared Profile Images library that allows users to upload, frame,
reuse, inspect, and delete pictures used by:

1. Companions
2. My Personas
3. User-side Roleplay Characters
4. Default User Image

The system must be pleasant to use, not merely convenient to implement.

Images must be reusable between identities. Removing an image from one
identity must not delete the saved image. Unused saved images must be
manageable and permanently deletable through the app.

APPROVED TERMINOLOGY AND CAPITALIZATION

Use Title Case for screen titles, named features, labels, filters, menu
items, and buttons.

Required examples:

Profile Images
Profile Image Settings
Default User Image
Default Shape
My Persona
Roleplay Character
Upload New
Add Picture
Change Picture
Remove Picture
All Images
In Use
Unused
Select
Select All Shown
Delete Selected
Cancel Selection
View Usage
Fine Rotation
Reset to 0°
Apply
Cancel
Delete Permanently
Flower
Circle
Square
Flip
Rotate
Done

My Persona and Roleplay Character are named app features and must always
retain full Title Case.

Questions and explanatory text use normal sentence capitalization.

Examples:

Permanently delete this image?
Delete all 12 unused images?
Used by 3 profiles
Used when the active My Persona or Roleplay Character has no image.
Pinch to zoom • Drag to reposition.
Some images were not deleted because they are now in use.

Do not use the label "Your Picture." The approved label is "Default User
Image."

APPROVED SCOPE

Companions

A Companion owns its picture. The picture appears on the assistant side of
chat and anywhere else that Companion pictures are explicitly included in
this plan.

Assistant-side fallback order:

1. Companion image, when assigned and its file exists
2. Existing per-chat assistant avatar
3. Existing built-in assistant fallback

My Personas

Each My Persona may have its own image.

Roleplay Characters

Each user-side Roleplay Character may have its own image.

Party members and NPC Roleplay Characters are not included.

Default User Image

One global image is used when the active user identity has no specific
image.

User-side fallback order:

1. Active Roleplay Character image
2. Active My Persona image
3. Default User Image
4. Existing generic user icon

Missing files must be treated as unavailable and skipped. Never display a
broken-image glyph and never crash.

Default Shape

The initial choices are:

Flower
Circle
Square

Flower uses the existing clover geometry.

Square means a true square with 90-degree corners and no corner radius.

Default Shape applies only to uploaded Profile Images. It must not reshape
existing built-in glyph avatars, their backgrounds, or the generic user
icon.

Gallery thumbnails remain plain square thumbnails so the complete saved
image is visible.

PREREQUISITE REPAIR: EXISTING PER-CHAT AVATAR FALLBACK

The current per-chat avatar writer saves either:

avatar_<hash>.png
avatar_<hash>.jpg

CORRECTED July 19 2026 (Phase 0 repository verification): an earlier
version of this plan claimed the second extension was literally ".jpeg",
citing a Bitmap.CompressFormat.JPEG -> "jpeg" mapping inside
CustomizeAssistantDialog. That mapping is real but belongs to a different,
unrelated local variable used only to build the in-memory Base64
data:image URI string. The value actually passed to the file writer
(writeImageToCache) is the sibling variable selectedImageType, which is
set to "jpg" (not "jpeg") for any non-PNG source. The file genuinely
written to disk today is avatar_<hash>.jpg. Repository history available
in this checkout (a shallow clone) shows no version of
CustomizeAssistantDialog.kt that ever wrote a ".jpeg" file, so this plan
does not add speculative ".jpeg" support.

Existing display paths reconstruct only avatar_<hash>.png, so a JPG avatar
saved by the writer is invisible to every reader.

Because the old per-chat avatar is the approved fallback for a Companion
without an image, repair this mismatch before integrating Companion images.

Create one shared legacy-avatar resolver that:

1. Receives the existing avatar hash.
2. Checks the legacy extensions in this deterministic order:
   1. .png
   2. .jpg
3. Returns the existing file when either is present.
4. Is used by every existing legacy display site, including:
   - ChatAdapter (chat messages)
   - ChatListAdapter (chat-list rows)
   - CustomizeAssistantDialog's own existing-avatar preview
5. Does not change existing preference keys or migrate unrelated avatar
   data.

Do not rewrite the existing avatar feature. Make only the narrow
file-resolution repair necessary for a reliable fallback.

Add regression verification for both old PNG and old JPG avatar files.

STORAGE PRIVACY DECISION

Permanent Profile Image JPEG files and profile_images.db are stored without
application-level encryption.

This is an explicit owner-approved tradeoff:

- Existing per-chat avatar files are already stored as ordinary files.
- The catalog contains only image hashes and timestamps.
- Encrypting the catalog alone would not protect the JPEG files.
- Encrypting and decrypting every image would add substantial
  implementation and runtime complexity that is not approved for this
  feature.

The files remain in the app's own storage area, but the plan must not
describe them as cryptographically encrypted.

Future encrypted media storage may be considered as a separate feature.

SHARED STORAGE ARCHITECTURE

Permanent Image Files

Store only the completed, user-approved square result.

Directory:

getExternalFilesDir("profile_images")

File contract:

profile_<hash>.jpg

Permanent image format:

JPEG
512 × 512 pixels
Quality 92

The original selected source image must never be copied into permanent
Profile Images storage.

The hash is the SHA-256 digest of the final encoded JPEG bytes, not a hash
of a Base64 string and not a hash of the source URI.

The existing Hash.kt currently accepts only String. Add a byte-safe SHA-256
method, either:

Hash.hash(bytes: ByteArray): String

or a clearly named dedicated utility such as:

Sha256.digest(bytes: ByteArray): String

Do not change the existing string-hash behavior used for persona labels and
other identifiers.

Add deterministic unit tests using known SHA-256 byte vectors.

Atomic Save Process

ProfileImageStore.save(bitmap) must:

1. Render or receive the final 512 × 512 bitmap.
2. Encode it once as JPEG quality 92.
3. Calculate SHA-256 from those exact encoded bytes.
4. Write to a temporary file.
5. Move or rename the completed temporary file to profile_<hash>.jpg.
6. Avoid replacing an existing identical hash file.
7. Insert or reuse the catalog record.
8. Return the bare hash only after the permanent file is valid.

If the catalog insert fails after the file is written, do not assign the
image to a profile. The uncatalogued valid file may be recovered by
reconciliation when the gallery next opens.

CATALOG DATABASE

Use a dedicated, small framework SQLite database:

profile_images.db

Suggested class:

preferences/profileimages/ProfileImageDb.kt

Use android.database.sqlite.SQLiteOpenHelper unless repository inspection
finds an established non-encrypted helper abstraction that is genuinely
appropriate.

Do not use the lazily provisioned companion_memory.db for the gallery.
Opening Profile Images must never provision the memory system as a side
effect.

Do not add this unrelated catalog to lorebook.db.

Schema Version 1

Use this literal minimal schema:

CREATE TABLE profile_images (
    hash TEXT PRIMARY KEY NOT NULL,
    created_at INTEGER NOT NULL
)

created_at is UTC epoch milliseconds representing the first time that
encoded image was added to the library.

No width column, height column, or format column is needed. Every valid
permanent Profile Image is fixed by contract to 512 × 512 JPEG.

Do not claim that future sync may supply arbitrary dimensions while
simultaneously rejecting non-512 files. Phase-8 sync must use the same
permanent file contract.

No additional index is needed. The primary key covers lookup by hash, and
the gallery may order by created_at DESC.

Catalog Operations

The catalog layer must provide at least:

insertOrIgnore(hash, createdAt)
listNewestFirst()
contains(hash)
delete(hash)
clearMissingUnusedRecord(hash)

Re-uploading byte-identical content reuses the existing file and record. It
retains the original created_at value rather than creating a duplicate or
moving the image to the newest position.

REFERENCE STORAGE

Every identity stores only the bare image hash.

Companions:

PersonaObject.avatarRef: String = ""

PersonaPreferences key:

<persona-id>_avatar_ref

The field must be handled in all required sites:

getPersona
writePersona
removePersonaKeys
EditPersonaDialogFragment argument/build/save plumbing

Because editing or renaming a Companion removes the old hashed-label keys
and creates new ones, the selected avatarRef must be copied into the newly
written PersonaObject before the old keys are removed.

My Personas:

MemoryStore user_personas.image_ref TEXT

UserPersonaRecord.imageRef: String? = null

Roleplay Characters:

MemoryStore roleplay_characters.image_ref TEXT

RoleplayCharacterRecord.imageRef: String? = null

Default User Image:

GlobalPreferences key:

default_user_image_ref

Default value:

empty string

Default Shape:

GlobalPreferences key:

profile_image_shape

Allowed values:

flower
circle
square

Default value:

flower

USAGE RESOLUTION

Do not store is_used, reference_count, or similar duplicated state in the
catalog.

Create ProfileImageUsage that computes live usage from:

1. GlobalPreferences.default_user_image_ref
2. Every Companion PersonaObject.avatarRef
3. Every My Persona image_ref
4. Every user-side Roleplay Character image_ref

MemoryStore must be queried only when MemoryStore.isProvisioned(context) is
true. Usage checks must never provision it.

Usage information must contain the actual identity names, not merely
totals.

Example:

Used by 3 profiles

Companion: Ash
My Persona: Explorer
Roleplay Character: Mira

Default User Image should appear by that exact name when it references the
image.

Usage is computed:

1. Off the main thread when the gallery loads.
2. Again for the selected hashes immediately before permanent deletion.

Display-time usage is advisory. Deletion-time usage is authoritative.

If an image became referenced after the gallery loaded:

1. Skip that image.
2. Delete only the selected images that remain unused.
3. Refresh the gallery and usage data.
4. Show:

Some images were not deleted because they are now in use.

RECONCILIATION

Run reconciliation only when Profile Images opens, never at app startup.

Catalog Record With Missing File

Display an unavailable square placeholder tile.

If unused, the record may be permanently removed.

If still referenced, View Usage identifies the affected identities. All
normal chat and list displays fall through their avatar precedence.

Uncatalogued Permanent File

Register it only after validating all of the following:

1. File name matches profile_<64 lowercase hexadecimal characters>.jpg.
2. The JPEG can be decoded.
3. The decoded dimensions are exactly 512 × 512.
4. SHA-256 of the file bytes exactly matches the hash in the file name.

Invalid, partial, corrupted, incorrectly named, or mismatched files are
ignored and never displayed.

For a valid reconciled file, created_at may use the file modification
timestamp when valid, otherwise the reconciliation time.

PROFILE IMAGE SETTINGS SCREEN

Main Settings Entry

The current main Settings screen uses TileFragment. Add one normal Settings
tile for Profile Image Settings so the main screen remains visually
consistent.

Do not insert a single chevron row among the existing main Settings tiles.

ROW-STYLE APPROVAL

The owner explicitly approves extending Widget.App.Row.WithSubtitle and
Widget.App.Row.TitleOnly to the new Profile Image Settings screen.

This approval is limited to this row-based secondary settings screen. It
does not authorize converting unrelated screens or the existing main
Settings tile layout.

Update CLAUDE.md so it no longer incorrectly claims these row styles or
AppButton.Secondary are unavailable.

Profile Image Settings Activity

Create:

ui/activities/ProfileImageSettingsActivity.kt

Use the existing full-screen app structure and Widget.App.Row styles.

Required row order:

1. Default User Image
2. Default Shape
3. Profile Images

Default User Image Row

Use Widget.App.Row.WithSubtitle.

Layout:

Leading shaped preview using the new Widget.App.Row.ProfileImage style
(@dimen/row_profile_image_size, approximately 48dp, always vertically
centered)
Title: Default User Image
Subtitle: Used when the active My Persona or Roleplay Character has no
image.
Trailing chevron

Tap opens Profile Images in assignment mode.

When an image is assigned, show a separate visible title-only action row
directly below it:

Remove Picture

Remove Picture clears only default_user_image_ref. It does not delete the
gallery image.

Default Shape Row

Use Widget.App.Row.WithSubtitle.

Title:

Default Shape

Subtitle shows the current value:

Flower
Circle
Square

Tap opens a Material single-choice dialog or bottom sheet. Each choice
includes a small visual preview.

Do not permanently place three large shape controls inside the settings
screen.

Profile Images Row

Use Widget.App.Row.WithSubtitle.

Title:

Profile Images

Subtitle:

Browse, reuse, and manage saved images.

Tap opens Profile Images in management mode.

PROFILE IMAGES GALLERY

Create:

ui/activities/ProfileImagesActivity.kt

Use one RecyclerView with GridLayoutManager.

Use a dynamic span count based on a minimum useful thumbnail width rather
than hardcoding a layout that breaks on tablets or landscape. Phone
portrait should normally produce three columns.

Order images newest-first.

LAUNCH MODES

Assignment Mode

Used by:

Companion editor
My Persona editor
User-side Roleplay Character editor
Default User Image row

Normal image tap:

1. Return RESULT_OK with the bare image hash.
2. Close the gallery.
3. Let the caller assign the hash.

The gallery does not know or edit the destination profile directly.

Back returns RESULT_CANCELED and changes nothing.

Assignment mode contains:

Top app bar
Filters
Grid
Upload New

Assignment mode must not display:

Select
Multi-selection checkboxes
Delete actions
Management overflow actions

Management Mode

Opened from the Profile Images row.

Normal image tap opens its detail and usage view.

Management mode includes selection and deletion controls.

EXACT GALLERY CONTROL ORDER

Normal Assignment Mode

Top app bar:

Left: Back
Center/start title: Profile Images
No action on the right

Below app bar:

Single-selection filter control in this order:

All Images
In Use
Unused

Main content:

Thumbnail grid

Sticky bottom action area:

One full-width AppButton.Primary:

Upload New

Normal Management Mode

Top app bar:

Left: Back
Title: Profile Images
Right: Select

Below app bar:

All Images
In Use
Unused

Main content:

Thumbnail grid

Sticky bottom action area:

Upload New

Selection Mode

When Select is pressed:

Top contextual bar:

Left: Cancel Selection icon or X
Title: live count such as 4 Selected
Right: Select All Shown

The active filter is locked while selection mode is active so filtering
cannot silently change or discard the selection.

Grid:

Unused images may be selected.
In-use images remain visible but cannot be selected and display their
locked/in-use state.

Bottom action area:

Hide Upload New.

Show one full-width AppButton.Destructive:

Delete Selected

Select All Shown means:

Select every deletion-eligible unused image in the current filtered
results.

It never selects an in-use image.

Cancel Selection clears the selection and restores normal management mode.

Select Availability

In management mode, enable or show Select only when the current filtered
results contain at least one deletion-eligible unused image.

Under the In Use filter, Select must be hidden or disabled because no
displayed image is eligible for deletion.

Under All Images, Select is available only when at least one displayed
image is unused.

Under Unused, Select is available when the filter is not empty.

Do not allow the user to enter an empty selection mode containing no
selectable images.

FILTERS

All Images

Shows every catalog image, including missing-file placeholders.

In Use

Shows images with one or more current identity references.

Unused

Shows images with zero current references.

Unused tiles may have a subtle Unused label. Do not rely solely on a border
color to communicate status.

EMPTY STATES

The sticky bottom Upload New button is the sole upload action in normal
assignment and management modes. Do not place a second Upload New button
inside the empty-state content while the sticky bottom Upload New button
is visible.

All Images:

Title: No Profile Images
Body: Upload an image to add it to the gallery.

In Use:

Title: No Images in Use
Body: Images assigned to profiles will appear here.

Unused:

Title: No Unused Images
Body: Saved images that are no longer assigned will appear here.

The In Use and Unused empty states remain informational and contain no
action button.

IMAGE DETAIL AND VIEW USAGE

Use a Material bottom sheet or similarly compact detail surface rather than
creating an unnecessary full activity.

Show:

Large plain-square preview
Availability status
Date added
Usage total
Actual identity list

For an in-use image:

Display View Usage information.
Do not provide permanent deletion.

For an unused image:

Provide Delete Permanently.

UPLOAD FLOW

Both gallery modes use the same Upload New flow:

Upload New
→ Android system image picker
→ Framing
→ save to Profile Images
→ assignment or gallery refresh

Use ACTION_OPEN_DOCUMENT with image/*.

Copy the selected source into a dedicated temporary framing session folder
before editing. Perform the copy off the main thread.

Temporary directory:

cacheDir/profile_framing/<session-token>/

The source copy remains temporary and is never placed in profile_images.

In assignment mode:

After Framing is confirmed and permanent saving succeeds, immediately
return the hash to the caller.

Do not make the user locate and tap the newly uploaded image again.

In management mode:

After saving, refresh the grid and show the new or reused image.

Cancellation at the picker or Framing screen leaves the existing profile
assignment unchanged and creates no catalog record.

TEMPORARY FILE CLEANUP

Each framing session has its own cache subdirectory.

Normal success or cancellation deletes that session directory.

Process death may prevent normal cleanup. When Profile Images opens, remove
framing session directories older than a defined stale threshold, such as
24 hours.

Do not indiscriminately delete every framing cache file because another
valid restored session may still depend on one.

If a restored Framing activity cannot find its temporary source, show a
clear load error and return to the gallery without changing the profile.
Do not silently reset the crop.

FRAMING SCREEN

Create:

ui/activities/ProfileImageFramingActivity.kt

The supplied game screenshot is an interaction reference only.

Crop Geometry

The crop window is always a fixed 1:1 square.

It cannot be resized into a portrait rectangle or landscape rectangle.

Do not show Flower, Circle, Square, or any other display mask in the
Framing screen.

The editor creates one square master image. Display masks are applied
later.

EXACT FRAMING CONTROL ORDER

Photo/crop area:

Full-screen photo behind a dimmed scrim
Centered square clear crop window
High-contrast corner guides

Top-right overlay controls, left to right:

Flip
Rotate

Flip performs horizontal mirroring.

Rotate performs one clockwise 90-degree step.

Below the crop area:

Centered fine-angle readout, always including the degree symbol, such as:

+7°
0°
−12.5°

Tapping the readout opens Fine Rotation numeric entry.

Below the readout:

Fine-rotation tick-mark dial

Bottom bar:

Left: Cancel X
Center: Framing
Right: Done checkmark

Done remains disabled until the image is loaded and the crop is valid.

FINE ROTATION

Range:

−45° through +45°

Support decimals.

The dial provides quick adjustment.

The numeric dialog provides exact adjustment.

Dialog:

Title: Fine Rotation
Input hint: Degrees (−45 to 45)

Actions:

Reset to 0°
Cancel
Apply

Reset to 0° immediately sets the fine angle to zero.

Invalid input shows:

Enter a value between −45° and 45°.

The displayed value represents fine rotation only.

Quarter-turn rotation is tracked separately.

Example:

One 90-degree turn plus +7° fine rotation produces 97° effective rotation,
while the readout remains +7°.

A 90-degree rotation must never erase the fine angle.

CROPPER IMPLEMENTATION DECISION

Do not automatically build a custom crop engine merely because the existing
PhotoView dependency cannot satisfy the feature.

During Phase 0, evaluate a currently maintained crop library before
building the custom Matrix editor.

Any candidate library must:

- Resolve through repositories already supported by the build, such as
  Maven Central or the project's existing configured sources.
- Compile successfully through Android Checks CI.
- Satisfy every mandatory Framing requirement without forking the library
  or depending on private implementation APIs.

The mandatory Framing requirements are:

1. Fixed, non-resizable 1:1 crop window
2. Drag and pinch zoom
3. Horizontal flip
4. Arbitrary fine rotation controlled externally
5. Numeric angle support
6. Custom app-owned UI
7. State restoration
8. Correct EXIF handling
9. Exact 512 × 512 output
10. Guaranteed no uncovered crop edges at any supported transform

The required custom UI, horizontal flip, externally controlled fine
rotation, numeric angle entry, state restoration, and no-empty-edges
guarantee make the custom Matrix implementation the expected outcome.

After evaluation, choose exactly one implementation. Do not retain both a
library path and a custom path.

Use AndroidX ExifInterface rather than relying on ad hoc orientation
parsing. Pin the stable version verified at implementation time.

CUSTOM MATRIX REQUIREMENTS, WHEN NEEDED

Transform composition must account for:

Translation
Fine rotation plus quarter turns
Scale
Horizontal flip
Image center

No Empty Edges

The crop square must remain completely covered by source pixels.

This is a correctness requirement, not a cosmetic preference.

After every:

Pan
Pinch
Fine rotation
Numeric rotation
90-degree rotation
Flip
Activity restoration

the implementation must validate the transformed crop.

Use inverse mapping of all four crop-square corners into image coordinates
as the final source of truth.

A cover-scale formula may establish the minimum scale, but the
implementation must not rely on bounding-box estimates alone.

Never save:

Transparent wedges
Blank strips
Background-colored corners
Uncovered crop pixels

EXIF AND DECODING

Decode large source images off the main thread with bounds sampling so the
working bitmap is reasonably bounded, approximately 2048 pixels on its
longest side unless testing demonstrates a better safe value.

Apply EXIF orientation once to establish the initial upright display.

After that point, every transformation is user-controlled.

The confirmed 512 × 512 result must contain the approved orientation with
no remaining EXIF rotation dependency.

STATE RESTORATION

Preserve:

Temporary source path and session token
Pan position
Zoom
Horizontal flip
Quarter-turn count
Fine angle

Use saved instance state or an appropriate lifecycle-safe state holder.

When Android provides restorable state and the temporary source still
exists, return to the same crop rather than resetting.

Do not promise restoration after force-stop, cache eviction, or missing
source. Handle those conditions clearly and safely.

FRAMING OUTPUT

Always create exactly one:

512 × 512 JPEG
Quality 92

Confirming produces one temporary output in the session directory.

The gallery then performs permanent hashing, deduplication, catalog
insertion, and assignment.

Transparent Sources

Permanent Profile Images are JPEG and therefore cannot preserve
transparency.

When a PNG, WebP, or other supported source contains transparent or
partially transparent pixels, composite those pixels over a solid white
background before encoding the final JPEG.

Do not allow transparent regions to become encoder-dependent black areas
or uninitialized colors.

The no-empty-edges requirement still applies to source-image coverage.
This transparency rule defines how legitimate transparent pixels inside
the source are flattened.

PROFILE IMAGE SHAPE RENDERING

Use one shared Glide BitmapTransformation for uploaded Profile Images:

ProfileShapeTransformation(shape)

Requirements:

- The transformation cache key includes the selected shape.
- Flower uses the existing clover vector geometry (the path in
  res/drawable/mtrl_shape_clover.xml) as an alpha mask.
- Circle uses a circular alpha mask.
- Square is a true square and performs no corner rounding.
- Gallery thumbnails remain unmasked plain squares.
- Existing built-in glyph avatars and generic fallback icons remain
  unchanged.
- Do not implement a second competing view-clipping system.

The existing Flower/clover resource is a vector path drawable and cannot be
represented by Material corner treatments, so ShapeableImageView and
ShapeAppearanceModel are not candidates. There is no investigation branch:
the Glide transformation is the single approved implementation.

Every RecyclerView bind must reset the ImageView before loading:

Clear any previous Glide request.
Clear stale tint.
Reset scale type.
Reset background and placeholder state.
Apply the current profile image or the unchanged fallback icon.

This prevents recycled rows from briefly showing another identity's picture
or tint.

Shape Change Refresh

After Default Shape changes:

- Update the Default User Image preview immediately.
- Update other visible Profile Image previews on the current screen
  immediately.
- Ensure chat, chat-list, Companion-selection, and editor views use the
  new shape when next bound or resumed.
- Do not require an app restart, image re-upload, or image reframing.
- Keep the selected shape in the Glide transformation cache key so cached
  output from the prior shape cannot be reused incorrectly.

EDITOR INTEGRATION AND LIFECYCLE SAFETY

Companion Editor

Update EditPersonaDialogFragment and fragment_edit_persona.xml.

Show:

Shaped preview
Add Picture or Change Picture
Remove Picture when assigned

Add/Change opens Profile Images in assignment mode.

The selected avatarRef must survive activity and dialog recreation.

Do not rely solely on a transient listener field for the new image result.

Use one lifecycle-safe approach:

Fragment Result API
A registered Activity Result contract with saved state
Or explicit restored-dialog listener rebinding by the host

The final PersonaObject must include avatarRef.

My Persona Editor

Update:

EditUserPersonaDialogFragment
MemoryUserPersonasActivity
UserPersonaRecord
MemoryStore read/upsert paths

Pass imageRef into the dialog.

Return imageRef with the saved form.

Preserve the existing imageRef when editing other fields.

The current dialog listener is a transient field. Make the save/result path
lifecycle-safe rather than adding another field-only callback that
disappears after FragmentManager restoration.

Roleplay Character Editor

Update CharacterCardActivity in user Roleplay Character mode only.

Do not show image controls for party members.

Keep selected imageRef in saved state.

When saving an existing record, preserve imageRef unless the user
explicitly changes or removes it.

Default User Image

The settings screen opens Profile Images in assignment mode.

RESULT_OK updates default_user_image_ref.

RESULT_CANCELED changes nothing.

CHAT AND CHAT-LIST DISPLAY

Do not perform memory-database work inside RecyclerView row binding.

Resolve active image references asynchronously at the activity or screen
level.

For ChatActivity:

1. Resolve Companion image, active Roleplay Character image, active My
   Persona image, and Default User Image off the main thread.
2. Pass the resolved hashes/files into ChatAdapter on the main thread.
3. Notify the relevant rows after the resolved state changes.
4. Refresh after Quick Settings changes and onResume.

Do not start uncontrolled background work from each adapter construction or
each row.

ChatAdapter must bind using already resolved values.

Every bind must explicitly set either the profile image or the complete
fallback state so recycled ImageViews cannot retain old Glide content or
tint.

Apply the same Companion-image precedence to chat-list rows when a chat's
Companion is known:

1. Companion image
2. Existing per-chat avatar, using the repaired legacy resolver
3. Existing built-in fallback

INTERACTION WITH EXISTING PER-CHAT AVATAR CUSTOMIZATION

When a Companion image is assigned, it intentionally takes precedence over
the existing per-chat assistant avatar.

Keep the existing per-chat avatar controls enabled because they still
configure the fallback.

When the active Companion has an assigned and available image, display
persistent explanatory text near the per-chat avatar controls:

This chat is currently using [Companion Name]'s picture. The avatar below
is used when no Companion picture is available.

Do not use a Toast or Snackbar for this explanation.

When no Companion image is active, hide the explanation and retain the
existing avatar UI behavior.

COMPANION SELECTION LIST

Add a small profile image at the start of Companion selection rows.

The image slot uses the shared Widget.App.Row.ProfileImage geometry —
apply the style itself where the existing row layout's structure allows,
otherwise reuse @dimen/row_profile_image_size — and the rest of the
existing row layout is unchanged. Do not restyle the Companion selection
rows as part of this feature.

When assigned:

Show the Companion image with Default Shape.

When unassigned or missing:

Show the existing generic glyph with its existing styling unchanged.

Do not add My Persona or Roleplay Character images to their memory-side
list rows in this phase unless separately approved.

MEMORY DATABASE MIGRATION

Bump:

MemoryStore.DATABASE_VERSION from 14 to 15

Fresh-install tables:

Add image_ref TEXT to user_personas.

Add image_ref TEXT to roleplay_characters.

Upgrade block:

Add a new if (oldVersion < 15) block.

Execute both ALTER TABLE statements.

Update meta.db_migration to "15" in that same migration block, following
MemoryStore's established requirement that the SQLite helper version and
metadata migration value move together.

Never edit an older migration block.

Update:

UserPersonaRecord
RoleplayCharacterRecord
readUserPersonas
upsertUserPersona
readRoleplayCharacters
upsertRoleplayCharacter
Every constructor and copy/save call site

Use nullable imageRef with null meaning no image.

BACKUP CODEC AND SCHEMA DOCUMENTATION

Update MemorySeedCodec:

Export optional image_ref for My Personas and Roleplay Characters.

Import with the existing tolerant optional-string helper so old backups
remain valid.

Update the relevant codec unit tests:

Round trip with image_ref.
Import without image_ref.
Null image_ref.
Unknown/missing files do not affect JSON parsing.

Because MemorySeedCodec states that its JSON mirrors the documented schema
shape, update the applicable documentation or JSON schema to include
optional image_ref properties, or explicitly document it as an approved
extension.

Do not silently let code and the documented schema diverge.

Current memory JSON backups do not carry:

Actual Profile Image files
The Profile Images catalog
Companion SharedPreferences avatar references unless an existing general
settings backup already includes them
Default User Image unless an existing general settings backup already
includes it

Repository inspection must verify the last two points rather than assuming
them.

This limitation must be documented honestly.

A restored hash without its image file falls through safely.

PHASE-8 SYNC READINESS

Future sync must carry:

Permanent profile_<hash>.jpg files
Bare hash references owned by identities
Enough metadata to recreate or reconcile catalog records
Hash validation
Deduplication

All synced permanent image files must obey the same 512 × 512 JPEG
contract.

SYNC itself is not implemented in this task.

PERMANENT DELETION

Removing a Picture From a Profile

Remove Picture clears only that identity reference.

It never deletes the catalog record or file.

Permanent deletion occurs only in Profile Images management mode.

Unused Image Deletion Order

Immediately before deletion:

1. Recompute usage for selected hashes.
2. Remove newly in-use hashes from the deletion set.
3. Confirm or continue with only still-unused images.

For each still-unused image:

1. Delete the permanent file first.
2. Treat an already missing file as success.
3. Delete the catalog record.
4. Refresh the gallery.

Deleting the file first is safer. If catalog deletion fails, the remaining
record appears as an unavailable placeholder and may be retried. Deleting
the record first could allow a failed file deletion to be rediscovered and
registered again.

CONFIRMATION WORDING

One image:

Title:
Permanently delete this image?

Body:
This removes the image from Profile Images. It cannot be restored unless
you upload it again.

Buttons:
Cancel
Delete Permanently

Several selected images:

Title:
Permanently delete 12 images?

Body:
These images are not currently assigned to any profiles. They cannot be
restored unless you upload them again.

Buttons:
Cancel
Delete 12 Images

All shown unused images:

Title:
Delete all 37 unused images?

Body:
This permanently removes every unused image currently shown. Images
assigned to profiles will not be deleted.

Buttons:
Cancel
Delete All 37 Images

Do not add an "I understand" checkbox.

ERROR PRESENTATION

Do not add new Toast or Snackbar messages for the Profile Images feature.

Use:

- Persistent inline status text for recoverable errors, partial results,
  and information the user may need time to read.
- A Material dialog for blocking failures or information requiring
  acknowledgment.
- A visible persistent empty or error state when the gallery cannot load
  or has no results.

Do not silently fail an upload, save, assignment, deletion, or image load.

ACCESSIBILITY AND LAYOUT

All icon-only controls must have accurate content descriptions,
including:

- Back
- Cancel
- Done
- Flip
- Rotate
- Cancel Selection

Interactive controls must have a minimum 48dp touch target even when
their visible icon is smaller.

Selected, In Use, Unused, unavailable, and disabled states must not be
communicated by color alone. Use an icon, label, checkmark, content
description, or another non-color indicator.

Screen readers must announce:

- Image selection state
- Whether an image is In Use or Unused
- Whether an image file is unavailable
- The current fine-rotation value
- The live selection count

Support normal Android font scaling without clipping essential labels or
actions.

If Select All Shown does not fit in the contextual top bar at larger font
sizes or narrow widths, move it into the top-bar overflow menu rather
than truncating it.

Sticky bottom controls must respect navigation-bar and gesture insets and
must not be hidden behind the system navigation area.

XML STYLE AND THEME INTEGRATION REQUIREMENTS

The Profile Images feature must use the app's centralized XML style
system. Do not recreate visual styling independently in each layout and
do not treat applying a style name as sufficient while overriding that
style elsewhere.

Approved shared styles (all verified present in values/themes.xml on
current main):

Buttons:

- AppButton.Primary
- AppButton.Secondary
- AppButton.Destructive

Rows:

- Widget.App.Row.WithSubtitle
- Widget.App.Row.TitleOnly
- Widget.App.Row.TextColumn
- Widget.App.Row.Title
- Widget.App.Row.Subtitle
- Widget.App.Row.Chevron
- Widget.App.Row.Icon (the leading 36dp icon piece, added July 18 2026;
  used by the first four Settings entries)

New shared style this feature must CREATE (owner-approved in chat,
July 18 2026):

- Widget.App.Row.ProfileImage

Widget.App.Row.ProfileImage is the leading profile-picture piece of a
canonical row. It is completely based on Widget.App.Row.Icon — same
formula, same slot: the first child of a Widget.App.Row.WithSubtitle or
Widget.App.Row.TitleOnly container, before the text column, vertically
centered by the row container exactly like the icon rows.

Define it in values/themes.xml as a child of Widget.App.Row.Icon that
overrides ONLY the size:

- parent: Widget.App.Row.Icon
- android:layout_width / android:layout_height:
  @dimen/row_profile_image_size (a new dimen, approximately 48dp)

Everything else (end margin, centerCrop scale type, the
no-baked-in-tint rule) is inherited from Widget.App.Row.Icon, so a
future change to the shared formula flows into both automatically —
while the two sizes stay independent variables: icons can be resized
without affecting profile pictures, and profile pictures without
affecting icons.

It bakes in no image content or tint — the actual picture is set per
instance. Every canonical row in this feature that shows a leading
profile picture must use this style; do not hand-place ad-hoc ImageView
attributes inside canonical rows, and do not use Widget.App.Row.Icon
directly for a profile picture.

The owner's separately supplied layout requirements for rows containing
Profile Images (the Widget.App.Row.ProfileImage specification above)
remain authoritative. The shared styles govern reusable geometry,
typography, colors, and theme behavior; they do not erase the approved
row-specific image placement.

The existing main Settings screen continues to use its existing
TileFragment pattern. Do not replace the main Settings tiles with row
styles as part of this feature.

Implementation rules:

1. Every ordinary action button introduced by this feature must use one
   of the three approved AppButton styles.

2. Do not duplicate button properties in individual layouts, including:

   - Corner radius
   - Text appearance
   - Font
   - Text size
   - Capitalization
   - Padding
   - Minimum height
   - Stroke
   - Background tint
   - Text color

3. AppButton.Destructive must use the centrally defined destructive style
   even though it currently resembles AppButton.Secondary (both are
   outlined today; Destructive is separately named precisely so it can be
   changed app-wide later without touching layouts). Do not hardcode a
   red color or create a local destructive-button variation unless the
   owner separately approves that app-wide change.

4. Every normal chevron row in Profile Image Settings must use either:

   - Widget.App.Row.WithSubtitle
   - Widget.App.Row.TitleOnly

5. A title-only row uses Widget.App.Row.TitleOnly. Do not insert an
   empty, invisible, or blank subtitle merely to reuse the subtitle-row
   layout.

6. A row with explanatory text uses Widget.App.Row.WithSubtitle and the
   shared Widget.App.Row.Title and Widget.App.Row.Subtitle styles.

7. Do not copy row geometry or typography into individual layouts,
   including:

   - Minimum height
   - Vertical padding
   - Title size
   - Subtitle size
   - Title color
   - Subtitle color
   - Chevron size
   - Chevron tint
   - Text-column spacing

8. Colors must be theme-driven. Use existing theme attributes and color
   roles such as:

   - ?attr/appRowTitleColor
   - ?attr/appRowSubtitleColor
   - Material theme color attributes already used by the app

   (Both appRow attributes are verified real: declared in
   values/attrs.xml and supplied by the theme and the palette overlay.)

   Do not hardcode light-theme or dark-theme colors into these layouts
   or Kotlin files.

9. Kotlin code must not overwrite styling that belongs to XML styles.

   Do not call code such as:

   - setBackgroundResource(...)
   - setBackgroundColor(...)
   - backgroundTintList = ...
   - setTextColor(...)
   - setPadding(...)
   - setting corner radii programmatically

   on normally styled buttons or rows unless the property is genuinely
   dynamic and cannot be represented by the shared style.

10. Dynamic state code may change state, visibility, enabled status,
    content, image, or selection. It must not rebuild the component's
    normal appearance.

11. When a disabled, selected, loading, or destructive state needs
    different colors, use a theme-aware ColorStateList, selector, shared
    style, or shared drawable. Do not place one-off literal colors in the
    activity or adapter.

12. Custom controls such as the rotation dial and crop overlay may have
    purpose-built XML styles or theme attributes because no existing
    shared component fits them. Their colors must still come from the
    active theme rather than fixed light/dark values.

13. Gallery image tiles may use a dedicated shared item style or drawable
    because they are a new component. Define the reusable appearance once
    and apply it to every gallery tile. Do not repeat the same tile
    attributes across several layouts.

14. Do not create near-duplicate styles such as:

    - AppButton.ProfilePrimary
    - ProfileImageSettingsRow
    - ProfileRowTitle
    - GalleryDeleteButton

    when an approved shared style already provides the required
    appearance.

15. When the approved shared style is genuinely missing a property needed
    by every instance, update the central style once rather than
    overriding every individual view.

16. Do not change the visual appearance of existing shared styles merely
    to make this feature easier to implement. Any app-wide style change
    requires separate owner approval.

17. The feature must work correctly in light mode, dark mode, and AMOLED
    mode. No screen may assume a specific background or text color.

    User-selectable palettes are NOT yet developed: the only palette
    overlay today is ThemeOverlay.Phosphor.Violet, which deliberately
    reproduces the app's current colors. The point of these rules is
    palette-readiness — every color in the new screens resolves through
    theme attributes and Material color roles, so future palettes can
    retheme them by redefining those attributes alone, with no layout,
    style, or code changes. Do not test against palettes that do not
    exist; do not add any palette machinery in this feature.

18. New layouts must support Android font scaling. Shared row and button
    styles must not be bypassed to force text into fixed dimensions.

19. Every new activity introduced by this feature
    (ProfileImageSettingsActivity, ProfileImagesActivity,
    ProfileImageFramingActivity) must call
    ThemeManager.getThemeManager().applyPalette(this) in onCreate BEFORE
    setContentView, following the app-wide pattern. The palette overlay
    is the runtime layer that guarantees custom attributes like
    appRowTitleColor/appRowSubtitleColor resolve; a screen that skips
    this call can crash inflating a canonical row (this exact crash hit
    CharactersActivity on July 18 2026 — see the comment above
    ThemeOverlay.Phosphor.Violet in values/themes.xml and that day's git
    history). Any new dialog or bottom sheet that inflates canonical rows
    must likewise be hosted in a palette-applied context.

STYLE REVIEW GATE

Before implementation is considered complete, perform a dedicated
XML-style review.

Verify:

- Every new ordinary button uses AppButton.Primary, AppButton.Secondary,
  or AppButton.Destructive.
- Every Profile Image Settings row uses Widget.App.Row.WithSubtitle or
  Widget.App.Row.TitleOnly.
- The owner-approved image-row geometry (Widget.App.Row.ProfileImage,
  overriding only the size of its Widget.App.Row.Icon parent) is
  preserved.
- Title-only rows contain no fake subtitle view or subtitle spacing.
- Shared row title, subtitle, text-column, and chevron styles are used
  rather than copied attributes.
- No new layout repeats the approved button or row styling attributes.
- No Kotlin code overwrites shared button or row backgrounds, colors,
  typography, padding, or corner shapes.
- No hardcoded light-theme, dark-theme, palette, or destructive colors
  were added.
- No near-duplicate feature-specific button or row styles were created.
- Gallery tiles use one shared reusable item treatment.
- Every canonical row with a leading profile picture uses
  Widget.App.Row.ProfileImage (never Widget.App.Row.Icon directly, and
  never ad-hoc ImageView attributes copied into row layouts), and the
  style overrides only the size of its Widget.App.Row.Icon parent.
- Every new activity calls ThemeManager.applyPalette before
  setContentView.
- Light, dark, and AMOLED are checked in the owner test build.
- Every color in the new screens resolves through theme attributes or
  Material color roles, so a future palette can retheme them without
  code changes or separate layouts.

Include the XML-style review results in the implementation report, naming
each new layout and the shared styles it uses.

FINAL UX CLARIFICATIONS

These nine requirements are binding. Each is also specified in full
detail in its own topical section of this plan (named in parentheses);
this list exists so the complete set is verifiable in one place.

1. The sticky bottom Upload New button is the only Upload New action in
   normal gallery mode. The empty All Images state shows its title and
   body but does not add a duplicate upload button. (Empty States)

2. In management mode, Select is available only when the current
   filtered results contain at least one deletion-eligible unused image.
   Do not allow entry into an empty selection mode. (Select
   Availability)

3. Permanent Profile Images use JPEG. Composite legitimate transparent
   source pixels over solid white before JPEG encoding so transparent
   PNG or WebP regions do not become black or undefined. (Transparent
   Sources)

4. Changing Default Shape immediately refreshes visible Profile Image
   previews. Chat, chat-list, Companion-selection, settings, and editor
   views use the new shape when rebound or resumed. No restart,
   re-upload, or reframing is required. (Shape Change Refresh)

5. All icon-only actions have accurate content descriptions and minimum
   48dp touch targets. (Accessibility and Layout)

6. Selection, In Use, Unused, unavailable, and disabled states are not
   communicated by color alone. (Accessibility and Layout)

7. Screen readers announce image usage state, selection state,
   unavailable files, fine-rotation value, and live selection count.
   (Accessibility and Layout)

8. Font scaling must not clip essential labels or actions. Select All
   Shown may move into the top-bar overflow menu when space is
   insufficient. (Accessibility and Layout)

9. Sticky bottom controls respect gesture and navigation-bar insets.
   (Accessibility and Layout)

IMPLEMENTATION PHASE ORDER

Phase 0: Repository Validation and Legacy Fallback Repair

Confirm current paths and call sites.
Repair legacy PNG/JPG avatar resolution (.png then .jpg, all three
display sites).
Add fallback regression tests.
Confirm the available database architecture.
Confirm the Flower/clover resource geometry for the alpha mask.
Evaluate a maintained crop library against the full requirements and the
build/CI constraints, then commit to exactly one implementation.

Phase 1: Storage and Catalog

Add byte SHA-256 support.
Add ProfileImageStore.
Add profile_images folder handling.
Add profile_images.db v1.
Add atomic save and deletion behavior.
Add reconciliation and validation.
Add framing-session cache management.

Phase 2: Identity Data Model and Migrations

Add Companion avatarRef.
Add GlobalPreferences keys.
Bump MemoryStore to v15.
Add image_ref columns.
Update meta.db_migration to 15.
Update record models and read/write paths.
Update codec and schema documentation.
Add migration and codec tests.

Phase 3: Shared Shape Rendering

Implement ProfileShapeTransformation.
Verify Flower, Circle, and true Square.
Keep generic and built-in icon styling unchanged.
Verify RecyclerView state clearing.

Phase 4: Framing Editor

Implement the chosen crop solution.
Add exact UI order.
Add fine rotation dial and numeric input.
Add EXIF orientation.
Add state restoration.
Guarantee no empty edges.
Produce 512 × 512 JPEG q92.
Add framing tests and manual matrix validation.

Phase 5: Profile Images Gallery

Implement assignment and management modes.
Implement exact top-bar, filter, grid, and bottom-button layout.
Add Upload New.
Add detail/View Usage bottom sheet.
Add selection mode.
Add deletion recheck and confirmation dialogs.
Add empty and unavailable states.

Phase 6: Profile Image Settings

Add the normal main Settings tile.
Create the Widget.App.Row.ProfileImage style (child of
Widget.App.Row.Icon) and @dimen/row_profile_image_size in
values/themes.xml and values/dimens.xml.
Add row-based Profile Image Settings screen.
Add Default User Image.
Add Default Shape.
Add Profile Images management entry.

Phase 7: Companion Integration

Update editor and persistence.
Make result handling lifecycle-safe.
Add assistant-side chat precedence.
Add chat-list precedence.
Add Companion selection-list image.
Add the per-chat avatar explanation text.
Verify rename survival.

Phase 8: My Persona and Roleplay Character Integration

Update My Persona editor and host.
Update Roleplay Character editor in user mode only.
Preserve imageRef through every save/edit route.
Add user-side chat precedence.

Phase 9: Documentation, Tests, and CI

Update CLAUDE.md and relevant feature/storage documents, including
correcting the stale claim that AppButton.Secondary is not yet defined
(the row-style variants are already documented there) and documenting
Widget.App.Row.ProfileImage beside Widget.App.Row.Icon.
Update the memory schema documentation.
Add unit and migration tests.
Perform the Style Review Gate and include its results, naming each new
layout and the shared styles it uses, in the implementation report.
Run Android Checks CI.
Prepare an owner test build.

Do not mark the work complete until the owner confirms the behavior on their
phone.

VERIFICATION

Storage

Every permanent image is exactly 512 × 512 JPEG q92.
Original sources never enter permanent Profile Images storage.
Identical encoded bytes reuse one file and one catalog record.
Byte hashing is deterministic.
File writes do not expose partial final files.
Missing files do not crash.
Invalid uncatalogued files are ignored.
Stale framing sessions are removed only after the stale threshold.

Gallery

All four assignment callers open the same gallery.
Existing images can be reused across identity categories.
Upload New is always in the defined bottom position.
Assignment mode has no management controls.
Management mode has Select in the top app bar.
Selection mode uses the defined contextual bar and bottom Delete Selected
button.
Filters are accurate.
Select All Shown selects only eligible unused images.
View Usage lists actual identity names.
Newly referenced images survive a stale deletion selection.
Removing one profile reference does not affect another profile.
An image becomes Unused only after its final reference is removed.
Single and bulk deletion remove the file and catalog record.
Select is unavailable whenever the current filtered results contain no
deletion-eligible unused image, so an empty selection mode cannot be
entered.
Empty states never show a second Upload New button while the sticky
bottom Upload New button is visible.

Framing

Crop window is always square and non-resizable.
Portrait, landscape, PNG, JPEG, WebP, HEIF/HEIC where supported, and
EXIF-rotated phone images load safely.
Drag, pinch, flip, 90-degree rotation, fine dial, numeric input, and reset
compose correctly.
Degree symbol is always shown.
90-degree rotation never resets fine rotation.
No empty crop edge can be saved.
Activity recreation restores the crop when restorable state and the source
cache exist.
Missing restored source exits safely without assignment.
Cancel saves nothing.
Done saves exactly one permanent library image or reuses an identical one.
Transparent or partially transparent source pixels flatten over solid
white in the final JPEG — never black or uninitialized color.

Migration

Existing version-14 memory databases migrate to 15.
meta.db_migration becomes 15.
Fresh installs include both image_ref columns.
Old backups without image_ref import cleanly.
New image_ref values round trip.
Every record constructor and save path preserves imageRef.
Companion rename preserves avatarRef.

Display

Assistant side:

Companion image
Legacy per-chat avatar
Built-in fallback

User side:

Roleplay Character image
My Persona image
Default User Image
Generic user icon

Missing files always fall through.
Generic and built-in icon styling remains unchanged.
RecyclerView recycling never displays the wrong identity image or stale
tint.
Legacy PNG and JPG per-chat avatars both display.
The per-chat avatar explanation text appears only while the active
Companion has an assigned and available picture, and hides otherwise.
A Default Shape change refreshes visible previews immediately and every
other display site on next bind or resume, with no restart, re-upload,
or reframing.

UI and Wording

Profile Image Settings uses row styles.
The main Settings entry matches the existing main-screen tile pattern.
Button and filter order matches this plan.
All interface labels use approved Title Case.
Questions and explanatory text use sentence capitalization.
My Persona and Roleplay Character are always capitalized exactly.
No "Your Picture" string remains.
No unapproved rounded Square is introduced.
No new Toast or Snackbar is introduced for this feature.

Accessibility

Every icon-only control has an accurate content description.
Interactive controls meet the 48dp minimum touch target.
Selected, In Use, Unused, unavailable, and disabled states are all
communicated by a non-color indicator, never color alone.
Screen readers announce selection state, In Use/Unused status, file
unavailability, the current fine-rotation value, and the live selection
count.
Normal Android font scaling never clips essential labels or actions;
Select All Shown moves to the top-bar overflow menu instead of
truncating.
Sticky bottom controls respect navigation-bar and gesture insets.

OWNER TEST BUILD

Test the complete flow on the owner's Android phone:

Set and remove Default User Image.
Set and remove Companion image.
Rename Companion and confirm its image survives.
Set My Persona image.
Set Roleplay Character image.
Verify precedence changes when identities change.
Reuse one image across multiple identities.
Remove one reference and confirm the others remain.
Filter All Images, In Use, and Unused.
Inspect actual View Usage names.
Delete one unused image.
Delete several unused images.
Try deleting an image that became used after selection.
Test framing with a real EXIF-rotated phone photo.
Test exact numeric degrees and decimals.
Test process/activity recreation.
Confirm no exposed crop corners.
Confirm old PNG and JPG assistant avatars still work.
Confirm the per-chat avatar explanation appears and hides correctly.
Confirm UI order and appearance are acceptable.

Not done until the owner approves the test build.

----------------------------------------------------------------------
ADDENDUM — owner rulings made in chat, July 19 2026 (mid Phase 5)
----------------------------------------------------------------------

These supersede the sections named wherever they differ. Read this
before starting Phase 6, and before touching deletion or the settings
screen in any later phase.

1. PERMANENT DELETION / CONFIRMATION WORDING — an in-use image is now
   deletable, not just an unused one (owner ruling: "people should be
   allowed to delete if they want to"). This overrides "For an in-use
   image: ... Do not provide permanent deletion." from IMAGE DETAIL AND
   VIEW USAGE. BUILT on Phase 5 (branch claude/profile-images-phase-5-
   s5qu2s): the Image Detail sheet's Delete Permanently action now also
   appears when the image is in use, behind a stronger confirmation
   naming every identity that uses it. Approved wording, verbatim, do
   not reword without asking again:

   Title: Are you sure you want to delete this?
   Body: It's currently in use by:
   <identity lines, one per line, same "Companion: Ash" / "My Persona:
   Explorer" / "Roleplay Character: Mira" / "Default User Image" format
   already used by View Usage>

   If you delete this, it will change to the default image.

   Buttons: Okay / Cancel

   The "will change to the default image" claim is only fully accurate
   once item 2 below is built (today, a Companion with no Global
   Default in place would fall to its old per-chat avatar or a plain
   built-in glyph, not a shared default). The owner was told this and
   chose to ship the wording now anyway, on the understanding that item
   2 makes it true. Do not water this wording down further without
   asking - it is intentional, approved, and not up for re-litigation
   ("This isn't a democracy").

   Bulk deletion (Select All Shown / Delete Selected) is UNCHANGED -
   still skips anything that became used since the gallery loaded, and
   still never shows an in-use image as selectable in Selection Mode.
   This ruling only opens a path for a SINGLE image, from the detail
   sheet, where the user has already been shown exactly who uses it.

2. NEW CONCEPT — Global Default Image (not yet built; this is now
   Phase 6+ scope, not merely "Default User Image" as REFERENCE STORAGE
   originally described it). One shared Profile Image, used as the
   ultimate fallback for BOTH sides of the app, not just the user side:

   - Assistant-side fallback order becomes: Companion image -> legacy
     per-chat avatar -> Global Default -> old built-in assistant glyph
     (now effectively unreachable, kept only as a final safety net).
   - User-side fallback order becomes: Active Roleplay Character image
     -> Active My Persona image -> Personal Default (see item 3) ->
     Global Default -> generic user icon (now effectively unreachable).

   Seeded out of the box from a repurposed BUILT-IN icon already in the
   app - CustomizeAssistantDialog's "gemini" preset
   (res/drawable/google_bard.xml, two overlapping sparkle/star shapes -
   the owner calls this "the double star"), NOT a user upload. The
   owner explicitly does not want to force any user to pick or upload
   anything for this to work ("users shouldn't have to select images").
   The owner considers today's shipped icon set "dumb" and plans to
   replace it later - do not treat this specific icon choice as
   precious; it must be easy to swap.

   Technical note for whoever builds this: google_bard.xml is a vector
   drawable with a theme-attribute fill color, normally tinted live at
   draw time (DrawableCompat.setTint(..., R.color.accent_900) in
   CustomizeAssistantDialog). There is no existing code path that
   rasterizes a builtin preset to a real bitmap. To seed it as a real
   Profile Image: render the vector to a 512x512 bitmap with a FIXED
   color baked in (use accent_900, matching how it already looks today)
   and save it through the normal ProfileImageStore.save() pipeline the
   first time it's needed - it should end up as an ordinary catalog
   entry, not a hardcoded special case, so it is viewable/replaceable
   like any other Profile Image. Store its hash as the Global Default
   reference (see item 4 for where that preference lives).

   When the user later changes the Global Default (owner ruling, July
   19 2026), the picker must offer ALL FOUR of CustomizeAssistantDialog's
   existing built-in presets as choices, not just the seeded "double
   star" one - chatgpt_icon.xml, google_bard.xml, perplexity_ai.xml,
   and assistant.xml (see StaticAvatarParser for how they're currently
   identified: avatarId "gpt" / "gemini" / "perplexity" / "speakgpt").
   Owner's words: "why take away options" - they consider the current
   art "dumb" but want every existing choice kept available alongside
   ordinary gallery photos, not narrowed down. Each would need the same
   vector-to-fixed-color-bitmap treatment as the seed icon before it can
   be a real, selectable Profile Image.

3. NEW CONCEPT — Personal Default (not yet built; Phase 6+ scope). A
   second, OPTIONAL user-side-only preference, distinct from the Global
   Default: the user may set their own default separately from the
   shared one. If unset, the user side falls through to the Global
   Default (item 2). This sits in the user-side chain between My
   Persona/Roleplay Character and the Global Default - see the updated
   order in item 2. This is effectively what REFERENCE STORAGE and
   APPROVED SCOPE called "Default User Image" / default_user_image_ref
   before this addendum; whoever builds Phase 6 must decide whether to
   keep that preference key name for the Personal Default or rename it,
   and must NOT reuse the same key for the new Global Default - they
   are two separate preferences now.

4. PROFILE IMAGE SETTINGS SCREEN — restructured (Phase 6+ scope,
   supersedes the flat three-row layout AND the "add one normal Settings
   TILE" instruction in that section and in the Phase 6 work-order
   entry). Owner's description, in their own words: a main-Settings
   entry labeled "Profile Image Properties", subtext "Profile image
   gallery and associated settings can be found here." Opens a screen
   with (at least):
   - A row into the gallery (management mode).
   - A row into "Default Images" - a screen or row group where the user
     sets the Global Default (item 2) and the Personal Default (item 3)
     separately.

   NOT A TILE (owner ruling, July 19 2026): "no more tiles. tiles are
   being slowly hunted to extinction." Because this entry lives on the
   MAIN Settings screen, it must use the row-with-leading-icon style
   already rolled out to the other main-screen entries (Characters, AI
   System Settings, Memory Manager, Roleplay in activity_settings.xml -
   see CLAUDE.md's "Shared chevron-row styles" section): a
   Widget.App.Row.WithSubtitle container with Widget.App.Row.Icon as the
   first child, then Widget.App.Row.TextColumn / Title / Subtitle /
   Chevron. Icon choice was left to whoever builds this ("pick an icon
   that seems like it would fit") - recommend res/drawable/ic_photo.xml
   (a plain picture-frame glyph, already in the app, distinct from
   ic_image_missing.xml so it can't be confused with the gallery's
   broken/missing-file state). Not binding if a better fit turns up.

   Whether Default Shape stays as a third row on this same screen was
   not revisited in this conversation - it was not removed, but Phase 6
   should confirm with the owner rather than assume either way. Exact
   row wording/subtitles for "Default Images" were not given verbatim
   and must be asked for in chat before shipping, per the standing
   wording-approval rule - do not invent them from this summary.

5. DEFERRED, NOT DESIGNED - do not build without further owner input.
   The owner wants it to eventually be possible to assign one of the
   shipped built-in icons (not just the Global Default) directly to any
   individual identity, independent of what the Global Default is set
   to. Her own words: "I don't know how we would set that up ... I
   don't really know the UI yet." Treat this as a noted future
   direction only. When it comes up, ask in plain chat rather than
   guessing a UI for it.

----------------------------------------------------------------------
ADDENDUM 2 — owner rulings made in chat, July 19 2026 (framing)
----------------------------------------------------------------------

These supersede the sections named wherever they differ.

6. THE PICTURE MAY BE SHRUNK SMALLER THAN THE CROP. This RETIRES the
   "No Empty Edges" rule (FRAMING SCREEN / CUSTOM MATRIX REQUIREMENTS /
   No Empty Edges / VERIFICATION - Framing). The owner could not shrink
   a picture below the point where it filled the square, and wants to.
   The editor no longer forces the crop to stay covered: zoom runs
   between a shrink floor (the picture's longer side down to ~20% of the
   crop) and a zoom-in ceiling (8x the cover scale). When the picture is
   smaller than the crop it is centred; the still-covered case keeps its
   old cover/pan behaviour. Do NOT restore the cover-only clamp. Built
   in ProfileImageTransform.clampParams (renamed from clampToCover).

7. THE FRAMING BACKGROUND IS BLACK, not white. This supersedes "composite
   transparent source pixels over solid white" (TRANSPARENT SOURCES,
   FRAMING OUTPUT). Both the area a shrunk picture does not cover AND
   transparent source pixels now flatten to BLACK, in FramingView's
   getResultBitmap. "It runs in better for right now" (owner). A
   user-pickable background colour is a possible LATER feature and is
   explicitly deferred - do NOT build it without the owner.

8. A ZOOM BAR was added ABOVE the image in the Framing screen (owner
   request) - a horizontal slider, twin of the fine-rotation dial below
   it, for precise sizing when the two-finger pinch is too fiddly. This
   adds a control the EXACT FRAMING CONTROL ORDER did not list; keep it.
   The pinch is unchanged and stays in sync with the bar. The screen-
   reader word for it ("Zoom", string framing_zoom) was chosen when
   added; the owner may change it.
