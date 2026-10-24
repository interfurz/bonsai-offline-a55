"""Evaluate the deployed FTS4/native-Q8 query path, separately from FTS5."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import time
import numpy as np
from rag.fts4 import search as lexical,PROFILE
from rag.ivf import IvfIndex
from .common import atomic_json,digest


def run(database,index_path,questions,queries,output,nprobes=(16,32,64,128)):
    if not nprobes or any(type(n) is not int or not 1<=n<=256 for n in nprobes):raise ValueError('Use nprobe integers in 1..256')
    if Path(output).exists():raise FileExistsError(output)
    labels=json.loads(Path(questions).read_text(encoding='utf-8'))['pilot_questions']
    with np.load(queries) as data:vectors=data['q8']
    if len(labels)!=len(vectors):raise ValueError('Query/label count differs')
    report=dict(scope='Windows host, portable FTS4 + native Q8 query embeddings + INT8 IVF; human GermanQuAD title-matched relevance, not representative of full Wikipedia and not A55 latency',questions=len(labels),lexical_profile=PROFILE,database_sha256=digest(database),index_sha256=digest(index_path),queries_sha256=digest(queries),details=[],metrics={})
    started=time.monotonic()
    with closing(sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)) as db,IvfIndex(index_path) as index:
        def score(ids,expected):
            first=next((i+1 for i,id in enumerate(ids[:10]) if db.execute('SELECT article_id FROM chunks WHERE id=?',(id,)).fetchone()[0] in expected),999)
            return dict(recall3=first<=3,recall5=first<=5,recall10=first<=10,mrr10=1/first if first<=10 else 0.)
        for row,query in zip(labels,vectors):
            tick=time.perf_counter();ids,stats=lexical(db,row['query']);ms=(time.perf_counter()-tick)*1000
            quality={'fts4':score(ids,set(row['article_ids']))};timings={}
            for probes in nprobes:
                if probes>index.clusters:continue
                tick=time.perf_counter();semantic=index.search(query,nprobe=probes,top_k=20,positive_only=True);timings[str(probes)]=(time.perf_counter()-tick)*1000
                dots=dict(zip(semantic['ids'],semantic['dots']));ranking={}
                # Match installed single-package global fusion, including cosine
                # and compound-string identity ties (not the host FTS5 shortcut).
                semantic_ids=sorted(dots,key=lambda id:(-dots[id],'dewiki-de:'+str(id)))
                for sequence in (ids,semantic_ids):
                    for rank,id in enumerate(sequence):ranking[id]=ranking.get(id,0.)+1/(61+rank)
                hybrid=sorted(ranking,key=lambda id:(-ranking[id],-dots.get(id,float('-inf')),'dewiki-de:'+str(id)))[:20]
                quality[f'ivf{probes}']=score(semantic['ids'],set(row['article_ids']))
                quality[f'hybrid{probes}']=score(hybrid,set(row['article_ids']))
            report['details'].append(dict(query=row['query'],quality=quality,lexical=stats,fts4_ms=ms,ivf_ms=timings))
            if len(report['details'])%50==0:print(json.dumps(dict(evaluated=len(report['details']),total=len(labels),seconds=time.monotonic()-started)),flush=True)
    for name in report['details'][0]['quality']:
        report['metrics'][name]={metric:sum(row['quality'][name][metric] for row in report['details'])/len(labels) for metric in ('recall3','recall5','recall10','mrr10')}
    report['mean_fts4_ms']=sum(row['fts4_ms'] for row in report['details'])/len(labels)
    report['fts4_candidate_cap_hits']=sum(row['lexical']['possibly_truncated'] for row in report['details'])
    report['seconds']=time.monotonic()-started;atomic_json(output,report)
    return {key:report[key] for key in ('questions','metrics','mean_fts4_ms','fts4_candidate_cap_hits','seconds')}
