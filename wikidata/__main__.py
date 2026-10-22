import argparse
import json
import sys
from pathlib import Path
from .pipeline import build


def main():
    parser = argparse.ArgumentParser(description="Offline Wikidata JSON dump → Android SQLite")
    parser.add_argument("source")
    parser.add_argument("output")
    parser.add_argument("--dump-date", required=True, help="Official snapshot date YYYY-MM-DD")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--batch", type=int, default=1000)
    parser.add_argument("--max-bytes", type=int, default=7_000_000_000)
    parser.add_argument("--max-errors", type=int, default=0)
    parser.add_argument("--max-line-bytes", type=int, default=16 * 1024 * 1024)
    args = parser.parse_args()
    config = json.loads(args.config.read_text()) if args.config else {}
    build(args.source, args.output, dump_date=args.dump_date, config=config,
          resume=args.resume, batch=args.batch, max_bytes=args.max_bytes,
          max_errors=args.max_errors, max_line_bytes=args.max_line_bytes,
          progress=lambda state: print(json.dumps(state), file=sys.stderr, flush=True))


if __name__ == "__main__":
    main()
