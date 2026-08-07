"""Parser parity: the Python parser must behave like the Kotlin
``ArchivistResponseParser``. Vectors here mirror the drop/keep decisions of the
production parser; if the Kotlin parser changes, update these in lockstep.
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import parser  # noqa: E402


class TestExtractJsonObject(unittest.TestCase):
    def test_fenced(self):
        raw = "Here you go:\n```json\n{\"memories\":[],\"model_rules\":[]}\n```\n"
        self.assertEqual(json.loads(parser.extract_json_object(raw)), {"memories": [], "model_rules": []})

    def test_prose_wrapped(self):
        raw = "I think this fits: {\"memories\": []} — let me know."
        self.assertEqual(json.loads(parser.extract_json_object(raw)), {"memories": []})

    def test_no_object_raises(self):
        with self.assertRaises(parser.NoJsonObject):
            parser.extract_json_object("no json here")


class TestParseCombined(unittest.TestCase):
    def test_valid_memory(self):
        raw = json.dumps({"memories": [
            {"content": "Likes tea", "scope": "real_life", "type_id": "preference", "tags": ["drink"]}
        ], "model_rules": []})
        p = parser.parse(raw)
        self.assertEqual(len(p.memories), 1)
        self.assertEqual(p.memories[0].scope, "real_life")
        self.assertEqual(p.memories[0].type_id, "preference")
        self.assertEqual(p.memories[0].stream, "general")
        self.assertEqual(p.dropped, 0)

    def test_companion_scope_is_companion_stream(self):
        raw = json.dumps({"memories": [{"content": "our thing", "scope": "companion"}]})
        self.assertEqual(parser.parse(raw).memories[0].stream, "companion")

    def test_unknown_scope_dropped(self):
        raw = json.dumps({"memories": [{"content": "x", "scope": "banana"}]})
        p = parser.parse(raw)
        self.assertEqual(len(p.memories), 0)
        self.assertEqual(p.dropped, 1)

    def test_missing_content_dropped(self):
        raw = json.dumps({"memories": [{"content": "  ", "scope": "global"}]})
        p = parser.parse(raw)
        self.assertEqual(len(p.memories), 0)
        self.assertEqual(p.dropped, 1)

    def test_absent_type_is_not_a_drop(self):
        raw = json.dumps({"memories": [{"content": "x", "scope": "global"}]})
        p = parser.parse(raw)
        self.assertEqual(len(p.memories), 1)
        self.assertIsNone(p.memories[0].type_id)

    def test_tags_normalized(self):
        raw = json.dumps({"memories": [{"content": "x", "scope": "global",
                          "tags": ["a", "A", " b ", "x" * 65] + [str(i) for i in range(20)]}]})
        tags = parser.parse(raw).memories[0].tags
        self.assertLessEqual(len(tags), parser.MAX_TAGS_PER_MEMORY)
        # case-insensitive dedupe: "a" and "A" collapse
        lowered = [t.lower() for t in tags]
        self.assertEqual(len(lowered), len(set(lowered)))
        self.assertNotIn("x" * 65, tags)

    def test_rule_empty_text_dropped(self):
        raw = json.dumps({"memories": [], "model_rules": [{"text": ""}, {"text": "Stop that"}]})
        p = parser.parse(raw)
        self.assertEqual(len(p.rules), 1)
        self.assertEqual(p.dropped, 1)

    def test_memory_bound(self):
        many = [{"content": f"m{i}", "scope": "global"} for i in range(parser.MAX_MEMORIES_PER_CONVERSATION + 5)]
        p = parser.parse(json.dumps({"memories": many}))
        self.assertEqual(len(p.memories), parser.MAX_MEMORIES_PER_CONVERSATION)
        self.assertEqual(p.dropped, 5)

    def test_unreadable(self):
        p = parser.parse("total garbage no braces")
        self.assertTrue(p.unreadable)
        self.assertEqual(len(p.memories), 0)


class TestParseEnvelope(unittest.TestCase):
    def test_streams_mapped(self):
        raw = json.dumps({
            "general_memories": [{"content": "g", "scope": "real_life"}],
            "companion_memories": [{"content": "c", "companion_target": "Iris"}],
            "model_rules": [{"content": "no trailing question"}],
        })
        p = parser.parse_envelope(raw)
        streams = sorted(m.stream for m in p.memories)
        self.assertEqual(streams, ["companion", "general"])
        self.assertEqual(len(p.rules), 1)

    def test_companion_without_target_dropped(self):
        raw = json.dumps({"companion_memories": [{"content": "c"}]})
        p = parser.parse_envelope(raw)
        self.assertEqual(len(p.memories), 0)
        self.assertEqual(p.dropped, 1)

    def test_general_with_companion_scope_dropped(self):
        raw = json.dumps({"general_memories": [{"content": "x", "scope": "companion"}]})
        self.assertEqual(len(parser.parse_envelope(raw).memories), 0)


if __name__ == "__main__":
    unittest.main()
