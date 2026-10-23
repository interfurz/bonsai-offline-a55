import bz2
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import random
import sqlite3
import tempfile
import unittest

from wikidata.fixtures import write_dump
from wikidata.pipeline import build,source_identity
from wikidata.streams import IndexedDump


@unittest.skipUnless(importlib.util.find_spec('indexed_bzip2'),'Optional indexed-bzip2 not installed')
class ParallelStreamTests(unittest.TestCase):
    def test_parallel_resume_matches_original_database_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'dump.json';write_dump(source)
            compressed=root/'dump.json.bz2';compressed.write_bytes(bz2.compress(source.read_bytes()))
            expected=root/'original.sqlite';actual=root/'parallel.sqlite'
            build(compressed,expected,dump_date='2026-10-19')
            self.assertFalse(build(compressed,actual,dump_date='2026-10-19',bzip2_threads=2,stop_after=5,batch=2))
            self.assertTrue(Path(str(actual)+'.bz2-index.json').exists())
            self.assertTrue(build(compressed,actual,dump_date='2026-10-19',bzip2_threads=2,resume=True))
            # Commit counters differ after interruption; persisted facts and
            # metadata must be identical, including the final input checkpoint.
            with closing(sqlite3.connect(expected)) as a,closing(sqlite3.connect(actual)) as b:
                self.assertEqual(list(a.iterdump()),list(b.iterdump()))

    def test_partial_block_index_seeks_without_losing_later_bytes_and_rejects_other_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'multi.bz2';payload=random.Random(7).randbytes(8*1024**2)
            source.write_bytes(bz2.compress(payload));index=root/'offsets.json';identity=source_identity(source)
            with IndexedDump(source,2,index,identity) as stream:
                self.assertEqual(payload[:100000],stream.read(100000))
                stream.checkpoint(force=True)
            with IndexedDump(source,2,index,identity) as stream:
                stream.seek(3*1024**2+17)
                self.assertEqual(payload[3*1024**2+17:],stream.read())
            changed=dict(identity,edge_sha256='0'*64)
            with self.assertRaisesRegex(ValueError,'source differs'):IndexedDump(source,2,index,changed)
            saved=json.loads(index.read_text(encoding='utf-8'));saved['offsets'][-1][1]+=1
            index.write_text(json.dumps(saved),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):IndexedDump(source,2,index,identity)


if __name__=='__main__':unittest.main()
