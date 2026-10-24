"""Fetch every model/tokenizer file at one pinned upstream commit."""
import json
from pathlib import Path
import re
import hashlib

from .common import atomic_json, digest, publish, require_space
from .download import curl,json_document
from rag.corpus import _writer_lock


def fetch(root, revision):
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Require immutable model revision")
    root = Path(root)
    directory = root / "models" / ("embeddinggemma-2-" + revision)
    directory.mkdir(parents=True, exist_ok=True)
    with _writer_lock(directory / "download"):
        manifest = root / "manifests" / ("embeddinggemma-2-"+revision+".json")
        info=json_document(f"https://huggingface.co/api/models/google/embeddinggemma-2/revision/{revision}?blobs=true",manifest)
        if info["sha"] != revision:
            raise ValueError("Pinned model metadata mismatch")
        receipts = []
        require_space(directory, sum(x["size"] for x in info["siblings"] if not (directory / x["rfilename"]).exists()))
        for item in info["siblings"]:
            name = item["rfilename"]
            path = directory / name
            if path.resolve().is_relative_to(directory.resolve()) is False:
                raise ValueError("Unsafe model file path")
            path.parent.mkdir(parents=True, exist_ok=True)
            part = path.with_name(path.name + ".part")
            if not path.exists():
                if not part.exists() or part.stat().st_size!=item['size']:curl(f"https://huggingface.co/google/embeddinggemma-2/resolve/{revision}/{name}", part, resume=True)
            candidate = path if path.exists() else part
            checksum = digest(candidate)
            if candidate.stat().st_size != item["size"] or (item.get("lfs") and checksum != item["lfs"]["sha256"]):
                raise ValueError("Model upstream size/hash mismatch: " + name)
            if not item.get('lfs'):
                blob=hashlib.sha1(b'blob '+str(item['size']).encode('ascii')+b'\0'+candidate.read_bytes()).hexdigest()
                if blob!=item['blobId']:raise ValueError('Model Git blob hash mismatch: '+name)
            if candidate == part:
                publish(part, path)
            receipts.append(dict(file=name, bytes=item["size"], sha256=checksum))
        atomic_json(directory / "model-lock.json", dict(model="google/embeddinggemma-2", revision=revision,
                    license=info.get("cardData", {}).get("license"), files=receipts))
    return str(directory.resolve())


def verify_local(directory,revision):
    """Verify every locked byte before inference, not just a model directory name."""
    directory=Path(directory).resolve();path=directory/'model-lock.json'
    lock=json.loads(path.read_text(encoding='utf-8'))
    if lock.get('model')!='google/embeddinggemma-2' or lock.get('revision')!=revision:raise ValueError('Local model revision differs from requested profile')
    files={item['file']:item for item in lock['files']}
    required={'model.safetensors','tokenizer.json','tokenizer.model','config.json','modules.json'}
    if len(files)!=len(lock['files']) or not required.issubset(files):raise ValueError('Incomplete/duplicate model lock')
    pins={'model.safetensors':'197a32965d4b1105faf060417baa899e193fb73cd401f42ec9295234d5553d79',
          'tokenizer.json':'4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4',
          'tokenizer.model':'e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03'} if revision=='914f7f89142e33e77833254d9c9b90c3cef7303b' else {}
    for name,item in files.items():
        file=(directory/name).resolve()
        if not file.is_relative_to(directory) or file.stat().st_size!=item['bytes'] or digest(file)!=item['sha256']:raise ValueError('Local model checksum/size mismatch: '+name)
        if name in pins and item['sha256']!=pins[name]:raise ValueError('Known upstream model/tokenizer pin mismatch')
    return dict(revision=revision,model_lock_sha256=digest(path),files={name:files[name]['sha256'] for name in required})
