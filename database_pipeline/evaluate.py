"""Real-question pilot evaluation; article relevance and ANN recall kept separate."""
from contextlib import closing
import heapq
import json
from pathlib import Path
import sqlite3
import time
import numpy as np

from rag.embeddings import ModelEncoder,DEFAULT_MODEL
from rag.ivf import float_source,normalized,IvfIndex
from rag.search import fts_query,fuse
from .common import atomic_json,digest


def exact(source,query,dimension,limit=20):
    q=np.asarray(query[:dimension],dtype=np.float32);q/=max(float(np.linalg.norm(q)),1e-30)
    heap=[]
    for start in range(0,len(source),4096):
        block=np.asarray(source['vector'][start:start+4096,:dimension]);norm=np.linalg.norm(block,axis=1)
        scores=(block@q)/np.where(norm==0,1,norm)
        positions=np.argsort(-scores,kind='stable')[:limit]
        for offset in positions:
            item=(float(scores[offset]),-int(source['id'][start+offset]))
            if len(heap)<limit:heapq.heappush(heap,item)
            elif item>heap[0]:heapq.heapreplace(heap,item)
    return [-identity for _,identity in sorted(heap,reverse=True)]


def run(database,reference,questions,model_path,revision,indexes,output,device='cpu'):
    # Query encoding is performed once; index tuning never reloads the model.
    questions=json.loads(Path(questions).read_text(encoding='utf-8'))['pilot_questions']
    valid=[q for q in questions if len(q['query'])<=256]
    backend=ModelEncoder(DEFAULT_MODEL,device,revision,model_path)
    query_vectors=backend.encode([q['query'] for q in valid],query=True)
    source=float_source(reference);mapped={name:IvfIndex(path) for name,path in indexes.items()}
    report=dict(question_source='deepset GermanQuAD human-authored test questions; matched by exact Wikipedia title',
                questions=len(valid),excluded_over_query_limit=len(questions)-len(valid),
                topics=sorted({q['title'] for q in valid}),database_sha256=digest(database),reference_sha256=source.reference_sha256 if hasattr(source,'reference_sha256') else digest(reference),
                scope='title-matched article relevance subset; not representative of full Wikipedia; historical QA answer freshness is not established',queries=[],metrics={},ann={})
    started=time.monotonic()
    try:
        with closing(sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)) as db:
            def articles(ids):
                return [db.execute('SELECT article_id FROM chunks WHERE id=?',(int(i),)).fetchone()[0] for i in ids]
            for question,vector in zip(valid,query_vectors):
                lexical=[r[0] for r in db.execute('SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts),rowid LIMIT 20',(fts_query(question['query']),))]
                full=exact(source,vector,768);reduced=exact(source,vector,256)
                rankings={'fts':lexical,'fp32_768':full,'fp32_256':reduced,'hybrid_exact256':fuse([lexical,reduced],20)}
                ann={}
                for name,index in mapped.items():
                    exhaustive=index.search(vector,full_scan=True,top_k=20)['ids']
                    rankings[name+'_int8_full']=exhaustive
                    for nprobe in [1,2,4,8,16,32,64]:
                        if nprobe>index.clusters:continue
                        tick=time.perf_counter();result=index.search(vector,nprobe=nprobe,top_k=20)
                        key=name+'_nprobe'+str(nprobe)
                        ann[key]=dict(recall10_vs_fp32_256=len(set(result['ids'][:10])&set(reduced[:10]))/10,
                                      recall10_vs_int8_full=len(set(result['ids'][:10])&set(exhaustive[:10]))/10,
                                      scanned=result['scanned'],milliseconds=(time.perf_counter()-tick)*1000)
                        if nprobe==16:rankings[name+'_hybrid_nprobe16']=fuse([lexical,result['ids']],20)
                targets=set(question['article_ids']);quality={}
                for name,ids in rankings.items():
                    sequence=articles(ids);ranks=[i+1 for i,value in enumerate(sequence) if value in targets]
                    first=min(ranks,default=0)
                    quality[name]=dict(recall5=float(any(value in targets for value in sequence[:5])),recall10=float(any(value in targets for value in sequence[:10])),mrr10=1/first if 0<first<=10 else 0)
                report['queries'].append(dict(query=question['query'],title=question['title'],benchmark_id=question['benchmark_id'],quality=quality,ann=ann))
            if valid:
                for name in report['queries'][0]['quality']:
                    report['metrics'][name]={metric:float(np.mean([q['quality'][name][metric] for q in report['queries']])) for metric in ('recall5','recall10','mrr10')}
                for name in report['queries'][0]['ann']:
                    report['ann'][name]={metric:float(np.mean([q['ann'][name][metric] for q in report['queries']])) for metric in ('recall10_vs_fp32_256','recall10_vs_int8_full','scanned','milliseconds')}
        report['retrieval_seconds_excluding_query_encoding']=time.monotonic()-started
        atomic_json(output,report);return report
    finally:
        if hasattr(source,'close'):source.close()
        if hasattr(source,'_mmap'):source._mmap.close()
        for index in mapped.values():index.close()
