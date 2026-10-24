package de.bonsai.offline;

/** Separate response envelopes keep free text from bypassing the tool JSON grammar. */
final class ToolGrammar {
    static final String FORMAT_PROMPT="\nGBNF ist aktiv. Für Tool-Aufrufe gilt unverändert das <tool>-Format. "
        +"Für JEDE Endantwort ersetzt folgende Regel das normale Textformat: "
        +"Gib ausschließlich <final>deine Antwort als normalen Text</final> aus. "
        +"Kein JSON und keine Anführungszeichen um die Endantwort. Die Markierungen werden von der App entfernt.";
    private static final String RULES="""
        tool ::= "<tool>" ws "{" ws "\\"name\\"" ws ":" ws (calc | remember | recall | search | wikidata | dictionary | tables) ws "}" ws "</tool>"
        dictionary ::= "\\"woerterbuch\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"word\\"" ws ":" ws json-string (ws "," ws "\\"language\\"" ws ":" ws json-string)? ws "}"
        tables ::= "\\"tabellen\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"op\\"" ws ":" ws (table-name | table-convert) ws "}"
        table-name ::= ("\\"constant\\"" | "\\"food\\"") ws "," ws "\\"name\\"" ws ":" ws json-string
        table-convert ::= "\\"convert\\"" ws "," ws "\\"value\\"" ws ":" ws json-string ws "," ws "\\"from\\"" ws ":" ws json-string ws "," ws "\\"to\\"" ws ":" ws json-string
        calc ::= "\\"calc\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"expr\\"" ws ":" ws json-string ws "}"
        remember ::= "\\"merken\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"key\\"" ws ":" ws json-string ws "," ws "\\"value\\"" ws ":" ws json-string ws "}"
        recall ::= "\\"abrufen\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"key\\"" ws ":" ws json-string ws "}"
        search ::= "\\"suche\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"query\\"" ws ":" ws json-string (ws "," ws "\\"corpus\\"" ws ":" ws ("\\"all\\"" | "\\"wikipedia\\"" | "\\"wikibooks\\"" | "\\"stackexchange\\""))? ws "}"
        wikidata ::= "\\"wikidata\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"op\\"" ws ":" ws (wd-resolve | wd-facts | wd-path | wd-greater | wd-largest | wd-count) ws "}"
        wd-entity ::= ws "," ws "\\"entity\\"" ws ":" ws json-string
        wd-property ::= ws "," ws "\\"property\\"" ws ":" ws json-string
        wd-value ::= ws "," ws "\\"value\\"" ws ":" ws json-string
        wd-unit ::= ws "," ws "\\"unit\\"" ws ":" ws json-string
        wd-resolve ::= "\\"resolve\\"" wd-entity
        wd-facts ::= "\\"facts\\"" wd-entity wd-property
        wd-path ::= "\\"path\\"" wd-entity wd-property
        wd-greater ::= "\\"greater\\"" wd-entity wd-property wd-value wd-unit
        wd-largest ::= "\\"largest\\"" wd-entity wd-property wd-unit
        wd-count ::= "\\"count\\"" wd-entity wd-property wd-value
        json-string ::= "\\"" char* "\\""
        char ::= [^"\\\\\\x00-\\x1F] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F]{4})
        ws ::= [ \\t\\n\\r]*
        final ::= "<final>" final-text "</final>"
        final-text ::= [^<]* ("<" final-lt)?
        final-lt ::= "<" final-lt | "/" final-slash | [^</] final-text | ""
        final-slash ::= "<" final-lt | "f" final-f | [^<f] final-text | ""
        final-f ::= "<" final-lt | "i" final-fi | [^<i] final-text | ""
        final-fi ::= "<" final-lt | "n" final-fin | [^<n] final-text | ""
        final-fin ::= "<" final-lt | "a" final-fina | [^<a] final-text | ""
        final-fina ::= "<" final-lt | "l" final-name | [^<l] final-text | ""
        final-name ::= "<" final-lt | [^<>] final-text | ""
        """;
    static String forRound(boolean finalOnly) {
        return (finalOnly?"root ::= final\n":"root ::= tool | final\n")+RULES;
    }
    private ToolGrammar() {}
}
