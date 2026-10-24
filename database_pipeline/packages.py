"""One streaming ZIP64 contract, no executable config from imported packages."""
from contextlib import closing
import datetime
import json
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
import zipfile

from .common import atomic_json, digest, publish, require_space
from .validate import corpus
from rag.corpus import _writer_lock

KINDS = {"rag", "wikidata", "dictionary", "tables"}
FILES = {"knowledge.sqlite", "knowledge.ivf", "vectors.i8", "sources.json", "LICENSES.txt"}


def build(database, output, *, package_id, version, kind, source, ivf=None, vectors=None, embedding=None,nprobe=16):
    Path(output).parent.mkdir(parents=True,exist_ok=True)
    with _writer_lock(database),_writer_lock(output):
        if any(Path(str(database)+suffix).exists() for suffix in ('-wal','-journal')):
            raise ValueError('Finish and close the source database before packaging')
        return _build(database,output,package_id=package_id,version=version,kind=kind,source=source,ivf=ivf,vectors=vectors,embedding=embedding,nprobe=nprobe)


def _build(database, output, *, package_id, version, kind, source, ivf=None, vectors=None, embedding=None,nprobe=16):
    if kind not in KINDS or not re.fullmatch(r"[a-z][a-z0-9.-]{1,63}", package_id):
        raise ValueError("Unknown package type or invalid package ID")
    if type(nprobe) is not int or not 1<=nprobe<=256 or (kind!='rag' and nprobe!=16):raise ValueError('RAG nprobe must be an integer in 1..256')
    if not re.fullmatch(r"[a-zA-Z0-9._-]{1,80}", version):
        raise ValueError("Invalid generation version")
    if not all(source.get(key) for key in ("dataset", "url", "date", "license")):
        raise ValueError("Dataset, source URL, date and license are mandatory")
    datetime.date.fromisoformat(source["date"])
    database, output = Path(database).resolve(), Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(output)
    paths = {"knowledge.sqlite": database}
    lineage=None
    if kind == "rag":
        if not embedding or embedding.get("profile") != "embeddinggemma2-search-v1" or embedding.get("dimension") != 256:
            raise ValueError("RAG package requires the production 256D embedding profile")
        if embedding.get("query_gguf_sha256") != '2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135':
            raise ValueError("Pinned Android query model hash required")
        if embedding.get("revision") != '914f7f89142e33e77833254d9c9b90c3cef7303b' or source.get('corpus') not in ('wikipedia','wikibooks','stackexchange'):
            raise ValueError("Known Android/HF revision pairing and corpus routing key required")
        if embedding.get("database_sha256") != digest(database):
            raise ValueError("Embedding source generation differs from database")
        if not ivf:
            raise ValueError("Production RAG package requires IVF")
        receipt=Path(str(ivf)+'.json')
        if not receipt.exists():raise ValueError('IVF lineage receipt missing; rebuild from verified shard assembly')
        lineage=json.loads(receipt.read_text(encoding='utf-8'))
        identity=lineage.get('embedding_identity')
        if not identity or any(embedding.get(key)!=value for key,value in identity.items()) or lineage.get('index_sha256')!=digest(ivf):
            raise ValueError('IVF bytes/profile/corpus lineage mismatch')
        paths["knowledge.ivf"] = Path(ivf)
        if vectors:
            paths["vectors.i8"] = Path(vectors)
    require_space(output.parent, sum(p.stat().st_size for p in paths.values()) + database.stat().st_size)
    with tempfile.TemporaryDirectory(prefix="bonsai-package-", dir=output.parent) as temp:
        staged_db = Path(temp) / "knowledge.sqlite"
        shutil.copyfile(database, staged_db)
        with closing(sqlite3.connect(staged_db)) as db, db:
            if embedding:
                for key, value in (("embedding_model", embedding["model"]), ("embedding_profile", embedding["profile"]),
                                   ("embedding_dimension", "256"), ("embedding_revision", embedding["revision"])):
                    db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", (key, value))
            db.execute("INSERT OR REPLACE INTO metadata VALUES('package_id',?)", (package_id,))
            if kind=='rag':
                # Android's system SQLite does not guarantee FTS5. Keep both
                # indexes in the host corpus, ship the portable FTS4 generation.
                db.execute('DROP TABLE IF EXISTS chunks_fts')
                db.execute("INSERT OR REPLACE INTO metadata VALUES('fts_profile','android-fts4')")
        if kind=='rag':
            with closing(sqlite3.connect(staged_db)) as db:db.execute('VACUUM')
        paths["knowledge.sqlite"] = staged_db
        if kind == "rag":
            validation = corpus(staged_db, ivf=ivf, vectors=vectors, scratch=temp)
            if nprobe>validation['ivf']['clusters']:raise ValueError('nprobe exceeds actual IVF cluster count')
            counts = dict(documents=validation["documents"], chunks=validation["chunks"], records=validation["documents"])
        else:
            with closing(sqlite3.connect(staged_db.as_uri() + "?mode=ro", uri=True)) as db:
                if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                    raise ValueError("Structured SQLite integrity failure")
                if db.execute("PRAGMA user_version").fetchone()[0] != 1:
                    raise ValueError("Unknown structured schema")
                metadata=dict(db.execute('SELECT key,value FROM metadata'))
                if metadata.get('complete')!='true':raise ValueError('Structured import incomplete')
                if db.execute('PRAGMA application_id').fetchone()[0]!={'wikidata':1113017159,'dictionary':1111770196,'tables':1112820044}[kind]:
                    raise ValueError('Structured database type mismatch')
                table = {"wikidata": "entity", "dictionary": "entry", "tables": "reference_value"}[kind]
                records = db.execute("SELECT count(*) FROM " + table).fetchone()[0]
            from .validate import structured
            validation = structured(staged_db)
            counts = dict(documents=records, chunks=0, records=records)
        manifest = dict(format="bonsai-offline-package", format_version=1, package_id=package_id,
                        version=version, type=kind, schema_version=1, source=source,
                        embedding=embedding, counts=counts, integrity=validation,
                        search=dict(nprobe=nprobe) if kind=='rag' else None,
                        lineage=lineage,
                        files=[dict(name=name, bytes=path.stat().st_size, sha256=digest(path)) for name, path in paths.items()])
        encoded = (json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
        if len(encoded) > 262144:
            raise ValueError("Manifest exceeds Android limit")
        part = output.with_name(output.name + ".part")
        with zipfile.ZipFile(part, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            archive.writestr("manifest.json", encoded)
            for name, path in paths.items():
                archive.write(path, name)
        publish(part, output)
        atomic_json(output.with_name(output.name + ".manifest.json"), manifest)
        receipt = dict(file=str(output.resolve()), bytes=output.stat().st_size, sha256=digest(output), manifest=manifest)
        atomic_json(output.with_name(output.name + ".verified.json"), receipt)
        return receipt
