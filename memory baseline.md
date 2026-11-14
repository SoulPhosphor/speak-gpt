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

**The 0.48 is provisional.** It was chosen from this test set, where the
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

The section search may add at most 1,500 ms to sending a message. The
limit is checked before each section is read, so one read can finish past
it; the sections not reached are skipped and the message is sent with
what was found. The 1,500 ms is an engineering starting value and has not
been checked on a phone.

| Check | Result (build environment) |
|---|---|
| Length of the 136 test-set sections as the model reads them | 89 tokens average, 100 longest |
| Time to read one section | 114 ms average, 141 ms longest |
| A 150 to 300 word message (4 sections) | about 460 ms added |
| A message with 8 or more sections | about 910 ms added |

These times come from the real q4 model on a 4-core cloud machine with
onnxruntime 1.29.0 in Python, two threads, not from the app on a phone.

**Still to measure on the owner's Pixel 8:** the added sending time for a
long message. The Memory Search Test screen shows it after a test of a
long message, on the line that begins "Additional section search".
