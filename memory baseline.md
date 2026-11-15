# Memory baseline

## Status

Step 0 is measured for all 86 samples. Results are recorded here, not in
`conversation_recall_plan.md`. No application code or pre-existing test
was changed.

The dataset contains 32 invented memories, 56 standalone messages, and
nine conversations: six with three turns and three with four turns, 86
search samples in total. No expected answer was changed after any run.

## How the vectors were made

All 118 vectors come from one run of `generate_vectors.py` with the app's
real q4 model (onnxruntime 1.29.0, tokenizers 0.23.3). Model files were
downloaded to a temporary folder and not added to the repository.

| Reference check | Measured | Reference |
|---|---:|---:|
| Related stored texts | 0.9304 | 0.9304 |
| Unrelated stored texts | 0.4371 | 0.4371 |
| Message against stored text | 0.6208 | 0.6208 |

All 123 inputs, including the five reference inputs, kept both markers
and held at most 512 token IDs; four inputs were cut.

An earlier run in another environment produced 106 of these vectors with
reference checks up to 0.0023 off. The owner chose to use this run for
all 118. Against that earlier run, three of the 74 earlier samples send
something different, because they sit at the search cut-off:

- `dictated_04` now wrongly sends `home_latch`.
- `conversation_01_turn_2` now wrongly sends `food_tea` beside `pet_tansy`.
- `very_long_03` no longer wrongly sends `health_wrist` or `home_leak`.

The totals are the same with either set of vectors.

## How results are counted

A correct match is a returned memory in that sample's `expect` list.
Every returned memory outside `expect` is incorrect. Every non-expected
memory is listed in `must_not`, so this equals the `must_not` count. A
missed match is an expected memory not returned. Conversations were
searched one turn at a time, independently, as the app does today.

## Totals

| Kind | Correct matches | Missed matches | Incorrect matches |
|---|---:|---:|---:|
| short | 10 | 0 | 3 |
| long_typed | 2 | 6 | 0 |
| long_dictated | 3 | 5 | 1 |
| dictated_bad_periods | 0 | 5 | 0 |
| very_long | 2 | 2 | 1 |
| accidental_word | 0 | 0 | 2 |
| nothing_relevant | 0 | 0 | 0 |
| two_subjects | 10 | 0 | 2 |
| conversation | 27 | 3 | 10 |
| **Total, 86 samples** | **54** | **21** | **19** |

Twelve turns are follow-ups that do not name their subject. Two of them
missed their memory when searched on their own: `conversation_07_turn_2`
and `conversation_09_turn_3`. The first turn of `conversation_04` also
missed its memory.

## Per-sample results

`baseline.json` stores the exact `found` and `wrongly_sent` ID lists.
This table also shows the missed expected IDs.

| Sample | Correct memory IDs | Missed memory IDs | Incorrect memory IDs |
|---|---|---|---|
| `short_01` | `pet_tansy` | — | — |
| `short_02` | `pet_brindle` | — | — |
| `short_03` | `pet_cobalt` | — | — |
| `short_04` | `health_knee` | — | `health_wrist` |
| `short_05` | `health_dental` | — | — |
| `short_06` | `work_archive` | — | `work_budget`, `work_password` |
| `short_07` | `family_ada` | — | — |
| `short_08` | `home_move` | — | — |
| `short_09` | `hobby_camera` | — | — |
| `short_10` | `plan_ferry` | — | — |
| `typed_01` | — | `pet_tansy` | — |
| `dictated_01` | — | `pet_tansy` | — |
| `typed_02` | — | `health_wrist` | — |
| `dictated_02` | — | `health_wrist` | — |
| `typed_03` | `work_budget` | — | — |
| `dictated_03` | `work_budget` | — | — |
| `typed_04` | — | `family_leon` | — |
| `dictated_04` | — | `family_leon` | `home_latch` |
| `typed_05` | — | `home_shelf` | — |
| `dictated_05` | — | `home_shelf` | — |
| `typed_06` | `hobby_chess` | — | — |
| `dictated_06` | `hobby_chess` | — | — |
| `typed_07` | — | `food_stew` | — |
| `dictated_07` | `food_stew` | — | — |
| `typed_08` | — | `plan_eclipse` | — |
| `dictated_08` | — | `plan_eclipse` | — |
| `bad_periods_01` | — | `pet_tansy` | — |
| `bad_periods_02` | — | `health_wrist` | — |
| `bad_periods_03` | — | `work_budget` | — |
| `bad_periods_04` | — | `family_leon` | — |
| `bad_periods_05` | — | `home_shelf` | — |
| `very_long_01` | — | `pet_cobalt` | — |
| `very_long_02` | `family_rafi` | — | — |
| `very_long_03` | `food_sesame` | — | `home_latch` |
| `very_long_04` | — | `plan_concert` | — |
| `accidental_01` | — | — | — |
| `accidental_02` | — | — | `plan_race` |
| `accidental_03` | — | — | — |
| `accidental_04` | — | — | — |
| `accidental_05` | — | — | `pet_tansy` |
| `accidental_06` | — | — | — |
| `accidental_07` | — | — | — |
| `accidental_08` | — | — | — |
| `nothing_01` | — | — | — |
| `nothing_02` | — | — | — |
| `nothing_03` | — | — | — |
| `nothing_04` | — | — | — |
| `nothing_05` | — | — | — |
| `nothing_06` | — | — | — |
| `nothing_07` | — | — | — |
| `nothing_08` | — | — | — |
| `two_01` | `hobby_camera`, `pet_tansy` | — | — |
| `two_02` | `health_dental`, `plan_ferry` | — | — |
| `two_03` | `family_ada`, `family_leon` | — | — |
| `two_04` | `hobby_pottery`, `home_shelf` | — | `health_wrist` |
| `two_05` | `food_tea`, `work_password` | — | `food_bread` |
| `conversation_01_turn_1` | `pet_tansy` | — | — |
| `conversation_01_turn_2` | `pet_tansy` | — | `food_tea` |
| `conversation_01_turn_3` | `hobby_camera` | — | — |
| `conversation_02_turn_1` | `family_leon` | — | — |
| `conversation_02_turn_2` | `family_leon` | — | `work_password` |
| `conversation_02_turn_3` | `food_tea` | — | — |
| `conversation_03_turn_1` | `plan_ferry` | — | — |
| `conversation_03_turn_2` | `plan_ferry` | — | — |
| `conversation_03_turn_3` | `home_shelf` | — | — |
| `conversation_04_turn_1` | — | `family_ada` | — |
| `conversation_04_turn_2` | `family_ada` | — | `work_budget` |
| `conversation_04_turn_3` | `pet_brindle` | — | — |
| `conversation_05_turn_1` | `work_budget` | — | `work_archive` |
| `conversation_05_turn_2` | `work_budget` | — | — |
| `conversation_05_turn_3` | `health_dental` | — | — |
| `conversation_06_turn_1` | `pet_cobalt` | — | — |
| `conversation_06_turn_2` | `pet_cobalt` | — | — |
| `conversation_06_turn_3` | `plan_eclipse` | — | — |
| `conversation_07_turn_1` | `pet_brindle` | — | — |
| `conversation_07_turn_2` | — | `pet_brindle` | `hobby_camera` |
| `conversation_07_turn_3` | `pet_brindle` | — | `family_leon` |
| `conversation_07_turn_4` | `family_ada` | — | — |
| `conversation_08_turn_1` | `hobby_spring` | — | — |
| `conversation_08_turn_2` | `hobby_spring` | — | — |
| `conversation_08_turn_3` | `hobby_spring` | — | — |
| `conversation_08_turn_4` | `pet_cobalt` | — | `work_password` |
| `conversation_09_turn_1` | `plan_concert` | — | — |
| `conversation_09_turn_2` | `plan_concert` | — | `work_archive`, `work_budget`, `work_password` |
| `conversation_09_turn_3` | — | `plan_concert` | — |
| `conversation_09_turn_4` | `health_dental` | — | — |

## Step 1: reading limit and embedding queue

Step 1 does not change any result above. All 32 test memories fit inside
the previous 512-token limit, so their vectors and every per-sample result
stay the same. The four `very_long` messages are still cut at 512 tokens,
because the message being sent keeps that limit. `baseline.json` is
unchanged and the test set passes against it.

**How these were measured.** The app's own compiled classes ran with the
real q4 model and ONNX Runtime 1.20.0 (the app's version) on a 4-core
cloud machine in the build environment, not on a phone. Model files were
downloaded to a temporary folder and not added to the repository.

| Check | Result (build environment) |
|---|---|
| Token counts against the reference tokenizer (all 118 test-set inputs with their prefixes, a 4,653-token text, and an accent, emoji and ligature text) | 120 of 120 match |
| A 512-token memory read under the 512 and 2,048 limits | Identical vector |
| A 2,048-token memory: cut at 512 compared with read in full | Cosine 0.927, so the cut vector was missing part of the memory |
| Read time, 128 tokens | 117 ms |
| Read time, 512 tokens | 542 ms |
| Read time, 1,024 tokens | 1,465 ms |
| Read time, 2,048 tokens | 4,075 ms |
| Live message waiting behind a running 2,048-token background read, without stopping it | 3,957 ms average, 4,032 ms longest |
| The same wait when the background read is stopped | 1,287 ms average, 1,346 ms longest |
| Time for a stop to take effect, 20 stops at random points in the read | 443 ms average, 1,245 ms longest |
| Vectors from the same model session after each of the 20 stops | 20 of 20 identical to an uninterrupted read |

ONNX Runtime checks for a stop between the model's internal steps, so a
stop waits for the step that is running. Stops requested 100 ms into a
read took about 1.3 seconds, longer than stops requested later, which
suggests the first step of a read is long. Which step that is was not
measured.

**Still to measure on the owner's Pixel 8:** the read time of a
2,048-token memory, the memory used during that read, the wait of a live
message behind it, and how long a stop takes. The phone will be slower
than the build environment.

## Step 2: long messages searched in sections

A message longer than 80 words is now also searched in overlapping
80-word sections, after the whole message. The whole-message search is
unchanged: on every sample it returns the same memories in the same order
as before, and the test checks this. Memories found only through a section
are placed after the whole-message matches.

### Result on this test set

| Kind | Correct before | Correct now | Missed now | Incorrect before | Incorrect now |
|---|---:|---:|---:|---:|---:|
| long_typed | 2 | 7 | 1 | 0 | 0 |
| long_dictated | 3 | 7 | 1 | 1 | 1 |
| dictated_bad_periods | 0 | 4 | 1 | 0 | 0 |
| very_long | 2 | 4 | 0 | 1 | 1 |
| All other kinds | 47 | 47 | 3 | 17 | 17 |
| **Total, 86 samples** | **54** | **69** | **6** | **19** | **19** |

Fifteen of the 18 memories missed in long messages are now found, and no
sample sends an incorrect memory it did not send before. This is what this
test set of 32 invented memories shows. It is not a guarantee that no
wrong memory is ever sent from a real memory library.

Every changed sample, with the section that found the memory:

| Sample | Memory now found | Section | Meaning match | Shared words |
|---|---|---|---:|---|
| `typed_02` | `health_wrist` | 4 of 4 | 0.57 | brace, typ, wrist |
| `dictated_02` | `health_wrist` | 4 of 4 | 0.56 | brace, typ, wrist |
| `typed_04` | `family_leon` | 4 of 4 | 0.54 | birthday, leon, need |
| `dictated_04` | `family_leon` | 4 of 4 | 0.52 | birthday, leon, need |
| `typed_05` | `home_shelf` | 2 of 4 | 0.50 | bookshelf, dowel, miss, number, part |
| `dictated_05` | `home_shelf` | 2 of 4 | 0.49 | bookshelf, dowel, miss, number, part |
| `typed_07` | `food_stew` | 3 of 4 | 0.54 | container, fit, lentil, lid, square, stew |
| `typed_08` | `plan_eclipse` | 2 of 4 | 0.54 | astronomy, hall, lecture, rowan, seat, ticket |
| `dictated_08` | `plan_eclipse` | 2 of 4 | 0.54 | astronomy, hall, lecture, rowan, seat, ticket |
| `bad_periods_02` | `health_wrist` | 4 of 4 | 0.57 | brace, typ, wrist |
| `bad_periods_03` | `work_budget` | 3 of 4 | 0.58 | budget, equipment, ivo, need, quote, request, scanner |
| `bad_periods_04` | `family_leon` | 3 of 4 | 0.52 | birthday, entrance, leon, need, restaurant, use |
| `bad_periods_05` | `home_shelf` | 2 of 4 | 0.49 | bookshelf, dowel, miss, number, part |
| `very_long_01` | `pet_cobalt` | 12 of 13 | 0.57 | aquarium, cobalt, heater, keep |
| `very_long_04` | `plan_concert` | 12 of 13 | 0.51 | chamber, concert, music, november, paper, require, ticket |

Shared words are shown as keyword search compares them: common words
dropped and word endings removed, so "typing" appears as "typ".

Still missed: `pet_tansy` in `typed_01`, `dictated_01` and
`bad_periods_01`. No 80-word section of those messages passed the
relevance check for that memory, under the plan's original rule or the
chosen one.

`baseline.json` records the 15 improvements. Each improved sample also
lists the memory under `found_by_section`, so the test can still check the
whole-message search on its own against what it found before Step 2.

### The rule for when a section's match counts, and why

The plan's original rule let a section pass the same meaning check as a
whole message. Measured here with 60-word sections and at most 8 sections,
it found 16 of the 18 missed memories and sent 29 more incorrect
memories. Nearly all of the incorrect ones were sections loosely similar
in meaning to an unrelated memory (meaning match 0.41 to 0.46) with no
word in common. The owner approved a stricter rule for Step 2. A section's
match counts only when all of these hold:

- the memory passes the section's normal relevance check, by meaning or
  by keywords;
- its meaning match with the section is at least 0.48;
- the section and the memory share at least one meaningful word. Common
  filler words are dropped and word endings are normalized first, by the
  same keyword rules the whole-message search already uses.

**The tradeoff.** Requiring a shared word means a section that describes
a memory in entirely different words does not find it. That was accepted
because a wrong memory is worse than a missed one.

**The 80-word size, eight-section cap, and 0.48 threshold are all provisional.** It was chosen from this test set, where the
correct section matches scored 0.49 to 0.58. It has not been validated on
any real memory library and may need adjusting.

### Section size, section limit, and minimum meaning match

Additional correct and additional incorrect memories on the 25 long
messages, with the approved rule. "All" means no section limit.

| Section size | Minimum | Limit 6 | Limit 8 | All |
|---|---:|---|---|---|
| 40 words | 0.48 | +12 / +1 | +15 / +1 | +15 / +1 |
| 60 words | 0.48 | +15 / +2 | +15 / +2 | +15 / +2 |
| **80 words** | 0.44 | +14 / 0 | +15 / 0 | +15 / 0 |
| **80 words** | 0.46 | +14 / 0 | +15 / 0 | +15 / 0 |
| **80 words** | **0.48** | +14 / 0 | **+15 / 0** | +15 / 0 |
| **80 words** | 0.50 | +12 / 0 | +13 / 0 | +13 / 0 |
| **80 words** | 0.52 | +11 / 0 | +11 / 0 | +11 / 0 |
| 100 words | 0.48 | +11 / 0 | +11 / 0 | +12 / 0 |

- **80-word sections** were chosen because they were the only size that
  found 15 with no additional incorrect memory. Smaller sections added
  incorrect memories; 100-word sections found fewer.
- **At most 8 sections** are read for one message. Eight found as many as
  reading every section; six found one fewer. A 500-word message has 13
  sections, so the sections holding the rarest words in the memory
  library are read first. Both `very_long` improvements were found in
  section 12 of 13 this way.
- **0.48** sits at the top of the range that kept all 15 (0.44 to 0.48
  gave the same result with 80-word sections). The highest value in that
  range was chosen because the smaller section sizes sent more incorrect
  memories as the minimum was lowered.

### Time

The original section search used a provisional 1,500 ms soft allowance. It checked elapsed time only before each read, so one queued/model operation could finish past it. It was not a maximum sending delay. The repair adds bounded queue waiting and cooperative model termination, described below; phone performance remains unmeasured.

| Check | Result (build environment) |
|---|---|
| Length of the 136 test-set sections as the model reads them | 89 tokens average, 100 longest |
| Time to read one section | 114 ms average, 141 ms longest |
| A 150 to 300 word message (4 sections) | about 460 ms added |
| A message with 8 or more sections | about 910 ms added |

These times come from the real q4 model on a 4-core cloud machine with
onnxruntime 1.29.0 in Python, two threads, not from the app on a phone.

**Still to measure on the owner's Pixel 8:** the added sending time for a
long message. The Memory Search Test line "Additional section search" measures section preparation, reads and matching, including queue waits; it does not include the full-message search, report-only diagnostic scoring, prompt assembly, or provider latency. Those must be included when measuring total sending latency.

## Step 2 repair audit

Base: PR #200 merge `693cca9f7a56a13753fe18843a2b1c82b90681b3`, fetched from `origin/main`.
Work branch: `repair/step2-recall-audit`. Implementation, emulator evidence and verification limits are recorded below. Final-commit CI, code review and merge status are tracked in [PR #201](https://github.com/SoulPhosphor/speak-gpt/pull/201). Step 3 is outside this repair.
Historical measurements and fixtures above are preserved; their Python timings are not Android measurements.

| Item | Finding / repair | Verification state |
|---|---|---|
| 1 | Section rows need the actual section threshold and complete gate | Shared rule and runtime threshold carried into report; automated coverage added |
| 2 | Failed candidates were discarded; diagnostic evaluation now retains searched section candidates | Shared rule and runtime threshold carried into report; automated coverage added |
| 3 | Attempted counts included failed reads without any indication | Processing, deadline and repeated-error state separated; automated coverage added |
| 4 | Timing string always used plural | Android plural resources and rendering assertions added |
| 5 | Keyword-only fragment is embedded inside a larger sentence | Actual summary formatter extracted for testing; no added internal period |
| 6 | Assistant incomplete-index wording was obsolete | Complete-set keyword fallback described explicitly; preparation sentence conditional |
| 7 | Enforcer had an obsolete no-model diagnostic | Removed the duplicate pre-search note; the actual retrieval summary identifies keyword-only operation |
| 8 | Background failures referred to the sent message | Background/live error contexts separated |
| 9 | Each section failure wrote another log | Aggregate failures with section IDs; stop after consecutive identical failures; unit coverage added |
| 10 | Typed and voice sending wait synchronously for retrieval | Both enter the same typed-turn funnel; final request paths await Enforcer on IO; encrypted-store/Enforcer integration coverage added |
| 11 | Allowance only checked before reads | Deadline bounds queue waiting and requests cooperative ONNX termination; caller retains model ownership until unwind; exact hard deadline cannot be guaranteed |
| 12 | Six-word excerpt unbounded by character count | Six words retained, capped at 96 Unicode code points; numbering preserved |
| 13 | Section-only +3 must obey Maximum Memories | Existing Enforcer policy retained; final assembly covered by Android integration tests |
| 14 | Size, cap and threshold selected on evaluation data | All provisional; independent real-model set measured at 4/5/0; no retuning |
| 15 | Status, G.10, G.13b, G26 and Build record contradict implementation | Status, matching rule, approved wording, resolved decisions and provisional parameters reconciled |
| 16 | Prior unfinished commit is merged history | History preserved; no unfinished repair commit created |
| 17 | Two prohibited generated footers in PR #200 | Removed from reply 4572452629 and comment 6874288698; review evidence retained |
| 18 | Prior subscription may be outside this connection | No PR #200 task found among accessible automations; GitHub subscription mutation is not exposed; unrelated tasks unchanged |
| 19 | Checks were not repeated after generator correction | Original fixtures reproduced byte for byte offline; final local checks, CI and final review recorded below |
| 20 | Screen never launched | CI emulator resource/screen-state tests passed; downloaded match/result screenshot visually inspected; local software emulator remained impractical without KVM |
| 21 | Retrieval-to-prompt integration missing | Actual encrypted-store/Librarian/Enforcer prompt integration passed on the emulator; runtime-specific observations and per-message regression guards described below |
| 22 | Python ORT 1.29 timings were presented as performance evidence | App uses Android ORT 1.20.0; all 313 token references match, native output differences measured, emulator queue/model/total timing recorded below; physical-phone timing remains pending |

### Additional independently established issues

- Near-misses could remain hidden under the ten-row whole-message diagnostic ranking. Unsent diagnostic rows now prioritize qualified exclusions and then the fewest failed section requirements, within the existing row limit. Sent-memory ranking is unchanged.
- Report attribution previously discarded qualifying section candidates beyond its pool cap and could call a relevance failure a memory-limit failure. Diagnostic matches are separate from the selection pool; regression coverage added.
- The summary called a failed message embedding "model unavailable" even when a model was loaded. Separate unavailable-model, failed-message, and no-ready-embedding paths.
- The summary called its bounded candidate-pool count “passed,” although that was not the total passing the relevance gate. The label is now “candidates.”
- Section reads swallowed cancellation/interruption as ordinary embedding failures. Propagate cancellation and preserve interrupt status.

### Validation record

Initial checks were blocked by Gradle network routing, an absent Android SDK and a Java runtime without a compiler. These environment blockers were resolved by provisioning the requested Gradle distribution, Android SDK and a full JDK locally. Robolectric downloads were routed through the available Maven mirror. Earlier runs passed all three required tasks. Two intermediate combined-task repeats lost the Gradle daemon during APK shrinking, and an overlapping attempt hit the shared Gradle cache lock. Final packaging tasks are therefore run serially in fresh 3 GB Gradle processes; this is a local build-environment adjustment, not a repository build-setting change.

**Initial repair implementation, before later instrumentation corrections:** the local JVM run executed **3,652 tests, zero failures/errors/skips**. `assembleBeta` subsequently passed in a fresh process (7m 13s). `aapt` verified package `com.soulphosphor.phosphorshines.beta`, version `4.38-stable-beta`, and label `Phosphor Shines Beta`. The local `assembleDebugAndroidTest` invocation passed in a fresh process (6m 5s). The local `testDebugUnitTest` task passed before those builds; a separate final invocation also completed successfully (1m 6s, reusing the unchanged final-source test result). No code changed between these checks. All three required local tasks passed for that implementation. Later fixture/build corrections, emulator measurements, screenshot inspection and fresh-environment limitations are recorded below. Final-commit CI/review status is tracked on PR #201. Its original recall benchmark remains **69 correct / 6 missed / 19 incorrect**. The independent fixed-parameter evaluation remains **4 / 5 / 0**, with no additional section recoveries.

Offline regeneration used Python ONNX Runtime 1.29.0 with the recorded q4 files inside a network namespace without an outbound route. Both generated fixtures compare byte for byte with the original committed files: 118 whole-message/stored vectors and 136 section vectors. Reference checks were 0.9304, 0.4371 and 0.6208; all 123 tokenizer/length checks passed, including four truncated inputs.

### ONNX telemetry opt-out

The owner explicitly requested disabling all Microsoft ONNX telemetry during this repair. All application ONNX environment access (saved-memory embeddings and Silero voice activity detection) now goes through one environment that calls the actual ONNX Runtime Android 1.20.0 `setTelemetry(false)` API before returning an environment for session creation. Failure to apply the opt-out propagates into existing model-unavailable fallback. The environment also sets `ORT_DISABLE_TELEMETRY=1` before calling into ONNX Runtime. Both Python fixture tools set that startup variable **before importing** ONNX Runtime, then apply the API opt-out immediately after import and before model sessions. Microsoft's [current privacy documentation](https://github.com/microsoft/onnxruntime/blob/main/docs/Privacy.md) explains that the API alone may allow a minimal initialization event, whereas the startup variable prevents the 1DS uploader, events and persistent identifier from being created. The shipped [1.20.0 privacy documentation](https://github.com/microsoft/onnxruntime/blob/v1.20.0/docs/Privacy.md) says telemetry was implemented only on Windows; the newer Python runtime has a broader provider. No telemetry-enabling call is introduced. The startup guard also protects a future runtime upgrade; no runtime version change is made here.

The earlier fixture regeneration attempt was stopped by automatic approval review for an attempted Microsoft telemetry connection despite the existing generator's `disable_telemetry_events()` call. That attempt produced no regenerated fixture. The initial blocked attempt is preserved in this record; the later network-isolated check below establishes reproducibility without external disclosure. Native opt-out APIs are not a packet-capture proof of zero network activity; model-download traffic is separate from telemetry. A materially safer regeneration was subsequently run in a new Linux network namespace with no outbound route (a socket check returned ENETUNREACH), using previously downloaded model files and ONNX Runtime 1.29.0. It regenerated all 136 section vectors for 25 messages; `cmp` confirmed byte-for-byte equality with the committed fixture. No Microsoft telemetry connection could leave that isolated namespace.

### Additional outbound-traffic audit requested during repair

- **Confirmed SDK metrics:** `com.google.mlkit:language-id:17.0.6` and `com.google.mlkit:text-recognition:16.0.1` are dependencies. `DefaultPromptsParser`, auto-language TTS in `ChatActivity`, and `PdfFallbackExtractor` use them. Google's [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and [privacy terms](https://developers.google.com/ml-kit/terms) describe device/app information, installation identifiers, utilization/performance metrics, and detected-language metrics. Google says input text/images and resulting content are processed on-device rather than uploaded. The merged manifest includes ML Kit initialization and Google Data Transport components. No supported collection opt-out has yet been established for these exact SDKs; do not invent an internal preference or silently remove OCR/language detection.
- **Automatic public price lookup:** `TokenPricingCatalogClient.loadFirstParty` downloads OpenRouter's public model catalog even for first-party OpenAI, Anthropic and some xAI price lookups. The request has no API key, prompt, or message body; normal connection metadata is still disclosed. Matching the requested model happens locally after fetching the full catalog. Cache TTL is six hours within the process.
- **Other expected network work:** configured chat/image/speech services, optional Memory Assistant background provider calls, model/voice catalogs and pricing documentation, and user-requested model downloads. Public TTS documentation requests are built without credentials; `OkHttpTtsExecutor` does not add credentials to them.
- **Crash handling inspected:** `CrashHandler` displays/copies and stores reports locally through `Logger`; no automatic upload path was found in that code. Manually copied crash reports include device information and Android ID.
- **Disclosure gap:** the built-in `assets/www/privacy.html` and light counterpart contain only an OpenAI terms link; they do not describe ML Kit metrics or auxiliary catalog requests.

This is a source/dependency audit, not a complete packet-capture verification of every third-party binary. Apart from the explicitly requested ONNX telemetry opt-out, these findings do not change unrelated product behavior during the Step 2 repair.

Actual app tokenizer parity was checked against independent Python-tokenizer references for all 254 stored-memory, message and section inputs: every token ID matched, including asymmetric prefixes, template markers and truncation. This verifies preprocessing compatibility; it does not by itself establish Android inference parity or phone timing.

Model artifacts used for the network-isolated reproducibility and tokenizer checks (no model artifact is committed):

| Artifact | SHA-256 |
|---|---|
| model_q4.onnx | ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043 |
| model_q4.onnx_data | 599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02 |
| tokenizer.json | 4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47 |

### Sending, queue and cancellation audit

Typed sends call `prepareTypedTurn` from the editor and send button; hands-free transcription calls that same method. It freezes the stored message before generation. Both the prepared-request and legacy request builders await `Enforcer.assembleTurnWithAttribution` on `Dispatchers.IO` and add its returned prompt as a system message. The Librarian's new shared `SavedMemorySearch` boundary searches the whole message first, then invokes section preparation at most once for that retrieval. Test-screen rescoring reuses the prepared vectors and does no model work.

The embedding queue is synchronous and FIFO within LIVE priority. It puts LIVE ahead of waiting BACKGROUND work and requests termination of a running BACKGROUND inference. A live read can also wait behind earlier LIVE reads; the older “at most one running read” comments are not a guarantee against contention from peer live requests. Section queue waits now expire within the remaining allowance. Inference termination remains cooperative through ONNX RunOptions; the queue is released only after the native call unwinds. Tokenization and native unwind can exceed the allowance, so no hard sending-latency promise is made. All background preparation continues using the existing single-flight scheduler and existing background priority. No result is injected asynchronously into a different turn.

The timing line measures section preparation, embedding and matching, with queue waiting, model-processing and scoring shown separately. Model-processing time includes tokenization, inference and vector postprocessing inside `EmbeddingModel.embed`; it is not a CPU-only kernel measurement. The shared live/test search now measures section relevance scoring too and includes it in the total. Whole-message search, report-only near-miss diagnostics, Enforcer filtering and provider latency remain outside that line. A section that finishes after its deadline does not add a result; already completed sections and whole-message results remain usable. Slow hardware can therefore search fewer sections than the original fixture-backed, zero-time benchmark.

### Independent regression scope

Six new independent contract tests use a new weaving/astronomy/spice setting, including punctuation-free dictation, details near the beginning/middle/end, unrelated topics, semantic paraphrases without shared vocabulary, accidental lexical overlap, relevance-gate rejection despite overlap/similarity, keyword fallback, and rendered diagnostics. They use synthetic vectors to isolate the approved contract; they are **not independent empirical model-accuracy measurements**. No section size, cap, threshold or baseline answer was retuned against them.

Additional confirmed lifetime defect: `Librarian.invalidateModel()` called `OnnxEmbeddingModel.close()` directly while a queued/native read could be active. Inspection of the shipped ORT 1.20 Java session bytecode confirmed that `run` and `close` do not serialize that lifetime for the caller. `ModelReadLifecycle` now retires the model immediately, rejects later reads, and defers native close to the last active reader, exactly once. No asynchronous result or additional UI wait is introduced. A replacement can briefly coexist with a retiring model until that read unwinds; native cancellation and real-device memory behavior remain relevant to hardware validation. Latch-based concurrency tests exercise running-read retirement and failure cleanup.

### Independent model evaluation (fixed parameters, no retuning)

A separate nine-memory, ten-message gold set was written before generating its real q4 vectors. Its topics and wording are new; it was not used to select section size, cap or threshold. The original 32-memory/86-sample set and its expected answers are unchanged. Generation ran offline in the network-isolated environment using the same model artifact hashes and Python ORT 1.29.0. These are accuracy measurements from fixture vectors, not Android performance measurements.

| Measure | Whole message | With section search |
|---|---:|---:|
| Correct matches | 4 | 4 |
| Missed matches | 5 | 5 |
| Incorrect matches | 0 | 0 |

Section search recovered none of the five misses in this set. This is weaker generalization than the original 69/6/19 result and reinforces that all parameters remain provisional. The approved rule was preserved rather than retuned against these new cases. Zero incorrect matches here is a measurement on this set, not a universal guarantee. The observed snapshot is separate from the frozen gold answers; future regressions are checked against it without redefining correctness.

| Case | Gold memory IDs | Retrieved IDs |
|---|---|---|
| beginning | pet | pet |
| middle_paraphrase | loom | loom |
| ending | ferry | None |
| several_topics | pet, sky | sky |
| shared_name_wrong_subject | spice | None |
| no_shared_vocabulary | pet | None |
| misleading_word | None | None |
| nothing_relevant | None | None |
| bad_periods | ferry | None |
| different_words | bike | bike |

Additional confirmed preparation cost: the original `readingOrder` ranked every section, then discarded everything after eight. For a very long message this performed quadratic prioritization work outside the read allowance. Runtime preparation now stops choosing as soon as the existing eight-section cap is filled, preserving exactly the original order's prefix. The full ordering remains available for comparison tests; a new regression test verifies prefix equality on a long message. Size, cap, relevance gate and ordering policy are unchanged.

The existing Android Checks workflow runs the Step 2 encrypted-store, prompt-assembly, logging, plural-resource and screen-state tests on its x86_64 emulator and archives the synthetic result-card screenshot. The CI emulator temporarily downloads the pinned q4 validation model, verifies its hashes, and includes actual Android 1.20.0 vector/decision comparison and timing. Initial setup attempts failed before inference; subsequent measurements are recorded below. Wi-Fi and mobile data are disabled during the Step 2 class and restored afterwards. Model files are kept out of artifacts; the real-model test uses an isolated internal directory and removes it after closing its session. Public model inputs are supplied under the app UID in private storage. Model artifacts are temporary CI inputs, not distributed in the application or uploaded in test artifacts. The original and independent fixtures are bundled only in the instrumentation APK for runtime comparison.

Section scores immediately below the minimum use extra decimal precision when normal two-decimal formatting would show an apparently equal threshold (for example, 0.479 is shown as 0.4790 rather than 0.48). This changes diagnostic formatting only; matching still uses the original unrounded value and approved threshold. Android screen coverage includes this boundary-display case.

Diagnostic data retains qualification/exclusion reasons beyond the candidate-pool cap, while extra on-screen cards remain restricted to the existing bounded candidate pool plus the closest-row allowance. A 200-match regression case prevents comprehensive diagnostic evaluation from accidentally rendering an unbounded list of cards.

### First Android CI execution

Commit `38b43f33`, Android Checks run 43589400696: the standard test/build job passed (3,652 tests in each of Debug, Beta and Release, zero failures/errors/skips; debug/Beta packaging, Beta identity and instrumentation compilation). The emulator executed nine new Step 2 tests: seven passed, including real retrieval-to-prompt injection, whole/manual-memory limits, repeated section failure handling, keyword fallback, JNI telemetry opt-out, Android plurals and screen states. The synthetic screenshot was downloaded and visually inspected: card order, wrapping, section 4-of-7 attribution, 0.55 similarity versus required 0.48, separate relevance-gate details, normalized shared words and rejected-candidate reasons were visible. This is an emulator debug screen, not a physical-phone Beta inspection.

Two tests failed. The background-context test inspected the complete accumulated encrypted log, whose filename cache spans the test contexts, so an earlier valid live-search entry caused its negative assertion. It now asserts only entries appended by the operation under test, without changing production wording or expected behavior. The real-model test could not read the shell-supplied external model files, despite the workflow's successful push and hash verification. The first follow-up attempted the instrumentation shell's file pipe; that also failed, as recorded below. The final fixture uses app-private provisioning, verifies all three hashes before model creation, and removes temporary runtime files even after setup failure.

Follow-up fixture corrections: all three required local tasks passed again after the final test changes (`testDebugUnitTest`: 4m 8s; `assembleBeta`: 7m 18s; `assembleDebugAndroidTest`: 6m 38s). Beta compilation was prepared in a separate Gradle process to keep compiler and shrinking memory peaks separate. No production matching or diagnostic code, historical gold answers, model parameters or vector fixture changed during this follow-up. Android re-execution was the next gate at that point; subsequent runs are recorded below.

### Android model provisioning correction

PR #201 commit `b43ea53`, Android Checks run 43594131058: the unit/build job passed, including Debug and Beta packaging, Beta identity and instrumentation compilation. Eight of nine Step 2 Android tests passed. The remaining real-model test failed before inference: the instrumentation shell copy returned an empty `model.onnx`, caught by the unchanged SHA-256 assertion. Therefore this run does not establish Android vector parity or model latency. Codex completed its review of that commit with no findings.

The workflow now streams the pinned, host-hash-verified model inputs through `adb shell -T run-as` directly into app-private storage as the app UID, before instrumentation starts. It compares hashes from that storage against the host files. The test reads those private inputs directly, copies them to its isolated runtime directory, and retains all three original hash assertions. The external-storage shell-copy fallback is removed. Cleanup removes the supplied private inputs after the class. Production loading, telemetry controls, matching rules, fixtures and expected answers are unchanged.

Local validation for this correction: workflow YAML parses; every workflow shell step passes `bash -n`; a shell-boundary simulation preserved binary bytes (including NUL and CR/LF) and passed the host/device hash comparison; `git diff --check` passes. A fresh local Gradle invocation stopped before execution because the distribution download returned `Network is unreachable`; this environment also lacks an Android SDK. No local Android or Gradle test pass is claimed for this correction. At that point, CI, the emulator suite and a fresh review were still required; subsequent outcomes are recorded below.

Run 43628720653 at `d5560f9` caught a further provisioning prerequisite: the repository explicitly sets Debug APKs non-debuggable, so Android rejected `run-as` before file transfer. The existing `recoveryInstrumentationAbi` flag now enables debugging only for the disposable emulator fixture, alongside its already test-only ABI and minification settings. Without that flag, normal Debug and Beta remain non-debuggable. CI asserts both the emulator Debug APK's debug flag and the normal Beta APK's non-debuggable identity. Production behavior and model hash checks are unchanged. Codex reviewed `d5560f9` with no findings; the build-configuration correction was subsequently reviewed and exercised at `fa32b1b`, as recorded below.

Run 43629503476 at `fa32b1b` verified the private model hashes and reached real Android inference. Eight of nine Step 2 tests passed, but the real-runtime comparison found a genuine unresolved difference: for `very_long_03`, the fixture whole-message results were `[home_latch, food_sesame]`, while Android returned `[home_latch, food_sesame, health_wrist, home_leak]`. The immediate assertion prevented a complete comparison and latency output, so no complete Android benchmark or parity claim is made from that run. Codex reviewed this commit with no findings. The emulator screenshot was independently inspected again: section attribution, 0.55 versus required 0.48, the separate relevance gate, shared words and rejection reasons are visible with correct wrapping.

At `66a40a6`, the runtime test collected all decision differences across both datasets, recorded per-input vector differences and independently generated token-ID hashes, and measured latency before asserting exact cross-runtime decision equality. New `token_hashes.json` files contain SHA-256 references for 254 original and 59 independent inputs. `generate_token_hashes.py` uses tokenizers 0.21.2 and verifies the pinned tokenizer hash; it imports no ONNX runtime and makes no network requests. Both hash fixtures regenerated byte for byte locally. Original vectors, gold answers, thresholds and production inference are unchanged. Per-input and decision reports are archived as test artifacts; all content derives from the existing invented cases. The completed investigation and corrected test contract follow.

### Android runtime results and corrected comparison contract

Run 43631334204 at `66a40a6`: the standard unit/build job passed, including normal Debug/Beta packaging, non-debuggable Beta identity and instrumentation compilation. Eight of nine Step 2 Android tests passed. The remaining test completed both real-model datasets and latency measurements, then failed its unproven assumption that Android ORT 1.20 and Python ORT 1.29 must make identical retrieval decisions.

All **313 input-token hashes matched** their independent references. All three model-file hashes matched. The minimum Android/Python vector cosine was **0.99957734** on the original set and **0.9996953** on the independent set; maximum absolute component differences were **0.0063216463** and **0.005674582**. Close vectors can still change a decision near a gate.

| Runtime / set | Whole-message correct / missed / incorrect | With sections correct / missed / incorrect |
|---|---|---|
| Python ORT 1.29, original frozen fixtures | 54 / 21 / 19 | 69 / 6 / 19 |
| Android ORT 1.20, x86_64 emulator | 54 / 21 / 21 | 69 / 6 / 21 |
| Python ORT 1.29, independent fixtures | 4 / 5 / 0 | 4 / 5 / 0 |
| Android ORT 1.20, independent set | 4 / 5 / 0 | 4 / 5 / 0 |

On Android, sections recover the same 15 additional correct matches and add **no incorrect matches** to the whole-message result on either set. This accuracy comparison reuses measured vectors without a wall-clock allowance, so it tests retrieval quality separately from device speed. It does not claim a slow device will finish all those sections within 1,500 ms. Original gold labels and all original vector fixtures are unchanged; the original 69/6/19 measurement is preserved, not relabelled as an Android measurement.

The three cross-runtime result changes are all in the pre-existing whole-message search:

| Sample | Python whole-message IDs | Android whole-message IDs | Gate evidence |
|---|---|---|---|
| very_long_03 | home_latch, food_sesame | home_latch, food_sesame, health_wrist, home_leak | health_wrist standout 2.414→2.719; home_leak 2.419→2.600, crossing the existing 2.5 gate |
| conversation_01_turn_2 | pet_tansy, food_tea | pet_tansy | food_tea standout 2.557→2.398 |
| conversation_06_turn_2 | pet_cobalt | pet_cobalt, food_bread | food_bread standout 2.499784→2.529728 |

An independent version-only experiment used the same pinned model, tokenizer, Python preprocessing and postprocessing under Python ORT 1.20.0 and 1.29.0. Version 1.29 regenerated the original fixtures byte for byte. Version 1.20 produced small vector differences and reproduced both added `very_long_03` matches: their standouts became 2.681 and 2.626. Android's compiled native kernels differ slightly from Linux's too, but the same unchanged gate explains the observed result. No evidence of tokenization drift, different model files, a section-rule regression, or a reason to retune the approved parameters was found.

Exact equality between runtimes is not guaranteed, but accepting the worse native result as a passing baseline was an acceptance error in PR #201. The recorded `android_ort120_x86_64_observed.json` files remain historical evidence only. The repair branch restores the original per-message quality requirement on every Android ABI: preserve previously correct matches, permit additional correct matches, and reject any newly incorrect match. Original gold labels, baseline files, vectors, thresholds and section parameters stay unchanged. The known Android 1.20 result therefore fails this gate until the cause is resolved; green CI from #201 did not establish the requested accuracy.

### Measured emulator latency and remaining limits

The same Android run measured the real `prepareSearchTest` path with the configured 1,500 ms allowance:

| Measure | Observed value |
|---|---:|
| Whole-message model processing, excluding queue wait | 7,899 ms |
| Additional section queue wait | 0 ms |
| Additional section model processing | 1,501 ms |
| Section scoring | 0 ms |
| Total additional search | 1,507 ms |
| Attempted / successful sections | 1 / 0 |
| Time limit reached | Yes |

The native read was stopped cooperatively, and no unfinished section result was injected. The total exceeded the allowance by 7 ms in this run; this is a measurement, not a hard-deadline guarantee. The inference input is the invented long filler message from the integration fixture. The constrained x86_64 emulator is not a Pixel 8 or a physical-phone Beta measurement. Physical-phone latency, hardware-dependent retrieval variation and practical section recovery within the allowance remain provisional. Parameters remain unchanged, as required.

Independent fixture verification during this investigation used ONNX startup/API opt-outs plus a Linux seccomp filter denying socket creation, connect and send syscalls before importing the runtime. A socket probe returned `ENETUNREACH`. All four original/independent whole-message and section vector files reproduced byte for byte under ORT 1.29.0. This verifies reproducibility without allowing runtime network traffic; these Python runs are not Android performance measurements. Local full Gradle checks remained unavailable in the fresh environment as documented above. Final code-check/review results are tracked on PR #201; no physical-phone or packet-capture verification is claimed.


## Step 2 acceptance reopened: contract audit branch

Branch `repair/step2-contract-audit` starts from current main `0911569797e7c54ed930ef551043b2a398edd65d`. This is an in-progress repair, not a completed or approved accuracy improvement. No runtime/model upgrade or merge is made by this checkpoint.

- Restored original per-message accuracy checks rather than treating native 69/6/21 as acceptable. The native whole-message differences add three wrong IDs across two messages and remove one wrong ID elsewhere: net two additional incorrect matches. The unchanged target remains 69/6/19 or better without per-message regressions.
- Removed the opt-in skip from the full Android Step 2 test class. Missing pinned model inputs must fail clearly.
- Fixed the shared queue discarding a successfully completed result solely because its cooperative deadline expired while native work unwound. The caller had already waited; dropping that result saved no time. Actual stopped inference still reports timeout, the queue retains ownership until work unwinds, and no extra waiting or allowance is introduced. Separate latch-based tests distinguish stopped work from completed work.
- Preserved actual embedding-index failure causes in Memory Search Test, and prevented an old query's result from appearing under edited message text or a changed scope.
- Python fixture generators now reject a runtime different from the app dependency before importing native code. An explicit new output path is required, and existing fixtures cannot be overwritten. Python diagnostics still do not substitute for Android measurements.

Verification at checkpoint: Python syntax and resource XML parse checks and `git diff --check` passed. These are not Android execution results. Renewed unit/build/instrumentation verification is pending. Prior PR #201 results remain historical results, not passes for this branch. The app stays on Android ONNX Runtime 1.20.0 and the existing EmbeddingGemma model while an isolated Android runtime comparison is prepared. Any candidate must preserve the original quality target and undergo telemetry opt-out and Silero voice compatibility checks before an upgrade recommendation.

Remaining evidence gaps: no controlled full live before/after on the app runtime; no physical-phone timing; no packet-capture proof of zero telemetry. The model catalog's unversioned `resolve/main` downloads and unset hashes also mean installed files cannot be assumed identical to CI's pinned files. These gaps must not be described as completed validation.


### Isolated Android 1.29 candidate

`experiment/step2-ort129-android` is based on contract-audit checkpoint `90cfdd293c5e499d68d041188a041ce4848bff87`. Its only production inference change is the Android ONNX Runtime dependency from 1.20.0 to 1.29.0. Main and the contract-audit branch retain 1.20.0. No model, tokenizer, input processing, retrieval rule, gold answer, quality gate, or section parameter is changed. Beta package/label and normal release controls remain unchanged. Candidate APKs are experimental and are not approved for shipping.

The same full Android dataset checks run on both branches with the pinned q4 files and Wi-Fi/mobile data disabled during inference. This is an actual Android candidate comparison, not a substituted Python runtime. Candidate artifacts additionally identify runtime, ABI, model and case hash. A native Silero smoke test checks loading the shipped voice model, finite recurrent state/probabilities and eight silent frames below the speech threshold. It is not a speech-accuracy or microphone/end-to-end voice regression test. The startup environment opt-out and native telemetry API are exercised; neither is packet-capture proof.

Version 1.29.0 is selected as the first controlled candidate because the recorded 1.29 Python results were better, not because it is the newest release or already proven better on Android. Its new POSIX telemetry provider makes privacy validation material. No upgrade recommendation or merge is justified until candidate quality, native compatibility and privacy evidence are assessed. Local Gradle validation stopped before compilation because the wrapper distribution download returned `Network is unreachable`; Android execution must be established by GitHub Actions, not inferred from syntax checks.


Upgrade requirement found during source audit: `EmbeddingModels.Model.embeddingTag` contains only model ID and dimensions, and `RetrievalDocument.effectiveKey` adds the document format version. Neither identifies the runtime or artifact hash. A dependency-only upgrade would therefore reuse vectors generated by the older runtime for existing memories. The candidate's fresh-vector evaluation does not validate that mixed state. A shipping recommendation must address index provenance/rebuilding or prove mixed-runtime compatibility first; saved memory text and historical research need not be discarded. No cache identity, data migration or rebuild behavior is changed by this experiment.


### Completed Android version comparison: no accuracy improvement

| Android runtime (x86_64 emulator) | Whole: correct / missed / incorrect | With sections: correct / missed / incorrect | Independent set, whole and sections |
| --- | --- | --- | --- |
| 1.20.0, contract audit `90cfdd29` | 54 / 21 / 21 | 69 / 6 / 21 | 4 / 5 / 0 |
| 1.29.0, isolated candidate `6a48c6ab` | 54 / 21 / 21 | 69 / 6 / 21 | 4 / 5 / 0 |

Evidence: [1.20 Android Checks](https://github.com/SoulPhosphor/speak-gpt/actions/runs/43657372299) and [1.29 Android Checks](https://github.com/SoulPhosphor/speak-gpt/actions/runs/43657754338). Both standard unit/build jobs passed, including Beta packaging/identity and instrumentation compilation. On 1.20, ten of eleven Step 2 tests passed; on 1.29, eleven of twelve passed, including the added native Silero compatibility check. The other 73 Android tests passed in each run. Each run has exactly one failed test: the restored original accuracy gate. It rejects `health_wrist` and `home_leak` on `very_long_03`, and `food_bread` on `conversation_06_turn_2`. No accuracy failure was converted into a pass.

All 313 token comparisons passed in each run. Both reported the same maximum vector differences and minimum cosines against the historical Python reference: original set 0.0063216463 / 0.99957734; independent set 0.005674582 / 0.9996953. These diagnostics and equal decisions do not establish bitwise equality of every native vector. They do establish that the Android dependency bump alone did not recover the better historical Python result. The earlier Linux/Python version-only experiment must not be generalized to an Android upgrade recommendation.

Measured live test timings: 1.20 whole-model 5,766 ms; section queue 0 ms, model 1,702 ms, total 1,707 ms; two attempts, one successful section, then timeout. For 1.29: whole-model 5,065 ms; section queue 0 ms, model 1,506 ms, total 1,510 ms; two attempts, one successful section, then timeout. These are single emulator observations on separate runners, not controlled speed or physical-phone comparisons. The full quality table uses measured cached vectors without the 1,500 ms allowance; it does not prove all section recoveries occur within that allowance.

**Recommendation: retain Android 1.20.0.** Version 1.29 produced no accuracy benefit and introduces the broader POSIX telemetry provider. It is an evaluated, rejected accuracy fix, not a shipping change. Main and `repair/step2-contract-audit` retain 1.20. No PR or merge was made from either experiment. Step 2 accuracy remains unresolved against the original 69/6/19 target.

Additional checks: both fixture generators rejected a mismatched version before native imports and set the startup opt-out before a permitted import, using isolated mocked import probes without inference. All original/independent case, baseline, vector, section-vector, token-hash and native-observation files remain byte-for-byte identical to main. Static inspection of the official 1.29 AAR found the startup opt-out flag in both x86_64 and arm64 native libraries; this is not packet-capture evidence. The native API/startup-variable checks passed on Android. No physical-phone or end-to-end speech-accuracy proof is claimed.

Specific remaining numerical lead: the exact pinned graph SHA was independently checked without executing it; all 170 `MatMulNBits` nodes specify `accuracy_level=4`. The official 1.20/1.29 CPU implementations select computation precision according to that allowance and available kernels. This makes platform-dependent calculation precision a concrete investigation target, not a proven root cause or approved model change. A higher-precision comparison would need actual Android evaluation with unchanged gold answers and explicit performance reporting; no such comparison or model edit has been run.

These notes document the completed code runs above. Subsequent documentation-only commits do not change their application/test source trees or turn the failed accuracy gates green. No new runtime/model/threshold experiment is silently accepted.


### Isolated FP32-activation comparison on Android 1.20

`experiment/step2-ort120-fp32` starts from contract-audit `f3274ef3`. This is a calculation-precision experiment, not an accepted shipping change or a completed accuracy fix. The application dependency stays ONNX Runtime Android 1.20.0; application source, model catalog, q4 weight file, tokenizer, matching rules, section parameters, baseline labels and vector fixtures remain unchanged.

The CI-only preparation script reads the pinned ONNX graph as protobuf without importing or running ONNX Runtime. With `onnx==1.23.2` used only for serialization, all 170 `MatMulNBits` nodes change `accuracy_level` from 4 to 1, requiring FP32 activations/computation instead of permitting INT8 activations. The weights remain q4. The original graph SHA is `ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043`; the prepared graph SHA is `fb08ff24b5291bb88ced003b8c3e292d3e69859ea2c4485edcc419eede64a81f`. Both graph files have 519,322 bytes and differ in exactly 170 bytes, each 4 -> 1. Restoring those attribute values restores the complete original protobuf; tensor data, graph connections and external-weight references are preserved.

CI verifies the original graph, external weights and tokenizer before preparation, and verifies the prepared graph plus unchanged weights/tokenizer afterwards. The prepared graph is supplied only to the temporary isolated instrumentation model directory and is not bundled in app APKs or committed. Android metrics explicitly identify FP32 activation precision and the graph SHA; the real runtime version must be 1.20.0. All inference, quality decisions and latency measurements come from Android. The original accuracy gate remains binding, including its unchanged 0.99 vector-reference tolerance, per-message correct-match preservation and rejection of newly wrong matches.

Local preparation and integrity checks passed without inference. The actual Android run is complete; results are below. Normal Beta artifacts still use the existing model catalog and do not adopt the prepared graph. No model migration, cache-identity change, rebuild behavior or shipping precision setting is introduced by this experiment.


### FP32 comparison completed: rejected as an accuracy fix

Evidence: [Android Checks run 43662657723](https://github.com/SoulPhosphor/speak-gpt/actions/runs/43662657723), code commit `f7ecb21167f90f79bac501c564206c8a98bbfb81`.

| Android 1.20 graph | Whole: correct / missed / incorrect | With sections: correct / missed / incorrect | Independent set, whole and sections |
| --- | --- | --- | --- |
| Original q4, accuracy allowance 4 | 54 / 21 / 21 | 69 / 6 / 21 | 4 / 5 / 0 |
| Same q4 weights, required FP32 activation math | 54 / 21 / 21 | 69 / 6 / 21 | 4 / 5 / 0 |

The standard unit/build job passed, including Beta packaging/identity and instrumentation compilation. Ten of eleven Step 2 Android tests passed; the other 73 Android tests passed. The one failed test is the original accuracy gate, with the same three newly incorrect IDs in two messages. All 313 token checks passed. Reported vector-reference diagnostics are unchanged: original set maximum absolute difference 0.0063216463 and minimum cosine 0.99957734; independent set 0.005674582 and 0.9996953. These are not a proof of bitwise equality of every vector or of which default native kernel ran.

The real Android run verified the prepared graph hash `fb08ff24b5291bb88ced003b8c3e292d3e69859ea2c4485edcc419eede64a81f` and runtime 1.20.0. Its single live latency observation was whole-model 7,102 ms; section queue 0 ms, model 1,503 ms, total 1,510 ms; one attempt, zero successful sections, then timeout. The control had whole-model 5,766 ms and section total 1,707 ms with one success from two attempts. Separate runners and single observations do not establish a controlled speed difference. No accuracy benefit was found, so the precision change is rejected. Main and the repair branch keep the original model configuration and Android 1.20. No model migration or vector rebuild was applied, and no PR or merge was made for the experiment.

Decision tracing from the archived Android similarity scores confirms where the extra matches enter. The whole-message relevance gate requires similarity at least 0.35 and relative standout at least 2.5, or sufficient keyword coverage. For `very_long_03`, Android admits `health_wrist` at similarity 0.403230 / standout 2.718614 and `home_leak` at 0.399786 / 2.600457. Their historical Python standouts were 2.414072 and 2.418576, below the unchanged cutoff. For `conversation_06_turn_2`, the query is `What temperature should it be?`; `food_bread` crosses from Python standout 2.499784 to Android 2.529728 while the correct `pet_cobalt` remains selected. The test intentionally searches each turn independently, without the preceding fish context. These are observed gate decisions, not evidence that changing a runtime alone resolves contextual ambiguity.

The section implementation itself changes native totals from 54/21/21 to 69/6/21 and adds no incorrect match on either frozen set. The outstanding 19-versus-21 quality gap originates in whole-message selection before sections run. It remains a failed acceptance requirement and has not been relabelled. Further repair should target whole-message relevance quality with frozen answers and independent validation, rather than more blind runtime/precision substitutions. Step 2's existing work order explicitly preserves whole-message selection; no new whole-message threshold, filtering rule or model choice has been implemented by this investigation.


## Whole-message default calibration after later-step review

The later work orders were checked before expanding the repair. Steps 3–4 add separate previous-message searches and keep the existing relevance gate. Steps 14–15 concern the new conversation-passage engine. The saved-memory gate was already implemented in #180 (the older memory plan's Step 1.8); no later numbered work order fixes this specific existing saved-memory calibration. The current repair therefore evaluates the existing default numbers under the original locked quality requirements. Runtime remains Android ONNX 1.20.0, model/weights/tokenizer and 256 dimensions remain unchanged, and no index rebuild is needed.

The input scores come from the original Android 1.20 control run `43657372299`, code `90cfdd29`, original graph `ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043`. The new `android_ort120_similarity_scores.json` is measured input, never correct-answer labels. Its SHA is `329ae241df535863a09443964e3fab141661b4b47b1d11a082c2f35fd09a884a`; its provenance, case hash, memory order and every measured scalar are preserved. The unchanged application Kotlin reproduced all 86 recorded native whole-message result lists exactly before evaluating candidates. Scalar cosine replay uses adapter vectors only; it is not fresh model inference, latency measurement or physical-phone evidence.

Raising Stand-Out Strength alone was rejected: 2.60 lost `work_budget` and `plan_concert`; 2.75 lost those and `food_stew`. A combined calibration of the existing distribution-spread floor and standout requirement was screened instead. The selected candidate defaults are **Stand-Out Strength 2.53** and **Minimum Spread 0.034**. The original similarity minimum 0.35, keyword coverage minimum 0.5, ranking, importance rules and section requirements are unchanged. The wider spread floor stops an unusually tight background distribution from making weak similarities look decisive; the small standout adjustment removes an additional marginal false match. These numbers were selected on the original set; the separate independent set was not used to choose them. They remain provisional and must not be generalized into a guarantee for every phone or library.

| Evaluation before native rerun | Original control | Candidate defaults |
| --- | --- | --- |
| Recorded Android scores, whole only | 54 correct / 21 missed / 21 incorrect | 54 / 21 / 17 |
| Frozen reference vectors, with sections | 69 / 6 / 19 | 69 / 6 / 17 |
| Independent recorded native scores, whole only | 4 / 5 / 0 | 4 / 5 / 0 |
| Independent frozen reference vectors, with sections | 4 / 5 / 0 | 4 / 5 / 0 |

Every original required correct match is retained per message and no newly incorrect ID is accepted in these checks. Aggregate reductions cannot conceal a per-message regression. All original cases, baseline labels, whole/section vectors, token hashes and native observation files remain byte-for-byte unchanged. The real Android full-section result is not inferred from the whole-score replay; it remains a separate required instrumentation gate.

The calibration is in production defaults, shared by chat and Memory Search Test; no test-only precision/model substitution is used. Existing saved custom values are respected. The shared numeric control supports the required third decimal for Minimum Spread only, leaving other controls at their existing two-place precision. Score diagnostics retain enough digits to distinguish a rejected standout of 2.5297 from a requirement of 2.53.

Local focused verification used the project's Kotlin compiler version 2.0.21 and the actual application source, without Android SDK stubs or embedding runtime substitution. **77 JUnit tests passed**, covering live retrieval, section search, queue/cancellation, the unchanged original 86-case baseline, measured-native score regressions and numeric control precision. Full Gradle builds remain impractical locally because the wrapper distribution download is blocked and the Android SDK is unavailable. Full unit/build/Beta identity and real Android model validation are pending GitHub Actions; no PR, merge or completed native accuracy claim is made at this checkpoint.

### Real Android validation of the calibrated defaults

[Android Checks run 43669339130](https://github.com/SoulPhosphor/speak-gpt/actions/runs/43669339130), source commit `4d4c646e190ca59dc488ba40bf34acaee20c3b31`, runs the original graph and q4 weights on **Android ONNX Runtime 1.20.0**, x86_64. The restored original quality gate passes. Correct-answer labels and historical fixtures are unchanged.

| Real Android dataset | Whole message only: correct / missed / incorrect | With all sections: correct / missed / incorrect |
| --- | --- | --- |
| Original 86-message set | **54 / 21 / 17** | **69 / 6 / 17** |
| Independent 10-message set | **4 / 5 / 0** | **4 / 5 / 0** |

Every originally required correct match is retained per message, with no newly incorrect ID against the original baseline. Sections recover 15 correct matches and add no incorrect match on this set. Compared with the earlier Android 1.20 control, exactly three messages change; the same removals occur in whole-only and whole-plus-section lists. Retained matches keep their order. Every other message and the entire independent set are unchanged.

| Message | Earlier Android IDs | Calibrated Android IDs | Effect |
| --- | --- | --- | --- |
| `very_long_03` | `home_latch`, `food_sesame`, `health_wrist`, `home_leak` | `home_latch`, `food_sesame` | Removes two incorrect matches |
| `two_04` | `home_shelf`, `hobby_pottery`, `health_wrist` | `home_shelf`, `hobby_pottery` | Removes one incorrect match |
| `conversation_06_turn_2` | `pet_cobalt`, `food_bread` | `pet_cobalt` | Removes one incorrect match |

Compared with the original Python baseline, the calibrated native result also omits the previously incorrect `food_tea` on `conversation_01_turn_2` and `health_wrist` on `two_04`, reducing the original 19 incorrect matches to 17 without losing correct matches. The three runtime-induced incorrect IDs from the initial Android run are rejected.

All **85 Android tests passed**: 12 Step 2 tests and 73 existing instrumentation/fault tests. The Step 2 checks include actual native model/token references and accuracy, startup/API telemetry opt-out, index-read failure with preserved keyword results/cause, stale-message result rejection, and three-decimal Minimum Spread/rejected-standout display. The archived result-card screenshot was inspected: section attribution, the independent 0.48 requirement, calibrated 2.53 standout requirement, rejection causes and wrapping are visible. This is an emulator Debug screen, not a physical-phone Beta inspection.

All 313 token references match. Original-set vector-reference diagnostics remain maximum absolute difference 0.0063216463 and minimum cosine 0.99957734; independent-set diagnostics remain 0.005674582 and 0.9996953. Default calibration changes selection, not model-generated vectors.

**Timing is a separate measurement.** Accuracy above evaluates all sections without an elapsed-time allowance; it does not establish that all 15 recoveries fit the live allowance. In the run's separate timed emulator example, whole-message model work was **5,840 ms**; additional section queue/model/scoring/total were **0 / 1,770 / 0 / 1,775 ms**, with two sections attempted, one completed and the cooperative time limit reported. Native cancellation/unwind can exceed the provisional 1,500 ms allowance. Physical Pixel timing and end-to-end user behavior remain unmeasured.

Current upstream model metadata was also checked: revision `5090578d9565bb06545b4552f76e6bc2c93e4a66` still matches the CI-pinned graph, external weights and tokenizer hashes: `ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043`, `599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02`, and `4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47`. The app catalog's unversioned URL is unchanged; this metadata check does not individually verify pre-existing installed copies. No model change, re-download or index rebuild is required by the calibration. Existing saved custom tuning remains untouched; defaults and Reset to Defaults use the calibrated numbers.

The standard unit/build job also passed: all Gradle unit-test tasks, Debug packaging, `assembleBeta`, non-debuggable Beta identity (`com.soulphosphor.phosphorshines.beta`, label `Phosphor Shines Beta`) and instrumentation compilation. Both jobs in run `43669339130` completed successfully.

The ONNX startup/API opt-out test passed in this run. No new packet capture was performed, so this is not a claim of universal zero-network behavior. The rejected 1.29 runtime and forced-FP32 experiments are not included in this repair. PR review and integration remain pending.

### PR #202 review follow-up

Both final-document-commit checks passed at `d7c303e`: push run `43671204545` and pull-request run `43671253028`. Review found a test-screen visibility edge case: editing a running query and restoring it (or adding trailing whitespace) hides the old result, but its matching completion was accepted without showing the result container again. The completion now restores visibility only after the existing normalized-message and scope checks pass. The different-message rejection remains unchanged. A new Android regression checks both accepted edit sequences, including visible completion, the prepared query and exactly one whole-message model call. No matching defaults, model/runtime, gold answers or accuracy requirements change in this follow-up. Android execution and renewed review of this correction remain pending.
