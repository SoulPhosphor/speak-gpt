#!/usr/bin/env python3
"""Prepare the isolated Android precision comparison; never run inference.

Requires onnx==1.23.2 for protobuf serialization, not onnxruntime.
The pinned q4 weights and external-file references are left unchanged.
Only the 170 MatMulNBits accuracy_level attributes change from 4 to 1.
No model artifacts are committed or bundled in application APKs.
"""

import argparse
import hashlib
from pathlib import Path

from onnx import AttributeProto, ModelProto


SOURCE_SHA256 = "ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043"
FP32_SHA256 = "fb08ff24b5291bb88ced003b8c3e292d3e69859ea2c4485edcc419eede64a81f"
EXPECTED_NODES = 170


def higher_precision_graph(source: bytes) -> bytes:
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Source is not the pinned, unmodified q4 graph")
    original = ModelProto()
    original.ParseFromString(source)
    model = ModelProto()
    model.CopyFrom(original)
    changed = []
    for index, node in enumerate(model.graph.node):
        if node.domain != "com.microsoft" or node.op_type != "MatMulNBits":
            continue
        attributes = [a for a in node.attribute if a.name == "accuracy_level"]
        if len(attributes) != 1 or attributes[0].type != AttributeProto.INT or attributes[0].i != 4:
            raise ValueError(f"Unexpected accuracy attribute on {node.name}")
        attributes[0].i = 1
        changed.append(index)
    if len(changed) != EXPECTED_NODES:
        raise ValueError(f"Expected {EXPECTED_NODES} MatMulNBits nodes, found {len(changed)}")

    # Restoring just these integers must restore the entire protobuf. This
    # protects every tensor, weight reference, graph edge and other attribute.
    restored = ModelProto()
    restored.CopyFrom(model)
    for index in changed:
        next(a for a in restored.graph.node[index].attribute if a.name == "accuracy_level").i = 4
    if restored.SerializeToString(deterministic=True) != original.SerializeToString(deterministic=True):
        raise ValueError("Something other than computation precision changed")
    return model.SerializeToString(deterministic=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve() or args.output.exists():
        parser.error("Choose a new output file; the original model must not be overwritten")
    result = higher_precision_graph(args.source.read_bytes())
    digest = hashlib.sha256(result).hexdigest()
    if digest != FP32_SHA256:
        raise ValueError(f"Precision graph hash differs from the verified preparation: {digest}")
    with args.output.open("xb") as output:
        output.write(result)
    print(f"Prepared {EXPECTED_NODES} FP32-activation nodes; q4 weights unchanged; SHA256={digest}")


if __name__ == "__main__":
    main()
