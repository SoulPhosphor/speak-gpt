#!/usr/bin/env python3
"""Generate independent input-ID references; no ONNX import or network work.

Requires tokenizers==0.21.2 and the pinned, separately downloaded tokenizer.
Only hashes derived from invented test inputs are written, never vocabulary.
"""

import argparse
import hashlib
import json
from pathlib import Path
import tokenizers


TOKENIZER_SHA256 = "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tokenizer", required=True, type=Path)
    parser.add_argument("--folder", required=True, type=Path)
    args = parser.parse_args()
    assert tokenizers.__version__ == "0.21.2"
    assert hashlib.sha256(args.tokenizer.read_bytes()).hexdigest() == TOKENIZER_SHA256
    tokenizer = tokenizers.Tokenizer.from_file(str(args.tokenizer))
    tokenizer.no_padding()
    tokenizer.no_truncation()
    definition = json.loads(args.tokenizer.read_text())
    processor = definition["post_processor"]
    single = processor["single"]
    start = processor["special_tokens"][single[0]["SpecialToken"]["id"]]["ids"]
    end = processor["special_tokens"][single[2]["SpecialToken"]["id"]]["ids"]
    assert len(start) == len(end) == 1
    hashes = {}

    def record(label, text, query):
        prefix = "task: search result | query: " if query else "title: none | text: "
        content = tokenizer.encode(prefix + text, add_special_tokens=False).ids
        limit = 512 if query else 2048
        ids = start + content[:limit - 2] + end
        hashes[label] = hashlib.sha256(
            ",".join(map(str, ids)).encode("utf-8")).hexdigest()

    cases = json.loads((args.folder / "cases.json").read_text())
    for memory in cases["memories"]:
        text = memory["content"].strip()
        if memory["tags"]:
            text += "\n" + ", ".join(memory["tags"])
        record("memory:" + memory["id"], text, False)
    messages = list(cases["messages"])
    for conversation in cases["conversations"]:
        messages.extend(conversation["messages"])
    for message in messages:
        record("query:" + message["id"], message["text"], True)
    sections = json.loads((args.folder / "section_vectors.json").read_text())["messages"]
    for identifier, values in sections.items():
        for index, section in enumerate(values):
            record(f"section:{identifier}:{index}", section["text"], True)
    output = {"tokenizer_sha256": TOKENIZER_SHA256,
              "tokenizers_version": tokenizers.__version__,
              "encoding": "SHA-256 of UTF-8 comma-separated decimal token IDs",
              "hashes": hashes}
    (args.folder / "token_hashes.json").write_text(json.dumps(output, indent=2) + "\n")
    print(f"Wrote {len(hashes)} token hashes for {args.folder.name}")


if __name__ == "__main__":
    main()
