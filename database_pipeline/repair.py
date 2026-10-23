"""Audited non-content classification repair into a separate immutable generation."""
from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
from rag.wiki import records
from rag.corpus import _writer_lock
from .common import digest,publish,require_space,atomic_json
from .validate import corpus


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
        validation=corpus(stage)
        publish(stage,output)
        result=dict(reclassified_noncontent_pages=len(found),source_database_sha256=digest(database),validation=validation)
        atomic_json(str(output)+'.repair.json',result);return result
