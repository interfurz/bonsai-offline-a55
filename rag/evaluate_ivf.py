"""Recall@10: labeled articles and exact Float32-256D neighbor agreement."""
import argparse
import heapq
import json
from pathlib import Path
import sqlite3
import numpy as np
from .embeddings import encoder
from .ivf import IvfIndex, float_source, normalized


def make_questions(articles):
    rows = [json.loads(line) for line in Path(articles).read_text(encoding='utf-8').splitlines()]
    return [dict(query=template.format(title=row['title']), article_ids=[str(row['id'])])
            for row in rows for template in ('Was weißt du über {title}?',
                                            'Erkläre {title} und seine wichtigsten Eigenschaften.')]


def float_ranks(reference, query, top_k=10, batch=8192):
    q = normalized(np.asarray(query)[:256])
    heap = []
    for start in range(0, len(reference), batch):
        rows = reference[start:start + batch]
        scores = normalized(rows['vector'][:, :256]) @ q
        for row, score in zip(rows, scores):
            item = (float(score), -int(row['id']))
            if len(heap) < top_k:
                heapq.heappush(heap, item)
            elif item > heap[0]:
                heapq.heapreplace(heap, item)
    return [-negative_id for _, negative_id in sorted(heap, reverse=True)]


def evaluate(database, reference, index, questions, probes=(4, 8, 16, 32)):
    if len(questions) < 100:
        raise ValueError('At least 100 questions required')
    with sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True) as db:
        model = db.execute("SELECT value FROM metadata WHERE key='embedding_model'").fetchone()[0]
        article_ids = dict(db.execute('SELECT id,article_id FROM chunks'))
    backend, source, ivf = encoder(model), float_source(reference), IvfIndex(index)
    if len(source) != ivf.count or set(map(int, source['id'])) != set(map(int, ivf.ids)):
        raise ValueError('Reference and index ID sets differ')
    methods = ['Float32 full', 'INT8 full'] + [f'IVF INT8 nprobe={p}' for p in probes]
    totals = {method: dict(neighbor=0.0, labeled=0.0, labeled_queries=0) for method in methods}
    details = []
    for question in questions:
        query = backend.encode([question['query']], query=True)[0]
        exact = float_ranks(source, query)
        rankings = [exact, ivf.search(query, top_k=10, positive_only=False, full_scan=True)['ids']]
        rankings += [ivf.search(query, nprobe=p, top_k=10, positive_only=False)['ids'] for p in probes]
        expected = set(map(str, question.get('article_ids', [])))
        if expected - set(article_ids.values()):
            raise ValueError('Unknown labeled article ID')
        details.append(dict(query=question['query'], rankings=dict(zip(methods, rankings))))
        for method, found in zip(methods, rankings):
            totals[method]['neighbor'] += len(set(found) & set(exact)) / max(1, len(exact))
            if expected:
                totals[method]['labeled'] += len({article_ids[i] for i in found} & expected) / len(expected)
                totals[method]['labeled_queries'] += 1
    table = [dict(method=method, queries=len(questions),
                  neighbor_recall_at10=total['neighbor'] / len(questions),
                  labeled_recall_at10=total['labeled'] / total['labeled_queries'] if total['labeled_queries'] else None)
             for method, total in totals.items()]
    return dict(scope='host; vector-only; 256D Float32 reference; no positive-score filter',
                model=model, chunks=ivf.count, clusters=ivf.clusters, scale=ivf.scale,
                table=table, per_query=details)


def markdown(report):
    lines = ['| Methode | Fragen | Recall@10 (Labels) | Recall@10 (Float-Nachbarn) |',
             '| --- | ---: | ---: | ---: |']
    for row in report['table']:
        label = '—' if row['labeled_recall_at10'] is None else f"{row['labeled_recall_at10']:.4f}"
        lines.append(f"| {row['method']} | {row['queries']} | {label} | {row['neighbor_recall_at10']:.4f} |")
    return '\n'.join(lines)


def write_report(path, report):
    # Keep each query's raw ranking on one line so diffs remain reviewable.
    metadata = {key: value for key, value in report.items() if key != 'per_query'}
    prefix = json.dumps(metadata, ensure_ascii=False, indent=2)[:-2]
    rows = ',\n'.join('    ' + json.dumps(row, ensure_ascii=False) for row in report['per_query'])
    Path(path).write_text(prefix + ',\n  "per_query": [\n' + rows + '\n  ]\n}\n',encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    for name in ('database', 'reference', 'index', 'questions'):
        parser.add_argument(name)
    parser.add_argument('--nprobe', nargs='+', type=int, default=[4, 8, 16, 32])
    parser.add_argument('--output')
    args = parser.parse_args()
    report = evaluate(args.database, args.reference, args.index,
                      json.loads(Path(args.questions).read_text(encoding='utf-8')), args.nprobe)
    if args.output:
        write_report(args.output, report)
    print(markdown(report))


if __name__ == '__main__':
    main()
