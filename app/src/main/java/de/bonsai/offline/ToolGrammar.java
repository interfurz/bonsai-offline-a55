package de.bonsai.offline;

/** Separate response envelopes keep free text from bypassing the tool JSON grammar. */
final class ToolGrammar {
    static final String FORMAT_PROMPT="\nGBNF ist aktiv. Für Tool-Aufrufe gilt unverändert das <tool>-Format. "
        +"Für JEDE Endantwort ersetzt folgende Regel das normale Textformat: "
        +"Gib ausschließlich <final>deine Antwort als normalen Text</final> aus. "
        +"Kein JSON und keine Anführungszeichen um die Endantwort. Die Markierungen werden von der App entfernt.";
    private static final String RULES="""
        tool ::= "<tool>" ws "{" ws "\\"name\\"" ws ":" ws (calc | remember | recall) ws "}" ws "</tool>"
        calc ::= "\\"calc\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"expr\\"" ws ":" ws json-string ws "}"
        remember ::= "\\"merken\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"key\\"" ws ":" ws json-string ws "," ws "\\"value\\"" ws ":" ws json-string ws "}"
        recall ::= "\\"abrufen\\"" ws "," ws "\\"args\\"" ws ":" ws "{" ws "\\"key\\"" ws ":" ws json-string ws "}"
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
