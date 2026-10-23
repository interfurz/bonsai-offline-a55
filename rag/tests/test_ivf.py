from contextlib import closing
import gc
import json
from pathlib import Path
import sqlite3
import struct
import tempfile
import unittest
import numpy as np
from rag.ivf import IvfIndex, DIM, HEADER, build_index, create_index, layout, normalized, quantized
from rag.embeddings import embed, encoder
from rag.corpus import build
from rag.search import search, fuse
from rag.evaluate_ivf import evaluate, make_questions, markdown, write_report
from rag.benchmark_ivf import synthetic, measure, write_queries

DATA = Path(__file__).resolve().parents[1] / 'data'


class IvfTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # Legacy search/embed helpers leave connections to cyclic GC. Do not
        # change their implementation in this importer-only branch.
        self.addCleanup(gc.collect)
        self.directory = Path(self.temp.name)
        self.db = self.directory / 'knowledge.sqlite'
        self.reference = self.directory / 'reference.f32'
        self.index = self.directory / 'knowledge.ivf'
        build(DATA / 'articles.jsonl', self.db)
        embed(self.db, self.directory / 'legacy.i8', 'hash-v1', self.reference)
        self.info = build_index(self.reference, self.index, clusters=8, backend='sklearn', batch=7)

    def test_layout_global_scale_ids_and_read_only_mapping(self):
        index = IvfIndex(self.index)
        self.assertEqual((50, 8), (index.count, index.clusters))
        self.assertEqual(1, HEADER.unpack(self.index.read_bytes()[:HEADER.size])[1])
        with closing(sqlite3.connect(self.db)) as db:
            expected_ids = {row[0] for row in db.execute("SELECT id FROM chunks")}
        self.assertEqual(expected_ids, set(index.ids.tolist()))
        self.assertEqual(50, int(index.offsets[-1]))
        self.assertEqual(layout(50, 8)[-1], self.index.stat().st_size)
        self.assertAlmostEqual(1 / 127, index.scale, places=8)
        self.assertFalse(index.vectors.flags.writeable)
        self.assertEqual(np.dtype('int8'), index.vectors.dtype)

    def test_all_clusters_equal_integer_full_scan_with_ties(self):
        index = IvfIndex(self.index)
        queries = encoder('hash-v1').encode(['Photosynthese Pflanzen', 'Wälder', '!!!'])
        for query in queries:
            vector = normalized(np.asarray(query)[:256])
            q = quantized(vector, index.scale).astype(np.int32)
            scores = index.vectors.astype(np.int32) @ q
            expected = sorted(zip(index.ids.tolist(), scores.tolist()), key=lambda v: (-v[1], v[0]))[:10]
            result = index.search(query, nprobe=index.clusters, top_k=10, positive_only=False, batch=3)
            self.assertEqual(expected, list(zip(result['ids'], result['dots'])))
            self.assertEqual(result['ids'], index.search(query, top_k=10, positive_only=False, full_scan=True)['ids'])
            self.assertEqual(50, result['scanned'])

    def test_small_corpus_clusters_clamped_and_empty_clusters_supported(self):
        output = self.directory / 'clamped.ivf'
        self.assertEqual(50, build_index(self.reference, output, clusters=4096, backend='sklearn')['clusters'])
        custom = self.directory / 'empty-clusters.ivf'
        create_index(custom, 2, np.zeros((4, DIM)), [0, 0, 1, 1, 2], np.float32(1 / 127))
        sections = layout(2, 4)
        ids = np.memmap(custom, dtype='<i8', mode='r+', offset=sections[2], shape=(2,)); ids[:] = [9, 3]; ids.flush()
        values = np.memmap(custom, dtype='i1', mode='r+', offset=sections[3], shape=(2, DIM)); values[:] = 0; values.flush()
        result = IvfIndex(custom).search(np.zeros(DIM), nprobe=4, top_k=10, positive_only=False)
        self.assertEqual([3, 9], result['ids'])
        self.assertEqual(2, result['scanned'])
        empty = self.directory / 'empty.ivf'
        create_index(empty, 0, np.zeros((1, DIM)), [0, 0], np.float32(1 / 127))
        self.assertEqual([], IvfIndex(empty).search(np.ones(DIM))['ids'])

    def test_builder_accepts_empty_float_source_and_never_overwrites(self):
        reference = self.directory / 'empty.f32'
        reference.write_bytes(struct.pack('<8sII', b'BRAGF001', 768, 0))
        output = self.directory / 'empty-built.ivf'
        self.assertEqual(0, build_index(reference, output, backend='sklearn')['count'])
        self.assertEqual([], IvfIndex(output).search(np.zeros(DIM))['ids'])
        with self.assertRaises(FileExistsError):
            build_index(reference, output)
        damaged = self.directory / 'invalid.f32'
        damaged.write_bytes(b'bad')
        with self.assertRaises(ValueError):
            build_index(damaged, self.directory / 'never-published.ivf')
        self.assertFalse((self.directory / 'never-published.ivf').exists())

    def test_missing_corrupt_offsets_header_and_truncation_fail(self):
        with self.assertRaises(FileNotFoundError):
            IvfIndex(self.directory / 'missing')
        original = self.index.read_bytes()
        for offset, value in ((8, 99), (16, 128), (128, 1), (32, 0)):
            damaged = bytearray(original)
            struct.pack_into('<I', damaged, offset, value)
            self.index.write_bytes(damaged)
            with self.assertRaises(ValueError):
                IvfIndex(self.index)
        self.index.write_bytes(original[:-1])
        with self.assertRaises(ValueError):
            IvfIndex(self.index)

    def test_hybrid_configurable_rrf_and_query_validation(self):
        self.assertEqual('2', search(self.db, 'Photosynthese', ivf=self.index, nprobe=8, rrf_k=20)[0]['article_id'])
        self.assertEqual([2, 1, 3], fuse([[1, 2], [2, 3]], rrf_k=0))
        index = IvfIndex(self.index)
        for kwargs in ({'nprobe': 0}, {'top_k': 0}, {'batch': 0}):
            with self.assertRaises(ValueError):
                index.search(np.zeros(DIM), **kwargs)
        with self.assertRaises(ValueError):
            index.search(np.full(DIM, np.nan))

    def test_hundred_question_quality_report_is_computed(self):
        questions = json.loads((DATA / 'ivf_questions.json').read_text(encoding='utf-8'))
        self.assertEqual(100, len(questions))
        self.assertEqual(questions, make_questions(DATA / 'articles.jsonl'))
        report = evaluate(self.db, self.reference, self.index, questions)
        self.assertEqual(6, len(report['table']))
        self.assertEqual(1.0, report['table'][0]['neighbor_recall_at10'])
        self.assertEqual(100, len(report['per_query']))
        self.assertIn('nprobe=32', markdown(report))
        output = self.directory / 'quality.json'
        write_report(output, report)
        self.assertEqual(report, json.loads(output.read_text(encoding='utf-8')))

    def test_benchmark_measures_small_synthetic_without_downloads(self):
        path = self.directory / 'synthetic.ivf'
        synthetic(path, 101, clusters=8, batch=11)
        queries = write_queries(self.directory / 'queries.f32', count=2)
        report = measure(path, queries, nprobe=2, warmup=0)
        self.assertEqual(4, len(report['rows']))
        self.assertTrue(all(row['scan_ms'] >= 0 for row in report['rows']))
        self.assertEqual(101, report['summary']['full']['scanned']['p50'])
        self.assertLess(report['summary']['ivf']['scanned']['p50'], 101)


if __name__ == '__main__':
    unittest.main()
