"""Measured retrieval agreement and labeled recall; no inferred quality claims."""
import argparse
import json
import sqlite3
import time
from pathlib import Path
from .embeddings import encoder, vector_ranks


def compare(database, vectors, reference, questions):
    with sqlite3.connect(database) as db:
        name = db.execute("SELECT value FROM metadata WHERE key='embedding_model'").fetchone()[0]
        article_ids = dict(db.execute("SELECT id,article_id FROM chunks"))
    backend = encoder(name)
    rows = []
    for question in questions:
        query = backend.encode([question["query"]], query=True)[0]
        start = time.perf_counter()
        low = vector_ranks(vectors, query, 3)
        low_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        high = vector_ranks(reference, query, 3, reference=True)
        high_ms = (time.perf_counter() - start) * 1000
        expected = set(map(str, question.get("article_ids", [])))
        rows.append(dict(query=question["query"], int8_top3=low, float_top3=high,
                         overlap_at3=len(set(low) & set(high)) / max(1, len(high)),
                         int8_recall=len({article_ids[i] for i in low} & expected) / len(expected) if expected else None,
                         float_recall=len({article_ids[i] for i in high} & expected) / len(expected) if expected else None,
                         int8_ms=low_ms, float_ms=high_ms))
    return dict(scope="host; vector-only; warm/cold OS cache uncontrolled; query encoding excluded",
                model=name, int8_bytes=Path(vectors).stat().st_size,
                float_bytes=Path(reference).stat().st_size, queries=rows)


def main():
    parser = argparse.ArgumentParser()
    for name in ("database", "vectors", "reference", "questions"):
        parser.add_argument(name)
    args = parser.parse_args()
    print(json.dumps(compare(args.database, args.vectors, args.reference,
                             json.loads(Path(args.questions).read_text())), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
