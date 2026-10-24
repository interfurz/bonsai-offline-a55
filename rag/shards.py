"""Crash-safe embedding shards; original BRAGV001/BRAGF001 formats are retained.

A shard becomes reusable only after both files are fsynced, hashed, and its JSON
receipt is atomically committed. Uncommitted .part files may be regenerated.
The database must be a quiescent standalone generation, never a live importer.
"""
from contextlib import closing
import json
import hashlib
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import struct
import time
import sys
import zlib

from database_pipeline.common import atomic_json, digest, publish, require_space
from .corpus import _writer_lock
from .embeddings import DEFAULT_MODEL, GEMMA2_PROFILE, DIM, MAGIC, FLOAT_MAGIC, encoder, normalize, quantize


def export(database, directory, *, model=DEFAULT_MODEL, revision=None, device=None,
           batch=32, shard_rows=8192, stop_after=None, model_path=None,compute_dtype='float32',reserve_bytes=5*1024**3):
    if batch < 1 or shard_rows < 1 or (stop_after is not None and stop_after < 1):
        raise ValueError("Positive batch/shard/stop limits required")
    if model != "hash-v1" and not re.fullmatch(r"[0-9a-f]{40}", revision or ""):
        raise ValueError("Production embeddings require an immutable 40-character model revision")
    if compute_dtype not in ('float32','bfloat16') or (model=='hash-v1' and compute_dtype!='float32'):raise ValueError('Unsupported inference dtype')
    if reserve_bytes<0:raise ValueError('Nonnegative disk reserve required')
    database, directory = Path(database).resolve(), Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    with _writer_lock(database), _writer_lock(directory / "export"):
        if any(Path(str(database) + suffix).exists() for suffix in ("-wal", "-journal")):
            raise ValueError("Database has a journal; finish ingestion and close all writers first")
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            if metadata.get("ingest_status") not in ("complete", "limited"):
                raise ValueError("Only completed or explicitly limited corpora may be vectorized")
            count = db.execute("SELECT count(*) FROM chunks").fetchone()[0]
            identity = dict(version=1, database_sha256=digest(database), count=count,
                            model=model, revision=revision, profile=GEMMA2_PROFILE if model == DEFAULT_MODEL else model,
                            dimension=DIM, shard_rows=shard_rows)
            # Absence means FP32 for compatibility with already sealed exports.
            # BF16 is a separate immutable generation, never mixed into FP32.
            if compute_dtype!='float32':identity['compute_dtype']=compute_dtype
            checkpoint = directory / "export.json"
            if checkpoint.exists():
                state = json.loads(checkpoint.read_text(encoding="utf-8"))
                if state["identity"] != identity:
                    raise ValueError("Corpus/model/revision/shard settings changed; create another generation")
            else:
                state = dict(identity=identity, complete=False, shards=[])
                atomic_json(checkpoint, state)
            last, total, dimension = 0, 0, None
            for number, info in enumerate(state["shards"]):
                if info["number"] != number or info["first_id"] <= last or info["count"] < 1:
                    raise ValueError("Invalid checkpoint order")
                for kind in ("i8", "f32"):
                    file = directory / f"shard-{number:06d}.{kind}"
                    if file.stat().st_size != info[kind + "_bytes"] or digest(file) != info[kind + "_sha256"]:
                        raise ValueError("Committed shard checksum mismatch: " + str(file))
                if dimension is not None and dimension != info["native_dimension"]:
                    raise ValueError("Shard dimension mismatch")
                dimension = info["native_dimension"]
                last, total = info["last_id"], total + info["count"]
            if total > count or (state["complete"] and total != count):
                raise ValueError("Checkpoint row count mismatch")
            if state["complete"]:
                return state
            backend = None  # Never reload the model for each shard.
            cursor = db.execute("SELECT id,title,text_zlib FROM chunks WHERE id>? ORDER BY id", (last,))
            generated = 0
            try:
                while total < count:
                    number, expected = len(state["shards"]), min(shard_rows, count - total)
                    require_space(directory, expected * (12 + DIM + 8 + 4 * max(768,dimension or 0)),reserve=reserve_bytes)
                    paths = {kind: directory / f"shard-{number:06d}.{kind}" for kind in ("i8", "f32")}
                    # Files without a committed receipt are not trusted. Preserve them
                    # for diagnosis, never mistake them for completed work.
                    for file in paths.values():
                        if file.exists():
                            file.rename(file.with_name(file.name + f".orphan-{time.time_ns()}"))
                    started, written, first = time.monotonic(), 0, None
                    token_stats = dict(count=0, maximum=0, total=0, over_800=0)
                    with paths["i8"].with_suffix(".i8.part").open("wb") as i8, paths["f32"].with_suffix(".f32.part").open("wb") as f32:
                        i8.write(struct.pack("<8sII", MAGIC, DIM, expected))
                        while written < expected:
                            rows = cursor.fetchmany(min(batch, expected - written))
                            if not rows:
                                raise ValueError("Corpus ended before recorded count")
                            texts = [(f"title: {title or 'none'} | text: " if model == DEFAULT_MODEL else title + "\n")
                                     + zlib.decompress(blob).decode("utf-8") for _, title, blob in rows]
                            if backend is None:
                                backend = encoder(model, device, revision, model_path) if compute_dtype=='float32' else encoder(model,device,revision,model_path,compute_dtype)
                                verified=getattr(backend,'model_identity',None)
                                if verified:
                                    if state.get('model_hashes',verified)!=verified:raise ValueError('Model lock changed since export checkpoint')
                                    state['model_hashes']=verified
                            if hasattr(backend, "token_lengths"):
                                lengths = backend.token_lengths(texts)
                                if max(lengths) > backend.model.max_seq_length:
                                    raise ValueError("Document exceeds actual tokenizer context; truncation refused")
                                token_stats["count"] += len(lengths)
                                token_stats["maximum"] = max(token_stats["maximum"], max(lengths))
                                token_stats["total"] += sum(lengths)
                                token_stats["over_800"] += sum(n > 800 for n in lengths)
                            vectors = backend.encode(texts)
                            if len(vectors) != len(rows):
                                raise ValueError("Encoder batch length mismatch")
                            for (chunk_id, _, _), vector in zip(rows, vectors):
                                if chunk_id <= last or len(vector) < DIM or not all(math.isfinite(v) for v in vector):
                                    raise ValueError("Invalid vector or non-increasing chunk ID")
                                if dimension is None:
                                    dimension = len(vector)
                                if len(vector) != dimension or not any(vector[:DIM]):
                                    raise ValueError("Zero vector or native dimension changed")
                                if written == 0:
                                    f32.write(struct.pack("<8sII", FLOAT_MAGIC, dimension, expected))
                                    first = chunk_id
                                scale, values = quantize(vector[:DIM])
                                i8.write(struct.pack("<qf256b", chunk_id, scale, *values))
                                f32.write(struct.pack(f"<q{dimension}f", chunk_id, *normalize(vector)))
                                last, written = chunk_id, written + 1
                        for stream in (i8, f32):
                            stream.flush()
                            os.fsync(stream.fileno())
                    for kind, file in paths.items():
                        publish(file.with_suffix("." + kind + ".part"), file)
                    info = dict(number=number, count=written, first_id=first, last_id=last,
                                native_dimension=dimension, seconds=time.monotonic() - started,
                                actual_tokens=token_stats)
                    torch = sys.modules.get("torch")
                    if torch is not None and torch.cuda.is_available():
                        info["cuda_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
                        info["cuda_peak_reserved_bytes"] = torch.cuda.max_memory_reserved()
                    for kind, file in paths.items():
                        info[kind + "_bytes"], info[kind + "_sha256"] = file.stat().st_size, digest(file)
                    state["shards"].append(info)
                    total += written
                    state["complete"] = total == count
                    atomic_json(checkpoint, state)
                    print(json.dumps(dict(shard=number, rows=total, count=count, seconds=info["seconds"])), flush=True)
                    generated += 1
                    if stop_after and generated >= stop_after:
                        return state
                if count == 0:
                    state["complete"] = True
                    atomic_json(checkpoint, state)
                return state
            except BaseException as exc:
                with (directory / "errors.jsonl").open("a", encoding="utf-8") as log:
                    log.write(json.dumps(dict(time=time.time(), committed_rows=total, error=repr(exc))) + "\n")
                raise


def assemble(directory, output, *, reserve_bytes=5*1024**3):
    """Produce legacy Android/index inputs without holding all IDs in RAM."""
    if reserve_bytes < 0:
        raise ValueError("Nonnegative disk reserve required")
    directory, output = Path(directory), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with _writer_lock(directory / "export"), _writer_lock(output / "assemble"):
        state = json.loads((directory / "export.json").read_text(encoding="utf-8"))
        if not state["complete"]:
            raise ValueError("Embedding export is incomplete")
        count = state["identity"]["count"]
        if count > 0xffffffff:
            raise ValueError("Legacy BRAG formats support at most uint32 rows")
        for kind, name, magic, dim in (("i8", "vectors.i8", MAGIC, DIM),
                                      ("f32", "reference.f32", FLOAT_MAGIC, state["shards"][0]["native_dimension"] if count else 768)):
            target = output / name
            if target.exists():
                raise FileExistsError(target)
            require_space(output, 16 + sum(info[kind + "_bytes"] - 16 for info in state["shards"]),reserve=reserve_bytes)
            part = target.with_suffix(target.suffix + ".part")
            with part.open("wb") as stream:
                stream.write(struct.pack("<8sII", magic, dim, count))
                previous, rows = 0, 0
                record_size = 12 + DIM if kind == "i8" else 8 + 4 * dim
                for number, info in enumerate(state["shards"]):
                    file = directory / f"shard-{number:06d}.{kind}"
                    if digest(file) != info[kind + "_sha256"] or file.stat().st_size != 16 + info["count"] * record_size:
                        raise ValueError("Shard hash/size mismatch")
                    with file.open("rb") as source:
                        if source.read(16) != struct.pack("<8sII", magic, dim, info["count"]):
                            raise ValueError("Shard header mismatch")
                        for _ in range(info["count"]):
                            record = source.read(record_size)
                            chunk_id = struct.unpack_from("<q", record)[0]
                            if chunk_id <= previous:
                                raise ValueError("Shard IDs overlap or are unordered")
                            previous, rows = chunk_id, rows + 1
                            stream.write(record)
                if rows != count:
                    raise ValueError("Missing shard rows")
                stream.flush()
                os.fsync(stream.fileno())
            publish(part, target)
        atomic_json(output / "embedding.json", state["identity"])
        verify_assembly(directory,output)
        return dict(count=count, identity=state["identity"])


def verify_assembly(directory,output):
    """Bind assembled bytes to committed shards, including after a prior version."""
    directory,output=Path(directory),Path(output)
    state=json.loads((directory/'export.json').read_text(encoding='utf-8'))
    if not state['complete']:raise ValueError('Incomplete export')
    count=state['identity']['count'];files={}
    for kind,name,magic,dim in [('i8','vectors.i8',MAGIC,DIM),('f32','reference.f32',FLOAT_MAGIC,state['shards'][0]['native_dimension'] if count else 768)]:
        expected=hashlib.sha256(struct.pack('<8sII',magic,dim,count))
        for number,info in enumerate(state['shards']):
            path=directory/f'shard-{number:06d}.{kind}'
            if digest(path)!=info[kind+'_sha256']:raise ValueError('Committed shard checksum mismatch')
            with path.open('rb') as stream:
                if stream.read(16)!=struct.pack('<8sII',magic,dim,info['count']):raise ValueError('Shard header mismatch')
                while block:=stream.read(4*1024**2):expected.update(block)
        actual=digest(output/name)
        if actual!=expected.hexdigest():raise ValueError('Assembled bytes differ from committed shards')
        files[name]=dict(sha256=actual,bytes=(output/name).stat().st_size)
    receipt=dict(identity=state['identity'],files=files)
    atomic_json(output/'assembly.json',receipt)
    return receipt
