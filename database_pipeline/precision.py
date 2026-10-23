"""Compare an inference candidate with retained true FP32 pilot embeddings."""
from contextlib import closing,ExitStack
import json
from pathlib import Path
import sqlite3
import time
import numpy as np
from threadpoolctl import threadpool_limits
from rag.ivf import float_source,IvfIndex
from rag.evaluate_ivf import float_ranks
from rag.search import fts_query,fuse
from .common import atomic_json,digest


def compare(baseline,candidate,database,queries,questions,output,ivf=None):
    if Path(output).exists():raise FileExistsError(output)
    started=time.monotonic();squares=0.;components=0;minimum=1.;cos_sum=0.;max_error=0.
    with ExitStack() as cleanup,threadpool_limits(limits=2):
        a,b=float_source(baseline),float_source(candidate)
        for source in (a,b):
            cleanup.callback(source.close if hasattr(source,'close') else source._mmap.close)
        if len(a)!=len(b) or not len(a):raise ValueError('Nonempty, identical row sets required')
        for start in range(0,len(a),8192):
            end=min(start+8192,len(a))
            if not np.array_equal(a['id'][start:end],b['id'][start:end]):raise ValueError('Source vector ID order differs')
            x,y=np.asarray(a['vector'][start:end,:256],dtype=np.float64),np.asarray(b['vector'][start:end,:256],dtype=np.float64)
            x=x/np.linalg.norm(x,axis=1,keepdims=True);y=y/np.linalg.norm(y,axis=1,keepdims=True)
            cosine=np.clip(np.einsum('ij,ij->i',x,y),-1.,1.);difference=x-y
            minimum=min(minimum,float(cosine.min()));cos_sum+=float(cosine.sum());max_error=max(max_error,float(np.abs(difference).max()))
            squares+=float(np.square(difference).sum());components+=difference.size
        with np.load(queries) as query_file:q=query_file['q8']
        rows=json.loads(Path(questions).read_text(encoding='utf-8'));rows=rows['pilot_questions'] if isinstance(rows,dict) else rows
        if len(q)!=len(rows):raise ValueError('Query/label count mismatch')
        db=cleanup.enter_context(closing(sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)))
        articles=dict(db.execute('SELECT id,article_id FROM chunks'))
        index=cleanup.enter_context(IvfIndex(ivf)) if ivf else None
        details=[]
        def score(ids,expected):
            ranks=[i+1 for i,id in enumerate(ids[:10]) if articles[id] in expected]
            return dict(recall_at10=bool(ranks),mrr_at10=1/min(ranks) if ranks else 0.)
        for query,question in zip(q,rows):
            expected=set(question['article_ids']);base=float_ranks(a,query,top_k=20);other=float_ranks(b,query,top_k=20)
            result=dict(query=question['query'],baseline=score(base,expected),candidate=score(other,expected),neighbor_overlap_at10=len(set(base[:10])&set(other[:10]))/10)
            if index:
                semantic=index.search(query,nprobe=16,top_k=20)['ids'];match=fts_query(question['query'])
                lexical=[r[0] for r in db.execute('SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts),rowid LIMIT 20',(match,))] if match else []
                result['candidate_ivf']=score(semantic,expected);result['candidate_hybrid']=score(fuse([lexical,semantic],limit=10),expected)
            details.append(result)
        metrics={key:{metric:sum(float(row[key][metric]) for row in details)/len(details) for metric in ('recall_at10','mrr_at10')} for key in ('baseline','candidate')+ (('candidate_ivf','candidate_hybrid') if index else ())}
        result=dict(scope='Real retained FP32 pilot vs candidate, native Q8 queries; host-only evaluation, no A55 claim',vectors=len(a),queries=len(rows),minimum_cosine_256d=minimum,mean_cosine_256d=cos_sum/len(a),maximum_absolute_error_256d=max_error,rmse_256d=(squares/components)**.5,mean_neighbor_overlap_at10=sum(row['neighbor_overlap_at10'] for row in details)/len(details),retrieval=metrics,candidate_identity=getattr(b,'identity',None),baseline_sha256=getattr(a,'reference_sha256',None) or digest(baseline),candidate_sha256=getattr(b,'reference_sha256',None) or digest(candidate),query_vectors_sha256=digest(queries),per_query=details,seconds=time.monotonic()-started)
        atomic_json(output,result)
        return result
