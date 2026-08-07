# Archiver Evaluation Harness (Phase 3)

A repeatable, offline-by-default harness that tests the memory Archiver's
extraction quality, chunk sizes, prompt profiles, extraction streams, output
schemas, and models against synthetic conversation fixtures — **without ever
writing to the live memory database**. It exists to choose evidence-backed
provisional defaults for the production archiver (counterplan §8.11, Phase 3).

It is pure-Python standard library: no Android SDK, no Gradle, no third-party
packages. It runs anywhere Python 3.9+ runs, so it can be re-run on every future
prompt/provider/model change.

## Layout

```
archiver_evaluation/
  harness/            the harness (tokens, chunking, prompts, parser, runner, scoring, report, run)
  fixtures/           17 synthetic conversations + gold labels (one JSON per case)
  recorded/           SYNTHETIC self-test recordings (not model evidence — see methodology §7)
  tests/              unittest suite (parser parity, chunking, scoring, offline end-to-end)
  results/            reports (RESULTS.md = the Phase 3 report; selftest_results.* = the offline demo)
  methodology.md      exactly what is measured and how
  README.md           this file
```

## Run the tests

```
cd "Memory System/archiver_evaluation"
python3 -m unittest discover -s tests -p "test_*.py"
```

## Run offline (free, deterministic — replays recorded responses)

```
cd "Memory System/archiver_evaluation"
python3 -m harness.make_selftest_recordings          # (re)generate the synthetic recordings
python3 -m harness.run --profiles broad,conservative \
    --streams general+companion+model_rules --chunks t8k \
    --models cheap --schemas plain_json
# -> results/results.md and results/results.csv
```

Offline numbers reflect only the synthetic self-test recordings. They prove the
harness works; they are **not** a measurement of any real model.

## Run live (the real measurement pass — needs provider keys)

The live pass is what produces actual cross-model / cost evidence. It is **not**
runnable in the build environment, which deliberately has no third-party model
API keys. To run it where keys exist:

1. Edit `harness/config.py`: set each model's `model_id`, `base_url`,
   `context_limit_tokens`, `supports_structured`, current `price_in`/`price_out`,
   and `api_key_env`.
2. Export the key(s), e.g. `export OPENAI_API_KEY=...`.
3. Run a **narrowed** matrix to control cost, e.g. sweep chunk size on both
   models with all three streams and both schemas:

```
python3 -m harness.run --live \
    --streams general+companion+model_rules \
    --record-out recorded/live_capture.json
```

`--record-out` saves every raw response so the whole run can be re-scored later
for free (`--offline` replay) after a scoring or gold-label change — you pay the
provider once.

Cost control: the full matrix is 480 configs. Filter aggressively
(`--profiles`, `--chunks`, `--models`, `--streams`, `--schemas`) and vary one
axis at a time. See `results/RESULTS.md` for the recommended narrowed passes.

## Important boundaries

- The harness does not write Active or Pending memories, and does not call the
  app. It is a standalone evaluation tool.
- The shipped `recorded/` responses are synthetic scaffolding, not evidence.
- A model advertising a large context window is **not** evidence that large
  chunks extract reliably — that is exactly what the chunk sweep measures.
