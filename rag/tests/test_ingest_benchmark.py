import importlib.util
import sqlite3
import tempfile
import unittest
from pathlib import Path

from rag.benchmark_ingest import measure, synthetic
from rag.corpus import articles


@unittest.skipUnless(importlib.util.find_spec("psutil"), "optional RSS benchmark: install requirements-benchmark.txt")
class IngestBenchmarkTests(unittest.TestCase):
    def test_small_benchmark_measures_actual_process_memory_speed_and_size(self):
        with tempfile.TemporaryDirectory() as directory:
            source, database = Path(directory) / "sample.xml", Path(directory) / "sample.sqlite"
            synthetic(source, 6)
            self.assertEqual(6, sum(1 for _ in articles(source)))
            report = measure(source, database, workers=1, fts4=False)
            self.assertEqual(6, report["articles"])
            self.assertEqual(database.stat().st_size, report["database_bytes"])
            self.assertGreaterEqual(report["sampled_peak_disk_bytes"], report["database_bytes"])
            self.assertGreater(report["parent_peak_rss_bytes"], 0)
            self.assertGreaterEqual(report["tree_peak_rss_sum_bytes"], report["parent_peak_rss_bytes"])
            self.assertGreater(report["articles_per_second"], 0)
            self.assertGreater(report["input_mib_per_second"], 0)
            self.assertGreater(report["original_zlib_bytes"], 0)
            with sqlite3.connect(database) as db:
                self.assertEqual(report["chunks"], db.execute("SELECT count(*) FROM chunks").fetchone()[0])
