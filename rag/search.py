"""FTS5 first, INT8 brute force, reciprocal rank fusion with source metadata."""
import re
import sqlite3
import zlib
from .embeddings import encoder, vector_ranks

MAX_HITS = 3
MAX_TEXT = 600
MAX_QUERY = 256


def fts_query(query):
    return " OR ".join('"' + word + '"' for word in re.findall(r"[^\W_]+", query.lower())[:32])


def fuse(rankings, limit=MAX_HITS):
    scores = {}
    for ranking in rankings:
        for rank, chunk_id in enumerate(ranking, 1):
            scores[chunk_id] = scores.get(chunk_id, 0) + 1 / (60 + rank)
    return sorted(scores, key=lambda key: (-scores[key], key))[:limit]


def search(database, query, vectors=None, top_k=MAX_HITS, backend=None):
    if not isinstance(query, str) or not query.strip() or len(query) > MAX_QUERY:
        raise ValueError("query must contain 1..256 characters")
    if not 1 <= top_k <= MAX_HITS:
        raise ValueError("top_k must be 1..3")
    from pathlib import Path
    if not Path(database).is_file():
        raise FileNotFoundError("Wissensdatenbank fehlt")
    with sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True) as db:
        match = fts_query(query)
        lexical = [row[0] for row in db.execute("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts),rowid LIMIT 20", (match,))] if match else []
        semantic = []
        if vectors:
            name = db.execute("SELECT value FROM metadata WHERE key='embedding_model'").fetchone()
            if not name:
                raise ValueError("Embedding model metadata missing")
            backend = backend or encoder(name[0])
            if backend.name != name[0]:
                raise ValueError("Query encoder does not match index model")
            semantic = vector_ranks(vectors, backend.encode([query], query=True)[0])
        hits = []
        for chunk_id in fuse([lexical, semantic], top_k):
            row = db.execute("SELECT article_id,title,heading,source,text_zlib FROM chunks WHERE id=?", (chunk_id,)).fetchone()
            if not row:
                raise ValueError("Vector chunk ID missing from database")
            hits.append(dict(chunk_id=chunk_id, article_id=row[0], title=row[1][:160],
                             heading=row[2][:160], source=row[3][:240],
                             text=zlib.decompress(row[4]).decode("utf-8")[:MAX_TEXT]))
        return hits
