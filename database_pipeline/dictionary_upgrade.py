"""Resume an audited dictionary normalization into a new immutable generation."""
from contextlib import closing
import json
from pathlib import Path
import re
import shutil
import sqlite3
import zlib
from rag.corpus import _writer_lock
from rag.wiki import records
from .common import digest,require_space,publish,atomic_json
from .dictionary import normalize,entries,store_entries,store_aliases


def upgrade(dump,source,output,*,dump_sha256,resume=False):
    source,output=Path(source).resolve(),Path(output).resolve()
    stage=output.with_name(output.name+'.building');output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists():raise FileExistsError(output)
    if stage.exists()!=resume:raise ValueError('Use resume only for an existing upgrade generation')
    with _writer_lock(source),_writer_lock(output):
        if any(Path(str(source)+s).exists() for s in ('-wal','-journal')):raise ValueError('Unsealed source dictionary')
        if digest(dump)!=dump_sha256:raise ValueError('Verified original dump required')
        identity=json.dumps(dict(pipeline='dictionary-alias-upgrade-v1',source_sha256=digest(source),dump_sha256=dump_sha256),sort_keys=True)
        if not resume:
            with closing(sqlite3.connect(source.as_uri()+'?mode=ro',uri=True)) as db:
                if dict(db.execute('SELECT * FROM metadata')).get('complete')!='true':raise ValueError('Incomplete source dictionary')
            require_space(output.parent,source.stat().st_size*2)
            shutil.copyfile(source,stage)
        with closing(sqlite3.connect(stage)) as db:
            db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL');db.execute('PRAGMA cache_size=-32768');db.execute('PRAGMA temp_store=FILE')
            if not resume:
                state=dict(phase='pages',last_id='',pages=0,position=0,new_entries=0)
                db.execute("UPDATE metadata SET value='false' WHERE key='complete'")
                db.executemany('INSERT INTO metadata VALUES(?,?)',[('upgrade_identity',identity),('upgrade_state',json.dumps(state))]);db.commit()
                db.execute('CREATE INDEX IF NOT EXISTS entry_page ON entry(page_id)')
                db.execute('CREATE TABLE IF NOT EXISTS lexical_alias(norm TEXT NOT NULL,lemma TEXT NOT NULL,language TEXT NOT NULL,target_norm TEXT NOT NULL,target_lemma TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(norm,language,target_norm,kind)) WITHOUT ROWID')
                db.execute('CREATE TABLE IF NOT EXISTS unparsed(norm TEXT NOT NULL,page_id TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,PRIMARY KEY(norm,page_id)) WITHOUT ROWID');db.commit()
            else:
                metadata=dict(db.execute('SELECT * FROM metadata'))
                if metadata['upgrade_identity']!=identity:raise ValueError('Upgrade source changed')
                state=json.loads(metadata['upgrade_state'])
                # A crash may occur after the initial checkpoint but before DDL.
                db.execute('CREATE INDEX IF NOT EXISTS entry_page ON entry(page_id)')
                db.execute('CREATE TABLE IF NOT EXISTS lexical_alias(norm TEXT NOT NULL,lemma TEXT NOT NULL,language TEXT NOT NULL,target_norm TEXT NOT NULL,target_lemma TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(norm,language,target_norm,kind)) WITHOUT ROWID')
                db.execute('CREATE TABLE IF NOT EXISTS unparsed(norm TEXT NOT NULL,page_id TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,PRIMARY KEY(norm,page_id)) WITHOUT ROWID');db.commit()
            def checkpoint():
                db.execute("UPDATE metadata SET value=? WHERE key='upgrade_state'",(json.dumps(state),));db.commit()
                print(json.dumps(state),flush=True)
                require_space(output.parent,0)
            if state['phase']=='pages':
                for page_id,lemma,url,raw,parsed in db.execute('SELECT p.id,p.lemma,p.source,p.raw_zlib,EXISTS(SELECT 1 FROM entry e WHERE e.page_id=p.id) FROM page p WHERE p.id>? ORDER BY p.id',(state['last_id'],)):
                    text=zlib.decompress(raw).decode('utf-8')
                    if not parsed:
                        extra=entries(text);store_entries(db,page_id,lemma,extra)
                        state['new_entries']+=len(extra);parsed=bool(extra)
                    if not parsed or re.search(r'schreibweise|hnlichkeiten\s+Umschrift',text,re.I):store_aliases(db,page_id,lemma,text,url,parsed)
                    state['last_id']=page_id;state['pages']+=1
                    if state['pages']%2000==0:checkpoint()
                state['phase']='redirects';checkpoint()
            if state['phase']=='redirects':
                for position,row in records(dump,state['position'],wiki='dewiktionary'):
                    if row['kind']=='error':raise ValueError(row['error'])
                    if row['kind']=='redirect':
                        target=row['redirect_target'].split('#',1)[0]
                        db.execute('INSERT OR IGNORE INTO page VALUES(?,?,?,?,?,?)',(row['id'],row['title'],row['revision_source'],row['revision_id'],row['timestamp'],zlib.compress(row['text'].encode('utf-8'))))
                        db.execute('INSERT OR IGNORE INTO lexical_alias VALUES(?,?,?,?,?,?,?)',(normalize(row['title']),row['title'],'',normalize(target),target,'redirect',row['revision_source']))
                        db.execute('INSERT OR REPLACE INTO unparsed VALUES(?,?,?,?)',(normalize(row['title']),row['id'],'redirect','Weiterleitung zu '+target))
                    state['position']=position
                    if position%5000==0:checkpoint()
                state['phase']='finalize';checkpoint()
            db.execute("UPDATE metadata SET value='true' WHERE key='complete'")
            db.execute("INSERT OR REPLACE INTO metadata VALUES('dictionary_features','bounded_alias_resolution; raw_lookup_for_unstructured_pages')");db.commit()
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE')
        from .validate import structured
        validation=structured(stage)
        publish(stage,output)
        result=dict(lineage=json.loads(identity),validation=validation)
        atomic_json(str(output)+'.upgrade.json',result)
        return result
