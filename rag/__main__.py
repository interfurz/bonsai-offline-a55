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
    args = parser.parse_args()
    if args.command == "ingest":
        build(args.source, args.database, args.limit, not args.no_android_fts4)
    elif args.command == "embed":
        embed(args.database, args.vectors, args.model, args.reference, args.batch, args.device)
    else:
        print(json.dumps(search(args.database, args.query, args.vectors, args.top_k), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
