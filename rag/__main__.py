import argparse
import json
from .corpus import build
from .embeddings import embed
from .search import search


def main():
    parser = argparse.ArgumentParser(description="Bonsai offline RAG pipeline")
    commands = parser.add_subparsers(dest="command", required=True)
    ingest = commands.add_parser("ingest")
    ingest.add_argument("source")
    ingest.add_argument("database")
    ingest.add_argument("--limit", type=int)
    ingest.add_argument("--no-android-fts4", action="store_true")
    ingest.add_argument("--resume", action="store_true")
    ingest.add_argument("--workers", type=int, default=1)
    ingest.add_argument("--commit-every", type=int, default=500)
    ingest.add_argument("--min-tokens", type=int, default=80)
    ingest.add_argument("--max-tokens", type=int, default=800)
    ingest.add_argument("--max-inflight-mib", type=int, default=64)
    ingest.add_argument("--max-page-mib", type=int, default=8)
    ingest.add_argument("--error-log")
    ingest.add_argument("--dump-sha256", help="User-verified dump hash for provenance (not verified by ingest)")
    ingest.add_argument("--quiet", action="store_true")
    embedding = commands.add_parser("embed")
    embedding.add_argument("database")
    embedding.add_argument("vectors")
    embedding.add_argument("--model", default="google/embeddinggemma-300m")
    embedding.add_argument("--reference")
    embedding.add_argument("--batch", type=int, default=32)
    embedding.add_argument("--device")
    retrieval = commands.add_parser("search")
    retrieval.add_argument("database")
    retrieval.add_argument("query")
    retrieval.add_argument("--vectors")
    retrieval.add_argument("--top-k", type=int, default=3)
    retrieval.add_argument("--ivf")
    retrieval.add_argument("--nprobe", type=int, default=16)
    retrieval.add_argument("--rrf-k", type=int, default=60)
    args = parser.parse_args()
    if args.command == "ingest":
        result = build(args.source, args.database, args.limit, not args.no_android_fts4,
                       resume=args.resume, workers=args.workers, commit_every=args.commit_every,
                       minimum=args.min_tokens, maximum=args.max_tokens,
                       max_inflight_mib=args.max_inflight_mib, max_page_mib=args.max_page_mib,
                       error_log=args.error_log, progress=not args.quiet, dump_sha256=args.dump_sha256)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    elif args.command == "embed":
        embed(args.database, args.vectors, args.model, args.reference, args.batch, args.device)
    else:
        print(json.dumps(search(args.database, args.query, args.vectors, args.top_k, ivf=args.ivf, nprobe=args.nprobe, rrf_k=args.rrf_k), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
