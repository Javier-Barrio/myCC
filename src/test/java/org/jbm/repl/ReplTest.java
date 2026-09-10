package org.jbm.repl;

import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.sema.types.Types;
import org.jbm.mycc.repl.Repl;
import org.jbm.mycc.repl.ScriptConsole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transcripts: each {@code src/test/resources/repl/*.cshell} is a
 * session as it appears on the terminal. Lines starting with the
 * prompt or the continuation prompt are the input; the file is what
 * the session must print, prompts and inputs included.
 */
class ReplTest {

    static final Path DIR = Path.of("src/test/resources/repl");

    static Stream<Path> transcripts() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".cshell")).sorted().toList().stream();
        }
    }

    static String session(List<String> inputs) {
        ScriptConsole console = new ScriptConsole(inputs);
        new Repl(console, BundledHeaders.INSTANCE, new Types(X86_64SysV.INSTANCE)).run();
        return console.transcript();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transcripts")
    void transcriptMatches(Path file) throws IOException {
        String expected = Files.readString(file);
        List<String> inputs = new ArrayList<>();
        for (String line : expected.split("\n")) {
            if (line.startsWith(Repl.PROMPT)) {
                inputs.add(line.substring(Repl.PROMPT.length()));
            } else if (line.startsWith(Repl.MORE)) {
                inputs.add(line.substring(Repl.MORE.length()));
            }
        }
        assertEquals(expected, session(inputs), file.getFileName().toString());
    }

    @org.junit.jupiter.api.io.TempDir
    Path dir;

    @Test
    void saveWritesTheKeptLines() throws IOException {
        Path file = dir.resolve("session.c");
        ScriptConsole console = new ScriptConsole(List.of("int x = 1;", "x++;", "int f(void) { return x; }", "/save " + file));
        new Repl(console, BundledHeaders.INSTANCE, new Types(X86_64SysV.INSTANCE)).run();
        assertEquals("int x = 1;\nint f(void) { return x; }\n", Files.readString(file));
        assertTrue(console.transcript().endsWith("|  saved 2 lines to " + file + "\n"), console.transcript());
        ScriptConsole again = new ScriptConsole(List.of("/load " + file, "f()"));
        new Repl(again, BundledHeaders.INSTANCE, new Types(X86_64SysV.INSTANCE)).run();
        assertTrue(again.transcript().endsWith("$1 ==> 1\n"), again.transcript());
    }

    @Test
    void keptLinesAreDeclarationsOnly() {
        ScriptConsole console = new ScriptConsole(List.of("int x = 1;", "x++;", "int f(void) { return x; }", "f()"));
        Repl repl = new Repl(console, BundledHeaders.INSTANCE, new Types(X86_64SysV.INSTANCE));
        repl.run();
        assertEquals(List.of("int x = 1;", "int f(void) { return x; }", "typeof_unqual((f())) $1;"), repl.kept(), console.transcript());
        assertEquals(2, repl.vm().memory().loadInt(repl.vm().addressOf("x"), 32, true));
    }
}
