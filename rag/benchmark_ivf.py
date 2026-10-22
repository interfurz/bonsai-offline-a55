"""Synthetic scaling measurements, independent of embeddings/k-means training quality."""
import argparse
import json
from pathlib import Path
import platform
import struct
import time
import numpy as np
from threadpoolctl import threadpool_limits
from .ivf import DIM, IvfIndex, create_index, layout, normalized, quantized

QUERY_MAGIC = b'BRAGQ001'


def write_queries(path, count=20, seed=43):
    values = normalized(np.random.default_rng(seed).standard_normal((count, DIM)).astype(np.float32))
    with open(path, 'xb') as stream:
        stream.write(struct.pack('<8sII', QUERY_MAGIC, DIM, count))
        stream.write(values.astype('<f4').tobytes())
    return values


def synthetic(path, count, clusters=4096, seed=42, batch=8192):
    if min(count, clusters, batch) < 1:
        raise ValueError('count, clusters and batch must be positive')
    rng = np.random.default_rng(seed)
    centers = normalized(rng.standard_normal((clusters, DIM)).astype(np.float32))
    offsets = (np.arange(clusters + 1, dtype=np.uint64) * count) // clusters
    scale = float(np.float32(1 / 127))
    create_index(path, count, centers, offsets, scale, seed)
    sections = layout(count, clusters)
    ids = np.memmap(path, dtype='<i8', mode='r+', offset=sections[2], shape=(count,))
    vectors = np.memmap(path, dtype='i1', mode='r+', offset=sections[3], shape=(count, DIM))
    for start in range(0, count, batch):
        end = min(start + batch, count)
        ids[start:end] = np.arange(start + 1, end + 1, dtype='<i8')
        values = normalized(rng.standard_normal((end - start, DIM)).astype(np.float32))
        vectors[start:end] = quantized(values, scale)
    ids.flush(); vectors.flush()


def summary(rows):
    return {key: dict(p50=float(np.percentile([row[key] for row in rows], 50)),
                      p95=float(np.percentile([row[key] for row in rows], 95)))
            for key in ('centroid_ms', 'scan_ms', 'total_ms', 'scanned')}


def measure(index, queries, nprobe=16, top_k=20, warmup=2, repeats=1):
    if nprobe < 1 or top_k < 1 or warmup < 0 or repeats < 1 or len(queries) < 1:
        raise ValueError('Invalid benchmark parameters')
    start = time.perf_counter_ns()
    ivf = IvfIndex(index)
    open_ms = (time.perf_counter_ns() - start) / 1e6
    rows = []
    with threadpool_limits(limits=1):
        for mode in ('full', 'ivf'):
            for step in range(warmup + len(queries) * repeats):
                query = queries[step % len(queries)]
                start = time.perf_counter_ns()
                result = ivf.search(query, nprobe=nprobe, top_k=top_k, positive_only=False, full_scan=mode == 'full')
                total_ms = (time.perf_counter_ns() - start) / 1e6
                if step >= warmup:
                    rows.append(dict(mode=mode, query=step % len(queries),
                                     centroid_ms=result['centroid_ms'], scan_ms=result['scan_ms'],
                                     total_ms=total_ms, scanned=result['scanned']))
    return dict(count=ivf.count, clusters=ivf.clusters, nprobe=min(nprobe, ivf.clusters),
                index_bytes=Path(index).stat().st_size, open_validate_ms=open_ms, warmup=warmup,
                queries=len(queries), repeats=repeats, top_k=top_k,
                summary={mode: summary([row for row in rows if row['mode'] == mode]) for mode in ('full', 'ivf')}, rows=rows)


def markdown(report):
    lines = ['| Vektoren | Modus | Scan-Anzahl p50 | Zentroiden p50 ms | Scan p50 ms | Gesamt p50 ms | Gesamt p95 ms |',
             '| ---: | --- | ---: | ---: | ---: | ---: | ---: |']
    for result in report['runs']:
        for mode, values in result['summary'].items():
            lines.append(f"| {result['count']} | {mode} | {values['scanned']['p50']:.0f} | {values['centroid_ms']['p50']:.3f} | {values['scan_ms']['p50']:.3f} | {values['total_ms']['p50']:.3f} | {values['total_ms']['p95']:.3f} |")
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', required=True)
    parser.add_argument('--sizes', type=int, nargs='+', default=[1000000, 5000000])
    parser.add_argument('--clusters', type=int, default=4096)
    parser.add_argument('--nprobe', type=int, default=16)
    parser.add_argument('--queries', type=int, default=20)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--reuse', action='store_true')
    parser.add_argument('--output')
    args = parser.parse_args()
    directory = Path(args.directory); directory.mkdir(parents=True, exist_ok=True)
    query_path = directory / 'queries.f32'
    if query_path.exists() and args.reuse:
        raw = query_path.read_bytes()
        magic, dim, count = struct.unpack('<8sII', raw[:16])
        if magic != QUERY_MAGIC or dim != DIM or count != args.queries or len(raw) != 16 + count * DIM * 4:
            raise ValueError('Query fixture mismatch')
        queries = np.frombuffer(raw, dtype='<f4', offset=16).reshape(count, DIM)
    else:
        queries = write_queries(query_path, args.queries, args.seed + 1)
    report = dict(scope='host only; vector retrieval; encoding/FTS/RRF excluded; NumPy int32 scan; one BLAS thread',
                  synthetic='Gaussian unit vectors in balanced arbitrary partitions; NOT trained k-means; no quality claim',
                  cache='full pass before IVF; warm OS page cache expected; cold-cache measurements not attempted',
                  python=platform.python_version(), numpy=np.__version__, machine=platform.machine(), seed=args.seed, runs=[])
    for count in args.sizes:
        index = directory / f'synthetic-{count}.ivf'
        start = time.perf_counter()
        if not (args.reuse and index.exists()):
            synthetic(index, count, args.clusters, args.seed)
        generation_ms = (time.perf_counter() - start) * 1000
        run = measure(index, queries, args.nprobe, warmup=args.warmup, repeats=args.repeats)
        if run['count'] != count or run['clusters'] != args.clusters:
            raise ValueError('Reused synthetic index shape mismatch')
        run['generation_or_reuse_ms'] = generation_ms
        report['runs'].append(run)
    if args.output:
        Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    print(markdown(report))


if __name__ == '__main__':
    main()
