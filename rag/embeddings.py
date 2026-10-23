"""Portable per-vector symmetric INT8 format; optional sentence-transformers backend."""
import math
import re
import sqlite3
import struct
import zlib
import sys
from contextlib import closing

DIM = 256
MAGIC = b"BRAGV001"
FLOAT_MAGIC = b"BRAGF001"
DEFAULT_MODEL = "google/embeddinggemma-2"
GEMMA2_PROFILE = "embeddinggemma2-search-v1"


def normalize(values):
    norm = math.sqrt(sum(float(v) ** 2 for v in values))
    return [float(v) / norm for v in values] if norm else [0.0] * len(values)


def fnv1a(word):
    value = 2166136261
    for byte in word.encode("utf-8"):
        value = ((value ^ byte) * 16777619) & 0xffffffff
    return value


class HashEncoder:
    """Fixture encoder only: lexical hashing, not a semantic model."""
    name = "hash-v1"

    def encode(self, texts, query=False):
        vectors = []
        for text in texts:
            vector = [0.0] * 768
            for word in re.findall(r"[^\W_]+", text.lower(), flags=re.UNICODE):
                value = fnv1a(word)
                vector[value % DIM] += 1.0 if value & 256 else -1.0
            vectors.append(vector)
        return vectors


class ModelEncoder:
    def __init__(self, model, device=None, revision=None, model_path=None, compute_dtype='float32'):
        self.model_identity=None
        if model==DEFAULT_MODEL and model_path:
            if revision is None:raise ValueError('Local production model requires pinned revision')
            from database_pipeline.model import verify_local
            self.model_identity=verify_local(model_path,revision)
        from sentence_transformers import SentenceTransformer
        self.name = model
        options = {}
        if model == DEFAULT_MODEL:
            import torch
            if compute_dtype not in ('float32','bfloat16'):raise ValueError('Only FP32 or explicitly validated BF16 inference is supported')
            if compute_dtype=='bfloat16' and (not device or not str(device).startswith('cuda') or not torch.cuda.is_bf16_supported()):raise ValueError('BF16 export requires a compatible CUDA device')
            if device and str(device).startswith("cuda"):
                # WDDM otherwise lets the caching allocator spill several GiB
                # into shared system memory despite a small live tensor peak.
                selected = torch.device(device)
                torch.cuda.set_per_process_memory_fraction(0.72, device=selected.index if selected.index is not None else 0)
            # Query encoder is text-only; FP16 overflows this model's activations.
            options = dict(config_kwargs={"vision_config": None, "audio_config": None},
                           model_kwargs={"torch_dtype": torch.bfloat16 if compute_dtype=='bfloat16' else torch.float32})
        if revision is not None:
            options["revision"] = revision
        self.model = SentenceTransformer(str(model_path or model), device=device, trust_remote_code=False, **options)

    def token_lengths(self, texts):
        return [len(ids) for ids in self.model.tokenizer(texts, truncation=False)["input_ids"]]

    def encode(self, texts, query=False):
        if self.name == DEFAULT_MODEL:
            # Documents already carry their real title; do not add "title: none" again.
            texts = [("task: search result | query: " + text) if query else text for text in texts]
            output = self.model.encode(texts, prompt="", convert_to_numpy=True,
                                       show_progress_bar=False).tolist()
            torch = sys.modules.get("torch")
            if torch is not None and str(getattr(self.model,"device","cpu")).startswith("cuda") and torch.cuda.memory_reserved() > torch.cuda.memory_allocated() + 1024**3:
                torch.cuda.empty_cache()
            return output
        method = self.model.encode_query if query else self.model.encode_document
        return method(texts, convert_to_numpy=True, show_progress_bar=False).tolist()


def encoder(name, device=None, revision=None, model_path=None, compute_dtype='float32'):
    return HashEncoder() if name == "hash-v1" else ModelEncoder(name, device, revision, model_path,compute_dtype)


def quantize(values):
    values = normalize(values)
    scale = max((abs(v) for v in values), default=0) / 127 or 1.0
    # Explicit half-away-from-zero rounding, identical to Java.
    quantized = [max(-127, min(127, int(math.copysign(math.floor(abs(v / scale) + .5), v)))) for v in values]
    return scale, quantized


def embed(database, output, model=DEFAULT_MODEL, reference=None, batch=32, device=None):
    if batch < 1:
        raise ValueError("batch must be positive")
    backend = encoder(model, device)
    with closing(sqlite3.connect(database)) as db, db, open(output, "xb") as target:
        count = db.execute("SELECT count(*) FROM chunks").fetchone()[0]
        target.write(struct.pack("<8sII", MAGIC, DIM, count))
        reference_file = open(reference, "xb") if reference else None
        try:
            cursor = db.execute("SELECT id,title,text_zlib FROM chunks ORDER BY id")
            reference_dim = None
            while rows := cursor.fetchmany(batch):
                texts = [(f"title: {title or 'none'} | text: " if model == DEFAULT_MODEL else title + "\n")
                         + zlib.decompress(blob).decode("utf-8") for _, title, blob in rows]
                vectors = backend.encode(texts)
                if len(vectors) != len(rows):
                    raise ValueError("Encoder row count mismatch")
                for (chunk_id, _, _), vector in zip(rows, vectors):
                    if len(vector) < DIM or not all(math.isfinite(v) for v in vector):
                        raise ValueError("Encoder must return at least 256 finite dimensions")
                    scale, values = quantize(vector[:DIM])
                    target.write(struct.pack("<qf", chunk_id, scale) + struct.pack("256b", *values))
                    if reference_file:
                        if reference_dim is None:
                            reference_dim = len(vector)
                            reference_file.write(struct.pack("<8sII", FLOAT_MAGIC, reference_dim, count))
                        if len(vector) != reference_dim:
                            raise ValueError("Encoder dimensions changed")
                        reference_file.write(struct.pack("<q", chunk_id) + struct.pack(f"<{reference_dim}f", *normalize(vector)))
            if reference_file and reference_dim is None:
                reference_file.write(struct.pack("<8sII", FLOAT_MAGIC, 768, 0))
        finally:
            if reference_file:
                reference_file.close()
        db.execute("INSERT OR REPLACE INTO metadata VALUES('embedding_model',?)", (model,))
        db.execute("INSERT OR REPLACE INTO metadata VALUES('embedding_dimension','256')")
        db.execute("DELETE FROM metadata WHERE key='embedding_profile'")
        if model == DEFAULT_MODEL:
            db.execute("INSERT INTO metadata VALUES('embedding_profile',?)", (GEMMA2_PROFILE,))


def vector_ranks(path, query, limit=20, reference=False):
    import heapq
    with open(path, "rb") as stream:
        magic, dim, count = struct.unpack("<8sII", stream.read(16))
        if magic != (FLOAT_MAGIC if reference else MAGIC) or dim < 1 or (not reference and dim != DIM):
            raise ValueError("Invalid vector header")
        q = normalize(query[:dim])
        if len(q) != dim:
            raise ValueError("Query dimension mismatch")
        if not reference:
            qscale, q = quantize(q)
        def scores():
            for _ in range(count):
                chunk_id = struct.unpack("<q", stream.read(8))[0]
                if reference:
                    values = struct.unpack(f"<{dim}f", stream.read(dim * 4))
                    score = sum(a * b for a, b in zip(q, values))
                else:
                    scale = struct.unpack("<f", stream.read(4))[0]
                    values = struct.unpack(f"{dim}b", stream.read(dim))
                    score = sum(a * b for a, b in zip(q, values)) * scale * qscale
                if score > 0:
                    yield score, -chunk_id
            if stream.read(1):
                raise ValueError("Trailing vector data")
        return [-negative_id for _, negative_id in heapq.nlargest(limit, scores())]
