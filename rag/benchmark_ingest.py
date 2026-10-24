"""Small offline ingestion benchmark with sampled parent + worker RSS.

No model, CUDA, network, or full dump is used. RSS sums count shared pages more
than once; sample interval can miss short peaks. Results are host-specific.
"""
import argparse
from contextlib import closing
import json
import platform
import sqlite3
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from .corpus import articles


def synthetic(path, count):
    if count < 1:
        raise ValueError("article count must be positive")
    examples = list(articles(Path(__file__).parent / "data/wikipedia/complex.xml"))
    # Emit XML incrementally, so the benchmark itself does not hide a full corpus load.
    with Path(path).open("wb") as stream:
        stream.write(b'<mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/">\n')
        for index in range(count):
            row = examples[index % len(examples)]
            page = ET.Element("page")
            ET.SubElement(page, "title").text = row["title"] + f" {index}"
            ET.SubElement(page, "ns").text = "0"
            ET.SubElement(page, "id").text = str(index + 1)
            revision = ET.SubElement(page, "revision")
            ET.SubElement(revision, "id").text = str(100000 + index)
            ET.SubElement(revision, "text").text = row["text"]
            stream.write(ET.tostring(page, encoding="utf-8") + b"\n")
        stream.write(b'</mediawiki>')


def _disk_bytes(database):
    total = 0
    for path in database.parent.glob(database.name + "*"):
        try:
            info = path.stat()
        except FileNotFoundError:
            # SQLite can unlink a journal between directory enumeration and stat.
            continue
        if stat.S_ISREG(info.st_mode):
            total += info.st_size
    return total


def measure(source, database, workers, fts4=True, sample_seconds=.02):
    import psutil
    source, database = Path(source), Path(database)
    command = [sys.executable, "-m", "rag", "ingest", str(source), str(database),
               "--workers", str(workers), "--quiet", "--commit-every", "50"]
    if not fts4:
        command.append("--no-android-fts4")
    started, peak_parent, peak_tree, peak_disk = time.perf_counter(), 0, 0, 0
    samples = 0
    stdout_path, stderr_path = Path(str(database) + ".stats.json"), Path(str(database) + ".stderr")
    with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr)
        try:
            parent = psutil.Process(process.pid)
            while process.poll() is None:
                total = 0
                try:
                    parent_rss = parent.memory_info().rss
                    peak_parent = max(peak_parent, parent_rss)
                    total = parent_rss
                    for child in parent.children(recursive=True):
                        try:
                            total += child.memory_info().rss
                        except psutil.Error:
                            pass
                except psutil.Error:
                    pass
                peak_tree = max(peak_tree, total)
                peak_disk = max(peak_disk, _disk_bytes(database))
                samples += 1
                time.sleep(sample_seconds)
        finally:
            children = []
            if process.poll() is None:
                try:
                    children = psutil.Process(process.pid).children(recursive=True)
                    for child in children:
                        try:
                            child.kill()
                        except psutil.Error:
                            pass
                except psutil.Error:
                    pass
                process.terminate()
            process.wait()
            # On Windows a venv launcher can exit before its killed Python child
            # releases inherited stdout/stderr handles.
            psutil.wait_procs(children, timeout=10)
        if process.returncode:
            raise RuntimeError("ingest failed: " + stderr_path.read_text(encoding="utf-8")[-2000:])
    elapsed = time.perf_counter() - started
    stats = json.loads(stdout_path.read_text(encoding="utf-8"))
    peak_disk = max(peak_disk, database.stat().st_size)
    with closing(sqlite3.connect(database)) as db:
        original, markdown = db.execute("SELECT coalesce(sum(length(wikitext_zlib)),0), coalesce(sum(length(markdown_zlib)),0) FROM articles").fetchone()
        compressed_chunks = db.execute("SELECT coalesce(sum(length(text_zlib)),0) FROM chunks").fetchone()[0]
    return dict(articles=stats["articles"], chunks=stats["chunks"], workers=workers,
                android_fts4=fts4, source_bytes=source.stat().st_size,
                database_bytes=database.stat().st_size, sampled_peak_disk_bytes=peak_disk,
                original_zlib_bytes=original, markdown_zlib_bytes=markdown,
                chunk_zlib_bytes=compressed_chunks, wall_seconds=elapsed,
                articles_per_second=stats["articles"] / elapsed,
                input_mib_per_second=source.stat().st_size / 1024**2 / elapsed,
                parent_peak_rss_bytes=peak_parent, tree_peak_rss_sum_bytes=peak_tree,
                sample_seconds=sample_seconds, samples=samples)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--articles", nargs="+", type=int, default=[60, 300])
    parser.add_argument("--workers", nargs="+", type=int, default=[1, 4])
    parser.add_argument("--fts4", choices=["both", "on", "off"], default="both")
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if min(args.articles + args.workers) < 1:
        parser.error("counts and workers must be positive")
    if args.output.exists():
        raise FileExistsError(args.output)
    args.work_dir.mkdir(parents=True, exist_ok=True)
    report = dict(dataset="self-authored complex MediaWiki fixture replicas; no Wikipedia download",
                  platform=platform.platform(), python=platform.python_version(),
                  memory_method="20 ms sampled RSS sum of subprocess plus descendants; shared pages counted per process; peaks may be missed",
                  scope="host only; uncontrolled filesystem caches; no Windows/RTX/A55/full-dump claim", runs=[])
    modes = [True, False] if args.fts4 == "both" else [args.fts4 == "on"]
    for count in args.articles:
        source = args.work_dir / f"synthetic-{count}.xml"
        synthetic(source, count)
        for workers in args.workers:
            for fts4 in modes:
                database = args.work_dir / f"wiki-{count}-w{workers}-fts4{int(fts4)}.sqlite"
                result = measure(source, database, workers, fts4)
                report["runs"].append(result)
                print(f"articles={count} workers={workers} fts4={fts4}: {result['articles_per_second']:.1f}/s, RSS sum={result['tree_peak_rss_sum_bytes']/1024**2:.1f} MiB", file=sys.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    main()
