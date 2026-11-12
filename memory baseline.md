# Memory baseline

## Status

Step 0 is incomplete. Results are recorded here, not in
`conversation_recall_plan.md`. No application code or pre-existing test
was changed. This work is being published as an incomplete handoff branch;
no PR, merge, or review request is included.

The current dataset contains 32 invented memories, 56 standalone messages,
and nine conversations: six with three turns and three with four turns.
There are 86 search samples in total. All previous 74 samples and their
expected answers are unchanged. The 12 additional turns and their expected
answers were written before attempting inference for them.

## Current verification blocker

Real-model generation for the expanded set was rejected by automatic
approval review. The rejection reported an unrelated Microsoft telemetry
request with an unknown payload, potentially disclosing environment or
repository metadata. Its originating component has not been established.
The attempt was rejected even with ONNX Runtime telemetry explicitly
disabled, and a repeat attempt was rejected for the same reason. No further execution workaround was attempted.

The additional 12 turns have no generated vectors or measured baseline.
`vectors.json` still contains the 106 previously verified vectors;
`baseline.json` preserves the 74 previously measured sample results as
exact memory-ID lists. Those results were migrated from the saved test
output and checked against the earlier counts; no expected answers or
measured outcomes were changed.

## Tests and checks in this continuation

- The real-model generation command was attempted, then retried at the
  user's request. Automatic approval review rejected execution before the
  reference-validation output or a complete new vector file was produced.
- The updated `RecallTestSetTest` was not run: the new vectors required by
  its dataset are missing. Its new regression assertions remain unverified.
- No full test suite, APK build, or instrumentation test was run in this
  continuation. Repository instructions request broader checks; the current
  session explicitly limits verification to the vector checks and the
  targeted test. Those broader checks were not treated as authorization.
- Static consistency checks confirmed that the previous 74 samples and
  expected answers are unchanged, all 106 previous vectors are unchanged,
  and all 74 converted per-sample ID lists match the saved measured counts.

## Earlier verified measurements: 74 samples only

These are the earlier measurements, not results for the expanded 86-sample
set and not a passing result for the updated test implementation.

The earlier real q4 model run generated 106 vectors. Its reference checks
were 0.9300 against 0.9304, 0.4348 against 0.4371, and 0.6204 against
0.6208. All passed the allowed absolute difference of 0.01. All 111 inputs,
including the five reference inputs, retained both markers and contained
at most 512 token IDs; four inputs were truncated. Model files were
stored temporarily and were not added to the repository.

The earlier targeted test passed: one test, zero failures, zero errors.
An earlier standalone full-suite run passed 3,541 tests with zero failures,
errors, or skips, before the current restriction on broader testing.
Earlier beta and instrumentation build verification did not complete
because the Gradle process exited during APK shrinking. Those checks were
not retried in this continuation.

A correct match is a returned memory in that sample's `expect` list.
Every returned memory outside `expect` is incorrect, including a return
absent from `must_not`. A missed match is an expected memory not returned.
In the original dataset every non-expected memory was already listed in
`must_not`, so the stricter incorrect-match definition does not change
any of the earlier recorded counts. Conversations were searched one turn
at a time, independently.

| Kind | Correct matches | Missed matches | Incorrect matches |
|---|---:|---:|---:|
| short | 10 | 0 | 3 |
| long_typed | 2 | 6 | 0 |
| long_dictated | 3 | 5 | 0 |
| dictated_bad_periods | 0 | 5 | 0 |
| very_long | 2 | 2 | 3 |
| accidental_word | 0 | 0 | 2 |
| nothing_relevant | 0 | 0 | 0 |
| two_subjects | 10 | 0 | 2 |
| conversation | 17 | 1 | 3 |
| **Total, earlier 74 samples** | **44** | **19** | **13** |

## Per-sample earlier measurements

`baseline.json` stores the exact `found` and `wrongly_sent` ID lists.
The table also shows the missed expected IDs. No expected answer is
currently in doubt. All six original immediate follow-up turns happened
to find their expected memory independently; this set therefore did not
exhibit a missed immediate follow-up in those six cases.

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
| `dictated_04` | — | `family_leon` | — |
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
| `very_long_03` | `food_sesame` | — | `health_wrist`, `home_latch`, `home_leak` |
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
| `conversation_01_turn_2` | `pet_tansy` | — | — |
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

## Samples still awaiting vectors and measurements

- `conversation_07_turn_1`
- `conversation_07_turn_2`
- `conversation_07_turn_3`
- `conversation_07_turn_4`
- `conversation_08_turn_1`
- `conversation_08_turn_2`
- `conversation_08_turn_3`
- `conversation_08_turn_4`
- `conversation_09_turn_1`
- `conversation_09_turn_2`
- `conversation_09_turn_3`
- `conversation_09_turn_4`

## Remaining Step 0 work

1. Resolve the automatic approval block without undisclosed telemetry.
2. Generate the missing real-model vectors and pass the reference checks.
3. Run only the targeted `RecallTestSetTest` and inspect its printed IDs.
4. Preserve all existing per-sample baseline IDs; record the new samples
   from their actual output, then rerun the targeted test until it passes.
5. Update this file with the measured 86-sample totals and verification.

Do not start another implementation step or publish this work.
