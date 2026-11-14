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

## Step 2 repair audit (in progress)

Base: PR #200 merge `693cca9f7a56a13753fe18843a2b1c82b90681b3`, fetched from `origin/main`.
Work branch: `repair/step2-recall-audit`. Local implementation is complete; final-commit CI, Android execution and Codex review are verification gates. Step 3 is outside this repair.
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
| 21 | Retrieval-to-prompt integration missing | Actual encrypted-store/Librarian/Enforcer prompt integration passed in first CI run; full suite and model parity still gated |
| 22 | Python ORT 1.29 timings were presented as performance evidence | App uses ONNX Runtime Android 1.20.0, EmbeddingGemma q4 by default, 512 query tokens, 2,048 stored tokens, asymmetric prefixes, 256 dimensions; runtime parity and hardware timing pending |

### Additional independently established issues

- Near-misses could remain hidden under the ten-row whole-message diagnostic ranking. Unsent diagnostic rows now prioritize qualified exclusions and then the fewest failed section requirements, within the existing row limit. Sent-memory ranking is unchanged.
- Report attribution previously discarded qualifying section candidates beyond its pool cap and could call a relevance failure a memory-limit failure. Diagnostic matches are separate from the selection pool; regression coverage added.
- The summary called a failed message embedding "model unavailable" even when a model was loaded. Separate unavailable-model, failed-message, and no-ready-embedding paths.
- The summary called its bounded candidate-pool count “passed,” although that was not the total passing the relevance gate. The label is now “candidates.”
- Section reads swallowed cancellation/interruption as ordinary embedding failures. Propagate cancellation and preserve interrupt status.

### Validation record

Initial checks were blocked by Gradle network routing, an absent Android SDK and a Java runtime without a compiler. These environment blockers were resolved by provisioning the requested Gradle distribution, Android SDK and a full JDK locally. Robolectric downloads were routed through the available Maven mirror. Earlier runs passed all three required tasks. Two intermediate combined-task repeats lost the Gradle daemon during APK shrinking, and an overlapping attempt hit the shared Gradle cache lock. Final packaging tasks are therefore run serially in fresh 3 GB Gradle processes; this is a local build-environment adjustment, not a repository build-setting change.

The final-source JVM run executed **3,652 tests, zero failures/errors/skips**. `assembleBeta` subsequently passed in a fresh process (7m 13s). `aapt` verified package `com.soulphosphor.phosphorshines.beta`, version `4.38-stable-beta`, and label `Phosphor Shines Beta`. The final `assembleDebugAndroidTest` invocation passed in a fresh process (6m 5s). The final-source `testDebugUnitTest` task passed before those builds; a separate final invocation also completed successfully (1m 6s, reusing the unchanged final-source test result). No code changed between these checks. All three required local tasks have successful final-source results. The first Android execution and screenshot inspection are recorded below; real-model measurements, a fully green final CI run and review remain pending on the repair branch. Its original recall benchmark remains **69 correct / 6 missed / 19 incorrect**. The independent fixed-parameter evaluation remains **4 / 5 / 0**, with no additional section recoveries.

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

The existing Android Checks workflow now runs the Step 2 encrypted-store, prompt-assembly, logging, plural-resource and screen-state tests on its x86_64 emulator and archives the synthetic result-card screenshot. The CI emulator temporarily downloads the pinned q4 validation model, verifies its recorded hashes, and includes the actual Android 1.20.0 vector/decision parity and timing test. Its first attempt failed during model-file setup before inference, as recorded below; measurements remain pending. Wi-Fi and mobile data are disabled during the Step 2 class and restored afterwards. Model files are kept out of artifacts; the real-model test uses an isolated internal directory and removes it after closing its session. Model artifacts are temporary CI inputs, not distributed in the application or uploaded in test artifacts. The original and independent fixtures are bundled only in the instrumentation APK for runtime comparison.

Section scores immediately below the minimum use extra decimal precision when normal two-decimal formatting would show an apparently equal threshold (for example, 0.479 is shown as 0.4790 rather than 0.48). This changes diagnostic formatting only; matching still uses the original unrounded value and approved threshold. Android screen coverage includes this boundary-display case.

Diagnostic data retains qualification/exclusion reasons beyond the candidate-pool cap, while extra on-screen cards remain restricted to the existing bounded candidate pool plus the closest-row allowance. A 200-match regression case prevents comprehensive diagnostic evaluation from accidentally rendering an unbounded list of cards.

### First Android CI execution

Commit `38b43f33`, Android Checks run 43589400696: the standard test/build job passed (3,652 tests in each of Debug, Beta and Release, zero failures/errors/skips; debug/Beta packaging, Beta identity and instrumentation compilation). The emulator executed nine new Step 2 tests: seven passed, including real retrieval-to-prompt injection, whole/manual-memory limits, repeated section failure handling, keyword fallback, JNI telemetry opt-out, Android plurals and screen states. The synthetic screenshot was downloaded and visually inspected: card order, wrapping, section 4-of-7 attribution, 0.55 similarity versus required 0.48, separate relevance-gate details, normalized shared words and rejected-candidate reasons were visible. This is an emulator debug screen, not a physical-phone Beta inspection.

Two tests failed. The background-context test inspected the complete accumulated encrypted log, whose filename cache spans the test contexts, so an earlier valid live-search entry caused its negative assertion. It now asserts only entries appended by the operation under test, without changing production wording or expected behavior. The real-model test could not read the shell-supplied external model files, despite the workflow's successful push and hash verification. The fixture now falls back to the instrumentation shell's file pipe to copy those public model inputs into isolated app-private storage, verifies all three hashes again before model creation, and removes temporary files even after setup failure. Android vector/runtime measurements remain pending until that test actually runs.

Follow-up fixture corrections: all three required local tasks passed again after the final test changes (`testDebugUnitTest`: 4m 8s; `assembleBeta`: 7m 18s; `assembleDebugAndroidTest`: 6m 38s). Beta compilation was prepared in a separate Gradle process to keep compiler and shrinking memory peaks separate. No production matching or diagnostic code, historical gold answers, model parameters or vector fixture changed during this follow-up. Android re-execution remains the next gate.
