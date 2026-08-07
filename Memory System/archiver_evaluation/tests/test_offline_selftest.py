"""End-to-end offline path: regenerate synthetic recordings, run the two
self-test configs, and assert the harness discriminates as designed and honors
the special cases (Do Not Analyze skip, malformed-output visibility)."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

from harness import config, runner, scoring  # noqa: E402
from harness import make_selftest_recordings as gen  # noqa: E402
from harness import run as run_mod  # noqa: E402


class TestOfflineSelfTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        gen.main()  # (re)write recorded/selftest.json deterministically
        cls.fixtures = run_mod.load_fixtures(run_mod.FIXTURES_DIR)
        cls.rec = runner.RecordedRunner.from_dir(run_mod.RECORDED_DIR)
        cls.budget = config.BudgetModel()

    def _agg(self, profile):
        cfg = config.RunConfig(profile=profile, streams=list(gen.STREAMS),
                               chunk=gen.CHUNK, model=gen.MODEL, schema="plain_json")
        scores = [run_mod.run_config_on_fixture(fx, cfg, self.rec, self.budget)
                  for fx in self.fixtures]
        return scores, scoring.aggregate(scores)

    def test_broad_higher_recall_conservative_higher_precision(self):
        _, broad = self._agg("broad")
        _, cons = self._agg("conservative")
        self.assertGreater(broad["recall"], cons["recall"])
        self.assertGreaterEqual(cons["precision"], broad["precision"])
        self.assertGreater(broad["invented"], cons["invented"])

    def test_do_not_analyze_never_produces_output(self):
        for profile in ("broad", "conservative"):
            scores, _ = self._agg(profile)
            dna = next(s for s in scores if s.fixture_id == "10_do_not_analyze")
            self.assertEqual(dna.dna_violations, 0)
            self.assertEqual(dna.requests, 0)

    def test_malformed_truncated_is_visible_not_silent(self):
        scores, _ = self._agg("conservative")
        mal = next(s for s in scores if s.fixture_id == "17_malformed_output")
        # the truncated response must surface as unreadable, not "no memories"
        self.assertGreaterEqual(mal.unreadable_calls, 1)

    def test_malformed_fenced_is_recovered(self):
        scores, _ = self._agg("broad")
        mal = next(s for s in scores if s.fixture_id == "17_malformed_output")
        # fenced/prose-wrapped but recoverable -> the firefox memory is found
        self.assertEqual(mal.useful_general, 1)


if __name__ == "__main__":
    unittest.main()
