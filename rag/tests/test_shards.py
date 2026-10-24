from contextlib import closing
from functools import partial
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from rag.corpus import build
from rag.embeddings import HashEncoder, embed
from rag.shards import export, assemble

export = partial(export, reserve_bytes=0)
assemble = partial(assemble, reserve_bytes=0)


class ShardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db = self.root / 'corpus.sqlite'
        build(Path(__file__).parents[1] / 'data/articles.jsonl', self.db, progress=False)

    def test_resume_produces_byte_identical_legacy_files_and_does_not_reload_finished_model(self):
        folder = self.root / 'shards'
        state = export(self.db, folder, model='hash-v1', shard_rows=7, batch=3, stop_after=2)
        self.assertFalse(state['complete'])
        first = (folder / 'shard-000000.i8').read_bytes()
        state = export(self.db, folder, model='hash-v1', shard_rows=7, batch=4)
        self.assertTrue(state['complete'])
        self.assertEqual(first, (folder / 'shard-000000.i8').read_bytes())
        with patch('rag.shards.encoder', side_effect=AssertionError('unnecessary model reload')):
            export(self.db, folder, model='hash-v1', shard_rows=7)
        assemble(folder, self.root / 'assembled')
        embed(self.db, self.root / 'legacy.i8', model='hash-v1', reference=self.root / 'legacy.f32')
        self.assertEqual((self.root / 'legacy.i8').read_bytes(), (self.root / 'assembled/vectors.i8').read_bytes())
        self.assertEqual((self.root / 'legacy.f32').read_bytes(), (self.root / 'assembled/reference.f32').read_bytes())

    def test_crash_mid_shard_keeps_committed_shards_and_resumes(self):
        folder = self.root / 'shards'
        backend = HashEncoder()
        original = backend.encode
        calls = 0
        def fail(texts):
            nonlocal calls
            calls += 1
            if calls == 4:
                raise RuntimeError('simulated interruption')
            return original(texts)
        with patch('rag.shards.encoder', return_value=backend), patch.object(backend, 'encode', side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, 'interruption'):
                export(self.db, folder, model='hash-v1', shard_rows=6, batch=3)
        checkpoint = json.loads((folder / 'export.json').read_text(encoding='utf-8'))
        self.assertEqual(1, len(checkpoint['shards']))
        self.assertTrue((folder / 'errors.jsonl').is_file())
        self.assertTrue(export(self.db, folder, model='hash-v1', shard_rows=6)['complete'])

    def test_disk_reserve_stop_preserves_checkpoint_and_can_resume_with_same_identity(self):
        folder=self.root/'reserve'
        before=export(self.db,folder,model='hash-v1',shard_rows=6,stop_after=1)
        saved=(folder/'shard-000000.f32').read_bytes()
        with patch('rag.shards.require_space',side_effect=OSError('disk reserve')):
            with self.assertRaisesRegex(OSError,'disk reserve'):
                export(self.db,folder,model='hash-v1',shard_rows=6,reserve_bytes=80*1024**3)
        self.assertEqual(before,json.loads((folder/'export.json').read_text(encoding='utf-8')))
        self.assertEqual(saved,(folder/'shard-000000.f32').read_bytes())
        self.assertTrue(export(self.db,folder,model='hash-v1',shard_rows=6,reserve_bytes=0)['complete'])

    def test_corruption_and_corpus_change_and_unpinned_model_are_rejected(self):
        folder = self.root / 'shards'
        export(self.db, folder, model='hash-v1', shard_rows=10, stop_after=1)
        file = folder / 'shard-000000.i8'
        data = file.read_bytes()
        file.write_bytes(data[:-1] + bytes([data[-1] ^ 1]))
        with self.assertRaisesRegex(ValueError, 'checksum'):
            export(self.db, folder, model='hash-v1', shard_rows=10)
        with self.assertRaisesRegex(ValueError, 'revision'):
            export(self.db, self.root / 'unpinned')
        file.write_bytes(data)
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("UPDATE chunks SET title='modified' WHERE id=(SELECT min(id) FROM chunks)")
        with self.assertRaisesRegex(ValueError, 'changed'):
            export(self.db, folder, model='hash-v1', shard_rows=10)

    def test_precision_is_frozen_before_any_model_reload(self):
        folder=self.root/'bf16-fixture'
        # Fixture vectors exercise the receipt contract, not model quality.
        with patch('rag.shards.encoder',return_value=HashEncoder()):
            state=export(self.db,folder,revision='f'*40,shard_rows=10,stop_after=1,compute_dtype='bfloat16')
        self.assertEqual('bfloat16',state['identity']['compute_dtype'])
        with patch('rag.shards.encoder',side_effect=AssertionError('must reject before loading')):
            with self.assertRaisesRegex(ValueError,'changed'):export(self.db,folder,revision='f'*40,shard_rows=10)

    def test_direct_shard_index_matches_assembled_bytes_without_full_float_copy(self):
        import numpy as np
        from rag.ivf import build_index,float_source
        build_index=partial(build_index,reserve_bytes=0)
        from rag.shard_source import ShardFloatSource
        from database_pipeline.common import digest
        folder=self.root/'source-shards';output=self.root/'assembled'
        export(self.db,folder,model='hash-v1',shard_rows=7)
        assemble(folder,output)
        source=ShardFloatSource(folder);reference=float_source(output/'reference.f32')
        try:
            self.assertEqual(digest(output/'reference.f32'),source.reference_sha256)
            selected=np.array([49,0,8,20,7,48],dtype=np.int64)
            np.testing.assert_array_equal(reference['vector'][selected,:256],source['vector'][selected,:256])
            np.testing.assert_array_equal(reference['id'][3:47],source['id'][3:47])
            self.assertLessEqual(len(source.cache),4)
        finally:source.close();reference._mmap.close()
        build_index(output/'reference.f32',self.root/'assembled.ivf',clusters=4,backend='sklearn')
        receipt=build_index(folder,self.root/'direct.ivf',clusters=4,backend='sklearn')
        self.assertEqual((self.root/'assembled.ivf').read_bytes(),(self.root/'direct.ivf').read_bytes())
        self.assertEqual(digest(output/'reference.f32'),receipt['reference_sha256'])
        broken=folder/'shard-000002.f32';data=broken.read_bytes();broken.write_bytes(data[:-1]+bytes([data[-1]^1]))
        with self.assertRaisesRegex(ValueError,'checksum'):build_index(folder,self.root/'refused.ivf',clusters=4,backend='sklearn')
        self.assertFalse((self.root/'refused.ivf').exists())
