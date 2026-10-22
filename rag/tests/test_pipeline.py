import bz2
import json
import sqlite3
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from rag.chunking import chunk_article, clean, tokens
from rag.corpus import articles, build
from rag.embeddings import embed, encoder, vector_ranks
from rag.search import search, fuse
from rag.compare import compare

DATA = Path(__file__).resolve().parents[1] / "data"


class ChunkingTests(unittest.TestCase):
    def test_clean_wiki_markup(self):
        self.assertEqual("Ein Baum und Link.", clean("Ein {{box|{{nested}}}}[[Baum]] und [https://example.org Link].<ref>Beleg</ref>[[Datei:A.png|Bild]]"))

    def test_heading_paths_and_small_sections_merge(self):
        wiki = "Einleitung.\n== Natur ==\nEin Baum.\n=== Blätter ===\nGrüne Blätter."
        chunks = chunk_article("12", "Wald", wiki, "quelle")
        self.assertEqual(1, len(chunks))
        self.assertIn("Wald / Natur / Blätter", chunks[0].heading)
        self.assertEqual("12", chunks[0].article_id)
        self.assertEqual("quelle", chunks[0].source)
        self.assertIn("Einleitung.", chunks[0].text)
        self.assertIn("Grüne Blätter.", chunks[0].text)

    def test_large_sibling_sections_keep_distinct_heading_paths(self):
        wiki = "== Natur ==\n" + "Lebende Pflanzen. " * 30 + "\n=== Blätter ===\n" + "Grüne Blätter. " * 30 + "\n== Nutzung ==\n" + "Holz bearbeiten. " * 30
        chunks = chunk_article("1", "Baum", wiki, "q")
        self.assertEqual(["Baum / Natur", "Baum / Natur / Blätter", "Baum / Nutzung"],
                         [c.heading for c in chunks])

    def test_large_unicode_and_unbroken_sections_split_without_loss(self):
        for text in ("Wälder 🌳 wachsen. " * 700, "ü" * 7000):
            chunks = chunk_article("1", "Wald", text, "q")
            self.assertGreater(len(chunks), 1)
            self.assertTrue(all(0 < tokens(c.text) <= 800 for c in chunks))
            self.assertEqual("".join(text.split()), "".join("".join(c.text.split()) for c in chunks))

    def test_xml_limit_namespace_redirect_and_bzip_stream(self):
        xml = '<mediawiki xmlns="urn:test">' + ''.join(
            f'<page><title>A{i}</title><ns>{1 if i == 1 else 0}</ns><id>{i}</id>'
            + ('<redirect title="x"/>' if i == 2 else '')
            + f'<revision><id>999</id><text>Text {i}</text></revision></page>' for i in range(10)) + '</mediawiki>'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dump.xml.bz2"
            path.write_bytes(bz2.compress(xml.encode()))
            rows = list(articles(path, 2))
            self.assertEqual(["0", "3"], [row["id"] for row in rows])
            self.assertEqual("Text 3", rows[-1]["text"])


class SearchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.db = Path(self.temp.name) / "knowledge.sqlite"
        self.vectors = Path(self.temp.name) / "vectors.i8"
        self.reference = Path(self.temp.name) / "reference.f32"
        build(DATA / "articles.jsonl", self.db)
        embed(self.db, self.vectors, "hash-v1", self.reference, batch=7)

    def test_fifty_articles_schema_compression_and_id_mapping(self):
        with sqlite3.connect(self.db) as db:
            self.assertEqual(50, db.execute("SELECT count(DISTINCT article_id) FROM chunks").fetchone()[0])
            self.assertEqual(1, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertIn("Hauptstadt", zlib.decompress(db.execute("SELECT text_zlib FROM chunks WHERE id=1").fetchone()[0]).decode())
        self.assertEqual(16 + 50 * (8 + 4 + 256), self.vectors.stat().st_size)
        self.assertEqual((b"BRAGV001", 256, 50), struct.unpack("<8sII", self.vectors.read_bytes()[:16]))

    def test_fts_and_hybrid_return_source_and_relevant_hit(self):
        for vectors in (None, self.vectors):
            hits = search(self.db, "Photosynthese", vectors)
            self.assertEqual("2", hits[0]["article_id"])
            self.assertEqual("fixture://bonsai/2", hits[0]["source"])
            self.assertIn("Überblick", hits[0]["heading"])
            self.assertLessEqual(len(hits), 3)
            self.assertTrue(all(len(hit["text"]) <= 600 for hit in hits))

    def test_long_text_is_bounded_and_index_ids_stay_unique(self):
        source = Path(self.temp.name) / "long.jsonl"
        source.write_text(json.dumps(dict(id="100", title="Langtext", source="fixture://long",
                                         text="Photosynthese Pflanzen Lichtenergie. " * 600)))
        database = Path(self.temp.name) / "long.sqlite"
        build(source, database)
        with sqlite3.connect(database) as db:
            self.assertGreater(db.execute("SELECT count(*) FROM chunks").fetchone()[0], 1)
        hits = search(database, "Photosynthese")
        self.assertEqual(3, len(hits))
        self.assertEqual(3, len({hit["chunk_id"] for hit in hits}))
        self.assertTrue(all(len(hit["text"]) == 600 for hit in hits))

    def test_model_backend_applies_task_prompts_and_rejects_mismatch(self):
        class WrongEncoder:
            name = "other-model"
        with self.assertRaisesRegex(ValueError, "does not match"):
            search(self.db, "Berlin", self.vectors, backend=WrongEncoder())
        # Exercise task-specific model methods without downloading a model.
        from rag.embeddings import ModelEncoder
        class Array:
            def tolist(self):
                return [[1.0] * 768]
        class Model:
            def encode_query(self, texts, **kwargs):
                self.task = "query"
                return Array()
            def encode_document(self, texts, **kwargs):
                self.task = "document"
                return Array()
        backend = ModelEncoder.__new__(ModelEncoder)
        backend.name = "legacy-model"
        backend.model = Model()
        backend.encode(["question"], query=True)
        self.assertEqual("query", backend.model.task)
        backend.encode(["document"])
        self.assertEqual("document", backend.model.task)

    def test_empty_lexical_query_and_escaped_fts_syntax(self):
        self.assertEqual([], search(self.db, "zzzzzxxyy"))
        self.assertEqual([], search(self.db, '" OR * ()'))
        self.assertEqual([], search(self.db, "!!!", self.vectors))

    def test_gemma2_uses_exact_query_prefix_and_titled_documents(self):
        from unittest.mock import patch
        from rag.embeddings import DEFAULT_MODEL, GEMMA2_PROFILE, ModelEncoder
        class Array:
            def tolist(self):
                return [[1.0] * 768]
        class Model:
            def encode(self, texts, **kwargs):
                self.texts, self.kwargs = texts, kwargs
                return Array()
        backend = ModelEncoder.__new__(ModelEncoder)
        backend.name, backend.model = DEFAULT_MODEL, Model()
        backend.encode(["Warum wächst ein Baum? 🌳"], query=True)
        self.assertEqual(["task: search result | query: Warum wächst ein Baum? 🌳"], backend.model.texts)
        self.assertEqual("", backend.model.kwargs["prompt"])
        backend.encode(["title: Wald | text: Bäume wachsen."])
        self.assertEqual(["title: Wald | text: Bäume wachsen."], backend.model.texts)
        # Exercise the exporter, not just the adapter: real title, output dimension and profile.
        class BatchModel:
            name = DEFAULT_MODEL
            def encode(self, texts, query=False):
                self.texts = texts
                return [[1.0] * 768 for _ in texts]
        model = BatchModel()
        output = self.db.parent / "gemma2.i8"
        with patch("rag.embeddings.encoder", return_value=model):
            embed(self.db, output, model=DEFAULT_MODEL, batch=50)
        self.assertTrue(model.texts[0].startswith("title: Berlin | text: "))
        with sqlite3.connect(self.db) as db:
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
        self.assertEqual(DEFAULT_MODEL, metadata["embedding_model"])
        self.assertEqual(GEMMA2_PROFILE, metadata["embedding_profile"])
        self.assertEqual("256", metadata["embedding_dimension"])

    def test_missing_invalid_and_limits(self):
        with self.assertRaises(FileNotFoundError):
            search(self.db.parent / "missing", "Berlin")
        for query in ("", " ", 7, "x" * 257):
            with self.assertRaises(ValueError):
                search(self.db, query)
        with self.assertRaises(ValueError):
            search(self.db, "Berlin", top_k=4)
        with self.assertRaises(FileExistsError):
            build(DATA / "articles.jsonl", self.db)

    def test_rrf_and_corrupt_vectors(self):
        self.assertEqual([2, 1, 3], fuse([[1, 2], [2, 3]]))
        self.vectors.write_bytes(self.vectors.read_bytes()[:-1])
        with self.assertRaises((ValueError, struct.error)):
            vector_ranks(self.vectors, encoder("hash-v1").encode(["Berlin"])[0])

    def test_comparison_reports_actual_host_measurements(self):
        questions = json.loads((DATA / "questions.json").read_text())
        report = compare(self.db, self.vectors, self.reference, questions)
        self.assertEqual(5, len(report["queries"]))
        self.assertEqual(self.vectors.stat().st_size, report["int8_bytes"])
        self.assertTrue(all(row["int8_ms"] >= 0 for row in report["queries"]))


if __name__ == "__main__":
    unittest.main()
