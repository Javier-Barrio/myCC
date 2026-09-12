package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.backend.arch.x86_64.X86Encoder;
import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Programs assembled by us into ELF objects, linked with gcc, run; and
 * each program's .text compared byte for byte with GNU as on our own
 * printed assembly of the same IR.
 */
class ObjectTest {

    static Stream<Path> programs() throws IOException {
        try (var files = Files.list(Path.of("src/test/resources/programs"))) {
            return files.filter(p -> p.toString().endsWith(".c")).sorted().toList().stream();
        }
    }

    static Compiler.Compiled compile(String source) {
        return compile(source, new Types(X86_64SysV.INSTANCE));
    }

    static Compiler.Compiled compile(String source, Types types) {
        return Compiler.compile(source, BundledHeaders.INSTANCE, "test.c", types);
    }

    record Run(int exit, String out) {
    }

    static Run link(String source) throws Exception {
        return link(source, new Types(X86_64SysV.INSTANCE));
    }

    static Run link(String source, Types types) throws Exception {
        assumeTrue(Native.gccAvailable(), "gcc is not available");
        Path dir = Files.createTempDirectory("mycc-object");
        Path o = dir.resolve("prog.o");
        Path exe = dir.resolve("prog");
        Files.write(o, Codegen.object(compile(source, types).tac()));
        Process gcc = new ProcessBuilder("gcc", "-o", exe.toString(), o.toString(), "-lm").redirectErrorStream(true).start();
        String gccOut = new String(gcc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!gcc.waitFor(60, TimeUnit.SECONDS) || gcc.exitValue() != 0) {
            throw new AssertionError("gcc failed to link our object:\n" + gccOut);
        }
        Process prog = new ProcessBuilder(List.of(exe.toString())).redirectErrorStream(true).start();
        String out = new String(prog.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!prog.waitFor(60, TimeUnit.SECONDS)) {
            prog.destroyForcibly();
            throw new AssertionError("the program did not finish");
        }
        return new Run(prog.exitValue(), out);
    }

    // The .text bytes GNU as produces from our printed text of the same IR.
    static byte[] textFromAs(List<Item> items) throws Exception {
        Path dir = Files.createTempDirectory("mycc-as");
        Path s = dir.resolve("prog.s");
        Path o = dir.resolve("prog.o");
        Path bin = dir.resolve("text.bin");
        Files.writeString(s, AttPrinter.print(items));
        Process as = new ProcessBuilder("as", "-o", o.toString(), s.toString()).redirectErrorStream(true).start();
        String asOut = new String(as.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!as.waitFor(60, TimeUnit.SECONDS) || as.exitValue() != 0) {
            throw new AssertionError("as failed:\n" + asOut);
        }
        Process copy = new ProcessBuilder("objcopy", "-O", "binary", "-j", ".text", o.toString(), bin.toString()).redirectErrorStream(true).start();
        copy.getInputStream().readAllBytes();
        copy.waitFor(60, TimeUnit.SECONDS);
        return Files.readAllBytes(bin);
    }

    static void assertTextMatchesAs(String source) throws Exception {
        assumeTrue(Native.gccAvailable(), "gcc is not available");
        List<Item> items = Codegen.build(compile(source).tac(), false);
        byte[] ours = new Assembler(new X86Encoder()).assemble(items).section(".text").bytes();
        byte[] theirs = textFromAs(items);
        int n = Math.min(ours.length, theirs.length);
        for (int k = 0; k < n; k++) {
            if (ours[k] != theirs[k]) {
                throw new AssertionError(String.format("first difference at .text+0x%x: ours %s, as %s", k, near(ours, k), near(theirs, k)));
            }
        }
        assertEquals(theirs.length, ours.length, ".text length");
    }

    private static String near(byte[] b, int at) {
        StringBuilder sb = new StringBuilder();
        for (int k = at; k < Math.min(b.length, at + 8); k++) {
            sb.append(String.format("%02x ", b[k] & 0xff));
        }
        return sb.toString().strip();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void corpusRunsFromOurObject(Path program) throws Exception {
        Run r = link(Files.readString(program));
        assertEquals(0, r.exit(), "CHECK " + r.exit() + " failed in " + program.getFileName() + "\n" + r.out());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void corpusTextMatchesAs(Path program) throws Exception {
        assertTextMatchesAs(Files.readString(program));
    }

    @Test
    void theSampleProgramAndTheLibrary() throws Exception {
        assertEquals(49, link(org.jbm.mycc.Main.SOURCE).exit());
        Run r = link("""
                #include <stdio.h>
                #include <string.h>
                #include <stdlib.h>
                int main(void) {
                    char buf[32];
                    strcpy(buf, "hello");
                    strcat(buf, ", world");
                    int *p = malloc(3 * sizeof(int));
                    p[0] = 1; p[1] = 2; p[2] = 3;
                    printf("%s %d %5.2f %c %x %lld|%-4d|\\n", buf, (int) strlen(buf), 3.14159, 'z', 255, 1LL << 40, 7);
                    printf("%d\\n", p[0] + p[1] + p[2]);
                    free(p);
                    return atoi("42");
                }
                """);
        assertEquals("hello, world 12  3.14 z ff 1099511627776|7   |\n6\n", r.out());
        assertEquals(42, r.exit());
        assertTextMatchesAs(org.jbm.mycc.Main.SOURCE);
    }
}
