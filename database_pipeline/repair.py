"""Audited non-content classification repair into a separate immutable generation."""
from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
import zlib
from rag.wiki import records
from rag.corpus import _writer_lock
from .common import digest,publish,require_space,atomic_json
from .validate import corpus


def stack_tags(database,output,resume=False):
    """Restore pipe-encoded tags from retained original Q/A into a new generation."""
    from .stackexchange import tags
    database,output=Path(database).resolve(),Path(output).resolve();stage=Path(str(output)+'.building')
    output.parent.mkdir(parents=True,exist_ok=True)
    with _writer_lock(database),_writer_lock(output):
        if output.exists():raise FileExistsError(output)
        if stage.exists()!=resume:raise ValueError('Resume flag must match the existing tag-upgrade generation')
        if any(Path(str(database)+suffix).exists() for suffix in ('-wal','-journal')):raise ValueError('Seal the source before upgrading tags')
        source_hash=digest(database);require_space(output.parent,database.stat().st_size)
        if not resume:shutil.copyfile(database,stage)
        with closing(sqlite3.connect(stage)) as db,db:
            metadata=dict(db.execute('SELECT key,value FROM metadata'))
            if metadata.get('ingest_status')!='complete' or json.loads(metadata['ingest_options']).get('wiki')!='stackexchange':raise ValueError('Completed Stack Exchange corpus required')
            state=json.loads(metadata.get('tag_upgrade_state',json.dumps(dict(source_sha256=source_hash,last_id='',scanned=0,updated=0,complete=False))))
            if state['source_sha256']!=source_hash:raise ValueError('Tag-upgrade source changed')
            for id,raw,encoded in db.execute('SELECT a.id,a.wikitext_zlib,p.data_json FROM articles a JOIN post_metadata p ON p.article_id=a.id WHERE a.id>? ORDER BY a.id',(state['last_id'],)):
                original=json.loads(zlib.decompress(raw));post=json.loads(encoded);expected=tags(original['question'].get('Tags',''))
                if post.get('tags')!=expected:
                    post['tags']=expected;db.execute('UPDATE post_metadata SET data_json=? WHERE article_id=?',(json.dumps(post,ensure_ascii=False),id));state['updated']+=1
                state['last_id']=id;state['scanned']+=1
                if state['scanned']%1000==0:
                    db.execute("INSERT OR REPLACE INTO metadata VALUES('tag_upgrade_state',?)",(json.dumps(state),));db.commit()
            if state['scanned']!=db.execute('SELECT count(*) FROM articles').fetchone()[0]:raise ValueError('Some Stack Exchange documents lack original tag metadata')
            state['complete']=True;db.execute("INSERT OR REPLACE INTO metadata VALUES('tag_upgrade_state',?)",(json.dumps(state),));db.commit()
        validation=corpus(stage);publish(stage,output)
        result=dict(database=str(output),source_sha256=source_hash,source_untouched=True,tag_upgrade=state,validation=validation)
        atomic_json(Path(str(output)+'.tag-upgrade.json'),result);return result


def noncontent(source,database,output,wiki='dewikibooks'):
    database,output=Path(database).resolve(),Path(output).resolve()
    if output.exists():raise FileExistsError(output)
    with _writer_lock(database),_writer_lock(output):
        with closing(sqlite3.connect(database.as_uri()+'?mode=ro',uri=True)) as db:
            metadata=dict(db.execute('SELECT * FROM metadata'))
            if metadata.get('ingest_status')!='complete':raise ValueError('Finish source import before repair')
            errors={r[0]:r for r in db.execute('SELECT * FROM ingest_errors')}
            if not errors or len(errors)>1000:raise ValueError('No bounded set of errors to classify')
            original_sha=json.loads(metadata['ingest_options']).get('dump_sha256')
            if not original_sha or digest(source)!=original_sha:raise ValueError('Verified original dump required')
        found={}
        for position,row in records(source,wiki=wiki):
            if position in errors:
                expected=errors[position]
                if row.get('kind')!='skip' or not row.get('skip_reason','').startswith('noncontent_model:') or (row.get('id'),row.get('title'))!=(expected[1],expected[2]):
                    raise ValueError('Import error is not a proven ancillary CSS/JavaScript page: '+str(expected))
                found[position]=row
                if len(found)==len(errors):break
        if len(found)!=len(errors):raise ValueError('Failed source records not all found')
        require_space(output.parent,database.stat().st_size*2)
        stage=output.with_name(output.name+'.building')
        if stage.exists():raise FileExistsError(stage)
        shutil.copyfile(database,stage)
        with closing(sqlite3.connect(stage)) as db,db:
            db.execute('CREATE TABLE IF NOT EXISTS skipped_pages(position INTEGER PRIMARY KEY,article_id TEXT,title TEXT,reason TEXT NOT NULL)')
            db.execute('CREATE TABLE repair_audit(position INTEGER PRIMARY KEY,article_id TEXT,title TEXT,original_error TEXT,resolution TEXT,source_sha256 TEXT)')
            for position,row in found.items():
                error=errors[position]
                db.execute('INSERT INTO repair_audit VALUES(?,?,?,?,?,?)',(*error,row['skip_reason'],original_sha))
                db.execute('INSERT INTO skipped_pages VALUES(?,?,?,?)',(position,row['id'],row['title'],row['skip_reason']))
                db.execute('DELETE FROM ingest_errors WHERE position=?',(position,))
            state=json.loads(metadata['ingest_checkpoint']);state['errors']-=len(found);state['attempted']-=len(found);state['skipped']+=len(found)
            db.execute("UPDATE metadata SET value=? WHERE key='ingest_checkpoint'",(json.dumps(state),))
            db.execute("INSERT OR REPLACE INTO metadata VALUES('repair_policy','proven_noncontent_css_js_v1')")
        validation=corpus(stage,allow_empty=True)
        publish(stage,output)
        result=dict(reclassified_noncontent_pages=len(found),source_database_sha256=digest(database),validation=validation)
        atomic_json(str(output)+'.repair.json',result);return result
