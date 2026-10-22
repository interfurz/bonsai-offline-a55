"""Small authored dump in the official format. Facts are examples, not a live snapshot.

The moon counts intentionally cover only these named satellites. Never a claim about
the current total of known moons. Generate shared Python/Android fixtures with:
python -m wikidata.fixtures
"""
import argparse
import json
from pathlib import Path
import sqlite3
import tempfile
from .pipeline import build


def snak(prop, kind, value):
    datatype, data_type = {"entity": ("wikibase-item", "wikibase-entityid"),
                           "quantity": ("quantity", "quantity"), "time": ("time", "time"),
                           "string": ("string", "string")}[kind]
    if kind == "entity":
        value = {"entity-type": "item", "numeric-id": int(value[1:]), "id": value}
    return {"snaktype": "value", "property": prop, "datatype": datatype,
            "datavalue": {"value": value, "type": data_type}}


def date(value, precision=11):
    return {"time": value, "timezone": 0, "before": 0, "after": 0,
            "precision": precision, "calendarmodel": "http://www.wikidata.org/entity/Q1985727"}


def dataset():
    entries = []

    def entity(qid, de, en=None, aliases=(), description="", datatype=None):
        obj = {"id": qid, "type": "property" if qid.startswith("P") else "item",
               "labels": {lang: {"language": lang, "value": text}
                          for lang, text in (("de", de), ("en", en or de))},
               "aliases": {"de": [{"language": "de", "value": a} for a in aliases]},
               "descriptions": {"de": {"language": "de", "value": description}},
               "lastrevid": 123, "modified": "2026-10-01T00:00:00Z", "claims": {}}
        if datatype:
            obj["datatype"] = datatype
        entries.append(obj)
        return obj

    def claim(obj, prop, kind, value, rank="normal", qualifiers=None):
        ordinal = sum(map(len, obj["claims"].values()))
        statement = {"id": obj["id"] + "$fixture-" + str(ordinal), "type": "statement",
                     "rank": rank, "mainsnak": snak(prop, kind, value),
                     "qualifiers": qualifiers or {},
                     "references": [{"snaks": {"P248": [snak("P248", "entity", "Q2013")]}}]}
        obj["claims"].setdefault(prop, []).append(statement)
        return statement

    # Endpoint metadata comes BEFORE referring entities: pass two is necessary.
    entity("Q1490", "Tokio", "Tokyo", aliases=("Tokyo",))
    entity("Q64", "Berlin")
    entity("Q40", "Österreich", "Austria")
    entity("Q39", "Schweiz", "Switzerland")
    for qid, name in (("Q28", "Ungarn"), ("Q213", "Tschechien"), ("Q35", "Dänemark"),
                      ("Q142", "Frankreich"), ("Q32", "Luxemburg"), ("Q31", "Belgien"),
                      ("Q55", "Niederlande"), ("Q36", "Polen")):
        entity(qid, name)
    entity("Q2013", "Wikidata")
    entity("Q1985727", "gregorianischer Kalender", "Gregorian calendar")
    entity("Q11573", "Meter", "metre")
    entity("Q6256", "Land", "country")
    entity("Q11344", "chemisches Element", "chemical element")
    entity("Q634", "Planet", "planet")
    giant = entity("Q121750", "Gasplanet", "gas giant")
    claim(giant, "P279", "entity", "Q634")
    entity("Q2537", "natürlicher Satellit", "natural satellite")
    for pid, de, en, datatype in (
            ("P31", "ist ein", "instance of", "wikibase-item"),
            ("P279", "Unterklasse von", "subclass of", "wikibase-item"),
            ("P36", "Hauptstadt", "capital", "wikibase-item"),
            ("P569", "Geburtsdatum", "date of birth", "time"),
            ("P47", "gemeinsame Grenze mit", "shares border with", "wikibase-item"),
            ("P1086", "Ordnungszahl", "atomic number", "quantity"),
            ("P19", "Geburtsort", "place of birth", "wikibase-item"),
            ("P17", "Staat", "country", "wikibase-item"),
            ("P397", "übergeordnetes astronomisches Objekt", "parent astronomical body", "wikibase-item"),
            ("P2048", "Höhe", "height", "quantity"),
            ("P585", "Zeitpunkt", "point in time", "time"),
            ("P248", "angegeben in", "stated in", "wikibase-item")):
        entity(pid, de, en, datatype=datatype)
    japan = entity("Q17", "Japan")
    claim(japan, "P36", "entity", "Q1490")
    germany = entity("Q183", "Deutschland", "Germany", aliases=("Bundesrepublik Deutschland", "BRD"))
    claim(germany, "P31", "entity", "Q6256")
    claim(germany, "P36", "entity", "Q64")
    for neighbor in ("Q40", "Q39", "Q213", "Q35", "Q142", "Q32", "Q31", "Q55", "Q36"):
        claim(germany, "P47", "entity", neighbor)
    ulm = entity("Q3012", "Ulm")
    claim(ulm, "P17", "entity", "Q183")
    einstein = entity("Q937", "Albert Einstein", aliases=("Einstein",), description="deutsch-amerikanischer Physiker")
    claim(einstein, "P569", "time", date("+1879-03-14T00:00:00Z"))
    claim(einstein, "P19", "entity", "Q3012")
    entity("Q60197", "Alfred Einstein", aliases=("Einstein",), description="Musikwissenschaftler")
    for qid, name, amount in (("Q131", "Iod", "+53"), ("Q130", "Brom", "+35"), ("Q1106", "Fluor", "+9")):
        element = entity(qid, name)
        claim(element, "P31", "entity", "Q11344")
        claim(element, "P1086", "quantity", {"amount": amount, "unit": "1"})
    tower = entity("Q243", "Eiffelturm", "Eiffel Tower")
    for amount, rank in (("+300", "normal"), ("+330.00", "preferred"), ("+999", "deprecated")):
        claim(tower, "P2048", "quantity", {"amount": amount, "unit": "http://www.wikidata.org/entity/Q11573",
                                          "lowerBound": amount, "upperBound": amount}, rank,
              {"P585": [snak("P585", "time", date("+2022-01-01T00:00:00Z", 9))]})
    for qid, name in (("Q319", "Jupiter"), ("Q193", "Saturn")):
        planet = entity(qid, name)
        claim(planet, "P31", "entity", "Q121750")
    for qid, name, parent in (("Q3123", "Io", "Q319"), ("Q3143", "Europa", "Q319"),
                              ("Q2565", "Titan", "Q193")):
        moon = entity(qid, name)
        claim(moon, "P31", "entity", "Q2537")
        claim(moon, "P397", "entity", parent,
              qualifiers={"P585": [snak("P585", "time", date("+2026-10-01T00:00:00Z"))]})
    return entries


def write_dump(path):
    with Path(path).open("w", encoding="utf-8") as stream:
        stream.write("[\n")
        entries = dataset()
        for i, entry in enumerate(entries):
            stream.write(json.dumps(entry, ensure_ascii=False) + ("," if i < len(entries)-1 else "") + "\n")
        stream.write("]\n")


def snapshot(path):
    with sqlite3.connect(path) as db:
        tables = {table: list(db.execute("SELECT * FROM "+table+" ORDER BY "+order))
                for table, order in (("entity", "id"), ("term", "entity,lang,kind,value"),
                                     ("statement", "id"))}
        import zlib
        tables["statement"] = [row[:-1] + (json.loads(zlib.decompress(row[-1])),) for row in tables["statement"]]
        return tables | {
            "metadata": list(db.execute("SELECT key,value FROM metadata WHERE key<>'signature' ORDER BY key")),
            "schema": list(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Check committed fixture content without rewriting it")
    args = parser.parse_args()
    directory = Path("work/wikidata-fixture")
    directory.mkdir(parents=True, exist_ok=True)
    source = directory / "entities.json"
    write_dump(source)
    output = Path("app/src/test/resources/wikidata/knowledge.sqlite")
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.check:
        with tempfile.TemporaryDirectory() as temp:
            rebuilt = Path(temp)/"fixture.sqlite"
            build(source, rebuilt, dump_date="2026-10-01", batch=20)
            if snapshot(rebuilt) != snapshot(output):
                raise ValueError("Shared Android fixture differs; regenerate with python -m wikidata.fixtures")
        print("Shared Python/Android fixture content matches")
        return
    output.unlink(missing_ok=True)
    build(source, output, dump_date="2026-10-01", batch=20)
    # Checkpoint identity is intentionally path/stat-specific. A completed test asset
    # needs no checkpoint and must not embed the builder's local filesystem paths.
    with sqlite3.connect(output) as db:
        db.execute("UPDATE metadata SET value='authored-fixture-v1' WHERE key='signature'")
        db.commit()
        db.execute("VACUUM")
    print(output, output.stat().st_size)


if __name__ == "__main__":
    main()
