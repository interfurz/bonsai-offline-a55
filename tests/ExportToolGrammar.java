package de.bonsai.offline;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exports the exact Java grammar bytes for the native runtime test. */
public final class ExportToolGrammar {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);
        Files.writeString(directory.resolve("tools.gbnf"),ToolGrammar.forRound(false));
        Files.writeString(directory.resolve("final.gbnf"),ToolGrammar.forRound(true));
    }
}
