"""Full SQLite, FTS and disk-backed vector/ID checks; never approximate sets."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import struct
import tempfile
import time

from .common import digest


def storage_stats(path):
    """Read actual b-tree allocation; APSW supplies dbstat on Windows builds."""
    path=Path(path).resolve()
    try:
        import apsw
    except ImportError:
        return None
    with closing(apsw.Connection(str(path),flags=apsw.SQLITE_OPEN_READONLY)) as db:
        return dict(objects=dict(db.execute('SELECT name,sum(pgsize) FROM dbstat GROUP BY name')),
                    page_size=next(db.execute('PRAGMA page_size'))[0],
                    page_count=next(db.execute('PRAGMA page_count'))[0],
                    freelist_count=next(db.execute('PRAGMA freelist_count'))[0],sqlite=apsw.sqlitelibversion())


def database(path,**kwargs):
    path=Path(path).resolve()
    with closing(sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)) as db:
        app_id=db.execute('PRAGMA application_id').fetchone()[0]
    if app_id==0:return corpus(path,**kwargs)
    if any(kwargs.get(key) for key in ('vectors','ivf')):raise ValueError('Structured facts do not have vector indexes')
    return structured(path)


def structured(path):
    from decimal import Decimal
    path=Path(path).resolve();started=time.monotonic()
    if any(Path(str(path)+suffix).exists() for suffix in ('-wal','-journal')):raise ValueError('Unsealed SQLite generation')
    result=dict(database_bytes=path.stat().st_size,database_sha256=digest(path))
    with closing(sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)) as db:
        db.execute('PRAGMA cache_size=-8192');db.execute('PRAGMA temp_store=FILE')
        if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise ValueError('SQLite integrity failed')
        if db.execute('PRAGMA user_version').fetchone()[0]!=1:raise ValueError('Unknown structured schema')
        kind={1111770196:'dictionary',1112820044:'tables',1113017159:'wikidata'}.get(db.execute('PRAGMA application_id').fetchone()[0])
        if kind is None:raise ValueError('Unknown structured application ID')
        metadata=dict(db.execute('SELECT * FROM metadata'))
        if metadata.get('complete')!='true':raise ValueError('Incomplete structured import')
        def count(table):return db.execute('SELECT count(*) FROM '+table).fetchone()[0]
        def reject(sql,message):
            if db.execute(sql).fetchone():raise ValueError(message)
        result['type']=kind
        if kind=='dictionary':
            result.update(records=count('entry'),pages=count('page'),forms=count('form'),details=count('detail'),errors=count('ingest_errors'))
            if result['errors']:raise ValueError('Dictionary import errors require repair')
            reject('SELECT 1 FROM entry e LEFT JOIN page p ON p.id=e.page_id WHERE p.id IS NULL LIMIT 1','Orphan dictionary entry')
            for table in ('form','detail'):
                reject('SELECT 1 FROM '+table+' f LEFT JOIN entry e ON e.id=f.entry_id WHERE e.id IS NULL LIMIT 1','Orphan dictionary '+table)
            result['pages_without_parsed_entry']=db.execute('SELECT count(*) FROM (SELECT id FROM page EXCEPT SELECT page_id FROM entry)').fetchone()[0]
            result['top_languages']=db.execute('SELECT language,count(*) FROM entry GROUP BY language ORDER BY count(*) DESC LIMIT 25').fetchall()
            result['detail_kinds']=dict(db.execute('SELECT kind,count(*) FROM detail GROUP BY kind'))
            if db.execute("SELECT 1 FROM sqlite_master WHERE name='lexical_alias'").fetchone():
                result['aliases']=count('lexical_alias');result['unstructured_lookup_pages']=count('unparsed')
                reject('SELECT 1 FROM unparsed u LEFT JOIN page p ON p.id=u.page_id WHERE p.id IS NULL LIMIT 1','Orphan raw dictionary lookup')
                result['pages_without_any_lookup']=db.execute('SELECT count(*) FROM (SELECT id FROM page EXCEPT SELECT page_id FROM entry EXCEPT SELECT page_id FROM unparsed)').fetchone()[0]
                if result['pages_without_any_lookup']:raise ValueError('Dictionary pages unavailable to exact lookup')
        elif kind=='tables':
            result.update(records=count('reference_value'),foods=count('food'),nutrients=count('nutrient'),units=count('unit'))
            reject('SELECT id FROM food EXCEPT SELECT id FROM food_fts LIMIT 1','Missing food FTS row')
            reject('SELECT id FROM food_fts EXCEPT SELECT id FROM food LIMIT 1','Extra food FTS row')
            if count('food_fts')!=result['foods']:raise ValueError('Duplicate food FTS rows')
            reject('SELECT 1 FROM nutrient n LEFT JOIN food f ON f.id=n.food_id WHERE f.id IS NULL LIMIT 1','Orphan nutrient')
            reject("SELECT 1 FROM nutrient WHERE status IN ('missing','invalid_negative') AND value IS NOT NULL LIMIT 1",'Unknown/invalid nutrient represented as known')
            for value, in db.execute('SELECT value FROM nutrient WHERE value IS NOT NULL'):
                if not Decimal(value).is_finite() or Decimal(value)<0:raise ValueError('Invalid nutrient decimal')
            result['nutrient_status']=dict(db.execute('SELECT status,count(*) FROM nutrient GROUP BY status'))
            result['fts_ids']='exact_match'
        else:
            result.update(records=count('entity'),statements=count('statement'),terms=count('term'))
            result['entity_depth']=dict(db.execute('SELECT loaded,count(*) FROM entity GROUP BY loaded'))
            result['p31_statements']=db.execute('SELECT count(*) FROM statement WHERE property=-31').fetchone()[0]
            result['p279_statements']=db.execute('SELECT count(*) FROM statement WHERE property=-279').fetchone()[0]
            result['unresolved_labels']=db.execute('SELECT count(*) FROM entity e WHERE NOT EXISTS(SELECT 1 FROM term t WHERE t.entity=e.id AND t.kind=0)').fetchone()[0]
            for field in ('subject','property','target','unit','calendar'):
                reject('SELECT 1 FROM statement s LEFT JOIN entity e ON e.id=s.'+field+' WHERE s.'+field+' IS NOT NULL AND s.'+field+'!=0 AND e.id IS NULL LIMIT 1','Unresolved typed entity reference: '+field)
            state=json.loads(metadata.get('state','{}'))
            if state.get('errors',0):raise ValueError('Wikidata import errors require review')
            result['coverage']='selected_local_subset'
        result['storage']=storage_stats(path)
        result.update(integrity='passed',seconds=time.monotonic()-started)
    return result


def corpus(database, *, vectors=None, ivf=None, scratch=None,allow_empty=False):
    database = Path(database).resolve()
    started = time.monotonic()
    if any(Path(str(database) + suffix).exists() for suffix in ("-wal", "-journal")):
        raise ValueError("Unsealed SQLite generation")
    result = dict(database_bytes=database.stat().st_size, database_sha256=digest(database))
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
        db.execute("PRAGMA cache_size=-8192")
        db.execute("PRAGMA temp_store=FILE")
        checks = [row[0] for row in db.execute("PRAGMA integrity_check")]
        if checks != ["ok"]:
            raise ValueError("SQLite integrity: " + repr(checks[:10]))
        if db.execute("PRAGMA user_version").fetchone()[0] != 1:
            raise ValueError("Unknown schema version")
        metadata = dict(db.execute("SELECT key,value FROM metadata"))
        if metadata.get("ingest_status") not in ("limited", "complete"):
            raise ValueError("Incomplete corpus import")
        result["ingest_status"] = metadata["ingest_status"]
        result["documents"] = db.execute("SELECT count(*) FROM articles WHERE redirect_target='' ").fetchone()[0]
        result["redirects"] = db.execute("SELECT count(*) FROM redirects").fetchone()[0]
        result["chunks"] = db.execute("SELECT count(*) FROM chunks").fetchone()[0]
        if not allow_empty and (not result['documents'] or not result['chunks']):raise ValueError('Empty text corpus cannot be validated for publication')
        result["errors"] = db.execute("SELECT count(*) FROM ingest_errors").fetchone()[0]
        if result["errors"]:
            raise ValueError(f"Import errors require repair: {result['errors']}")
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master")}
        for table, field in (("chunks_fts", "rowid"), ("chunks_fts4", "rowid"), ("chunk_metadata", "chunk_id")):
            if table not in tables:
                if table=='chunks_fts' and metadata.get('fts_profile')=='android-fts4':
                    result['fts_profile']='android-fts4'
                    continue
                if table == "chunks_fts4":
                    result["android_fts4"] = False
                    continue
                raise ValueError("Missing table " + table)
            if db.execute(f"SELECT id FROM chunks EXCEPT SELECT {field} FROM {table} LIMIT 1").fetchone() or db.execute(f"SELECT {field} FROM {table} EXCEPT SELECT id FROM chunks LIMIT 1").fetchone():
                raise ValueError("FTS/chunk metadata ID mismatch: " + table)
            result[table + "_ids"] = "exact_match"
        if db.execute("SELECT id FROM chunks WHERE id<=0 LIMIT 1").fetchone():
            raise ValueError("Android chunk IDs must be positive signed int64")
        result["compressed_bytes"] = dict(zip(("wikitext", "markdown"), db.execute("SELECT coalesce(sum(length(wikitext_zlib)),0),coalesce(sum(length(markdown_zlib)),0) FROM articles").fetchone()))
        result["compressed_bytes"]["chunks"] = db.execute("SELECT coalesce(sum(length(text_zlib)),0) FROM chunks").fetchone()[0]
        try:
            result["sqlite_objects_bytes"] = dict(db.execute("SELECT name,sum(pgsize) FROM dbstat GROUP BY name"))
        except sqlite3.OperationalError:
            allocation=storage_stats(database)
            result["sqlite_objects_bytes"] = allocation['objects'] if allocation else None
        if vectors:
            path = Path(vectors)
            with path.open("rb") as stream:
                header = stream.read(16)
                if len(header) != 16:
                    raise ValueError("Truncated vector header")
                magic, dimension, count = struct.unpack("<8sII", header)
                if magic != b"BRAGV001" or dimension != 256 or count != result["chunks"] or path.stat().st_size != 16 + count * 268:
                    raise ValueError("Vector header/count/length mismatch")
                for expected, in db.execute("SELECT id FROM chunks ORDER BY id"):
                    record = stream.read(268)
                    if struct.unpack_from("<q", record)[0] != expected:
                        raise ValueError("Vector/database ID order mismatch")
            result["vectors"] = dict(bytes=path.stat().st_size, sha256=digest(path), ids="exact_match")
        if ivf:
            from rag.ivf import IvfIndex
            index = IvfIndex(ivf)
            try:
                if index.count != result["chunks"]:
                    raise ValueError("IVF/database count mismatch")
                # External SQLite B-tree catches duplicates and sorts IDs without
                # keeping an O(N) Python set or int64 sort array in RAM.
                with tempfile.TemporaryDirectory(prefix="bonsai-ids-", dir=scratch or database.parent) as temp:
                    with closing(sqlite3.connect(Path(temp) / "ids.sqlite")) as seen:
                        seen.execute("PRAGMA cache_size=-8192")
                        seen.execute("CREATE TABLE ids(id INTEGER PRIMARY KEY)")
                        for start in range(0, index.count, 8192):
                            seen.executemany("INSERT INTO ids VALUES(?)", ((int(i),) for i in index.ids[start:start + 8192]))
                            seen.commit()
                        a, b = db.execute("SELECT id FROM chunks ORDER BY id"), seen.execute("SELECT id FROM ids ORDER BY id")
                        if any(left != right for left, right in zip(a, b)):
                            raise ValueError("IVF/database ID set mismatch")
                sizes = index.offsets[1:] - index.offsets[:-1]
                result["ivf"] = dict(bytes=Path(ivf).stat().st_size, sha256=digest(ivf), ids="exact_match",
                                     clusters=index.clusters, smallest_cluster=int(sizes.min()),
                                     largest_cluster=int(sizes.max()), empty_clusters=int((sizes == 0).sum()))
            finally:
                for value in (index.ids, index.vectors, index.offsets, index.centroids):
                    if hasattr(value, "_mmap"):
                        value._mmap.close()
    result.update(integrity="passed", seconds=time.monotonic() - started)
    return result
