"""Reclaim only our reproducible download fragments after full verification.

Never removes raw published dumps, models, database generations or embeddings.
All candidate paths and bytes are checked before the first unlink operation.
"""
import hashlib
import json
from pathlib import Path
import re
import time
from rag.corpus import _writer_lock
from .common import digest,atomic_json


def completed_ranges(root,wiki,date):
    if wiki not in ('dewiki','dewikibooks','dewiktionary') or not re.fullmatch(r'20\d{6}',date):raise ValueError('Pinned supported Wikimedia source required')
    root=Path(root).resolve();directory=(root/'downloads').resolve()
    if not directory.is_relative_to(root):raise ValueError('Download directory escapes configured data root')
    name=f'{wiki}-{date}-pages-articles-multistream.xml.bz2';target=directory/name
    receipt_path=root/'manifests'/(name+'.verified.json')
    with _writer_lock(target):
        receipt=json.loads(receipt_path.read_text(encoding='utf-8'))
        if not receipt.get('official_verified') or not target.is_file() or target.stat().st_size!=receipt['bytes'] or digest(target)!=receipt['hashes']['sha256']:raise ValueError('Unchanged officially verified complete dump required')
        folder=directory/(name+'.segments');plan=[]
        if folder.exists():
            if folder.is_symlink() or folder.resolve().parent!=directory:raise ValueError('Unsafe segment directory')
            identity=json.loads((folder/'identity.json').read_text(encoding='utf-8'))
            if identity['bytes']!=receipt['bytes'] or identity['sha1']!=receipt['hashes']['sha1']:raise ValueError('Segment source mismatch')
            for file in folder.glob('*.bin'):
                if not re.fullmatch(r'\d{6}\.bin',file.name) or file.is_symlink() or file.resolve().parent!=folder.resolve():raise ValueError('Unexpected segment path')
                info=json.loads(file.with_suffix('.json').read_text(encoding='utf-8'))
                number=int(file.stem);start=number*identity['segment_bytes'];length=min(identity['segment_bytes'],receipt['bytes']-start)
                if length<1 or info['number']!=number or info['start']!=start or info['bytes']!=length:raise ValueError('Unexpected segment extent')
                if file.stat().st_size!=info['bytes'] or digest(file)!=info['sha256']:raise ValueError('Changed segment retained for diagnosis')
                checksum=hashlib.sha256();remaining=length
                with target.open('rb') as source:
                    source.seek(start)
                    while remaining:
                        block=source.read(min(4*1024**2,remaining))
                        if not block:raise ValueError('Published dump changed during verification')
                        checksum.update(block);remaining-=len(block)
                if checksum.hexdigest()!=info['sha256']:raise ValueError('Segment is not reproducible from verified dump')
                plan.append(file)
        prefix=directory/(name+'.part')
        if prefix.exists():
            if prefix.is_symlink() or prefix.resolve().parent!=directory or prefix.stat().st_size>target.stat().st_size:raise ValueError('Unsafe download prefix')
            checksum=hashlib.sha256();remaining=prefix.stat().st_size
            with target.open('rb') as source:
                while remaining:
                    block=source.read(min(4*1024**2,remaining))
                    if not block:raise ValueError('Published dump changed during verification')
                    checksum.update(block);remaining-=len(block)
            if digest(prefix)!=checksum.hexdigest():raise ValueError('Nonmatching partial download retained for diagnosis')
            plan.append(prefix)
        reclaimed=sum(file.stat().st_size for file in plan)
        for file in plan:file.unlink()
        result=dict(source=str(target),source_sha256=receipt['hashes']['sha256'],reclaimed_bytes=reclaimed,removed_only_reproducible_download_fragments=[str(file.relative_to(directory)) for file in plan],retained='verified full dump, all receipts/headers, database and embedding generations')
        atomic_json(root/'logs'/(name+'.cleanup-'+str(time.time_ns())+'.json'),result)
        return result
