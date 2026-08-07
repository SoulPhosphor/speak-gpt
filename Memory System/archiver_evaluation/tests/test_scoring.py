"""Scoring logic: semantic matching, placement, leakage, invention, DNA."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import scoring  # noqa: E402
from harness.parser import Candidate, Rule  # noqa: E402


def gold(**kw):
    return scoring.Gold.from_dict(kw)


TYPES = {"preference", "fact"}


class TestAnchors(unittest.TestCase):
    def test_conjunctive_groups(self):
        content = "The user is allergic to shellfish and avoids seafood."
        self.assertTrue(scoring.anchors_match([["shellfish", "seafood"], ["allerg"]], content))
        self.assertFalse(scoring.anchors_match([["shellfish"], ["peanut"]], content))

    def test_paraphrase_ok(self):
        # non-exact wording still matches on anchors
        self.assertTrue(scoring.anchors_match([["metric"], ["24"]],
                        "Prefers the metric system and a 24 hour clock."))


class TestScoreFixture(unittest.TestCase):
    def _score(self, g, cands, rules, streams, dropped=0, unread=0):
        return scoring.score_fixture("t", g, cands, rules, streams,
                                     valid_type_ids=TYPES, parser_dropped=dropped,
                                     unreadable_calls=unread)

    def test_useful_and_missed(self):
        g = gold(expected=[
            {"id": "a", "stream": "general", "anchors": [["tea"]]},
            {"id": "b", "stream": "general", "anchors": [["bass"]]},
        ])
        cands = [Candidate(content="likes tea", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.useful_general, 1)
        self.assertEqual(fs.missed_general, 1)

    def test_placement_error(self):
        # expected companion, but candidate is general-scope -> placement error
        g = gold(expected=[{"id": "a", "stream": "companion", "anchors": [["grey days"]]}])
        cands = [Candidate(content="the grey days ritual", scope="real_life")]
        fs = self._score(g, cands, [], ["companion", "general"])
        self.assertEqual(fs.placement_errors, 1)
        self.assertEqual(fs.useful_companion, 0)

    def test_stream_leakage(self):
        # only general requested; a companion candidate leaked
        g = gold(expected=[{"id": "a", "stream": "general", "anchors": [["gluten"]]}])
        cands = [Candidate(content="gluten free", scope="real_life"),
                 Candidate(content="our sunday ritual", scope="companion")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.stream_leakage, 1)
        self.assertEqual(fs.useful_general, 1)

    def test_invented_trap(self):
        g = gold(expected=[],
                 traps=[{"id": "t", "kind": "self_memory", "anchors": [["iris is playful"]]}])
        cands = [Candidate(content="Iris is playful and warm", scope="companion")]
        fs = self._score(g, cands, [], ["general", "companion"])
        self.assertEqual(fs.invented, 1)
        self.assertIn("self_memory:t", fs.trap_detail)

    def test_overextraction_soft(self):
        g = gold(expected=[])
        cands = [Candidate(content="something unrelated and new", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.overextraction, 1)
        self.assertEqual(fs.invented, 0)

    def test_acceptable_extra_not_penalized(self):
        g = gold(expected=[], acceptable_extra=[[["wrap"], ["tomorrow"]]])
        cands = [Candidate(content="needs to wrap by 10 tomorrow", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.overextraction, 0)
        self.assertEqual(fs.invented, 0)

    def test_invalid_type(self):
        g = gold(expected=[{"id": "a", "stream": "general", "anchors": [["tea"]]}])
        cands = [Candidate(content="likes tea", scope="real_life", type_id="not_a_type")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.invalid_type, 1)

    def test_dedupe(self):
        g = gold(expected=[{"id": "a", "stream": "general", "anchors": [["tea"]]}])
        cands = [Candidate(content="Likes tea", scope="real_life"),
                 Candidate(content="likes tea", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.duplicates_removed, 1)
        self.assertEqual(fs.useful_general, 1)

    def test_rules(self):
        g = gold(rules=[{"id": "r", "anchors": [["question"], ["end", "not"]]}])
        rules = [Rule(text="Do not end with a question"), Rule(text="use fewer emojis")]
        fs = self._score(g, [], rules, ["model_rules"])
        self.assertEqual(fs.useful_rules, 1)
        self.assertEqual(fs.noisy_rules, 1)

    def test_rules_not_requested_are_noise(self):
        g = gold(rules=[{"id": "r", "anchors": [["question"]]}])
        rules = [Rule(text="Do not end with a question")]
        fs = self._score(g, [], rules, ["general"])
        self.assertEqual(fs.useful_rules, 0)
        self.assertEqual(fs.noisy_rules, 1)

    def test_do_not_analyze_violation(self):
        g = gold(do_not_analyze=True)
        cands = [Candidate(content="anything", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        self.assertEqual(fs.dna_violations, 1)

    def test_aggregate_precision_recall(self):
        g = gold(expected=[{"id": "a", "stream": "general", "anchors": [["tea"]]},
                           {"id": "b", "stream": "general", "anchors": [["bass"]]}])
        cands = [Candidate(content="likes tea", scope="real_life"),
                 Candidate(content="hallucinated nonsense", scope="real_life")]
        fs = self._score(g, cands, [], ["general"])
        agg = scoring.aggregate([fs])
        self.assertEqual(agg["recall"], 0.5)          # 1 of 2 found
        self.assertAlmostEqual(agg["precision"], 0.5)  # 1 good of 2 kept


if __name__ == "__main__":
    unittest.main()
