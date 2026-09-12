package org.jbm.mycc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DriverTest {

    @Test
    void compilesWithDefinesIncludesAndTheStandard() throws Exception {
        Path dir = Files.createTempDirectory("mycc-driver");
        Files.writeString(Files.createDirectories(dir.resolve("inc")).resolve("k.h"), "#define K 40\n");
        Files.writeString(dir.resolve("a.c"), "#include \"k.h\"\nint f() { return K + TWO; }\nint g(void) { return f(1); }\n");
        Path o = dir.resolve("a.o");
        int status = new Driver().run(new String[]{"-c", "-O2", "-g", "-Wall", "-MT", "x", "-MD", "-DTWO=2", "-I" + dir.resolve("inc"), "-std=gnu99", dir.resolve("a.c").toString(), "-o", o.toString()});
        assertEquals(0, status);
        assertTrue(Files.size(o) > 0);
        assertEquals(1, new Driver().run(new String[]{"-c", dir.resolve("a.c").toString(), "-o", o.toString()}), "C23: f() takes no arguments");
    }

    @Test
    void preprocessesToStandardOutput() throws Exception {
        Path dir = Files.createTempDirectory("mycc-driver");
        Files.writeString(dir.resolve("p.c"), "#define X 1\nint a = X;\n#ifdef Y\nint b;\n#endif\nint c = Y;\n");
        PrintStream saved = System.out;
        var bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, new Driver().run(new String[]{"-E", "-DY=7", dir.resolve("p.c").toString()}));
        } finally {
            System.setOut(saved);
        }
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("int a = 1;"), out);
        assertTrue(out.contains("int b;"), out);
        assertTrue(out.contains("int c = 7;"), out);
    }

    @Test
    void linksThroughGcc() throws Exception {
        assumeTrue(gccAvailable(), "gcc is not available");
        Path dir = Files.createTempDirectory("mycc-driver");
        Files.writeString(dir.resolve("m.c"), "#include <stdio.h>\n#include <math.h>\nint main(void) { printf(\"%d\\n\", (int) sqrt(SQ)); return 0; }\n");
        Path exe = dir.resolve("m");
        assertEquals(0, new Driver().run(new String[]{"-DSQ=49", "-o", exe.toString(), dir.resolve("m.c").toString(), "-lm"}));
        Process p = new ProcessBuilder(exe.toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals("7\n", out);
    }

    static boolean gccAvailable() {
        try {
            Process p = new ProcessBuilder("gcc", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
