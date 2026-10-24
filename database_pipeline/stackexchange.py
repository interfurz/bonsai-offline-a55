"""Score-filtered, disk-backed question/answer adapter for five agreed sites."""
from contextlib import closing
import datetime
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET
import zlib

from .common import atomic_json, digest, publish, require_space
from .download import curl,json_document
from rag.corpus import _fingerprint, _writer_lock

SITES={'stackoverflow.com','math.stackexchange.com','physics.stackexchange.com','electronics.stackexchange.com','superuser.com'}
TAGS={'python','python-3.x','java','c','c++','c#','javascript','typescript','android','linux','sql','sqlite','git','bash','powershell','numpy','pandas','pytorch','html','css','algorithms','data-structures'}


def tags(value):
    value=html.unescape(value or '')
    if not value:return []
    if value.startswith('|') and value.endswith('|'):
        result=[item for item in value.split('|') if item]
    else:
        result=re.findall(r'<([^>]+)>',value)
        if ''.join('<'+item+'>' for item in result)!=value:raise ValueError('Unknown Stack Exchange tag encoding')
    if not result or any(any(char.isspace() for char in item) for item in result):raise ValueError('Invalid Stack Exchange tags')
    return result


class Markdown(HTMLParser):
    def __init__(self):super().__init__(convert_charrefs=True);self.output=[];self.pre=0;self.suppressed=0
    def handle_starttag(self,tag,attrs):
        if tag in ('script','style'):self.suppressed+=1
        if self.suppressed:return
        if tag=='pre':self.pre+=1;self.output.append('\n```\n')
        elif tag=='code' and not self.pre:self.output.append('`')
        elif tag in ('p','div','blockquote','h1','h2','h3','br','tr'):self.output.append('\n\n')
        elif tag in ('td','th'):self.output.append(' | ')
        elif tag=='li':self.output.append('\n- ')
        elif tag in ('strong','b'):self.output.append('**')
        elif tag in ('em','i'):self.output.append('*')
    def handle_endtag(self,tag):
        if tag in ('script','style'):self.suppressed=max(0,self.suppressed-1);return
        if self.suppressed:return
        if tag=='pre':self.pre=max(0,self.pre-1);self.output.append('\n```\n')
        elif tag=='code' and not self.pre:self.output.append('`')
        elif tag in ('p','div','blockquote'):self.output.append('\n\n')
        elif tag in ('strong','b'):self.output.append('**')
        elif tag in ('em','i'):self.output.append('*')
    def handle_data(self,data):
        if not self.suppressed:self.output.append(data)


def markdown(value):
    parser=Markdown();parser.feed(value);parser.close()
    # Preserve code indentation, newlines and LaTeX verbatim.
    return ''.join(parser.output).strip()


def rows(path,member='Posts.xml'):
    from .archive_stream import member_stream
    source=member_stream(path,member) if Path(path).suffix.lower()=='.7z' else Path(path).open('rb')
    with source as stream:
        parser=ET.iterparse(stream,events=('start','end'));_,root=next(parser)
        for event,row in parser:
            if event=='end' and row.tag=='row':
                yield dict(row.attrib)
                root.remove(row);row.clear()


def fetch(root,site,component='posts'):
    if site not in SITES:raise ValueError('Only the five agreed communities are supported')
    if component not in ('posts','users'):raise ValueError('Only posts/users archives are needed')
    root=Path(root);manifest=root/'manifests/stackexchange-archive.json'
    manifest.parent.mkdir(parents=True,exist_ok=True)
    metadata=json_document('https://archive.org/metadata/stackexchange',manifest)
    name=site+('-Posts.7z' if component=='posts' else '-Users.7z') if site=='stackoverflow.com' else site+'.7z'
    entry=next((x for x in metadata['files'] if x['name']==name),None)
    if not entry or not entry.get('md5'):raise ValueError('Archive manifest does not provide expected file/checksum')
    target=root/'downloads'/name;part=target.with_name(name+'.part');target.parent.mkdir(parents=True,exist_ok=True)
    with _writer_lock(target):
        if not target.exists():
            require_space(target.parent,max(0,int(entry['size'])-(part.stat().st_size if part.exists() else 0)))
            if not part.exists() or part.stat().st_size!=int(entry['size']):curl('https://archive.org/download/stackexchange/'+name,part,resume=True)
        candidate=target if target.exists() else part
        if candidate.stat().st_size!=int(entry['size']) or digest(candidate,'md5')!=entry['md5']:raise ValueError('Archive size/MD5 mismatch')
        if candidate==part:publish(part,target)
        receipt=dict(site=site,file=name,bytes=int(entry['size']),sha256=digest(target),official_archive_md5=entry['md5'],
                     archive_mtime=entry.get('mtime'),uploader=metadata['metadata'].get('uploader'),source='https://archive.org/details/stackexchange')
        atomic_json(root/'manifests'/(name+'.verified.json'),receipt)
        return receipt


def extract(archive,directory):
    import py7zr
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    with py7zr.SevenZipFile(archive,'r') as file:
        entries={entry.filename:entry for entry in file.list()}
        wanted=[name for name in ('Posts.xml','Users.xml','Tags.xml') if name in entries]
        if 'Posts.xml' not in wanted:raise ValueError('Archive lacks Posts.xml')
        require_space(directory,sum(entries[name].uncompressed for name in wanted))
        if any((directory/name).exists() for name in wanted):raise FileExistsError('Extract into a new raw generation')
        file.extract(path=directory,targets=wanted)
    return wanted


def select(posts,output,*,site,max_questions=50000,min_question_score=5,min_answer_score=0,resume=False):
    if site not in SITES or min(max_questions,min_question_score+1,min_answer_score+1)<1:raise ValueError('Invalid community selection')
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True);stage=output.with_name(output.name+'.building')
    identity=json.dumps(dict(pipeline='stackexchange-select-v2-tags',source=_fingerprint(posts),site=site,max_questions=max_questions,min_question_score=min_question_score,min_answer_score=min_answer_score,tags=sorted(TAGS) if site=='stackoverflow.com' else None),sort_keys=True)
    with _writer_lock(output):
        if output.exists():raise FileExistsError(output)
        if stage.exists()!=resume:raise ValueError('Resume flag must match existing staging generation')
        with closing(sqlite3.connect(stage)) as db,db:
            if not resume:
                db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT); CREATE TABLE question(id INTEGER PRIMARY KEY,accepted INTEGER,score INTEGER,title TEXT,tags TEXT,data BLOB); CREATE TABLE answer(id INTEGER PRIMARY KEY,question INTEGER,score INTEGER,accepted INTEGER,data BLOB); CREATE INDEX answer_parent ON answer(question,accepted,score);')
                db.executemany('INSERT INTO metadata VALUES(?,?)',[('identity',identity),('phase','questions'),('position','0')]);db.commit()
            meta=dict(db.execute('SELECT * FROM metadata'))
            if meta['identity']!=identity:raise ValueError('Source/selection changed')
            db.execute('CREATE INDEX IF NOT EXISTS question_score ON question(score DESC,id)')
            db.execute('PRAGMA cache_size=-16384');db.execute('PRAGMA journal_mode=WAL')
            for phase in ('questions','answers'):
                meta=dict(db.execute('SELECT * FROM metadata'))
                if meta['phase']!=phase:continue
                after=int(meta['position'])
                eligible=int(meta.get('quality_eligible_questions',str(db.execute('SELECT count(*) FROM question').fetchone()[0])))
                for position,row in enumerate(rows(posts),1):
                    if position<=after:continue
                    if phase=='questions' and row.get('PostTypeId')=='1' and int(row.get('Score','0'))>=min_question_score and row.get('AcceptedAnswerId') and not row.get('ClosedDate') and not row.get('DeletionDate'):
                        row_tags=tags(row.get('Tags',''))
                        if site!='stackoverflow.com' or TAGS.intersection(row_tags):
                            db.execute('INSERT INTO question VALUES(?,?,?,?,?,?)',(int(row['Id']),int(row['AcceptedAnswerId']),int(row['Score']),row.get('Title',''),json.dumps(row_tags),zlib.compress(json.dumps(row,ensure_ascii=False).encode())))
                            eligible+=1
                    elif phase=='answers' and row.get('PostTypeId')=='2' and int(row.get('Score','0'))>=min_answer_score and not row.get('DeletionDate'):
                        parent=db.execute('SELECT accepted FROM question WHERE id=?',(int(row['ParentId']),)).fetchone()
                        if parent and (int(row['Id'])==parent[0] or int(row.get('Score','0'))>=5):
                            db.execute('INSERT INTO answer VALUES(?,?,?,?,?)',(int(row['Id']),int(row['ParentId']),int(row.get('Score','0')),int(int(row['Id'])==parent[0]),zlib.compress(json.dumps(row,ensure_ascii=False).encode())))
                    if position%10000==0:
                        if phase=='questions':
                            # Exact incremental top-k: a discarded lower-ranked
                            # question cannot re-enter when only new rows arrive.
                            db.execute('DELETE FROM question WHERE id NOT IN (SELECT id FROM question ORDER BY score DESC,id LIMIT ?)',(max_questions,))
                            db.execute("INSERT OR REPLACE INTO metadata VALUES('quality_eligible_questions',?)",(str(eligible),))
                        db.execute("UPDATE metadata SET value=? WHERE key='position'",(str(position),));db.commit();print(json.dumps(dict(phase=phase,position=position)),flush=True)
                if phase=='questions':
                    db.execute('DELETE FROM question WHERE id NOT IN (SELECT id FROM question ORDER BY score DESC,id LIMIT ?)',(max_questions,))
                    db.execute("INSERT OR REPLACE INTO metadata VALUES('quality_eligible_questions',?)",(str(eligible),))
                    if not db.execute('SELECT 1 FROM question LIMIT 1').fetchone():raise ValueError('Selection contains no eligible questions; verify tag encoding and filters')
                db.execute("UPDATE metadata SET value=? WHERE key='phase'",('answers' if phase=='questions' else 'complete',));db.execute("UPDATE metadata SET value='0' WHERE key='position'");db.commit()
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE')
            result=dict(questions=db.execute('SELECT count(*) FROM question').fetchone()[0],answers=db.execute('SELECT count(*) FROM answer').fetchone()[0])
            if not result['answers']:raise ValueError('Selection contains no answers; do not publish an empty corpus')
        publish(stage,output);return result


def export(selected,output,*,site,users=None):
    with _writer_lock(output):return _export(selected,output,site=site,users=users)


def _export(selected,output,*,site,users=None):
    """JSONL is a replayable input for the existing RAG importer, not a RAM corpus."""
    if site not in SITES:raise ValueError('Unsupported site')
    output=Path(output)
    if output.exists():raise FileExistsError(output)
    stage=output.with_name(output.name+'.part')
    with closing(sqlite3.connect(Path(selected).resolve().as_uri()+'?mode=ro',uri=True)) as db:
        if not db.execute('SELECT 1 FROM question LIMIT 1').fetchone() or not db.execute('SELECT 1 FROM answer LIMIT 1').fetchone():raise ValueError('Empty Stack Exchange selection cannot be exported')
        # Only selected author IDs are retained. Attribution names are resolved
        # against Users.xml with a disk table; deleted users retain dump names.
        with closing(sqlite3.connect(str(output)+'.authors.sqlite')) as authors,authors:
            authors.execute('CREATE TABLE IF NOT EXISTS author(id INTEGER PRIMARY KEY,name TEXT)')
            for table in ('question','answer'):
                for blob, in db.execute('SELECT data FROM '+table):
                    row=json.loads(zlib.decompress(blob));uid=row.get('OwnerUserId')
                    if uid:authors.execute('INSERT OR IGNORE INTO author VALUES(?,?)',(int(uid),row.get('OwnerDisplayName','')))
            authors.commit()
            if users:
                for user in rows(users,member='Users.xml'):authors.execute('UPDATE author SET name=? WHERE id=?',(user.get('DisplayName',''),int(user['Id'])))
                authors.commit()
            def attribution(row):
                uid=row.get('OwnerUserId');found=authors.execute('SELECT name FROM author WHERE id=?',(int(uid),)).fetchone() if uid else None
                return dict(name=(found[0] if found and found[0] else row.get('OwnerDisplayName') or 'deleted/unknown contributor'),url='https://'+site+'/users/'+uid if uid else None,license=row.get('ContentLicense','unspecified_in_dump'))
            count=0
            with stage.open('w',encoding='utf-8',newline='\n') as target:
                for qid,title,qblob,aid,score,accepted,ablob in db.execute('SELECT q.id,q.title,q.data,a.id,a.score,a.accepted,a.data FROM question q JOIN answer a ON a.question=q.id ORDER BY q.id,a.accepted DESC,a.score DESC,a.id'):
                    q,a=json.loads(zlib.decompress(qblob)),json.loads(zlib.decompress(ablob))
                    question,answer=markdown(q.get('Body','')),markdown(a.get('Body',''))
                    if len(answer.strip())<40:continue
                    source=f'https://{site}/a/{aid}'
                    text='## Frage\n'+question+'\n\n## '+('Akzeptierte Antwort' if accepted else 'Antwort')+'\n'+answer
                    target.write(json.dumps(dict(id=site+':'+str(aid),title=title,text=text,original_text=json.dumps(dict(question=q,answer=a),ensure_ascii=False),source=source,revision_source=source,
                        revision_id=a.get('LastEditDate') or a.get('CreationDate',''),timestamp=a.get('LastActivityDate',''),license=a.get('ContentLicense','unspecified_in_dump'),format='markdown',
                        sections=[dict(path=['Frage'],level=2,text=question),dict(path=['Akzeptierte Antwort' if accepted else 'Antwort'],level=2,text='Frage: '+title+'\n\n'+answer)],
                        post_metadata=dict(site=site,question_id=qid,answer_id=aid,accepted=bool(accepted),score=score,tags=tags(q.get('Tags','')),question_author=attribution(q),answer_author=attribution(a))),ensure_ascii=False)+'\n')
                    count+=1
    if not count:raise ValueError('No usable answers exported; empty corpus retained only as diagnostic partial')
    publish(stage,output);return dict(answer_documents=count,bytes=output.stat().st_size)
