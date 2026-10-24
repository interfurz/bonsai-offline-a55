import argparse
import json
import os
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description='Bonsai offline database pipeline; run from repository root')
    p.add_argument('--data-root',default=os.environ.get('BONSAI_DATA_ROOT',str(Path(__file__).resolve().parents[2]/'data')))
    sub=p.add_subparsers(dest='command',required=True)
    sub.add_parser('preflight');sub.add_parser('status')
    h=sub.add_parser('hash');h.add_argument('file')
    t=sub.add_parser('precision-eval');t.add_argument('baseline');t.add_argument('candidate');t.add_argument('database');t.add_argument('queries');t.add_argument('questions');t.add_argument('output');t.add_argument('--ivf')
    t=sub.add_parser('portable-eval');t.add_argument('database');t.add_argument('ivf');t.add_argument('questions');t.add_argument('queries');t.add_argument('output');t.add_argument('--nprobes',type=int,nargs='+',default=[16,32,64,128])
    q=sub.add_parser('parity');q.add_argument('--model-path',required=True);q.add_argument('--gguf',required=True);q.add_argument('--cli',required=True);q.add_argument('--questions',required=True);q.add_argument('--output',required=True)
    s=sub.add_parser('stats');s.add_argument('database');s.add_argument('--report')
    w=sub.add_parser('wikidata-download');w.add_argument('snapshot')
    w=sub.add_parser('wikidata-profile');w.add_argument('output');w.add_argument('--date',required=True)
    w=sub.add_parser('wikidata-import');w.add_argument('source');w.add_argument('output');w.add_argument('--config',required=True);w.add_argument('--receipt',required=True);w.add_argument('--date',required=True);w.add_argument('--max-bytes',type=int,default=7000000000);w.add_argument('--resume',action='store_true')
    w.add_argument('--bzip2-threads',type=int,default=1)
    d=sub.add_parser('download');d.add_argument('wiki',choices=['dewiki','dewikibooks','dewiktionary']);d.add_argument('snapshot');d.add_argument('--file')
    d=sub.add_parser('download-cleanup');d.add_argument('wiki',choices=['dewiki','dewikibooks','dewiktionary']);d.add_argument('snapshot')
    m=sub.add_parser('model');m.add_argument('--revision',default='914f7f89142e33e77833254d9c9b90c3cef7303b')
    # Add range options to the actual download parser, not the cleanup parser.
    sub.choices['download'].add_argument('--segments',type=int,default=0,help='Independent resumable HTTP ranges; 1..8 workers')
    for command in ('import','resume'):
        i=sub.add_parser(command);i.add_argument('source');i.add_argument('database');i.add_argument('--wiki',choices=['dewiki','dewikibooks','stackexchange'],default='dewiki');i.add_argument('--limit',type=int);i.add_argument('--workers',type=int,default=4);i.add_argument('--tokenizer',required=True);i.add_argument('--dump-sha256',required=True)
        i.add_argument('--reserve-gib',type=int,default=20)
    e=sub.add_parser('embed');e.add_argument('database');e.add_argument('directory');e.add_argument('--model-path',required=True);e.add_argument('--revision',default='914f7f89142e33e77833254d9c9b90c3cef7303b');e.add_argument('--batch',type=int,default=2);e.add_argument('--shard-rows',type=int,default=8192);e.add_argument('--device',default='cuda')
    e.add_argument('--compute-dtype',choices=['float32','bfloat16'],default='float32')
    e.add_argument('--reserve-gib',type=int,default=20)
    a=sub.add_parser('assemble');a.add_argument('directory');a.add_argument('output')
    i=sub.add_parser('index');i.add_argument('reference');i.add_argument('output');i.add_argument('--clusters',type=int,default=4096);i.add_argument('--train-size',type=int,default=262144);i.add_argument('--backend',choices=['auto','faiss','sklearn'],default='auto');i.add_argument('--batch',type=int,default=8192)
    v=sub.add_parser('validate');v.add_argument('database');v.add_argument('--ivf');v.add_argument('--vectors');v.add_argument('--report')
    b=sub.add_parser('package');b.add_argument('database');b.add_argument('output');b.add_argument('--id',required=True);b.add_argument('--version',required=True);b.add_argument('--kind',choices=['rag','wikidata','dictionary','tables'],required=True);b.add_argument('--source-json',required=True);b.add_argument('--ivf');b.add_argument('--vectors');b.add_argument('--embedding-json')
    b.add_argument('--nprobe',type=int,default=16)
    for command in ('dictionary','nutrition'):
        s=sub.add_parser(command);s.add_argument('source');s.add_argument('output');s.add_argument('--date',required=True);s.add_argument('--resume',action='store_true')
    c=sub.add_parser('constants');c.add_argument('source');c.add_argument('output');c.add_argument('--date',required=True)
    c=sub.add_parser('repair-noncontent');c.add_argument('source');c.add_argument('database');c.add_argument('output');c.add_argument('--wiki',default='dewikibooks')
    c=sub.add_parser('stack-tags-upgrade');c.add_argument('database');c.add_argument('output');c.add_argument('--resume',action='store_true')
    c=sub.add_parser('nutrition-upgrade');c.add_argument('source');c.add_argument('output')
    c=sub.add_parser('dictionary-upgrade');c.add_argument('dump');c.add_argument('source');c.add_argument('output');c.add_argument('--dump-sha256',required=True);c.add_argument('--resume',action='store_true')
    s=sub.add_parser('stack-download');s.add_argument('site');s.add_argument('--component',choices=['posts','users'],default='posts')
    s=sub.add_parser('stack-extract');s.add_argument('archive');s.add_argument('output')
    s=sub.add_parser('stack-select');s.add_argument('posts');s.add_argument('output');s.add_argument('--site',required=True);s.add_argument('--max-questions',type=int,default=50000);s.add_argument('--min-score',type=int,default=5);s.add_argument('--resume',action='store_true')
    s=sub.add_parser('stack-export');s.add_argument('selected');s.add_argument('output');s.add_argument('--site',required=True);s.add_argument('--users')
    args=p.parse_args();root=Path(args.data_root).resolve()
    if args.command=='preflight':
        from .preflight import preflight
        result=preflight(root)
    elif args.command=='status':
        import shutil,sqlite3
        from contextlib import closing
        generations=[]
        for path in (root/'staging').glob('*.sqlite*'):
            if not str(path).endswith(('.sqlite','.sqlite.building')):continue
            try:
                with closing(sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True,timeout=.2)) as db:
                    metadata=dict(db.execute('SELECT key,value FROM metadata'))
                generations.append(dict(file=str(path),bytes=path.stat().st_size,metadata={key:metadata[key] for key in ('ingest_status','ingest_checkpoint','complete','checkpoint','phase','position','state','upgrade_state','dump_date','coverage','normalization_version','dictionary_features') if key in metadata}))
            except sqlite3.Error as error:generations.append(dict(file=str(path),status='not_yet_readable',error=str(error)))
        exports=[]
        for file in (root/'embeddings').glob('*/export.json'):
            state=json.loads(file.read_text(encoding='utf-8'));confirmed=sum(shard['count'] for shard in state['shards'])
            exports.append(dict(checkpoint=str(file),identity=state['identity'],complete=state['complete'],committed_shards=len(state['shards']),committed_vectors=confirmed,remaining_vectors=state['identity']['count']-confirmed,last_shard_seconds=state['shards'][-1]['seconds'] if state['shards'] else None))
        result=dict(data_root=str(root),disk_free_bytes=shutil.disk_usage(root).free,
                    generations=generations,
                    packages=[str(x) for x in (root/'packages').glob('*.bonsai')],
                    embedding_exports=exports,
                    downloads=[dict(file=x.name,bytes=x.stat().st_size,partial=x.name.endswith('.part')) for x in (root/'downloads').glob('*') if x.is_file() and not x.name.endswith('.lock')])
    elif args.command=='hash':
        from .common import digest
        path=Path(args.file).resolve();result=dict(file=str(path),bytes=path.stat().st_size,sha256=digest(path))
    elif args.command=='precision-eval':
        from .precision import compare
        result=compare(args.baseline,args.candidate,args.database,args.queries,args.questions,args.output,ivf=args.ivf)
    elif args.command=='portable-eval':
        from .portable_eval import run
        result=run(args.database,args.ivf,args.questions,args.queries,args.output,nprobes=args.nprobes)
    elif args.command=='parity':
        from .parity import run
        result=run(args.model_path,args.gguf,args.cli,args.questions,args.output)
    elif args.command=='stats':
        from .validate import storage_stats
        result=storage_stats(args.database)
        if args.report:
            from .common import atomic_json
            atomic_json(args.report,result)
    elif args.command=='wikidata-profile':
        from .wikidata_profile import prepare
        result=prepare(root,args.output,args.date)
    elif args.command=='wikidata-import':
        from .common import digest,require_space
        from rag.corpus import _writer_lock
        from wikidata.pipeline import build
        receipt=json.loads(Path(args.receipt).read_text(encoding='utf-8-sig'))
        if not receipt.get('upstream_verified') or Path(args.source).stat().st_size!=receipt['bytes'] or digest(args.source)!=receipt['sha256']:raise ValueError('Official whole-file verification required before Wikidata import')
        if not receipt['url'].startswith('https://dumps.wikimedia.org/wikidatawiki/entities/'+args.date.replace('-','')+'/'):raise ValueError('Wikidata snapshot date/receipt mismatch')
        Path(args.output).parent.mkdir(parents=True,exist_ok=True);require_space(Path(args.output).parent,args.max_bytes*3)
        with _writer_lock(args.output):result=build(args.source,args.output,dump_date=args.date,config=json.loads(Path(args.config).read_text(encoding='utf-8-sig')),resume=args.resume,max_bytes=args.max_bytes,bzip2_threads=args.bzip2_threads,progress=lambda state:print(json.dumps(state),flush=True))
    elif args.command=='wikidata-download':
        from .download import wikidata
        result=wikidata(root,args.snapshot)
    elif args.command=='download-cleanup':
        from .cleanup import completed_ranges
        result=completed_ranges(root,args.wiki,args.snapshot)
    elif args.command=='download':
        from .download import download,parallel_wikimedia
        result=parallel_wikimedia(root,args.wiki,args.snapshot,args.segments) if args.segments else download(root,args.wiki,args.snapshot,args.file)
    elif args.command=='model':
        from .model import fetch
        result=fetch(root,args.revision)
    elif args.command in ('import','resume'):
        from rag.corpus import build
        from .common import digest,require_space
        if digest(args.source)!=args.dump_sha256:raise ValueError('Actual input SHA256 differs from requested verified dump')
        if digest(args.tokenizer)!='4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4':raise ValueError('Chunking requires the pinned EmbeddingGemma-2 tokenizer')
        if args.reserve_gib<1:raise ValueError('Positive SSD reserve required')
        require_space(Path(args.database).parent,1024**3,reserve=args.reserve_gib*1024**3)
        result=build(args.source,args.database,limit=args.limit,workers=args.workers,resume=args.command=='resume',wiki=args.wiki,tokenizer_path=args.tokenizer,dump_sha256=args.dump_sha256,disk_reserve_bytes=args.reserve_gib*1024**3)
    elif args.command=='embed':
        from rag.shards import export
        result=export(args.database,args.directory,revision=args.revision,device=args.device,batch=args.batch,shard_rows=args.shard_rows,model_path=args.model_path,compute_dtype=args.compute_dtype,reserve_bytes=args.reserve_gib*1024**3)
    elif args.command=='assemble':
        from rag.shards import assemble
        result=assemble(args.directory,args.output)
    elif args.command=='index':
        from rag.ivf import build_index
        result=build_index(args.reference,args.output,clusters=args.clusters,train_size=args.train_size,backend=args.backend,batch=args.batch)
    elif args.command=='validate':
        from .validate import database
        result=database(args.database,ivf=args.ivf,vectors=args.vectors)
        if args.report:
            from .common import atomic_json
            atomic_json(args.report,result)
    elif args.command=='package':
        from .packages import build
        result=build(args.database,args.output,package_id=args.id,version=args.version,kind=args.kind,source=json.loads(Path(args.source_json).read_text(encoding='utf-8-sig')),ivf=args.ivf,vectors=args.vectors,embedding=json.loads(Path(args.embedding_json).read_text(encoding='utf-8-sig')) if args.embedding_json else None,nprobe=args.nprobe)
    elif args.command=='dictionary':
        from .dictionary import ingest
        result=ingest(args.source,args.output,date=args.date,resume=args.resume)
    elif args.command=='repair-noncontent':
        from .repair import noncontent
        result=noncontent(args.source,args.database,args.output,wiki=args.wiki)
    elif args.command=='stack-tags-upgrade':
        from .repair import stack_tags
        result=stack_tags(args.database,args.output,resume=args.resume)
    elif args.command=='nutrition-upgrade':
        from .tables import upgrade_energy
        result=upgrade_energy(args.source,args.output)
    elif args.command=='dictionary-upgrade':
        from .dictionary_upgrade import upgrade
        result=upgrade(args.dump,args.source,args.output,dump_sha256=args.dump_sha256,resume=args.resume)
    elif args.command in ('constants','nutrition'):
        from .tables import constants,products
        result=constants(args.source,args.output,date=args.date) if args.command=='constants' else products(args.source,args.output,date=args.date,resume=args.resume)
    else:
        from . import stackexchange as se
        if args.command=='stack-download':result=se.fetch(root,args.site,args.component)
        elif args.command=='stack-extract':result=se.extract(args.archive,args.output)
        elif args.command=='stack-select':result=se.select(args.posts,args.output,site=args.site,max_questions=args.max_questions,min_question_score=args.min_score,resume=args.resume)
        else:result=se.export(args.selected,args.output,site=args.site,users=args.users)
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
