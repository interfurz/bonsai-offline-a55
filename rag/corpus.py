"""Resumable, bounded Wikipedia ingestion into the shared Android schema."""
import hashlib
import json
import multiprocessing
import os
import re
import sqlite3
import sys
import time
import zlib
from collections import deque
from concurrent.futures import ProcessPoolExecutor
from contextlib import contextmanager, closing
from pathlib import Path
from functools import lru_cache

from mwparserfromhell.parser import ParserError

from .chunking import Section, chunk_article, markdown_article, sections, tokens
from .wiki import records, title_key

PIPELINE_VERSION = "wikipedia-v5-tokenizer-budget"
SCHEMA = """
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id TEXT NOT NULL, title TEXT NOT NULL,
 heading TEXT NOT NULL, source TEXT NOT NULL, text_zlib BLOB NOT NULL);
CREATE INDEX chunks_article ON chunks(article_id);
CREATE VIRTUAL TABLE chunks_fts USING fts5(title,heading,text,content='',tokenize='unicode61');
CREATE TABLE articles(id TEXT PRIMARY KEY, title TEXT NOT NULL, title_key TEXT NOT NULL, source TEXT NOT NULL,
 revision_id TEXT NOT NULL, timestamp TEXT NOT NULL, revision_source TEXT NOT NULL,
 history_source TEXT NOT NULL, license TEXT NOT NULL, sha1 TEXT NOT NULL,
 redirect_target TEXT NOT NULL, wikitext_zlib BLOB NOT NULL, markdown_zlib BLOB NOT NULL);
CREATE INDEX articles_title ON articles(title_key);
CREATE TABLE redirects(article_id TEXT PRIMARY KEY REFERENCES articles(id),
 target_title TEXT NOT NULL, target_fragment TEXT NOT NULL);
CREATE TABLE chunk_metadata(chunk_id INTEGER PRIMARY KEY REFERENCES chunks(id),
 stable_key TEXT NOT NULL UNIQUE, ordinal INTEGER NOT NULL,
 section_paths TEXT NOT NULL, estimated_tokens INTEGER NOT NULL);
CREATE TABLE ingest_errors(position INTEGER PRIMARY KEY, article_id TEXT, title TEXT,
 error TEXT NOT NULL);
CREATE TABLE conversion_audit(article_id TEXT PRIMARY KEY, template_counts TEXT NOT NULL,
 table_count INTEGER NOT NULL, math_count INTEGER NOT NULL);
CREATE TABLE book_structure(article_id TEXT PRIMARY KEY, book TEXT NOT NULL, path TEXT NOT NULL);
CREATE TABLE chunk_tokenization(chunk_id INTEGER PRIMARY KEY, actual_tokens INTEGER NOT NULL);
CREATE TABLE post_metadata(article_id TEXT PRIMARY KEY,data_json TEXT NOT NULL);
CREATE TABLE skipped_pages(position INTEGER PRIMARY KEY,article_id TEXT,title TEXT,reason TEXT NOT NULL);
"""


def articles(path, limit=None):
    """Compatibility iterator: article dictionaries, excluding redirects/namespaces."""
    if limit is not None and limit < 1:
        raise ValueError("limit must be positive")
    count = 0
    for _, article in records(path):
        if article["kind"] == "error":
            raise ValueError(article["error"])
        if article["kind"] != "article":
            continue
        yield article
        count += 1
        if limit and count >= limit:
            break


def _fingerprint(source):
    """Fast resume guard, not a substitute for the Wikimedia full SHA256 check."""
    path = Path(source).resolve()
    stat = path.stat()
    with path.open("rb") as stream:
        sample = stream.read(65536)
        if stat.st_size > 65536:
            stream.seek(max(65536, stat.st_size - 65536))
            sample += stream.read(65536)
    return dict(path=str(path), size=stat.st_size, mtime_ns=stat.st_mtime_ns,
                sampled_sha256=hashlib.sha256(sample).hexdigest())


@contextmanager
def _writer_lock(database):
    # OS locks are released on crashes. Keep the tiny lock file to avoid unlink races.
    with open(str(database) + ".lock", "a+b") as stream:
        if os.name == "nt":
            import msvcrt
            # Inspect length without reading another importer's locked byte.
            if os.fstat(stream.fileno()).st_size == 0:
                stream.write(b"0")
                stream.flush()
            stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                raise RuntimeError("Another importer holds the database lock") from exc
            try:
                yield
            finally:
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise RuntimeError("Another importer holds the database lock") from exc
            try:
                yield
            finally:
                fcntl.flock(stream, fcntl.LOCK_UN)


@lru_cache(maxsize=1)
def _tokenizer(path):
    from tokenizers import Tokenizer
    return Tokenizer.from_file(path)


def _prepare(item, minimum, maximum, max_page_bytes, tokenizer_path=None):
    position, article = item
    if article["kind"] in {"skip", "error"}:
        return position, article
    try:
        for field in ("id", "title", "text", "source"):
            if not isinstance(article.get(field), str) or (field != "text" and not article[field]):
                raise ValueError("missing/invalid " + field)
        for field in ("revision_id", "timestamp", "revision_source", "history_source", "license", "sha1", "model", "redirect_target"):
            if field in article and not isinstance(article[field], str):
                raise ValueError("invalid " + field)
        if len(article["text"].encode("utf-8")) > max_page_bytes:
            raise ValueError("article exceeds --max-page-mib")
        if article["kind"] == "redirect" and not article.get("redirect_target"):
            raise ValueError("empty redirect target")
        if article.get("model", "wikitext") != "wikitext":
            raise ValueError("unsupported content model")
        if article.get("format") == "markdown":
            parsed = [Section(tuple(s["path"]),s["level"],s["text"]) for s in article["sections"]]
        else:
            parsed = sections(article["text"]) if article["kind"] == "article" else []
        count_tokens = (lambda text: len(_tokenizer(tokenizer_path).encode(f"title: {article['title'] or 'none'} | text: " + text).ids)) if tokenizer_path else None
        chunks = chunk_article(article["id"], article["title"], article["text"],
                               article.get("revision_source", article["source"]), minimum, maximum, parsed, count_tokens)
        result = dict(article)
        result.pop("text")
        result["original"] = zlib.compress(article.get("original_text",article["text"]).encode("utf-8"))
        result["markdown"] = zlib.compress(markdown_article(article["title"], parsed).encode("utf-8"))
        result["chunks"] = [(chunk, zlib.compress(chunk.text.encode("utf-8"))) for chunk in chunks]
        result["actual_tokens"] = [(chunk.id, count_tokens(chunk.text)) for chunk in chunks] if count_tokens else []
        # Unknown templates remain recoverable in the original; make every used
        # template family inspectable instead of silently claiming full expansion.
        result["template_counts"] = json.dumps(dict(__import__("collections").Counter(
            name.strip().casefold() for name in re.findall(r"\{\{\s*([^{}|\n]+)", article["text"]))), ensure_ascii=False)
        result["table_count"] = len(re.findall(r"\{\||<table\b", article["text"], re.I))
        result["math_count"] = len(re.findall(r"<math\b", article["text"], re.I))
        return position, result
    except (ValueError, TypeError, RecursionError, ParserError) as exc:
        # Parser errors are per-page; DB, disk and process failures remain fatal.
        safe_id = article.get("id") if isinstance(article.get("id"), str) else None
        safe_title = article.get("title") if isinstance(article.get("title"), str) else None
        return position, dict(kind="error", attempted=article["kind"] == "article", id=safe_id, title=safe_title,
                              error=type(exc).__name__ + ": " + str(exc)[:400])


def _prepared(stream, workers, inflight_bytes, minimum, maximum, max_page_bytes, tokenizer_path=None):
    if workers == 1:
        for item in stream:
            yield _prepare(item, minimum, maximum, max_page_bytes, tokenizer_path)
        return
    # spawn also on Unix: exercise Windows-compatible pickling and initialization.
    with ProcessPoolExecutor(max_workers=workers, mp_context=multiprocessing.get_context("spawn")) as pool:
        pending, pending_bytes = deque(), 0
        for item in stream:
            text = item[1].get("text", "")
            size = len(text.encode("utf-8")) if isinstance(text, str) else 0
            # Reject oversized pages in the parent, without shipping them over IPC.
            if size > max_page_bytes:
                while pending:
                    future, weight = pending.popleft()
                    pending_bytes -= weight
                    yield future.result()
                yield _prepare(item, minimum, maximum, max_page_bytes, tokenizer_path)
                continue
            while pending and (len(pending) >= workers * 2 or pending_bytes + size > inflight_bytes):
                future, weight = pending.popleft()
                pending_bytes -= weight
                yield future.result()
            pending.append((pool.submit(_prepare, item, minimum, maximum, max_page_bytes, tokenizer_path), size))
            pending_bytes += size
        while pending:
            future, _ = pending.popleft()
            yield future.result()


def _put_metadata(db, key, value):
    db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", (key, value))


def _write_article(db, article, android_fts4):
    # Duplicate IDs and 63-bit hash collisions abort instead of silently corrupting mappings.
    db.execute("INSERT INTO articles VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", (
        article["id"], article["title"], title_key(article["title"]), article["source"], article.get("revision_id", ""),
        article.get("timestamp", ""), article.get("revision_source", article["source"]),
        article.get("history_source", ""), article.get("license", ""), article.get("sha1", ""),
        article.get("redirect_target", ""), article["original"], article["markdown"]))
    db.execute("INSERT INTO conversion_audit VALUES(?,?,?,?)", (article["id"], article["template_counts"], article["table_count"], article["math_count"]))
    db.executemany("INSERT INTO chunk_tokenization VALUES(?,?)", article["actual_tokens"])
    if article.get("post_metadata"):
        db.execute("INSERT INTO post_metadata VALUES(?,?)",(article["id"],json.dumps(article["post_metadata"],ensure_ascii=False)))
    if article.get("wiki") == "dewikibooks":
        path = article["title"].split("/")
        db.execute("INSERT INTO book_structure VALUES(?,?,?)", (article["id"], path[0].strip(), json.dumps(path, ensure_ascii=False)))
    if article["kind"] == "redirect":
        target, _, fragment = article["redirect_target"].partition("#")
        db.execute("INSERT INTO redirects VALUES(?,?,?)", (article["id"], title_key(target or article["title"]), fragment))
    for ordinal, (chunk, compressed) in enumerate(article["chunks"]):
        db.execute("INSERT INTO chunks VALUES(?,?,?,?,?,?)", (chunk.id, chunk.article_id,
                   chunk.title, chunk.heading, chunk.source, compressed))
        db.execute("INSERT INTO chunk_metadata VALUES(?,?,?,?,?)", (chunk.id, chunk.stable_key,
                   ordinal, json.dumps(chunk.section_paths, ensure_ascii=False), tokens(chunk.text)))
        values = (chunk.id, chunk.title, chunk.heading, chunk.text)
        db.execute("INSERT INTO chunks_fts(rowid,title,heading,text) VALUES(?,?,?,?)", values)
        if android_fts4:
            db.execute("INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(?,?,?,?)", values)


def build(source, database, limit=None, android_fts4=True, *, resume=False, workers=1,
          commit_every=500, minimum=80, maximum=800, max_inflight_mib=64,
           max_page_mib=8, error_log=None, progress=True, dump_sha256=None, wiki="dewiki", tokenizer_path=None,
           disk_reserve_bytes=0):
    """Import only local data. Limit is the total attempted non-redirect articles.

    Checkpoint, article rows, errors and both FTS indices commit atomically. On
    resume only uncommitted pages are processed again. XML/bzip replay is O(input
    before checkpoint), while conversion/SQLite work is not repeated.
    """
    if limit is not None and limit < 1:
        raise ValueError("limit must be positive")
    if wiki not in {"dewiki", "dewikibooks", "stackexchange"}:
        raise ValueError("Text RAG supports only the agreed text corpora")
    if workers < 1 or commit_every < 1 or min(max_inflight_mib, max_page_mib) <= 0:
        raise ValueError("workers, commit interval and memory limits must be positive")
    if not 0 < minimum <= maximum:
        raise ValueError("Require 0 < minimum <= maximum")
    if dump_sha256 is not None and (len(dump_sha256) != 64 or any(c not in "0123456789abcdefABCDEF" for c in dump_sha256)):
        raise ValueError("dump SHA256 must have 64 hexadecimal characters")
    database = Path(database)
    if Path(source).resolve() == database.resolve():
        raise ValueError("source and database must differ")
    database.parent.mkdir(parents=True, exist_ok=True)
    options = dict(pipeline=PIPELINE_VERSION, android_fts4=android_fts4, minimum=minimum,
                   maximum=maximum, max_page_mib=max_page_mib, dump_sha256=dump_sha256, wiki=wiki)
    identity = _fingerprint(source)
    if tokenizer_path:
        tokenizer_path = str(Path(tokenizer_path).resolve())
        options["tokenizer_sha256"] = hashlib.sha256(Path(tokenizer_path).read_bytes()).hexdigest()
    log_path = Path(error_log) if error_log else Path(str(database) + ".errors.jsonl")
    reserved = [Path(source), database] + [Path(str(database) + suffix) for suffix in (".lock", "-wal", "-shm", "-journal")]
    if log_path.resolve() in {p.resolve() for p in reserved}:
        raise ValueError("error log must differ from input, database and SQLite sidecars")
    started = time.monotonic()
    with _writer_lock(database):
        if database.exists() and not resume:
            raise FileExistsError(database)
        if resume and not database.exists():
            raise FileNotFoundError(database)
        with closing(sqlite3.connect(database)) as db, db:
            # Bound page cache to 32 MiB; WAL/checkpoint makes batches durable.
            db.execute("PRAGMA cache_size=-32768")
            db.execute("PRAGMA temp_store=FILE")
            db.execute("PRAGMA foreign_keys=ON")
            if resume:
                metadata = dict(db.execute("SELECT key,value FROM metadata"))
                if metadata.get("ingest_options") != json.dumps(options, sort_keys=True) or metadata.get("ingest_source") != json.dumps(identity, sort_keys=True):
                    raise ValueError("Resume source/options differ; use the original dump and chunk settings")
                if "embedding_model" in metadata:
                    raise ValueError("Cannot resume a vectorized database; create a new corpus")
                state = json.loads(metadata["ingest_checkpoint"])
            else:
                db.executescript(SCHEMA)
                if android_fts4:
                    db.execute("CREATE VIRTUAL TABLE chunks_fts4 USING fts4(title,heading,text,tokenize=unicode61)")
                for key, value in (("schema", "1"), ("ingest_pipeline", PIPELINE_VERSION),
                                   ("ingest_options", json.dumps(options, sort_keys=True)),
                                   ("ingest_source", json.dumps(identity, sort_keys=True)),
                                   ("vector_target_dimension", "256"), ("vector_target_dtype", "int8")):
                    _put_metadata(db, key, value)
                state = dict(position=0, attempted=0, articles=0, redirects=0, skipped=0,
                             chunks=0, errors=0, elapsed_seconds=0.0)
                _put_metadata(db, "ingest_checkpoint", json.dumps(state))
                _put_metadata(db, "ingest_status", "in_progress")
                db.commit()
            already_complete = resume and metadata.get("ingest_status") == "complete"
            db.execute("CREATE TABLE IF NOT EXISTS skipped_pages(position INTEGER PRIMARY KEY,article_id TEXT,title TEXT,reason TEXT NOT NULL)")
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            session_start_attempted = state["attempted"]
            last_position, last_report = state["position"], started
            base_elapsed = state["elapsed_seconds"]
            log_path.parent.mkdir(parents=True, exist_ok=True)
            with log_path.open("a", encoding="utf-8") as log:
                errors_to_log = []

                def checkpoint(status="in_progress"):
                    nonlocal last_position, last_report
                    state["elapsed_seconds"] = base_elapsed + time.monotonic() - started
                    _put_metadata(db, "ingest_checkpoint", json.dumps(state))
                    _put_metadata(db, "ingest_status", status)
                    db.commit()
                    # DB journal is authoritative if a crash happens before this mirror.
                    for error in errors_to_log:
                        log.write(json.dumps(error, ensure_ascii=False) + "\n")
                    log.flush()
                    errors_to_log.clear()
                    last_position = state["position"]
                    if progress:
                        now = time.monotonic()
                        rate = (state["attempted"] - session_start_attempted) / max(now - started, .001)
                        print(f"[{status}] page={state['position']} articles={state['articles']} redirects={state['redirects']} chunks={state['chunks']} errors={state['errors']} {rate:.1f} articles/s", file=sys.stderr, flush=True)
                    last_report = time.monotonic()
                    if disk_reserve_bytes:
                        from database_pipeline.common import require_space
                        require_space(database.parent,0,reserve=disk_reserve_bytes)

                def selected():
                    if already_complete or (limit and state["attempted"] >= limit):
                        return
                    attempted = state["attempted"]
                    for item in records(source, state["position"], progress=progress, wiki=wiki):
                        yield item
                        if item[1]["kind"] in {"article", "error"}:
                            attempted += 1
                            if limit and attempted >= limit:
                                break

                stream = _prepared(selected(), workers, int(max_inflight_mib * 1024**2),
                                   minimum, maximum, int(max_page_mib * 1024**2), tokenizer_path)
                try:
                    for position, article in stream:
                        kind = article["kind"]
                        state["position"] = position
                        if kind == "skip":
                            state["skipped"] += 1
                            if article.get('skip_reason'):
                                db.execute('INSERT INTO skipped_pages VALUES(?,?,?,?)',(position,article.get('id'),article.get('title'),article['skip_reason']))
                        elif kind == "error":
                            # JSON parse failures count as an attempted article too.
                            state["attempted"] += article.get("attempted", True)
                            state["errors"] += 1
                            error = dict(position=position, article_id=article.get("id"),
                                         title=article.get("title"), error=article["error"])
                            db.execute("INSERT INTO ingest_errors VALUES(?,?,?,?)", tuple(error.values()))
                            errors_to_log.append(error)
                        else:
                            _write_article(db, article, android_fts4)
                            state["redirects" if kind == "redirect" else "articles"] += 1
                            state["attempted"] += kind == "article"
                            state["chunks"] += len(article["chunks"])
                        if position - last_position >= commit_every or time.monotonic() - last_report >= 5:
                            checkpoint()
                    checkpoint("limited" if not already_complete and limit and state["attempted"] >= limit else "complete")
                finally:
                    stream.close()
            # Bounded FTS compaction per transaction is automatic; avoid an enormous
            # final optimize transaction and temporary disk usage on the full dump.
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            # A clean exit leaves a standalone database for Android/read-only copies.
            db.execute("PRAGMA journal_mode=DELETE")
            result = dict(state, status=db.execute("SELECT value FROM metadata WHERE key='ingest_status'").fetchone()[0],
                          database_bytes=database.stat().st_size,
                          session_seconds=time.monotonic() - started)
            result["session_articles_per_second"] = (state["attempted"] - session_start_attempted) / max(result["session_seconds"], .001)
            return result
