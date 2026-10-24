"""Pinned Wikimedia snapshots, curl resume, official hashes and local receipts."""
import json
from pathlib import Path
import re
import subprocess
import os
import uuid
import threading
import time
from concurrent.futures import ThreadPoolExecutor,as_completed
from urllib.parse import urlsplit

from rag.corpus import _writer_lock
from .common import atomic_json, digest, publish, require_space

WIKIS = {"dewiki", "dewikibooks", "dewiktionary"}


def curl(url, path, resume=False):
    if urlsplit(url).scheme != "https":
        raise ValueError("HTTPS source required")
    args = ["curl.exe" if __import__("os").name == "nt" else "curl",
            "--fail", "--location", "--show-error", "--silent",
            "--proto", "=https", "--proto-redir", "=https", "--retry", "5",
            "--retry-delay", "3", "--connect-timeout", "30",
            "--speed-limit", "1024", "--speed-time", "120"]
    if resume:
        args += ["--continue-at", "-"]
    for attempt in range(6):
        try:
            subprocess.run(args + ["--output", str(path), url], check=True)
            return
        except subprocess.CalledProcessError as error:
            # Restart curl itself: -C - then reads the NEW on-disk length after
            # a partial transfer, rather than replaying an old range offset.
            if attempt==5 or error.returncode not in (18,28,52,55,56,92):raise
            time.sleep(3)


def json_document(url,path):
    """Commit small upstream metadata only after complete, bounded JSON parsing."""
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with _writer_lock(path):
        candidate=path if path.exists() else path.with_name(path.name+'.part')
        if not path.exists():curl(url,candidate)
        if candidate.stat().st_size>64*1024**2:raise ValueError('Metadata JSON exceeds bounded download profile')
        value=json.loads(candidate.read_text(encoding='utf-8'))
        if not isinstance(value,dict) or value.get('error'):raise ValueError('Upstream metadata API error')
        if candidate!=path:publish(candidate,path)
        return value


def snapshot(root, wiki, date):
    if wiki not in WIKIS or not re.fullmatch(r"20\d{6}", date):
        raise ValueError("Unsupported wiki or unpinned snapshot")
    folder = Path(root) / "manifests"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{wiki}-{date}-status.json"
    if not path.exists():
        staged = path.with_suffix(".json.part")
        curl(f"https://dumps.wikimedia.org/{wiki}/{date}/dumpstatus.json", staged)
        value = json.loads(staged.read_text(encoding="utf-8"))
        if "jobs" not in value:
            raise ValueError("Invalid Wikimedia status document")
        publish(staged, path)
    return json.loads(path.read_text(encoding="utf-8"))


def download(root, wiki, date, filename=None):
    status = snapshot(root, wiki, date)
    filename = filename or f"{wiki}-{date}-pages-articles-multistream.xml.bz2"
    candidates = [job["files"][filename] for job in status["jobs"].values()
                  if job["status"] == "done" and filename in job.get("files", {})]
    if not candidates or Path(filename).name != filename:
        raise ValueError("File is not in a completed official dump job")
    info = candidates[0]
    folder = Path(root) / "downloads"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / filename
    part = folder / (filename + ".part")
    receipt = Path(root) / "manifests" / (filename + ".verified.json")
    with _writer_lock(target):
        if not target.exists():
            require_space(folder, max(0, info["size"] - (part.stat().st_size if part.exists() else 0)))
            if not part.exists() or part.stat().st_size!=info['size']:
                curl("https://dumps.wikimedia.org" + info["url"], part, resume=True)
        candidate = target if target.exists() else part
        if candidate.stat().st_size != info["size"]:
            raise ValueError("Download length differs from official manifest")
        hashes = {}
        for algorithm in ("sha1", "md5"):
            if info.get(algorithm):
                hashes[algorithm] = digest(candidate, algorithm)
                if hashes[algorithm] != info[algorithm]:
                    raise ValueError(f"Official {algorithm} mismatch; retaining file for diagnosis")
        if not hashes:
            raise ValueError("Official checksum missing")
        hashes["sha256"] = digest(candidate)
        if candidate == part:
            publish(part, target)
        result = dict(wiki=wiki, snapshot=date, file=filename, bytes=info["size"],
                      url="https://dumps.wikimedia.org" + info["url"],
                      hashes=hashes, official_verified=True,
                      manifest_sha256=digest(Path(root) / "manifests" / f"{wiki}-{date}-status.json"))
        atomic_json(receipt, result)
        return result


def verified_file(url,target,*,size,sha256=None,sha1=None,receipt=None):
    """Pinned official artifacts; upstream hash plus local SHA256 receipt."""
    target=Path(target).resolve();target.parent.mkdir(parents=True,exist_ok=True)
    if (sha256 is None)==(sha1 is None):raise ValueError('Exactly one official SHA256/SHA1 required')
    algorithm,expected=('sha256',sha256) if sha256 else ('sha1',sha1)
    if not re.fullmatch(r'[a-f0-9]{'+('64' if sha256 else '40')+r'}',expected):raise ValueError('Invalid official hash')
    with _writer_lock(target):
        part=target.with_name(target.name+'.part')
        if not target.exists():
            require_space(target.parent,max(0,size-(part.stat().st_size if part.exists() else 0)))
            if not part.exists() or part.stat().st_size!=size:curl(url,part,resume=True)
        candidate=target if target.exists() else part
        actual=digest(candidate)
        if candidate.stat().st_size!=size or (actual if sha256 else digest(candidate,'sha1'))!=expected:raise ValueError('Pinned file size/official hash mismatch')
        if candidate==part:publish(part,target)
        result=dict(url=url,file=str(target),bytes=size,sha256=actual,upstream_verified=True,upstream_hash={algorithm:expected})
        if receipt:atomic_json(receipt,result)
        return result


def wikidata(root,date):
    if not re.fullmatch(r'20\d{6}',date):raise ValueError('Pinned Wikidata date required')
    root=Path(root);folder=root/'manifests';folder.mkdir(parents=True,exist_ok=True)
    base=f'https://dumps.wikimedia.org/wikidatawiki/entities/{date}/'
    checksum=folder/f'wikidata-{date}-sha1sums.txt'
    if not checksum.exists():curl(base+'wikidata-'+date+'-sha1sums.txt',checksum)
    name=f'wikidata-{date}-all.json.bz2'
    expected=next((line.split()[0] for line in checksum.read_text(encoding='ascii').splitlines() if line.split()[-1]==name),None)
    if expected is None:raise ValueError('Official checksum manifest lacks requested entity dump')
    header=folder/(name+'.headers.txt')
    subprocess.run(['curl.exe' if os.name=='nt' else 'curl','--fail','--silent','--show-error','--head','--location','--proto','=https','--proto-redir','=https','--output',str(header),base+name],check=True)
    lengths=re.findall(r'^content-length:\s*(\d+)\s*$',header.read_text(encoding='ascii'),re.I|re.M)
    if not lengths:raise ValueError('Official dump size unavailable')
    return verified_file(base+name,root/'downloads'/name,size=int(lengths[-1]),sha1=expected,receipt=folder/(name+'.verified.json'))


def parallel_wikimedia(root,wiki,date,workers=4,segment_bytes=64*1024**2):
    """Bounded independent HTTP ranges; completed ranges survive interruptions.

    Range hashes are local resume guards. Only official whole-file hashes confer
    verified status. Existing single-stream partial data can seed complete ranges.
    """
    if not 1<=workers<=8 or segment_bytes<1024**2:raise ValueError('Invalid download concurrency/segment size')
    root=Path(root);status=snapshot(root,wiki,date);name=f'{wiki}-{date}-pages-articles-multistream.xml.bz2'
    info=next((job['files'][name] for job in status['jobs'].values() if job['status']=='done' and name in job.get('files',{})),None)
    if info is None:raise ValueError('No completed official multistream file')
    target=root/'downloads'/name;target.parent.mkdir(parents=True,exist_ok=True)
    with _writer_lock(target):
        if target.exists():
            # Validate below after releasing this writer lock.
            pass
        else:
            require_space(target.parent,info['size']*2)
            folder=target.with_name(name+'.segments');folder.mkdir(exist_ok=True)
            identity=dict(url='https://dumps.wikimedia.org'+info['url'],bytes=info['size'],sha1=info.get('sha1'),segment_bytes=segment_bytes)
            identity_file=folder/'identity.json'
            if identity_file.exists() and json.loads(identity_file.read_text(encoding='utf-8'))!=identity:raise ValueError('Range source/settings changed')
            atomic_json(identity_file,identity)
            count=(info['size']+segment_bytes-1)//segment_bytes
            stopped=threading.Event()
            def receipt(number,file):
                start=number*segment_bytes;length=min(segment_bytes,info['size']-start)
                return dict(number=number,start=start,bytes=length,sha256=digest(file))
            # The prior stream is never deleted or written by this downloader.
            prefix=target.with_name(name+'.part')
            if prefix.exists():
                with prefix.open('rb') as source:
                    for number in range(min(count,prefix.stat().st_size//segment_bytes)):
                        file=folder/f'{number:06d}.bin';record=file.with_suffix('.json')
                        if record.exists():continue
                        source.seek(number*segment_bytes)
                        with file.with_suffix('.part').open('wb') as out:
                            remaining=segment_bytes
                            while remaining:
                                block=source.read(min(remaining,1024**2))
                                if not block:raise ValueError('Partial source changed during range seeding')
                                out.write(block);remaining-=len(block)
                            out.flush();os.fsync(out.fileno())
                        if file.exists():file.rename(file.with_suffix('.orphan'))
                        publish(file.with_suffix('.part'),file);atomic_json(record,receipt(number,file))
            def fetch_range(number):
                if stopped.is_set():raise RuntimeError('Download stopped after a failed range; resume later')
                file=folder/f'{number:06d}.bin';record=file.with_suffix('.json')
                start=number*segment_bytes;end=min(info['size'],start+segment_bytes)-1
                if record.exists():
                    data=json.loads(record.read_text(encoding='utf-8'))
                    if data!=receipt(number,file) or file.stat().st_size!=end-start+1:raise ValueError('Range checkpoint checksum mismatch')
                    return number
                # A killed parent may leave curl alive. Unique attempt files
                # prevent an orphan from writing a resumed attempt's output.
                part=file.with_name(file.name+'.part-'+uuid.uuid4().hex);headers=part.with_name(part.name+'.headers')
                try:
                    # No automatic retry storm on HTTP 429. Preserve all completed
                    # ranges and require a later, lower-concurrency continuation.
                    subprocess.run(['curl.exe' if os.name=='nt' else 'curl','--fail','--silent','--show-error','--location','--proto','=https','--proto-redir','=https','--connect-timeout','30','--speed-time','120','--speed-limit','1024','--max-filesize',str(end-start+1),'--range',f'{start}-{end}','--dump-header',str(headers),'--output',str(part),identity['url']],check=True)
                except BaseException:
                    stopped.set()
                    raise
                header=headers.read_text(encoding='iso-8859-1')
                content_range=re.findall(r'(?im)^content-range:\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$',header)
                if not content_range or tuple(map(int,content_range[-1]))!=(start,end,info['size']) or part.stat().st_size!=end-start+1:raise ValueError('Server did not return requested exact byte range')
                if file.exists():raise FileExistsError(file)
                publish(part,file);atomic_json(record,receipt(number,file));return number
            completed=0
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures=[pool.submit(fetch_range,n) for n in range(count)]
                try:
                    for future in as_completed(futures):
                        future.result();completed+=1
                        print(json.dumps(dict(download=name,completed_ranges=completed,total_ranges=count)),flush=True)
                except BaseException:
                    stopped.set()
                    for future in futures:future.cancel()
                    raise
            assembled=target.with_name(name+'.ranged.part')
            with assembled.open('wb') as out:
                for number in range(count):
                    file=folder/f'{number:06d}.bin'
                    with file.open('rb') as source:
                        while block:=source.read(4*1024**2):out.write(block)
                out.flush();os.fsync(out.fileno())
            for algorithm in ('sha1','md5'):
                if info.get(algorithm) and digest(assembled,algorithm)!=info[algorithm]:raise ValueError('Official whole-file checksum mismatch after range assembly')
            publish(assembled,target)
    return download(root,wiki,date)
