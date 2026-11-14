#!/usr/bin/env python3
"""Generate the Step 0 fixtures with the app's real q4 embedding model.

Requires Python, numpy, onnxruntime and tokenizers. Run this file directly;
model artifacts are downloaded into a TemporaryDirectory and never committed.
The normal unit tests read vectors.json and need no model or network access.
"""

import argparse
import json
import math
import os
from pathlib import Path
import tempfile
import urllib.request

# The API alone can be too late to suppress the native initialization event.
os.environ["ORT_DISABLE_TELEMETRY"] = "1"
import numpy as np
import onnxruntime as ort
# Apply the process-wide opt-out immediately, including when imported as a tool.
ort.disable_telemetry_events()
from tokenizers import Tokenizer


BASE = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"
DOCUMENT_PREFIX = "title: none | text: "
QUERY_PREFIX = "task: search result | query: "


def main():
    # Only the requested Hugging Face downloads may make network requests.
    # Disable ONNX Runtime's telemetry before constructing any model session.
    ort.disable_telemetry_events()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("vectors.json"))
    parser.add_argument("--model-dir", type=Path, help="Use existing model files without downloading anything")
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))

    with tempfile.TemporaryDirectory(prefix="recall-embedding-") as temporary:
        folder = args.model_dir or Path(temporary)
        if args.model_dir is None:
            for remote in ("onnx/model_q4.onnx", "onnx/model_q4.onnx_data", "tokenizer.json"):
                print(f"Downloading {remote}", flush=True)
                urllib.request.urlretrieve(f"{BASE}/{remote}", folder / Path(remote).name)

        tokenizer_path = folder / "tokenizer.json"
        definition = json.loads(tokenizer_path.read_text(encoding="utf-8"))
        processor = definition["post_processor"]
        assert processor["type"] == "TemplateProcessing"
        single = processor["single"]
        assert len(single) == 3 and single[1]["Sequence"]["id"] == "A"
        start_name = single[0]["SpecialToken"]["id"]
        end_name = single[2]["SpecialToken"]["id"]
        start_ids = processor["special_tokens"][start_name]["ids"]
        end_ids = processor["special_tokens"][end_name]["ids"]
        assert len(start_ids) == len(end_ids) == 1
        start, end = start_ids[0], end_ids[0]
        tokenizer = Tokenizer.from_file(str(tokenizer_path))
        tokenizer.no_truncation()
        tokenizer.no_padding()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        session = ort.InferenceSession(
            str(folder / "model_q4.onnx"), options, providers=["CPUExecutionProvider"]
        )
        checked_inputs = 0
        truncated_inputs = 0

        def embed(text, query=False):
            nonlocal checked_inputs, truncated_inputs
            prepared = (QUERY_PREFIX if query else DOCUMENT_PREFIX) + text
            content = tokenizer.encode(prepared, add_special_tokens=False).ids
            # HfTokenizer.encode reserves both template markers before cutting
            # content. Cutting the fully encoded sequence would lose EOS.
            ids = [start] + content[:510] + [end]
            assert ids[0] == start and ids[-1] == end and len(ids) <= 512
            checked_inputs += 1
            truncated_inputs += int(len(content) > 510)
            inputs = np.asarray([ids], dtype=np.int64)
            output = session.run(
                ["sentence_embedding"],
                {"input_ids": inputs, "attention_mask": np.ones_like(inputs)},
            )[0]
            vector = np.asarray(output, dtype=np.float32).reshape(-1)[:256].copy()
            assert vector.size == 256 and np.isfinite(vector).all()
            # VectorMath.normalize accumulates a double norm, converts its
            # reciprocal to float32, then multiplies float32 components.
            norm = math.sqrt(sum(float(x) * float(x) for x in vector))
            assert norm > 0
            return vector * np.float32(1.0 / norm)

        def cosine(a, b):
            a, b = a.astype(np.float64), b.astype(np.float64)
            return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

        coffee = embed("I love drinking coffee in the morning.")
        checks = [
            ("related stored texts", cosine(coffee, embed("My favorite morning drink is coffee.")), 0.9304),
            ("unrelated stored texts", cosine(coffee, embed("The spaceship landed on the distant planet.")), 0.4371),
            ("query against stored text", cosine(
                embed("What should I feed Pepper?", query=True),
                embed("Pepper is the user's cat. She has kidney disease and eats a prescription diet."),
            ), 0.6208),
        ]
        for label, actual, expected in checks:
            print(f"CHECK {label}: {actual:.4f} (reference {expected:.4f})", flush=True)
        if any(abs(actual - expected) > 0.01 for _, actual, expected in checks):
            raise SystemExit("Reference check failed; no vectors were written.")

        vectors = {}

        def record(identifier, text, query=False):
            assert identifier not in vectors, f"Duplicate id: {identifier}"
            vectors[identifier] = [round(float(x), 5) for x in embed(text, query)]

        for memory in cases["memories"]:
            text = memory["content"].strip()
            if memory["tags"]:
                text += "\n" + ", ".join(memory["tags"])
            record(memory["id"], text)
        messages = list(cases["messages"])
        for conversation in cases["conversations"]:
            messages.extend(conversation["messages"])
        for message in messages:
            record(message["id"], message["text"], query=True)

        print(f"CHECK input markers and length: {checked_inputs} passed, "
              f"including {truncated_inputs} truncated inputs", flush=True)
        args.output.write_text(json.dumps(vectors, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(f"Wrote {len(vectors)} vectors to {args.output}", flush=True)


if __name__ == "__main__":
    main()
