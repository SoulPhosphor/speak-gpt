#!/usr/bin/env python3
"""Generate the vectors of long-message sections with the app's real q4 model.

Each test message longer than one section is cut the way
LongMessageSearch.cut cuts it, and every section is read as a message being
sent. The unit test checks that the app's own cutter produces exactly the
section texts stored here, so this file cannot drift from the app unnoticed.

Requires Python, numpy, onnxruntime and tokenizers. Model files are
downloaded into a temporary folder unless --model-dir names a folder that
already holds model_q4.onnx, model_q4.onnx_data and tokenizer.json. They are
never committed. vectors.json is not read or changed.
"""

import argparse
import json
import math
from pathlib import Path
import re
import tempfile
import urllib.request

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


BASE = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main"
QUERY_PREFIX = "task: search result | query: "
DOCUMENT_PREFIX = "title: none | text: "
# Java's \S: anything except these six characters.
WORD = re.compile(r"[^ \t\n\x0b\f\r]+")


def cut(message, section_words):
    """The same cut as LongMessageSearch.cut."""
    size = max(section_words, 4)
    words = list(WORD.finditer(message))
    n = len(words)
    if n <= size:
        return []
    after_break = [
        i > 0 and any(ch in "\n\r" for ch in message[words[i - 1].end():words[i].start()])
        for i in range(n)
    ]

    def last_break(start, end):
        for i in range(min(end, n - 1), start - 1, -1):
            if after_break[i]:
                return i
        return None

    half, quarter = size // 2, size // 4
    sections = []
    start = 0
    while True:
        end = min(start + size, n)
        if end < n:
            found = last_break(start + size - quarter, end)
            end = end if found is None else found
        sections.append(message[words[start].start():words[end - 1].end()])
        if end >= n:
            break
        latest = end - half
        found = last_break(start + max(quarter, 1), latest)
        start = max(latest if found is None else found, start + 1)
    return sections


def main():
    ort.disable_telemetry_events()
    here = Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=here / "cases.json")
    parser.add_argument("--output", type=Path, default=here / "section_vectors.json")
    parser.add_argument("--section-words", type=int, default=60)
    parser.add_argument("--model-dir", type=Path)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding="utf-8"))

    with tempfile.TemporaryDirectory(prefix="recall-embedding-") as temporary:
        folder = args.model_dir or Path(temporary)
        if args.model_dir is None:
            for remote in ("onnx/model_q4.onnx", "onnx/model_q4.onnx_data", "tokenizer.json"):
                print(f"Downloading {remote}", flush=True)
                urllib.request.urlretrieve(f"{BASE}/{remote}", folder / Path(remote).name)

        tokenizer_path = folder / "tokenizer.json"
        processor = json.loads(tokenizer_path.read_text(encoding="utf-8"))["post_processor"]
        single = processor["single"]
        start = processor["special_tokens"][single[0]["SpecialToken"]["id"]]["ids"][0]
        end = processor["special_tokens"][single[2]["SpecialToken"]["id"]]["ids"][0]
        tokenizer = Tokenizer.from_file(str(tokenizer_path))
        tokenizer.no_truncation()
        tokenizer.no_padding()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        session = ort.InferenceSession(
            str(folder / "model_q4.onnx"), options, providers=["CPUExecutionProvider"]
        )

        def embed(text, query=True):
            prepared = (QUERY_PREFIX if query else DOCUMENT_PREFIX) + text
            content = tokenizer.encode(prepared, add_special_tokens=False).ids
            ids = [start] + content[:510] + [end]
            inputs = np.asarray([ids], dtype=np.int64)
            output = session.run(
                ["sentence_embedding"],
                {"input_ids": inputs, "attention_mask": np.ones_like(inputs)},
            )[0]
            vector = np.asarray(output, dtype=np.float32).reshape(-1)[:256].copy()
            assert vector.size == 256 and np.isfinite(vector).all()
            norm = math.sqrt(sum(float(x) * float(x) for x in vector))
            assert norm > 0
            return vector * np.float32(1.0 / norm)

        def cosine(a, b):
            a, b = a.astype(np.float64), b.astype(np.float64)
            return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))

        # The same reference check as generate_vectors.py.
        actual = cosine(
            embed("What should I feed Pepper?"),
            embed("Pepper is the user's cat. She has kidney disease and eats a prescription diet.",
                  query=False),
        )
        print(f"CHECK message against stored text: {actual:.4f} (reference 0.6208)", flush=True)
        if abs(actual - 0.6208) > 0.01:
            raise SystemExit("Reference check failed; no vectors were written.")

        messages = list(cases["messages"])
        for conversation in cases["conversations"]:
            messages.extend(conversation["messages"])
        result = {}
        count = 0
        for message in messages:
            sections = cut(message["text"], args.section_words)
            if not sections:
                continue
            result[message["id"]] = [
                {"text": text, "vector": [round(float(x), 5) for x in embed(text)]}
                for text in sections
            ]
            count += len(sections)
        document = {"section_words": args.section_words, "messages": result}
        args.output.write_text(json.dumps(document, allow_nan=False) + "\n", encoding="utf-8")
        print(f"Wrote {count} section vectors for {len(result)} messages to {args.output}", flush=True)


if __name__ == "__main__":
    main()
