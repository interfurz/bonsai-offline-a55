"""Bounded XML/JSONL reader. Never downloads a dump or expands wiki templates."""
import bz2
from contextlib import closing
import json
import re
import sqlite3
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote

LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/"


def title_key(title):
    title = re.sub(r"[ _]+", " ", title.strip())
    return title[:1].upper() + title[1:]


def resolve_redirect(database, title, max_hops=64):
    """Resolve aliases using indexed disk lookups, including cycles/missing targets.

    Redirects are retained as provenance, never duplicated as embedding chunks.
    A limited import can legitimately have missing targets.
    """
    if max_hops < 1:
        raise ValueError("max_hops must be positive")
    seen, fragment, key = set(), "", title_key(title)
    with closing(sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True)) as db:
        for _ in range(max_hops):
            if key in seen:
                return dict(status="cycle", title=key, fragment=fragment)
            seen.add(key)
            row = db.execute("SELECT a.id,a.title,r.target_title,r.target_fragment FROM articles a LEFT JOIN redirects r ON r.article_id=a.id WHERE a.title_key=?", (key,)).fetchone()
            if row is None:
                return dict(status="missing", title=key, fragment=fragment)
            if row[2] is None:
                return dict(status="resolved", article_id=row[0], title=row[1], fragment=fragment)
            key = row[2]
            fragment = fragment or row[3]
    return dict(status="depth_exceeded", title=key, fragment=fragment)


def _local(element):
    return element.tag.rsplit("}", 1)[-1]


def _value(fields, name, default=""):
    node = fields.get(name)
    return node.text or default if node is not None else default


def records(path, after=0, progress=False):
    """Yield (absolute page/line position, record) including skips and redirects.

    Resume of plain/bzip XML replays the stream to the last committed page;
    it intentionally does not pretend arbitrary bzip offsets are seekable.
    """
    path = Path(path)
    last_report = time.monotonic()
    opener = bz2.open if path.suffix.lower() == ".bz2" else open
    with opener(path, "rb") as stream:
        if path.name.lower().endswith((".jsonl", ".jsonl.bz2")):
            for position, line in enumerate(stream, 1):
                if position <= after:
                    continue
                try:
                    row = json.loads(line)
                    if not isinstance(row, dict):
                        raise ValueError("JSONL record must be an object")
                    row["kind"] = "redirect" if row.get("redirect_target") else "article"
                    yield position, row
                except (ValueError, UnicodeError) as exc:
                    yield position, {"kind": "error", "error": type(exc).__name__ + ": invalid JSONL record"}
            return
        parser = ET.iterparse(stream, events=("start", "end"))
        _, root = next(parser)
        position = 0
        for event, page in parser:
            if event != "end" or _local(page) != "page":
                continue
            position += 1
            if progress and position <= after and time.monotonic() - last_report >= 5:
                print(f"[replay] page={position}/{after}", file=sys.stderr, flush=True)
                last_report = time.monotonic()
            if position > after:
                fields = {_local(c): c for c in page}
                if _value(fields, "ns") != "0":
                    yield position, {"kind": "skip"}
                elif sum(_local(c) == "revision" for c in page) != 1:
                    yield position, dict(kind="error", id=_value(fields, "id"), title=_value(fields, "title"), error="Expected one current revision; history dumps are unsupported")
                else:
                    revision = fields.get("revision")
                    rev = {_local(c): c for c in revision} if revision is not None else {}
                    article_id = _value(fields, "id")
                    source = "https://de.wikipedia.org/?curid=" + quote(article_id)
                    revision_id = _value(rev, "id")
                    redirect = fields.get("redirect")
                    # Legacy and modern main-slot dumps; history dumps are unsupported.
                    if "slots" in rev:
                        for slot in rev["slots"]:
                            if slot.attrib.get("role", "main") == "main":
                                rev.update({_local(c): c for c in slot})
                    yield position, dict(kind="redirect" if redirect is not None else "article",
                        id=article_id, title=_value(fields, "title"), text=_value(rev, "text"),
                        source=source, revision_id=revision_id, timestamp=_value(rev, "timestamp"),
                        sha1=_value(rev, "sha1"), model=_value(rev, "model", "wikitext"),
                        redirect_target=redirect.attrib.get("title", "") if redirect is not None else "",
                        revision_source="https://de.wikipedia.org/w/index.php?oldid=" + quote(revision_id) if revision_id else source,
                        history_source="https://de.wikipedia.org/w/index.php?curid=" + quote(article_id) + "&action=history",
                        license=LICENSE)
            # Release each completed page, including skipped/replayed pages.
            root.remove(page)
            page.clear()
