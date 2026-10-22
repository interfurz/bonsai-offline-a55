"""Versioned, mmap-readable IVF-INT8 index. All integers/floats little endian."""
import argparse
import heapq
import json
import math
import os
from contextlib import ExitStack
from pathlib import Path
import struct
import tempfile
import time
import numpy as np

DIM = 256
MAGIC = b"BRAGIVF1"
VERSION = 1
HEADER = struct.Struct("<8sIIIIQfI6Q40s")
DEFAULT_CLUSTERS = 4096
DEFAULT_NPROBE = 16


def normalized(values):
    values = np.asarray(values, dtype=np.float32)
    if values.shape[-1] != DIM or not np.isfinite(values).all():
        raise ValueError("Require finite 256D vectors")
    norms = np.linalg.norm(values.astype(np.float64), axis=-1, keepdims=True)
    return (values / np.where(norms == 0, 1, norms)).astype(np.float32)


def quantized(values, scale):
    scaled = np.asarray(values, dtype=np.float64) / scale
    return np.clip(np.sign(scaled) * np.floor(np.abs(scaled) + .5), -127, 127).astype(np.int8)


def layout(count, clusters):
    offsets = HEADER.size
    centroids = offsets + (clusters + 1) * 8
    ids = centroids + clusters * DIM * 4
    vectors = ((ids + count * 8 + 4095) // 4096) * 4096
    return offsets, centroids, ids, vectors, vectors + count * DIM


def create_index(path, count, centroids, offsets, scale, seed=42):
    """Allocate a file; caller fills its ID/vector maps in cluster order."""
    path = Path(path)
    centroids = np.asarray(centroids, dtype='<f4')
    clusters = len(centroids)
    offsets = np.asarray(offsets, dtype='<u8')
    if (centroids.shape != (clusters, DIM) or clusters < 1 or count < 0
            or offsets.shape != (clusters + 1,) or offsets[0] != 0 or offsets[-1] != count
            or np.any(offsets[1:] < offsets[:-1]) or not np.isfinite(centroids).all()
            or not math.isfinite(scale) or scale <= 0):
        raise ValueError("Invalid IVF metadata")
    positions = layout(count, clusters)
    with path.open('xb') as stream:
        stream.write(HEADER.pack(MAGIC, VERSION, HEADER.size, DIM, clusters, count,
                                 scale, 1, *positions, seed, bytes(40)))
        stream.seek(positions[0]); stream.write(offsets.tobytes())
        stream.seek(positions[1]); stream.write(centroids.tobytes())
        stream.truncate(positions[-1])
    return IvfIndex(path)


class IvfIndex:
    def __init__(self, path):
        self.path = Path(path)
        with self.path.open('rb') as stream:
            header = stream.read(HEADER.size)
        if len(header) != HEADER.size:
            raise ValueError("Truncated IVF header")
        (magic, version, size, dim, clusters, count, scale, flags,
         offsets, centers, ids, vectors, file_bytes, seed, reserved) = HEADER.unpack(header)
        if (magic != MAGIC or version != VERSION or size != HEADER.size or dim != DIM
                or clusters < 1 or flags != 1 or reserved != bytes(40)
                or not math.isfinite(scale) or scale <= 0
                or (offsets, centers, ids, vectors, file_bytes) != layout(count, clusters)
                or self.path.stat().st_size != file_bytes):
            raise ValueError("Invalid IVF header/layout")
        self.count, self.clusters, self.scale, self.seed = count, clusters, scale, seed
        self.offsets = np.memmap(path, dtype='<u8', mode='r', offset=offsets, shape=(clusters + 1,))
        self.centroids = np.memmap(path, dtype='<f4', mode='r', offset=centers, shape=(clusters, DIM))
        if (self.offsets[0] != 0 or self.offsets[-1] != count
                or np.any(self.offsets[1:] < self.offsets[:-1]) or not np.isfinite(self.centroids).all()):
            raise ValueError("Invalid IVF offsets/centroids")
        # Zero-length memmap is not portable, so use empty arrays for an empty corpus.
        self.ids = np.memmap(path, dtype='<i8', mode='r', offset=ids, shape=(count,)) if count else np.empty(0, dtype='<i8')
        self.vectors = np.memmap(path, dtype='i1', mode='r', offset=vectors, shape=(count, DIM)) if count else np.empty((0, DIM), dtype='i1')
        self.ids.flags.writeable = False
        self.vectors.flags.writeable = False

    def search(self, query, nprobe=DEFAULT_NPROBE, top_k=20, positive_only=True, batch=8192, full_scan=False):
        if nprobe < 1 or top_k < 1 or batch < 1:
            raise ValueError("nprobe, top_k and batch must be positive")
        query = np.asarray(query, dtype=np.float32)
        if query.ndim != 1 or query.size < DIM:
            raise ValueError("Require one query vector with >=256 dimensions")
        query = normalized(query[:DIM])
        q = quantized(query, self.scale).astype(np.int32)
        start = time.perf_counter_ns()
        # Distance to the actual k-means center (centers need not have unit norm).
        if full_scan:
            chosen = range(self.clusters)
        else:
            distances = np.einsum('ij,ij->i', self.centroids, self.centroids) - 2 * (self.centroids @ query)
            chosen = heapq.nsmallest(min(nprobe, self.clusters), range(self.clusters), key=lambda i: (float(distances[i]), i))
        centroid_ns = time.perf_counter_ns() - start
        start = time.perf_counter_ns()
        heap, scanned = [], 0
        for cluster in chosen:
            lo, hi = int(self.offsets[cluster]), int(self.offsets[cluster + 1])
            scanned += hi - lo
            for position in range(lo, hi, batch):
                end = min(position + batch, hi)
                # int32 accumulation: 256*127^2 fits; never numpy int8 dot.
                scores = self.vectors[position:end].astype(np.int32) @ q
                # At most k local candidates can enter the global top-k heap.
                eligible = np.flatnonzero(scores > 0) if positive_only else np.arange(len(scores))
                if len(eligible) > top_k:
                    cutoff = np.partition(scores[eligible], -top_k)[-top_k]
                    eligible = eligible[scores[eligible] >= cutoff]
                for row in eligible:
                    chunk_id = int(self.ids[position + row])
                    if chunk_id <= 0:
                        raise ValueError("Invalid IVF chunk ID")
                    item = (int(scores[row]), -chunk_id)
                    if len(heap) < top_k:
                        heapq.heappush(heap, item)
                    elif item > heap[0]:
                        heapq.heapreplace(heap, item)
        scan_ns = time.perf_counter_ns() - start
        results = [(-negative_id, score) for score, negative_id in sorted(heap, reverse=True)]
        return dict(ids=[r[0] for r in results], dots=[r[1] for r in results],
                    centroid_ms=centroid_ns / 1e6, scan_ms=scan_ns / 1e6,
                    scanned=scanned, nprobe=len(chosen))


def float_source(path):
    from .embeddings import FLOAT_MAGIC
    with open(path, 'rb') as stream:
        header = stream.read(16)
    if len(header) != 16:
        raise ValueError("Truncated float header")
    magic, dim, count = struct.unpack('<8sII', header)
    if magic != FLOAT_MAGIC or dim < DIM or Path(path).stat().st_size != 16 + count * (8 + 4 * dim):
        raise ValueError("Invalid BRAGF001 float source")
    dtype = np.dtype([('id', '<i8'), ('vector', '<f4', (dim,))])
    return np.memmap(path, dtype=dtype, mode='r', offset=16, shape=(count,)) if count else np.empty(0, dtype=dtype)


def train_centroids(sample, clusters, backend='auto', device='cpu', seed=42, iterations=25):
    if backend not in ('auto', 'faiss', 'sklearn') or device not in ('auto', 'cpu', 'gpu'):
        raise ValueError("Unknown backend/device")
    if backend != 'sklearn':
        try:
            import faiss
        except ImportError:
            if backend == 'faiss' or device == 'gpu':
                raise ValueError("Install a suitable Faiss build for this backend/device") from None
        else:
            gpu = device == 'gpu' or (device == 'auto' and hasattr(faiss, 'get_num_gpus') and faiss.get_num_gpus() > 0)
            if gpu and (not hasattr(faiss, 'get_num_gpus') or faiss.get_num_gpus() < 1):
                raise ValueError("Faiss GPU support/device unavailable")
            trainer = faiss.Kmeans(DIM, clusters, niter=iterations, nredo=1, seed=seed, gpu=gpu)
            trainer.train(np.ascontiguousarray(sample))
            return trainer.centroids.astype('<f4'), 'faiss-gpu' if gpu else 'faiss-cpu'
    if device == 'gpu' or len(sample) > 100000 or clusters > 512:
        raise ValueError("sklearn fallback is limited to <=100000 training rows / <=512 clusters; install Faiss")
    from sklearn.cluster import KMeans
    from threadpoolctl import threadpool_limits
    with threadpool_limits(limits=2):
        model = KMeans(n_clusters=clusters, n_init=1, max_iter=iterations, random_state=seed).fit(sample)
    return model.cluster_centers_.astype('<f4'), 'sklearn-cpu'


def build_index(reference, output, clusters=DEFAULT_CLUSTERS, train_size=262144,
                backend='auto', device='cpu', batch=8192, seed=42):
    if min(clusters, train_size, batch) < 1:
        raise ValueError("clusters, train_size and batch must be positive")
    if Path(output).exists():
        raise FileExistsError(output)
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    source = float_source(reference)
    count = len(source)
    actual_clusters = min(clusters, max(1, count))
    if count and train_size < actual_clusters:
        raise ValueError("train_size must be >= actual cluster count")
    with tempfile.TemporaryDirectory(prefix='bonsai-ivf-', dir=Path(output).parent) as temp, ExitStack() as cleanup:
        data = np.memmap(Path(temp) / 'normalized.f32', dtype='<f4', mode='w+', shape=(max(1, count), DIM))
        cleanup.callback(data._mmap.close)
        maximum = 0.0
        for start in range(0, count, batch):
            block = normalized(source['vector'][start:start + batch, :DIM])
            data[start:start + len(block)] = block
            maximum = max(maximum, float(np.abs(block).max()))
        sample_ids = np.random.default_rng(seed).choice(count, min(count, train_size), replace=False, shuffle=False) if count else []
        if count:
            centers, used_backend = train_centroids(np.asarray(data[sample_ids]), actual_clusters, backend, device, seed)
        else:
            centers, used_backend = np.zeros((1, DIM), '<f4'), 'empty'
        # One global float32 scale, stored once. No per-vector scales.
        scale = float(np.float32(max(1.0, maximum) / 127))
        labels = np.empty(count, dtype=np.uint32)
        norms = np.einsum('ij,ij->i', centers, centers)
        # Bounded assignment batches; Faiss also accelerates this step when available.
        assignment = None
        if used_backend.startswith('faiss'):
            import faiss
            assignment = faiss.IndexFlatL2(DIM)
            if used_backend == 'faiss-gpu':
                resources = faiss.StandardGpuResources()
                assignment = faiss.index_cpu_to_gpu(resources, 0, assignment)
            assignment.add(centers)
        for start in range(0, count, batch):
            block = np.asarray(data[start:start + batch])
            labels[start:start + len(block)] = (assignment.search(block, 1)[1][:, 0] if assignment is not None
                                               else np.argmin(norms[None, :] - 2 * (block @ centers.T), axis=1))
        order = np.argsort(labels, kind='stable')
        offsets = np.concatenate((np.zeros(1, dtype=np.uint64), np.cumsum(np.bincount(labels.astype(np.int64), minlength=actual_clusters), dtype=np.uint64)))
        staged = Path(temp) / 'complete.ivf'
        create_index(staged, count, centers, offsets, scale, seed)
        positions = layout(count, actual_clusters)
        if count:
            ids = np.memmap(staged, dtype='<i8', mode='r+', offset=positions[2], shape=(count,))
            vectors = np.memmap(staged, dtype='i1', mode='r+', offset=positions[3], shape=(count, DIM))
            cleanup.callback(ids._mmap.close)
            cleanup.callback(vectors._mmap.close)
            for start in range(0, count, batch):
                rows = order[start:start + batch]
                ids[start:start + len(rows)] = source['id'][rows]
                if np.any(ids[start:start + len(rows)] <= 0):
                    raise ValueError("Chunk IDs must be positive")
                vectors[start:start + len(rows)] = quantized(data[rows], scale)
            ids.flush(); vectors.flush()
        # Publish the complete file without overwriting an existing destination.
        # Same-volume hard link is atomic; temporary files/mappings close on exit.
        os.link(staged, output)
        return dict(count=count, clusters=actual_clusters, requested_clusters=clusters,
                    scale=scale, backend=used_backend, bytes=Path(output).stat().st_size,
                    training_rows=len(sample_ids), seed=seed)


def main():
    parser = argparse.ArgumentParser(description='Build IVF from BRAGF001 normalized float embeddings')
    parser.add_argument('reference'); parser.add_argument('output')
    parser.add_argument('--clusters', type=int, default=DEFAULT_CLUSTERS)
    parser.add_argument('--train-size', type=int, default=262144)
    parser.add_argument('--backend', choices=['auto', 'faiss', 'sklearn'], default='auto')
    parser.add_argument('--device', choices=['auto', 'cpu', 'gpu'], default='cpu')
    parser.add_argument('--batch', type=int, default=8192)
    parser.add_argument('--seed', type=int, default=42)
    args = vars(parser.parse_args())
    print(json.dumps(build_index(**args), indent=2))


if __name__ == '__main__':
    main()
