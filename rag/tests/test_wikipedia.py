"""Small, self-authored MediaWiki fixtures; no downloads/models/device needed."""
import bz2
from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

from rag import corpus
from rag.chunking import chunk_article, clean, sections, tokens
from rag.corpus import articles, build
from rag.search import search
from rag.wiki import records, resolve_redirect

FIXTURE = Path(__file__).resolve().parents[1] / "data/wikipedia/complex.xml"


def logical_rows(path):
    with closing(sqlite3.connect(path)) as db:
        return (db.execute("SELECT * FROM articles ORDER BY id").fetchall(),
                db.execute("SELECT * FROM chunks ORDER BY id").fetchall(),
                db.execute("SELECT * FROM chunk_metadata ORDER BY chunk_id").fetchall(),
                db.execute("SELECT * FROM redirects ORDER BY article_id").fetchall())


class WikipediaTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.database = self.root / "wiki.sqlite"

    def ingest(self, **kwargs):
        return build(FIXTURE, self.database, progress=False, **kwargs)

    def test_complex_markup_to_markdown_and_omitted_subtrees(self):
        article = next(articles(FIXTURE))
        parsed = sections(article["text"])
        self.assertEqual([(), ("Gestaltung",), ("Gestaltung", "Drahtung"),
                          ("Gestaltung", "Schnitt"), ("Pflege",),
                          ("Pflege", "Wasser und Licht"), ("Gesundheit",)], [s.path for s in parsed])
        text = "\n".join(s.text for s in parsed)
        for unwanted in ("Tabellenrauschen", "Galerierauschen", "Navigationsrauschen",
                         "Bibliographierauschen", "Kommentar", "Versteckter Beleg", "Kategorie:", "{{"):
            self.assertNotIn(unwanted, text)
        for wanted in ("**Bonsai**", "*heller*", "**sauberes**", "盆栽", "$E = m c^2$", "`H2O`",
                       "- Morgens", "  - Bei Hitze", "1. Erst", "[Pflegehinweise](https://example.org/pflege)"):
            self.assertIn(wanted, text)
        self.assertIn("== Kein Abschnitt ==", text)
        self.assertEqual("kursiv", clean("{{lang|de|kursiv}}"))
        self.assertEqual("", clean("[[Datei:X.png]] [[:Kategorie:Test]] <references/>"))

    def test_introduction_short_merge_long_split_and_sources(self):
        article = next(articles(FIXTURE))
        chunks = chunk_article(article["id"], article["title"], article["text"], article["revision_source"])
        self.assertEqual(((article["title"],),), chunks[0].section_paths)
        self.assertNotIn("Gestaltung", chunks[0].text)
        design = [c for c in chunks if any("Gestaltung" in p for p in c.section_paths)]
        self.assertEqual(1, len(design))
        self.assertEqual(3, len(design[0].section_paths))
        self.assertIn("### Drahtung", design[0].text)
        self.assertIn("Bonsai – Testartikel / Gestaltung / Schnitt", design[0].heading)
        care = [c for c in chunks if c.section_paths == ((article["title"], "Pflege"),)]
        self.assertGreater(len(care), 1)
        self.assertTrue(all("Gesunde Blätter" not in c.text for c in care))
        self.assertTrue(all(0 < tokens(c.text) <= 800 for c in chunks))
        self.assertTrue(all(c.article_id == "101" and c.source.endswith("oldid=90101") for c in chunks))
        self.assertEqual(len(chunks), len({c.id for c in chunks}))

    def test_paragraphs_preserved_and_nested_short_section_keeps_topic(self):
        text = "Einleitung.\n\nZweiter Absatz.\n== A ==\n" + "Langer Absatz. " * 400 + "\n=== Kind ===\nKurzer Kindabschnitt.\n== B ==\n" + "Eigenes Thema. " * 30
        chunks = chunk_article("1", "Titel", text, "quelle")
        self.assertEqual("Einleitung.\n\nZweiter Absatz.", chunks[0].text)
        child = next(c for c in chunks if "Kurzer Kindabschnitt." in c.text)
        self.assertNotIn("Eigenes Thema", child.text)
        self.assertEqual((("Titel", "A", "Kind"),), child.section_paths)

    def test_template_and_nowiki_headings_do_not_split(self):
        parsed = sections('Einleitung. {{Box|\n== Falsch ==\nVorlage}}\n<nowiki>== Auch falsch ==</nowiki>\n== Echt ==\nInhalt.')
        self.assertEqual([(), ("Echt",)], [s.path for s in parsed])
        self.assertNotIn("Vorlage", parsed[0].text)

    def test_stable_ids_under_reordering_workers_and_unrelated_edits(self):
        self.ingest(workers=1)
        other = self.root / "parallel.sqlite"
        build(FIXTURE, other, workers=2, commit_every=2, progress=False)
        self.assertEqual(logical_rows(self.database), logical_rows(other))
        source = self.root / "reverse.jsonl"
        rows = list(articles(FIXTURE))
        source.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in reversed(rows)), encoding="utf-8")
        reversed_db = self.root / "reversed.sqlite"
        build(source, reversed_db, progress=False)
        with closing(sqlite3.connect(self.database)) as a, closing(sqlite3.connect(reversed_db)) as b:
            self.assertEqual(a.execute("SELECT * FROM chunks ORDER BY id").fetchall(), b.execute("SELECT * FROM chunks ORDER BY id").fetchall())
        text = "Einleitung.\n== A ==\n" + "Ein Absatz. " * 50 + "\n== B ==\n" + "Anderer Absatz. " * 50
        first = chunk_article("1", "Titel", text, "quelle")
        second = chunk_article("1", "Titel", text.replace("Einleitung.", "Neue Einleitung."), "andere-quelle")
        self.assertEqual([c.id for c in first[1:]], [c.id for c in second[1:]])
        self.assertNotEqual(first[0].id, second[0].id)
        repeated = chunk_article("1", "Titel", "== A ==\n" + "x " * 2000, "quelle")
        self.assertEqual(len(repeated), len({c.id for c in repeated}))

    def test_compressed_originals_revisions_indices_and_search(self):
        result = self.ingest()
        self.assertEqual((3, 5, 1, 0), (result["articles"], result["redirects"], result["skipped"], result["errors"]))
        expected = {row["id"]: row for _, row in records(FIXTURE) if row["kind"] in {"article", "redirect"}}
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            for article_id, original, markdown, revision, timestamp in db.execute("SELECT id,wikitext_zlib,markdown_zlib,revision_id,timestamp FROM articles"):
                self.assertEqual(expected[article_id]["text"], zlib.decompress(original).decode())
                self.assertTrue(zlib.decompress(markdown).decode().startswith("# "))
                self.assertEqual(expected[article_id]["revision_id"], revision)
                self.assertEqual("2026-10-22T00:00:00Z", timestamp)
            self.assertEqual(0, db.execute("SELECT count(*) FROM chunks WHERE article_id IN (SELECT article_id FROM redirects)").fetchone()[0])
            self.assertEqual(result["chunks"], db.execute("SELECT count(*) FROM chunks_fts").fetchone()[0])
            self.assertEqual(result["chunks"], db.execute("SELECT count(*) FROM chunks_fts4").fetchone()[0])
            self.assertEqual(256, int(db.execute("SELECT value FROM metadata WHERE key='vector_target_dimension'").fetchone()[0]))
            self.assertIsNone(db.execute("SELECT value FROM metadata WHERE key='embedding_model'").fetchone())
        hit = search(self.database, "Lichtreaktion")[0]
        self.assertEqual("102", hit["article_id"])
        self.assertEqual("https://de.wikipedia.org/w/index.php?oldid=90102", hit["source"])

    def test_redirect_chains_fragments_cycles_and_missing(self):
        self.ingest()
        result = resolve_redirect(self.database, "kleiner_Baum")
        self.assertEqual(dict(status="resolved", article_id="101", title="Bonsai – Testartikel", fragment="Pflege"), result)
        self.assertEqual("missing", resolve_redirect(self.database, "Verwaist")["status"])
        self.assertEqual("cycle", resolve_redirect(self.database, "Kreis A")["status"])
        self.assertEqual("depth_exceeded", resolve_redirect(self.database, "Kleiner Baum", max_hops=1)["status"])

    def test_multistream_bzip2_and_limit_resume_match_uninterrupted(self):
        source = self.root / "dump.xml.bz2"
        data = FIXTURE.read_bytes()
        # Real multistream bz2 consists of independently compressed concatenated blocks.
        source.write_bytes(bz2.compress(data[:1000]) + bz2.compress(data[1000:]))
        first = build(source, self.database, limit=1, workers=2, progress=False)
        self.assertEqual((1, "limited"), (first["articles"], first["status"]))
        resumed = build(source, self.database, resume=True, workers=2, commit_every=1, progress=False)
        self.assertEqual("complete", resumed["status"])
        other = self.root / "full.sqlite"
        build(FIXTURE, other, progress=False)
        self.assertEqual(logical_rows(other), logical_rows(self.database))
        self.assertEqual(0, build(source, self.database, resume=True, progress=False)["session_articles_per_second"])

    def test_crash_after_checkpoint_rolls_back_fts_and_resumes(self):
        real_write = corpus._write_article
        count = 0

        def failing_write(db, article, fts4):
            nonlocal count
            count += 1
            real_write(db, article, fts4)
            if count == 3:
                raise RuntimeError("injected interruption after writing")

        with patch("rag.corpus._write_article", side_effect=failing_write):
            with self.assertRaisesRegex(RuntimeError, "injected"):
                self.ingest(commit_every=2)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual(2, db.execute("SELECT count(*) FROM articles").fetchone()[0])
            self.assertEqual(2, json.loads(dict(db.execute("SELECT * FROM metadata"))["ingest_checkpoint"])["position"])
            self.assertEqual(0, db.execute("SELECT count(*) FROM chunks WHERE article_id='103'").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT count(*) FROM chunks_fts WHERE chunks_fts MATCH 'Unicode'").fetchone()[0])
        self.ingest(resume=True, commit_every=2)
        other = self.root / "full.sqlite"
        build(FIXTURE, other, progress=False)
        self.assertEqual(logical_rows(other), logical_rows(self.database))

    def test_invalid_records_logged_once_and_recoverable(self):
        source = self.root / "bad.jsonl"
        source.write_text('\n'.join(['{bad', json.dumps(dict(id="bad", title="X", source="fixture://x", text=7)),
                                   json.dumps(dict(id="large", title="Large", source="fixture://large", text="x" * 3000)),
                                   json.dumps(dict(id="good", title="Gut", source="fixture://good", text="Suchbarer Inhalt."))]), encoding="utf-8")
        result = build(source, self.database, workers=2, limit=2, max_page_mib=.001, progress=False)
        self.assertEqual((2, 2, "limited"), (result["attempted"], result["errors"], result["status"]))
        result = build(source, self.database, workers=2, resume=True, max_page_mib=.001, progress=False)
        self.assertEqual((1, 3), (result["articles"], result["errors"]))
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual([1, 2, 3], [r[0] for r in db.execute("SELECT position FROM ingest_errors ORDER BY position")])
        log = Path(str(self.database) + ".errors.jsonl")
        self.assertEqual(3, len(log.read_text(encoding="utf-8").splitlines()))
        build(source, self.database, resume=True, max_page_mib=.001, progress=False)
        self.assertEqual(3, len(log.read_text(encoding="utf-8").splitlines()))

    def test_resume_guards_duplicate_ids_and_no_clobber(self):
        source = self.root / "copy.xml"
        source.write_bytes(FIXTURE.read_bytes())
        build(source, self.database, limit=1, progress=False)
        for kwargs in (dict(minimum=90), dict(android_fts4=False)):
            with self.assertRaisesRegex(ValueError, "differ"):
                build(source, self.database, resume=True, progress=False, **kwargs)
        source.write_bytes(source.read_bytes().replace(b"Draht", b"draht"))
        with self.assertRaisesRegex(ValueError, "differ"):
            build(source, self.database, resume=True, progress=False)
        with self.assertRaises(FileExistsError):
            self.ingest()
        with self.assertRaises(ValueError):
            build(FIXTURE, self.root / "other.sqlite", error_log=FIXTURE, progress=False)
        source = self.root / "duplicate.jsonl"
        row = dict(id="x", title="X", source="fixture://x", text="Duplikat.")
        source.write_text(json.dumps(row) + '\n' + json.dumps(row), encoding="utf-8")
        with self.assertRaises(sqlite3.IntegrityError):
            build(source, self.root / "duplicate.sqlite", commit_every=1, progress=False)

    def test_fts5_only_and_writer_lock(self):
        self.ingest(android_fts4=False)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name='chunks_fts4'").fetchone())
        self.assertTrue(search(self.database, "Bonsai"))
        with corpus._writer_lock(self.database):
            with self.assertRaisesRegex(RuntimeError, "lock"):
                self.ingest(resume=True, android_fts4=False)

    def test_existing_vector_writer_accepts_new_database_and_large_ids(self):
        # Existing fixture encoder only; no model download or new vectorization code.
        from rag.embeddings import embed
        import struct
        self.ingest()
        vectors = self.root / "fixture.i8"
        embed(self.database, vectors, "hash-v1", batch=3)
        payload = vectors.read_bytes()
        magic, dimension, count = struct.unpack("<8sII", payload[:16])
        self.assertEqual((b"BRAGV001", 256), (magic, dimension))
        ids = {struct.unpack_from("<q", payload, 16 + i * 268)[0] for i in range(count)}
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual({r[0] for r in db.execute("SELECT id FROM chunks")}, ids)
        self.assertTrue(search(self.database, "Photosynthese", vectors))
        with self.assertRaisesRegex(ValueError, "vectorized"):
            self.ingest(resume=True)

    def test_checked_in_example_is_searchable_and_matches_fixture(self):
        self.ingest()
        example = FIXTURE.with_name("example.sqlite")
        self.assertEqual(logical_rows(self.database), logical_rows(example))
        self.assertEqual("102", search(example, "Lichtreaktion")[0]["article_id"])

    def test_slot_export_and_history_dump_rejection(self):
        source = self.root / "slots.xml"
        source.write_text('<mediawiki><page><title>Slot</title><ns>0</ns><id>42</id><revision><id>99</id><slots><slot role="main"><model>wikitext</model><text>Slotinhalt.</text></slot></slots></revision></page></mediawiki>', encoding="utf-8")
        self.assertEqual("Slotinhalt.", next(articles(source))["text"])
        source.write_text(source.read_text(encoding="utf-8").replace('</revision>', '</revision><revision><id>100</id><text>Zweite Version.</text></revision>'), encoding="utf-8")
        result = build(source, self.database, progress=False)
        self.assertEqual((0, 1), (result["articles"], result["errors"]))

    def test_invalid_xml_stops_without_marking_complete(self):
        source = self.root / "truncated.xml"
        data = FIXTURE.read_bytes()
        source.write_bytes(data[:data.index(b"</page>") + len(b"</page>")] + b'<page>')
        import xml.etree.ElementTree as ET
        with self.assertRaises(ET.ParseError):
            build(source, self.database, commit_every=1, progress=False)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual("in_progress", dict(db.execute("SELECT * FROM metadata"))["ingest_status"])
            self.assertEqual(1, db.execute("SELECT count(*) FROM articles").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
