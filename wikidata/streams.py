"""Optional bounded parallel bzip2 reader with source-bound seek checkpoints."""
import json
import hashlib
from pathlib import Path
import time


class IndexedDump:
    def __init__(self,source,threads,index_path,identity):
        import indexed_bzip2
        self.index_path=Path(index_path);self.identity=identity;self.saved=0.
        self.stream=indexed_bzip2.IndexedBzip2File(str(source),parallelization=threads)
        try:
            if self.index_path.exists():
                if self.index_path.stat().st_size>128*1024**2:raise ValueError('Bzip2 seek index exceeds memory limit')
                state=json.loads(self.index_path.read_text(encoding='utf-8'))
                if state.get('version')!=1 or state.get('source')!=identity:raise ValueError('Bzip2 seek index source differs')
                offsets=state['offsets'];previous_bits=-1;previous_bytes=-1
                if state.get('offsets_sha256')!=self.offset_hash(offsets):raise ValueError('Bzip2 seek index checksum mismatch')
                if not offsets:raise ValueError('Empty bzip2 seek index')
                for bits,decoded in offsets:
                    if type(bits) is not int or type(decoded) is not int or bits<=previous_bits or bits>identity['bytes']*8 or decoded<previous_bytes or decoded<0:raise ValueError('Invalid bzip2 seek offsets')
                    previous_bits,previous_bytes=bits,decoded
                # The 1.7 API treats supplied offsets as a COMPLETE index.
                # Loading a partial map would silently turn its last block into
                # EOF. Keep partial maps for diagnostics and replay normally.
                if state.get('complete') is True:self.stream.set_block_offsets(dict(offsets))
        except BaseException:
            self.stream.close();raise

    def read(self,*args):return self.stream.read(*args)
    def readline(self,*args):return self.stream.readline(*args)
    def seek(self,*args):return self.stream.seek(*args)
    def tell(self):return self.stream.tell()

    @staticmethod
    def offset_hash(offsets):return hashlib.sha256(json.dumps(offsets,separators=(',',':')).encode('ascii')).hexdigest()

    def checkpoint(self,force=False):
        if not force and time.monotonic()-self.saved<30:return
        from database_pipeline.common import atomic_json
        offsets=sorted(self.stream.available_block_offsets().items())
        if offsets:
            atomic_json(self.index_path,dict(version=1,source=self.identity,offsets=offsets,offsets_sha256=self.offset_hash(offsets),complete=bool(self.stream.block_offsets_complete())))
            self.saved=time.monotonic()

    def __enter__(self):return self
    def __exit__(self,*args):
        try:self.checkpoint(force=True)
        finally:self.stream.close()
