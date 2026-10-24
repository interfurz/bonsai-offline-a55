from contextlib import closing
import importlib.util
import sqlite3
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from rag.benchmark_ingest import _disk_bytes, measure, synthetic
from rag.corpus import articles


@unittest.skipUnless(importlib.util.find_spec("psutil"), "optional RSS benchmark: install requirements-benchmark.txt")
class IngestBenchmarkTests(unittest.TestCase):
    def test_disk_sample_tolerates_journal_unlinked_after_enumeration(self):
        database=Mock();database.name='sample.sqlite'
        stable=Mock();stable.stat.return_value=Mock(st_mode=stat.S_IFREG,st_size=4096)
        journal=Mock();journal.stat.side_effect=FileNotFoundError('journal removed')
        directory=Mock();directory.stat.return_value=Mock(st_mode=stat.S_IFDIR,st_size=4096)
        database.parent.glob.return_value=[stable,journal,directory]
        self.assertEqual(4096,_disk_bytes(database))

    def test_monitor_failure_stops_import_before_releasing_database_files(self):
        processes=[]
        start=subprocess.Popen
        def launch(*args,**kwargs):
            process=start(*args,**kwargs);processes.append(process);return process
        with tempfile.TemporaryDirectory() as directory:
            source,database=Path(directory)/'sample.xml',Path(directory)/'sample.sqlite'
            synthetic(source,6)
            with patch('rag.benchmark_ingest.subprocess.Popen',side_effect=launch), patch('rag.benchmark_ingest._disk_bytes',side_effect=RuntimeError('monitor failed')):
                with self.assertRaisesRegex(RuntimeError,'monitor failed'):
                    measure(source,database,workers=1)
            self.assertEqual(1,len(processes))
            self.assertIsNotNone(processes[0].poll())

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
            with closing(sqlite3.connect(database)) as db:
                self.assertEqual(report["chunks"], db.execute("SELECT count(*) FROM chunks").fetchone()[0])
