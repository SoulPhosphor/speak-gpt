"""Chunking boundary behavior (§8.6)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import chunking, tokens  # noqa: E402


def msgs(*pairs):
    return [chunking.Message(role=r, content=c) for r, c in pairs]


class TestChunking(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(chunking.chunk_conversation([], 1000), [])

    def test_single_chunk_when_fits(self):
        m = msgs(("user", "hello"), ("assistant", "hi"))
        chunks = chunking.chunk_conversation(m, 1000)
        self.assertEqual(len(chunks), 1)
        self.assertEqual(chunks[0].message_indices, [0, 1])
        self.assertIn("User: hello", chunks[0].text)
        self.assertIn("Assistant: hi", chunks[0].text)

    def test_splits_on_budget_preserving_whole_messages(self):
        # Each message ~ 25 tokens; budget 30 forces one message per chunk.
        big = "word " * 100  # ~100 tokens
        m = msgs(("user", big), ("assistant", big), ("user", big))
        chunks = chunking.chunk_conversation(m, 120)
        self.assertEqual(len(chunks), 3)
        # order + speaker preserved
        self.assertEqual([c.message_indices for c in chunks], [[0], [1], [2]])
        self.assertTrue(chunks[0].text.startswith("User:"))
        self.assertTrue(chunks[1].text.startswith("Assistant:"))

    def test_oversized_message_split_at_paragraphs(self):
        para = "sentence one is here. " * 30  # big paragraph
        content = para + "\n\n" + para
        m = msgs(("user", content))
        budget = tokens.estimate_tokens(para) - 5  # neither half fits whole
        chunks = chunking.chunk_conversation(m, budget)
        self.assertGreaterEqual(len(chunks), 2)
        for c in chunks:
            self.assertLessEqual(c.tokens, budget + 5)
            self.assertEqual(c.message_indices, [0])

    def test_ordering_stable(self):
        m = msgs(("user", "a " * 60), ("assistant", "b " * 60), ("user", "c " * 60), ("assistant", "d " * 60))
        chunks = chunking.chunk_conversation(m, 80)
        flat = [i for c in chunks for i in c.message_indices]
        self.assertEqual(flat, [0, 1, 2, 3])

    def test_overlap_repeats_prior_message(self):
        m = msgs(("user", "a " * 60), ("assistant", "b " * 60), ("user", "c " * 60))
        no = chunking.chunk_conversation(m, 80, overlap_messages=0)
        ov = chunking.chunk_conversation(m, 80, overlap_messages=1)
        total_no = sum(len(c.message_indices) for c in no)
        total_ov = sum(len(c.message_indices) for c in ov)
        self.assertGreater(total_ov, total_no)


if __name__ == "__main__":
    unittest.main()
