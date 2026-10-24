import hashlib,json
from functools import partial
from pathlib import Path
import tempfile,unittest
from unittest.mock import patch
from database_pipeline.download import parallel_wikimedia,verified_file,curl,json_document
from database_pipeline.common import require_space
import subprocess


class DownloadTests(unittest.TestCase):
    def setUp(self):
        # Fake downloads are at most a few MiB; retain real byte checks while
        # excluding unused production reserve from these isolated fixtures.
        guard=patch('database_pipeline.download.require_space',partial(require_space,reserve=0))
        guard.start();self.addCleanup(guard.stop)

    def test_incomplete_metadata_is_not_published_and_retries_a_fresh_document(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'metadata.json'
            def broken(url,path,resume=False):path.write_bytes(b'{"files":')
            with patch('database_pipeline.download.curl',side_effect=broken):
                with self.assertRaises(json.JSONDecodeError):json_document('https://archive.org/metadata/fixture',path)
            self.assertFalse(path.exists())
            def complete(url,path,resume=False):path.write_bytes(b'{"files":[]}')
            with patch('database_pipeline.download.curl',side_effect=complete):self.assertEqual({'files':[]},json_document('https://archive.org/metadata/fixture',path))
            with patch('database_pipeline.download.curl',side_effect=AssertionError('reuse immutable metadata')):json_document('https://archive.org/metadata/fixture',path)
    def test_receive_failure_restarts_from_new_partial_length_and_full_partial_needs_no_get(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'dump.bz2';part=path.with_name(path.name+'.part');calls=[]
            def transfer(args,**kwargs):
                calls.append(part.stat().st_size if part.exists() else 0)
                with part.open('ab') as stream:stream.write(b'abc' if len(calls)==1 else b'def')
                if len(calls)==1:raise subprocess.CalledProcessError(56,args)
            with patch('database_pipeline.download.subprocess.run',side_effect=transfer),patch('database_pipeline.download.time.sleep'):
                curl('https://dumps.wikimedia.org/fixture',part,resume=True)
            self.assertEqual([0,3],calls)
            with patch('database_pipeline.download.curl',side_effect=AssertionError('Complete partial must be verified first')):
                result=verified_file('https://dumps.wikimedia.org/fixture',path,size=6,sha256=hashlib.sha256(b'abcdef').hexdigest())
            self.assertTrue(result['upstream_verified']);self.assertEqual(b'abcdef',path.read_bytes())

    def test_official_sha1_download_records_sha256_and_never_publishes_corruption(self):
        payload=b'official entity dump bytes'
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'dump.bz2'
            def fetch(url,output,resume=False):Path(output).write_bytes(payload)
            with patch('database_pipeline.download.curl',side_effect=fetch):
                result=verified_file('https://dumps.wikimedia.org/fixture',path,size=len(payload),sha1=hashlib.sha1(payload).hexdigest())
                self.assertEqual(hashlib.sha256(payload).hexdigest(),result['sha256'])
                with self.assertRaisesRegex(ValueError,'official hash'):verified_file('https://dumps.wikimedia.org/fixture',Path(temp)/'bad.bz2',size=len(payload),sha1='0'*40)
                self.assertFalse((Path(temp)/'bad.bz2').exists())

    def test_range_resume_publishes_only_after_official_whole_file_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'manifests').mkdir();(root/'downloads').mkdir()
            name='dewiki-20261001-pages-articles-multistream.xml.bz2'
            payload=b'a'*(1024**2)+b'b'*(1024**2)+b'c'*8192
            info=dict(size=len(payload),url='/dewiki/20261001/'+name,sha1=hashlib.sha1(payload).hexdigest(),md5=hashlib.md5(payload).hexdigest())
            manifest=dict(jobs={'articles':dict(status='done',files={name:info})})
            (root/'manifests/dewiki-20261001-status.json').write_text(json.dumps(manifest),encoding='utf-8')
            calls=[]
            def fetch(args,**kwargs):
                start,end=map(int,args[args.index('--range')+1].split('-'));calls.append((start,end))
                Path(args[args.index('--output')+1]).write_bytes(payload[start:end+1])
                Path(args[args.index('--dump-header')+1]).write_text(f'HTTP/1.1 206 Partial Content\nContent-Range: bytes {start}-{end}/{len(payload)}\n',encoding='ascii')
            with patch('database_pipeline.download.subprocess.run',side_effect=fetch):
                first=parallel_wikimedia(root,'dewiki','20261001',workers=2,segment_bytes=1024**2)
                self.assertEqual(payload,(root/'downloads'/name).read_bytes());self.assertTrue(first['official_verified'])
                self.assertEqual(3,len(calls))
                # Resume a completed transfer performs verification, no new GET.
                parallel_wikimedia(root,'dewiki','20261001',workers=2,segment_bytes=1024**2)
                self.assertEqual(3,len(calls))
            from database_pipeline.cleanup import completed_ranges
            prefix=root/'downloads'/(name+'.part');prefix.write_bytes(payload[:1024**2])
            prefix.write_bytes(b'changed partial retained for diagnosis')
            with self.assertRaisesRegex(ValueError,'Nonmatching partial'):
                completed_ranges(root,'dewiki','20261001')
            self.assertEqual(3,len(list((root/'downloads'/(name+'.segments')).glob('*.bin'))))
            self.assertTrue(prefix.exists())
            prefix.write_bytes(payload[:1024**2])
            result=completed_ranges(root,'dewiki','20261001')
            self.assertEqual(len(payload)+1024**2,result['reclaimed_bytes'])
            self.assertEqual(payload,(root/'downloads'/name).read_bytes());self.assertFalse(prefix.exists())
            self.assertFalse(list((root/'downloads'/(name+'.segments')).glob('*.bin')))
            bad=dict(info,sha1='0'*40);manifest['jobs']['articles']['files'][name]=bad
            other=root/'new';(other/'manifests').mkdir(parents=True);(other/'manifests/dewiki-20261001-status.json').write_text(json.dumps(manifest),encoding='utf-8')
            with patch('database_pipeline.download.subprocess.run',side_effect=fetch):
                with self.assertRaisesRegex(ValueError,'whole-file'):parallel_wikimedia(other,'dewiki','20261001',workers=2,segment_bytes=1024**2)
                self.assertFalse((other/'downloads'/name).exists())
