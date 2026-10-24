"""Pinned HF FP32 vs the app runtime's native Q8 query encoder on real queries."""
import json
from pathlib import Path
import subprocess
import time
import numpy as np
from .common import atomic_json,digest


def run(model_path,gguf,cli,questions,output):
    from rag.embeddings import ModelEncoder,DEFAULT_MODEL
    revision='914f7f89142e33e77833254d9c9b90c3cef7303b'
    expected='2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135'
    if digest(gguf)!=expected:raise ValueError('Pinned Q8_0 model required')
    output=Path(output)
    if output.exists():raise FileExistsError(output)
    output.parent.mkdir(parents=True,exist_ok=True)
    rows=json.loads(Path(questions).read_text(encoding='utf-8'))
    rows=rows['pilot_questions'] if isinstance(rows,dict) else rows
    queries=[row['query'] for row in rows]
    if not 2<=len(queries)<=1000:raise ValueError('Use 2..1000 explicit test queries')
    import torch
    torch.set_num_threads(4)
    backend=ModelEncoder(DEFAULT_MODEL,'cpu',revision,model_path)
    started=time.monotonic()
    reference=np.asarray(backend.encode(queries,query=True),dtype=np.float64)
    hf_seconds=time.monotonic()-started
    prompt=output.with_suffix('.prompts.txt')
    # A separator cannot occur accidentally in a question. Newlines within a
    # query are retained, so the runtime receives exactly the same prompt.
    separator='<#bonsai-parity-separator#>'
    if any(separator in query for query in queries):raise ValueError('Reserved separator in query')
    prompt.write_text(separator.join('task: search result | query: '+q for q in queries),encoding='utf-8')
    raw=output.with_suffix('.native.json');log=output.with_suffix('.native.log')
    command=[str(Path(cli).resolve()),'-m',str(Path(gguf).resolve()),'-f',str(prompt.resolve()),'--embd-separator',separator,
             '--embd-output-format','array','--embd-normalize','-1','--pooling','mean','-ctk','f32','-ctv','f32','-fa','off',
             '-ngl','0','-t','4','-c','8192','-b','8192','-ub','8192']
    started=time.monotonic()
    with raw.open('wb') as out,log.open('wb') as err:subprocess.run(command,stdout=out,stderr=err,check=True)
    native_seconds=time.monotonic()-started
    native=np.asarray(json.loads(raw.read_text(encoding='utf-8')),dtype=np.float64)
    if reference.shape!=native.shape or reference.shape!=(len(queries),768):raise ValueError('Unexpected embedding shape')
    if not np.isfinite(native).all() or not np.isfinite(reference).all():raise ValueError('Nonfinite embedding')
    def unit(values):return values/np.linalg.norm(values,axis=1,keepdims=True)
    fp32,q8=unit(reference[:,:256]),unit(native[:,:256])
    cosine=np.einsum('ij,ij->i',fp32,q8)
    result=dict(scope='Windows native pinned PrismML backport CLI, CPU HF FP32; no Android/JNI/A55 measurement',
                model=DEFAULT_MODEL,revision=revision,gguf_sha256=expected,cli_sha256=digest(cli),
                questions_sha256=digest(questions),count=len(queries),hf_seconds=hf_seconds,native_seconds=native_seconds,
                minimum_cosine_256d=float(cosine.min()),mean_cosine_256d=float(cosine.mean()),maximum_absolute_error_256d=float(np.abs(fp32-q8).max()),
                per_query=[dict(query=q,cosine_256d=float(c)) for q,c in zip(queries,cosine)],command=command)
    np.savez(output.with_suffix('.vectors.npz'),fp32=fp32,q8=q8)
    atomic_json(output,result)
    return result
