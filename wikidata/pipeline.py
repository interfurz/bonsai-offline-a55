"""Streaming official line-oriented JSON/JSON.gz/JSON.bz2 dumps, never a DOM of the dump.

The staging SQLite file IS the checkpoint. Offsets and entity writes commit together.
Compressed seek replays decompression (bounded RAM); uncompressed seek is direct.
"""
import bz2
import datetime
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import unicodedata
import zlib

APPLICATION_ID = 1113017159
MAX_ID = 2**63 - 1


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[QP][1-9][0-9]*", value):
        raise ValueError(f"Invalid Wikidata ID: {value!r}")
    number = int(value[1:])
    if number > MAX_ID:
        raise ValueError("Wikidata ID exceeds SQLite integer range")
    return number if value[0] == "Q" else -number


def normalize(value):
    # Same algorithm on Android; no fuzzy fallback that could choose another person.
    return " ".join(unicodedata.normalize("NFKC", value).lower().split())


def packed(value):
    return zlib.compress(json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                                    allow_nan=False).encode(), 9)


def open_dump(path,*,bzip2_threads=1,index_path=None,identity=None):
    if str(path).endswith('.bz2') and bzip2_threads>1:
        from .streams import IndexedDump
        return IndexedDump(path,bzip2_threads,index_path,identity)
    return (gzip.open if str(path).endswith(".gz") else
            bz2.open if str(path).endswith(".bz2") else open)(path, "rb")


def records(stream, offset=0, max_line_bytes=16 * 1024 * 1024):
    stream.seek(0)
    array = stream.read(4096).lstrip().startswith(b"[")
    closed = False
    stream.seek(offset)
    while True:
        start = stream.tell()
        line = stream.readline(max_line_bytes + 1)
        if not line:
            if array and not closed:
                raise ValueError("Truncated official JSON array: closing ] missing")
            return
        if len(line) > max_line_bytes:
            while line and not line.endswith(b"\n"):
                line = stream.readline(max_line_bytes + 1)
            yield start, stream.tell(), None, "Entity line exceeds max_line_bytes"
            continue
        data = line.strip().rstrip(b",")
        if data == b"]":
            closed = True
            continue
        if data in (b"", b"["):
            continue
        if closed:
            raise ValueError("Unexpected content after closing JSON array")
        try:
            obj = json.loads(data)
            if not isinstance(obj, dict):
                raise ValueError("Expected one entity object per line (official JSON dump format)")
            yield start, stream.tell(), obj, None
        except (ValueError, UnicodeError) as exc:
            yield start, stream.tell(), None, str(exc)


def source_identity(path):
    stat = path.stat()
    with path.open("rb") as source:
        head = source.read(65536)
        source.seek(max(0, stat.st_size - 65536))
        tail = source.read(65536)
    return {"path": str(path.resolve()), "bytes": stat.st_size, "mtime_ns": stat.st_mtime_ns,
            "edge_sha256": hashlib.sha256(head + tail).hexdigest()}


def validate_config(config):
    allowed = {"entities", "classes", "exclude_classes", "properties", "qualifiers", "references"}
    if set(config) - allowed:
        raise ValueError("Unknown filter keys")
    for key in ("entities", "classes", "exclude_classes", "properties"):
        if key in config:
            if not isinstance(config[key], list):
                raise ValueError(key + " must be a list")
            for value in config[key]:
                number = identifier(value)
                if (key == "properties") != (number < 0):
                    raise ValueError("Wrong ID kind in " + key)
    for key in ("qualifiers", "references"):
        if key in config and not isinstance(config[key], bool):
            raise ValueError(key + " must be boolean")


def selected(obj, config):
    if obj.get("type") != "item":
        return False  # Properties referenced by selected statements get metadata in pass 2.
    classes = {claim.get("mainsnak", {}).get("datavalue", {}).get("value", {}).get("id")
               for claim in obj.get("claims", {}).get("P31", [])
               if claim.get("mainsnak", {}).get("datatype") == "wikibase-item"}
    if classes.intersection(config.get("exclude_classes", [])):
        return False
    return (not config.get("entities") and not config.get("classes") or
            obj["id"] in config.get("entities", []) or
            bool(classes.intersection(config.get("classes", []))))


def ensure_entity(db, value):
    key = identifier(value)
    db.execute("INSERT OR IGNORE INTO entity(id) VALUES(?)", (key,))
    return key


def referenced(db, value):
    """Only typed IDs/units/calendars, not arbitrary strings resembling IDs."""
    if isinstance(value, dict):
        if value.get("entity-type") in ("item", "property") and "id" in value:
            ensure_entity(db, value["id"])
        for key, child in value.items():
            if key in ("unit", "calendarmodel") and isinstance(child, str) and child != "1":
                ensure_entity(db, child.rsplit("/", 1)[-1])
            elif key in ("property",) and isinstance(child, str):
                ensure_entity(db, child)
            elif isinstance(child, (dict, list)):
                referenced(db, child)
    elif isinstance(value, list):
        for child in value:
            referenced(db, child)


def terms(db, obj, loaded):
    key = ensure_entity(db, obj["id"])
    db.execute("UPDATE entity SET datatype=?,revision=?,modified=?,loaded=MAX(loaded,?) WHERE id=?",
               (obj.get("datatype"), obj.get("lastrevid"), obj.get("modified"), loaded, key))
    db.execute("DELETE FROM term WHERE entity=?", (key,))
    for lang in ("de", "en"):
        for kind, field in enumerate(("labels", "aliases", "descriptions")):
            entries = obj.get(field, {}).get(lang, [])
            if isinstance(entries, dict):
                entries = [entries]
            for entry in entries:
                value = entry["value"]
                if not isinstance(value, str):
                    raise ValueError("Non-text label")
                db.execute("INSERT OR IGNORE INTO term VALUES(?,?,?,?,?)",
                           (key, lang, kind, value, normalize(value) if kind < 2 else None))
    return key


def insert_entity(db, obj, config):
    subject = terms(db, obj, 2)
    db.execute("DELETE FROM statement WHERE subject=?", (subject,))
    for prop, claims in obj.get("claims", {}).items():
        if config.get("properties") and prop not in config["properties"]:
            continue
        property_id = ensure_entity(db, prop)
        if property_id >= 0:
            raise ValueError("Statement property must be P ID")
        for claim in claims:
            snak = claim["mainsnak"]
            kind = snak["snaktype"]
            target = amount = number = unit = time = precision = calendar = None
            value = snak.get("datavalue", {}).get("value")
            if kind == "value":
                datatype = snak.get("datavalue", {}).get("type")
                if datatype == "wikibase-entityid":
                    kind = "entity" if value.get("entity-type") in ("item", "property") else datatype
                    if kind == "entity":
                        target = ensure_entity(db, value["id"])
                elif datatype == "quantity":
                    kind = "quantity"
                    amount = value["amount"]
                    # Keep decimal text and bounds intact; REAL is only the range index.
                    from decimal import Decimal
                    if not Decimal(amount).is_finite():
                        raise ValueError("Non-finite quantity")
                    number = float(amount)
                    if not math.isfinite(number):
                        number = None  # Exact value still available, not range-queryable.
                    unit = 0 if value["unit"] == "1" else ensure_entity(db, value["unit"].rsplit("/", 1)[-1])
                elif datatype == "time":
                    kind = "time"
                    time, precision = value["time"], value["precision"]
                    calendar = ensure_entity(db, value["calendarmodel"].rsplit("/", 1)[-1])
                else:
                    kind = datatype or "unknown"
            elif kind not in ("somevalue", "novalue"):
                raise ValueError("Unknown snak type")
            detail = {"guid": claim.get("id"), "mainsnak": snak}
            if config.get("qualifiers", True):
                detail["qualifiers"] = claim.get("qualifiers", {})
            if config.get("references", True):
                detail["references"] = claim.get("references", [])
            referenced(db, detail)
            rank = {"deprecated": 0, "normal": 1, "preferred": 2}[claim.get("rank", "normal")]
            db.execute("""INSERT INTO statement(subject,property,rank,kind,target,amount,number,
                       unit,time,precision,calendar,detail) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                       (subject, property_id, rank, kind, target, amount, number, unit,
                        time, precision, calendar, packed(detail)))


def build(source, output, *, dump_date, config=None, resume=False, batch=1000,
          max_bytes=7_000_000_000, max_errors=0, max_line_bytes=16 * 1024 * 1024,
          stop_after=None, progress=None,bzip2_threads=1):
    """Return False on an intentional checkpoint stop, True after atomic publication.

    Source must be immutable while importing. Identity uses stat plus edge hashes;
    verify the official full checksum separately before starting a production import.
    """
    datetime.date.fromisoformat(dump_date)
    config = config or {}
    validate_config(config)
    if batch < 1 or max_bytes < 65536 or max_errors < 0 or max_line_bytes < 128:
        raise ValueError("Invalid resource/error limits")
    if not 1<=bzip2_threads<=16:raise ValueError('Require 1..16 bzip2 threads')
    source, output = Path(source), Path(output)
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(str(output) + ".building")
    identity=source_identity(source)
    signature = json.dumps({"source": identity, "config": config,
                            "dump_date": dump_date, "max_bytes": max_bytes,
                            "max_line_bytes": max_line_bytes}, sort_keys=True)
    runtime_config = {key: set(value) if key in ("entities", "classes", "exclude_classes", "properties")
                      else value for key, value in config.items()}
    if staging.exists() != resume:
        raise ValueError("Use --resume for existing .building file; omit it for a new import")
    db = sqlite3.connect(staging)
    try:
        db.execute("PRAGMA page_size=4096")
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA cache_size=-32768")
        db.execute("PRAGMA temp_store=FILE")
        db.execute(f"PRAGMA max_page_count={max_bytes // 4096}")
        if not resume:
            db.executescript(Path(__file__).with_name("schema.sql").read_text())
            state = {"phase": "entities", "offset": 0, "scanned": 0, "selected": 0, "errors": 0}
            db.executemany("INSERT INTO metadata VALUES(?,?)", [
                ("signature", signature), ("state", json.dumps(state)), ("dump_date", dump_date),
                ("selection", json.dumps(config)), ("complete", "false"),
                ("source", "https://dumps.wikimedia.org/wikidatawiki/entities/"),
                ("license", "CC0; Wikidata structured data")])
            db.commit()
        else:
            meta = dict(db.execute("SELECT key,value FROM metadata"))
            if meta["signature"] != signature:
                raise ValueError("Resume source/configuration differs from checkpoint")
            state = json.loads(meta["state"])
        processed = 0
        active_stream=None

        def checkpoint():
            db.execute("UPDATE metadata SET value=? WHERE key='state'", (json.dumps(state),))
            db.commit()
            if active_stream is not None and hasattr(active_stream,'checkpoint'):active_stream.checkpoint()
            if progress:
                progress(dict(state, database_bytes=staging.stat().st_size))

        for phase in ("entities", "labels"):
            if state["phase"] != phase:
                continue
            with open_dump(source,bzip2_threads=bzip2_threads,index_path=Path(str(output)+'.bz2-index.json'),identity=identity) as stream:
                active_stream=stream
                for start, end, obj, error in records(stream, state["offset"], max_line_bytes):
                    if not db.in_transaction:
                        db.execute("BEGIN")
                    db.execute("SAVEPOINT entity_record")
                    chosen = False
                    try:
                        if error:
                            raise ValueError(error)
                        # Lexemes/forms/senses are intentionally outside this format.
                        if obj.get("type") in ("item", "property"):
                            key = identifier(obj["id"])
                            if phase == "entities" and selected(obj, runtime_config):
                                insert_entity(db, obj, runtime_config)
                                chosen = True
                            elif phase == "labels" and db.execute(
                                    "SELECT 1 FROM entity WHERE id=? AND loaded=0", (key,)).fetchone():
                                terms(db, obj, 1)
                    except (ValueError, KeyError, TypeError, OverflowError) as exc:
                        db.execute("ROLLBACK TO entity_record")
                        if state["errors"] >= max_errors:
                            raise ValueError(f"{phase} offset {start}: {exc}") from exc
                        state["errors"] += 1
                        with Path(str(output) + ".errors.jsonl").open("a", encoding="utf-8") as errors:
                            errors.write(json.dumps({"phase": phase, "offset": start,
                                                     "error": str(exc)[:1000]}) + "\n")
                    finally:
                        # SQLITE_FULL may already have rolled back the whole transaction.
                        if db.in_transaction:
                            db.execute("RELEASE entity_record")
                    state["offset"] = end
                    state["scanned"] += 1
                    state["selected"] += int(chosen)
                    processed += 1
                    if processed % batch == 0:
                        checkpoint()
                    if stop_after is not None and processed >= stop_after:
                        checkpoint()
                        return False
            active_stream=None
            state["phase"] = "labels" if phase == "entities" else "finalize"
            state["offset"] = 0
            checkpoint()
        if state["phase"] == "finalize":
            if not db.execute("SELECT 1 FROM statement LIMIT 1").fetchone():
                raise ValueError("Selection contains no supported statements")
            db.execute("ANALYZE")
            db.execute("UPDATE metadata SET value='true' WHERE key='complete'")
            db.commit()
            db.execute("VACUUM")
            if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise ValueError("SQLite integrity check failed")
            if staging.stat().st_size > max_bytes:
                raise ValueError("Database exceeds size budget; narrow selection")
    finally:
        db.close()
    # Single writer per output path. Android must only receive the published file.
    os.replace(staging, output)
    return True
