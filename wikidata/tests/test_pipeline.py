import bz2
import gzip
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zlib

from wikidata.fixtures import dataset, write_dump
from wikidata.pipeline import build, identifier, normalize, records


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "dump.json"
        self.output = self.root / "knowledge.sqlite"
        write_dump(self.source)

    def build(self, **kwargs):
        return build(self.source, self.output, dump_date="2026-10-01", **kwargs)

    def connect(self, output=None):
        db = sqlite3.connect(output or self.output)
        self.addCleanup(db.close)
        return db

    def test_ids_and_cross_language_normalization(self):
        self.assertEqual(-36, identifier("P36"))
        self.assertEqual("österreich", normalize("  ÖSTERREICH\u00a0"))
        self.assertEqual("japan", normalize("ＪＡＰＡＮ"))
        for bad in ("Q0", "p31", "Q1;DROP TABLE entity", "Q9223372036854775808", None):
            with self.assertRaises(ValueError):
                identifier(bad)

    def test_typed_facts_aliases_descriptions_and_source_details(self):
        self.assertTrue(self.build())
        db = self.connect()
        self.assertEqual((1490,), db.execute("SELECT target FROM statement WHERE subject=17 AND property=-36").fetchone())
        self.assertEqual(("+1879-03-14T00:00:00Z", 11, 1985727), db.execute(
            "SELECT time,precision,calendar FROM statement WHERE subject=937 AND property=-569").fetchone())
        self.assertEqual(9, db.execute("SELECT COUNT(*) FROM statement WHERE subject=183 AND property=-47").fetchone()[0])
        self.assertEqual(2, db.execute("SELECT COUNT(DISTINCT entity) FROM term WHERE norm='einstein'").fetchone()[0])
        self.assertIn("Physiker", db.execute("SELECT value FROM term WHERE entity=937 AND kind=2").fetchone()[0])
        amount, unit, detail = db.execute("SELECT amount,unit,detail FROM statement WHERE subject=243 AND rank=2").fetchone()
        self.assertEqual(("+330.00", 11573), (amount, unit))
        detail = json.loads(zlib.decompress(detail))
        self.assertEqual("+330.00", detail["mainsnak"]["datavalue"]["value"]["lowerBound"])
        self.assertEqual(9, detail["qualifiers"]["P585"][0]["datavalue"]["value"]["precision"])
        self.assertEqual("Q2013", detail["references"][0]["snaks"]["P248"][0]["datavalue"]["value"]["id"])
        self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])

    def test_selection_second_pass_fetches_earlier_endpoints_and_properties(self):
        self.build(config={"entities": ["Q17"], "properties": ["P36"], "references": False})
        db = self.connect()
        self.assertEqual(1, db.execute("SELECT COUNT(*) FROM statement").fetchone()[0])
        self.assertEqual("Tokio", db.execute("SELECT value FROM term WHERE entity=1490 AND kind=0 AND lang='de'").fetchone()[0])
        self.assertEqual("wikibase-item", db.execute("SELECT datatype FROM entity WHERE id=-36").fetchone()[0])
        self.assertIsNone(db.execute("SELECT id FROM entity WHERE id=937").fetchone())
        detail = json.loads(zlib.decompress(db.execute("SELECT detail FROM statement").fetchone()[0]))
        self.assertNotIn("references", detail)

    def test_class_filters_exclusions_and_no_deprecated_data_loss(self):
        self.build(config={"classes": ["Q11344"], "exclude_classes": ["Q6256"]})
        db = self.connect()
        self.assertEqual(3, db.execute("SELECT COUNT(*) FROM entity WHERE loaded=2").fetchone()[0])
        self.assertEqual({9.0, 35.0, 53.0}, {r[0] for r in db.execute("SELECT number FROM statement WHERE kind='quantity'")})

    def test_gzip_bz2_and_resume_mid_second_pass_match_fresh_build(self):
        self.build()
        expected = list(self.connect().execute("SELECT subject,property,rank,kind,target,amount FROM statement ORDER BY id"))
        for suffix, compressor in ((".gz", gzip.compress), (".bz2", bz2.compress)):
            source = self.root / ("dump.json" + suffix)
            source.write_bytes(compressor(self.source.read_bytes()))
            output = self.root / ("result" + suffix + ".sqlite")
            seen = []
            self.assertFalse(build(source, output, dump_date="2026-10-01", batch=3,
                                   stop_after=len(dataset())+4, progress=seen.append))
            self.assertFalse(output.exists())
            self.assertEqual("labels", seen[-1]["phase"])
            self.assertTrue(build(source, output, dump_date="2026-10-01", resume=True))
            self.assertEqual(expected, list(self.connect(output).execute(
                "SELECT subject,property,rank,kind,target,amount FROM statement ORDER BY id")))

    def test_checkpoint_source_and_config_are_immutable(self):
        self.build(stop_after=4)
        with self.assertRaisesRegex(ValueError, "differs"):
            self.build(resume=True, config={"entities": ["Q17"]})
        self.source.write_text(self.source.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.build(resume=True)

    def test_failures_rollback_entity_and_offset_together(self):
        entries = dataset()
        good = next(e for e in entries if e["id"] == "Q17")
        bad = next(e for e in entries if e["id"] == "Q937")
        bad["claims"]["P569"][0]["rank"] = "invalid"
        self.source.write_text(json.dumps(good)+"\n"+json.dumps(bad)+"\n")
        with self.assertRaisesRegex(ValueError, "offset"):
            self.build(batch=100)
        db = self.connect(str(self.output)+".building")
        self.assertEqual(0, db.execute("SELECT COUNT(*) FROM statement").fetchone()[0])
        state = json.loads(db.execute("SELECT value FROM metadata WHERE key='state'").fetchone()[0])
        self.assertEqual(0, state["offset"])
        self.assertFalse(self.output.exists())

    def test_explicit_error_tolerance_and_line_limit(self):
        self.source.write_bytes(b"{"+b"x"*300+b"}\n"+self.source.read_bytes())
        self.build(max_line_bytes=10000, max_errors=2)
        db = self.connect()
        self.assertEqual(2, json.loads(db.execute("SELECT value FROM metadata WHERE key='state'").fetchone()[0])["errors"])
        self.assertTrue(Path(str(self.output)+".errors.jsonl").exists())
        import io
        rows = list(records(io.BytesIO(b"{"+b"x"*300+b"}\n{}\n"), max_line_bytes=128))
        self.assertIn("exceeds", rows[0][3])
        self.assertEqual({}, rows[1][2])

    def test_truncated_dump_does_not_publish(self):
        self.source.write_text(self.source.read_text().rstrip()[:-1])
        with self.assertRaisesRegex(ValueError, "Truncated"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_corrupt_compressed_input_does_not_publish(self):
        source = self.root / "dump.json.gz"
        source.write_bytes(gzip.compress(self.source.read_bytes())[:-12])
        with self.assertRaises((EOFError, OSError)):
            build(source, self.output, dump_date="2026-10-01")
        self.assertFalse(self.output.exists())

    def test_page_budget_aborts_without_publishing(self):
        entries = dataset()
        for entry in entries:
            entry["descriptions"]["de"]["value"] = "x" * 20000
        self.source.write_text("\n".join(json.dumps(e) for e in entries))
        with self.assertRaisesRegex(sqlite3.OperationalError, "full"):
            self.build(max_bytes=65536)
        self.assertFalse(self.output.exists())

    def test_special_snaks_bce_and_unindexed_large_numbers_are_preserved(self):
        obj = next(e for e in dataset() if e["id"] == "Q937")
        obj["claims"]["P569"][0]["mainsnak"]["datavalue"]["value"]["time"] = "-0044-00-00T00:00:00Z"
        obj["claims"]["P569"][0]["mainsnak"]["datavalue"]["value"]["precision"] = 9
        obj["claims"]["P47"] = [{"mainsnak": {"snaktype": t, "property": "P47"}} for t in ("somevalue", "novalue")]
        obj["claims"]["P2048"] = [{"mainsnak": {"snaktype": "value", "property": "P2048",
            "datavalue": {"type": "quantity", "value": {"amount": "+1e999", "unit": "1"}}}}]
        self.source.write_text(json.dumps(obj)+"\n")
        self.build()
        db = self.connect()
        self.assertEqual({"somevalue", "novalue"}, {r[0] for r in db.execute("SELECT kind FROM statement WHERE property=-47")})
        self.assertEqual(("-0044-00-00T00:00:00Z", 9), db.execute("SELECT time,precision FROM statement WHERE property=-569").fetchone())
        self.assertEqual(("+1e999", None), db.execute("SELECT amount,number FROM statement WHERE property=-2048").fetchone())

    def test_budget_existing_output_and_invalid_configuration(self):
        with self.assertRaises(ValueError):
            self.build(config={"sql": "DROP TABLE entity"})
        with self.assertRaises(ValueError):
            self.build(max_bytes=100)
        self.build()
        with self.assertRaises(FileExistsError):
            self.build()

    def test_query_plans_use_name_forward_reverse_and_numeric_indices(self):
        self.build()
        db = self.connect()
        queries = (
            ("SELECT entity FROM term WHERE norm='japan' AND kind IN (0,1)", "term_name"),
            ("SELECT id FROM statement WHERE subject=17 AND property=-36 AND rank>0", "statement_forward"),
            ("SELECT subject FROM statement WHERE property=-397 AND target=319 AND kind='entity'", "statement_reverse"),
            ("SELECT subject FROM statement WHERE property=-1086 AND unit=0 AND number>50 AND kind='quantity'", "statement_number"))
        for sql, index in queries:
            plan = str(list(db.execute("EXPLAIN QUERY PLAN "+sql)))
            self.assertIn(index, plan)


if __name__ == "__main__":
    unittest.main()
