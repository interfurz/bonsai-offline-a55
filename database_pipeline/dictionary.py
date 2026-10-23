"""German Wiktionary: language/POS entries, sense markers and indexed word forms.

No remote template expansion. Unsupported markup remains in the compressed page
and a per-entry template inventory; extraction coverage is reported explicitly.
"""
from collections import Counter
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata
import zlib
import mwparserfromhell as mw

from rag.chunking import clean as wiki_clean
from rag.corpus import _fingerprint, _writer_lock
from rag.wiki import records
from .common import publish

SCHEMA = """
PRAGMA user_version=1;
PRAGMA application_id=1111770196;
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE page(id TEXT PRIMARY KEY,lemma TEXT NOT NULL,source TEXT NOT NULL,revision TEXT NOT NULL,timestamp TEXT NOT NULL,raw_zlib BLOB NOT NULL);
CREATE TABLE entry(id INTEGER PRIMARY KEY,page_id TEXT NOT NULL,lemma TEXT NOT NULL,norm TEXT NOT NULL,language TEXT NOT NULL,pos TEXT NOT NULL,data_json TEXT NOT NULL);
CREATE INDEX entry_lemma ON entry(norm,language,pos);
CREATE INDEX entry_page ON entry(page_id);
CREATE TABLE detail(entry_id INTEGER NOT NULL,kind TEXT NOT NULL,ordinal INTEGER NOT NULL,sense TEXT,value TEXT NOT NULL,PRIMARY KEY(entry_id,kind,ordinal)) WITHOUT ROWID;
CREATE TABLE form(norm TEXT NOT NULL,entry_id INTEGER NOT NULL,value TEXT NOT NULL,feature TEXT NOT NULL,PRIMARY KEY(norm,entry_id,feature)) WITHOUT ROWID;
CREATE TABLE ingest_errors(position INTEGER PRIMARY KEY,article_id TEXT,title TEXT,error TEXT NOT NULL);
CREATE TABLE lexical_alias(norm TEXT NOT NULL,lemma TEXT NOT NULL,language TEXT NOT NULL,target_norm TEXT NOT NULL,target_lemma TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(norm,language,target_norm,kind)) WITHOUT ROWID;
CREATE TABLE unparsed(norm TEXT NOT NULL,page_id TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,PRIMARY KEY(norm,page_id)) WITHOUT ROWID;
"""
MARKERS = {"bedeutungen":"definitions", "grammatische merkmale":"grammar_notes",
           "synonyme":"synonyms", "gegenwörter":"antonyms", "herkunft":"etymology",
           "beispiele":"examples", "sinnverwandte wörter":"related", "oberbegriffe":"hypernyms",
           "unterbegriffe":"hyponyms", "worttrennung":"syllables", "aussprache":"pronunciation"}


def normalize(value):
    return " ".join(unicodedata.normalize("NFKC",value).lower().split())


def clean(text):
    code=mw.parse(text)
    for template in reversed(code.filter_templates(recursive=True)):
        name=str(template.name).strip().casefold()
        replacement=None
        if name in {'ü','üt','üxx4','üt?','ü?'} and template.has('2'):replacement=str(template.get('2').value)
        elif name in {'lautschrift','ipa'} and template.has('1'):replacement='['+str(template.get('1').value)+']'
        elif name=='k':replacement='('+', '.join(str(p.value).strip() for p in template.params if not p.showkey)+') '
        if replacement is not None:code.replace(template,replacement)
    return wiki_clean(str(code))


def entries(text):
    code=mw.parse(text); language=None; parts=[]; current=[]
    # Top-level heading nodes avoid headings inside template parameters.
    for node in code.nodes:
        if isinstance(node,mw.nodes.Heading) and node.level==2:
            if current and language:parts.append((language,mw.wikicode.Wikicode(current)))
            current=[]
            langs=[t for t in node.title.filter_templates() if str(t.name).strip().casefold()=="sprache" and t.has("1")]
            language=str(langs[0].get("1").value).strip() if langs else None
        else:current.append(node)
    if current and language:parts.append((language,mw.wikicode.Wikicode(current)))
    output=[]
    for language,part in parts:
        groups=[]; pos=""; body=[]
        for node in part.nodes:
            if isinstance(node,mw.nodes.Heading) and node.level==3:
                if body and pos:groups.append((pos,mw.wikicode.Wikicode(body)))
                body=[]
                words=[t for t in node.title.filter_templates() if str(t.name).strip().casefold()=="wortart" and t.has("1")]
                pos="; ".join(str(t.get("1").value).strip() for t in words)
            else:body.append(node)
        if body and pos:groups.append((pos,mw.wikicode.Wikicode(body)))
        if not groups and ('{{Bedeutungen}}' in str(part) or language=='Umschrift'):
            groups=[('Umschrift' if language=='Umschrift' else '',part)]
        for pos,body in groups:
            data=dict(definitions=[],synonyms=[],antonyms=[],translations=[],etymology=[],grammar={},forms=[],unexpanded_templates={})
            inventory=Counter()
            translation_senses={}
            for table in body.filter_templates(recursive=True):
                if str(table.name).strip().casefold()=='ü-tabelle':
                    sense=str(table.get('1').value).strip() if table.has('1') else None
                    for child in table.params:
                        for nested in child.value.filter_templates(recursive=True):translation_senses[id(nested)]=sense
            for template in body.filter_templates(recursive=True):
                name=str(template.name).strip(); lower=name.casefold();inventory[lower]+=1
                if lower.endswith("übersicht"):
                    grammar={str(p.name).strip():clean(str(p.value)) for p in template.params if p.showkey}
                    data["grammar"][name]=grammar
                    for feature,value in grammar.items():
                        if any(term in feature.casefold() for term in ("nominativ","genitiv","dativ","akkusativ","singular","plural","präsens","präteritum","partizip","imperativ","positiv","komparativ","superlativ")):
                            for form in re.split(r"<br\s*/?>|\n|,",value):
                                form=form.strip()
                                if form and form not in ("—","-","?","…"):
                                    data["forms"].append(dict(value=form,feature=feature))
                elif lower in {"ü","üt","üxx4","üt?","ü?"} and template.has("1") and template.has("2"):
                    translated=clean(str(template.get("2").value))
                    if not translated:continue
                    data["translations"].append(dict(language=str(template.get("1").value).strip(),value=translated,template=name,sense=translation_senses.get(id(template)),
                                                     transliteration=str(template.get("3").value).strip() if template.has("3") else None))
            data["unexpanded_templates"]=dict(inventory)
            marker=None
            for line in str(body).splitlines():
                match=re.fullmatch(r"\s*\{\{([^{}|]+)\}\}\s*",line)
                if match:
                    marker=MARKERS.get(match[1].strip().casefold());continue
                if line.startswith("==="):
                    marker=None
                if marker and line.strip():
                    value=clean(line).strip()
                    if value:
                        sense=re.match(r"\[([^]]+)\]\s*(.*)",value)
                        data.setdefault(marker,[]).append(dict(sense=sense[1] if sense else None,text=sense[2] if sense else value))
            output.append(dict(language=language,pos=pos,data=data))
    return output


def aliases(text):
    """Explicit links only; never infer spelling equivalence from edit distance."""
    if not re.search(r'schreibweise|hnlichkeiten\s+Umschrift',text,re.I):return []
    language='';output=[]
    for node in mw.parse(text).nodes:
        if isinstance(node,mw.nodes.Heading) and node.level==2:
            langs=[t for t in node.title.filter_templates() if str(t.name).strip().casefold()=='sprache' and t.has('1')]
            language=str(langs[0].get('1').value).strip() if langs else ''
        if not hasattr(node,'name'):continue
        name=str(node.name).strip().casefold()
        if name in ('alte schreibweise','schweizer und liechtensteiner schreibweise','afghanische schreibweise'):
            targets=[str(node.get('1').value)] if node.has('1') else []
        elif name=='ähnlichkeiten umschrift':
            targets=[str(param.value) for param in node.params if str(param.name).strip().isdigit()]
        else:continue
        for target in targets:
            target=clean(target).strip().split('#',1)[0].strip()
            if target:output.append(dict(language=language,target=target,kind=name))
    return output


def store_entries(db,page_id,lemma,data):
    for ordinal,item in enumerate(data):
        key=int.from_bytes(hashlib.sha256(json.dumps([page_id,item['language'],item['pos'],ordinal],ensure_ascii=False).encode()).digest()[:8],'big')&((1<<63)-1) or 1
        db.execute('INSERT INTO entry VALUES(?,?,?,?,?,?,?)',(key,page_id,lemma,normalize(lemma),item['language'],item['pos'],json.dumps(item['data'],ensure_ascii=False)))
        for kind,values in item['data'].items():
            if isinstance(values,list):
                for n,value in enumerate(values):db.execute('INSERT INTO detail VALUES(?,?,?,?,?)',(key,kind,n,value.get('sense'),value.get('text') or json.dumps(value,ensure_ascii=False)))
        db.execute('INSERT INTO detail VALUES(?,?,?,?,?)',(key,'grammar',0,None,json.dumps(item['data']['grammar'],ensure_ascii=False)))
        for form in item['data']['forms']:db.execute('INSERT OR IGNORE INTO form VALUES(?,?,?,?)',(normalize(form['value']),key,form['value'],form['feature']))


def store_aliases(db,page_id,lemma,text,source,parsed):
    values=aliases(text)
    for value in values:
        db.execute('INSERT OR IGNORE INTO lexical_alias VALUES(?,?,?,?,?,?,?)',(normalize(lemma),lemma,value['language'],normalize(value['target']),value['target'],value['kind'],source))
    if not parsed:db.execute('INSERT OR REPLACE INTO unparsed VALUES(?,?,?,?)',(normalize(lemma),page_id,'variant_or_transliteration' if values else 'unstructured_source',clean(text)[:4500]))
    return len(values)


def ingest(source,output,*,date,resume=False,limit=None,commit_every=500):
    __import__('datetime').date.fromisoformat(date)
    output=Path(output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
    stage=output.with_name(output.name+'.building')
    identity=json.dumps(dict(source=_fingerprint(source),date=date,pipeline='dewiktionary-v3-aliases'),sort_keys=True)
    with _writer_lock(output):
        if output.exists():raise FileExistsError(output)
        if stage.exists()!=resume:raise ValueError('Use --resume with existing staging generation, or a new output')
        with closing(sqlite3.connect(stage)) as db,db:
            if not resume:
                db.executescript(SCHEMA)
                db.executemany('INSERT INTO metadata VALUES(?,?)',[('identity',identity),('dump_date',date),('complete','false'),('checkpoint','0'),('coverage','all_main_namespace_entries; unexpanded_templates_retained'),('license','CC-BY-SA-4.0')]);db.commit()
            meta=dict(db.execute('SELECT * FROM metadata'))
            if meta['identity']!=identity:raise ValueError('Resume source/settings changed')
            db.execute('PRAGMA cache_size=-16384');db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL')
            position=int(meta['checkpoint']);processed=0
            for position,row in records(source,position,wiki='dewiktionary'):
                if row['kind']=='error':raise ValueError(row['error'])
                if row['kind']=='article':
                    data=entries(row['text'])
                    db.execute('INSERT INTO page VALUES(?,?,?,?,?,?)',(row['id'],row['title'],row['revision_source'],row['revision_id'],row['timestamp'],zlib.compress(row['text'].encode('utf-8'))))
                    store_entries(db,row['id'],row['title'],data)
                    store_aliases(db,row['id'],row['title'],row['text'],row['revision_source'],bool(data))
                    processed+=1
                elif row['kind']=='redirect':
                    db.execute('INSERT INTO page VALUES(?,?,?,?,?,?)',(row['id'],row['title'],row['revision_source'],row['revision_id'],row['timestamp'],zlib.compress(row['text'].encode('utf-8'))))
                    target=row['redirect_target'].split('#',1)[0]
                    db.execute('INSERT OR IGNORE INTO lexical_alias VALUES(?,?,?,?,?,?,?)',(normalize(row['title']),row['title'],'',normalize(target),target,'redirect',row['revision_source']))
                    db.execute('INSERT OR REPLACE INTO unparsed VALUES(?,?,?,?)',(normalize(row['title']),row['id'],'redirect','Weiterleitung zu '+target))
                if position%commit_every==0 or (limit and processed>=limit):
                    db.execute("UPDATE metadata SET value=? WHERE key='checkpoint'",(str(position),));db.commit()
                    print(json.dumps(dict(position=position,pages=processed,bytes=stage.stat().st_size)),flush=True)
                if limit and processed>=limit:break
            else:
                db.execute("UPDATE metadata SET value='true' WHERE key='complete'")
            db.execute("UPDATE metadata SET value=? WHERE key='checkpoint'",(str(position),));db.commit()
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE')
            if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)]:raise ValueError('Dictionary integrity failure')
            complete=dict(db.execute('SELECT * FROM metadata'))['complete']=='true'
            counts=dict(pages=db.execute('SELECT count(*) FROM page').fetchone()[0],entries=db.execute('SELECT count(*) FROM entry').fetchone()[0],forms=db.execute('SELECT count(*) FROM form').fetchone()[0],complete=complete)
        if complete:publish(stage,output)
        return counts


def lookup(database,word,language=None):
    with closing(sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)) as db:
        has_alias=db.execute("SELECT 1 FROM sqlite_master WHERE name='lexical_alias'").fetchone()
        prefix="WITH RECURSIVE matched(norm,depth) AS (VALUES(?,0) UNION SELECT a.target_norm,m.depth+1 FROM lexical_alias a JOIN matched m ON a.norm=m.norm WHERE m.depth<4 AND (?='' OR a.language=? OR a.language='') LIMIT 256) " if has_alias else ''
        sql=prefix+'SELECT id,lemma,language,pos,data_json FROM entry WHERE (norm IN (SELECT norm FROM matched) OR id IN (SELECT entry_id FROM form WHERE norm IN (SELECT norm FROM matched)))' if has_alias else 'SELECT id,lemma,language,pos,data_json FROM entry WHERE (norm=? OR id IN (SELECT entry_id FROM form WHERE norm=?))'
        args=[normalize(word),language or '',language or ''] if has_alias else [normalize(word),normalize(word)]
        if language:sql+=' AND language=?';args.append(language)
        rows=db.execute(sql+' ORDER BY lemma,language,pos,id LIMIT 20',args)
        return [dict(id=r[0],lemma=r[1],language=r[2],pos=r[3],data=json.loads(r[4])) for r in rows]
