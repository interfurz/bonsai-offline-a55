"""Streaming Wikipedia XML/JSONL ingestion into the shared Android schema."""
import bz2
import json
import sqlite3
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path
from urllib.parse import quote
from .chunking import chunk_article

SCHEMA = """
PRAGMA user_version=1;
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id TEXT NOT NULL, title TEXT NOT NULL,
 heading TEXT NOT NULL, source TEXT NOT NULL, text_zlib BLOB NOT NULL);
CREATE INDEX chunks_article ON chunks(article_id);
CREATE VIRTUAL TABLE chunks_fts USING fts5(title,heading,text,content='',tokenize='unicode61');
"""


def articles(path, limit=None):
    if limit is not None and limit < 1:
        raise ValueError("limit must be positive")
    path = Path(path)
    opener = bz2.open if path.suffix == ".bz2" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        if ".jsonl" in path.name:
            for index, line in enumerate(stream):
                yield json.loads(line)
                if limit and index + 1 >= limit:
                    break
            return
        count = 0
        # Clear completed pages from root: memory does not grow with dump size.
        parser = ET.iterparse(stream, events=("start", "end"))
        _, root = next(parser)
        for event, page in parser:
            if event != "end" or page.tag.rsplit("}", 1)[-1] != "page":
                continue
            fields = {c.tag.rsplit("}", 1)[-1]: c for c in page}
            if fields.get("ns") is not None and fields["ns"].text == "0" and "redirect" not in fields:
                revision = fields.get("revision")
                text = next((c.text or "" for c in revision if c.tag.rsplit("}", 1)[-1] == "text"), "") if revision is not None else ""
                title = fields["title"].text or ""
                yield {"id": fields["id"].text, "title": title, "text": text,
                       "source": "https://de.wikipedia.org/?curid=" + quote(fields["id"].text or "")}
                count += 1
            root.clear()
            if limit and count >= limit:
                break


def build(source, database, limit=None, android_fts4=True):
    database = Path(database)
    database.parent.mkdir(parents=True, exist_ok=True)
    if database.exists():
        raise FileExistsError(database)
    with sqlite3.connect(database) as db:
        db.executescript(SCHEMA)
        # Stock Android builds can omit FTS5; this compatibility index costs space.
        if android_fts4:
            db.execute("CREATE VIRTUAL TABLE chunks_fts4 USING fts4(title,heading,text,tokenize=unicode61)")
        db.execute("INSERT INTO metadata VALUES('schema','1')")
        for article in articles(source, limit):
            for chunk in chunk_article(article["id"], article["title"], article["text"], article["source"]):
                row = db.execute("INSERT INTO chunks(article_id,title,heading,source,text_zlib) VALUES(?,?,?,?,?)",
                                 (chunk.article_id, chunk.title, chunk.heading, chunk.source,
                                  zlib.compress(chunk.text.encode("utf-8"))))
                values = (row.lastrowid, chunk.title, chunk.heading, chunk.text)
                db.execute("INSERT INTO chunks_fts(rowid,title,heading,text) VALUES(?,?,?,?)", values)
                if android_fts4:
                    db.execute("INSERT INTO chunks_fts4(rowid,title,heading,text) VALUES(?,?,?,?)", values)
        db.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')")
