package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.sema.types.Types;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Compiles C to assembly, assembles and links it with gcc, runs it: the exit status and the output. */
final class Native {

    record Run(int exit, String out) {
    }

    private Native() {
    }

    static boolean gccAvailable() {
        try {
            Process p = new ProcessBuilder("gcc", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static String assembly(String source) {
        Types types = new Types(X86_64SysV.INSTANCE);
        return Codegen.emit(Compiler.compile(source, BundledHeaders.INSTANCE, "test.c", types).tac(), true);
    }

    /** The program run natively; the test is skipped without gcc. */
    static Run run(String source) throws IOException, InterruptedException {
        assumeTrue(gccAvailable(), "gcc is not available");
        Path dir = Files.createTempDirectory("mycc-native");
        Path s = dir.resolve("prog.s");
        Path exe = dir.resolve("prog");
        Files.writeString(s, assembly(source));
        Process gcc = new ProcessBuilder("gcc", "-o", exe.toString(), s.toString()).redirectErrorStream(true).start();
        String gccOut = new String(gcc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!gcc.waitFor(60, TimeUnit.SECONDS) || gcc.exitValue() != 0) {
            throw new AssertionError("gcc failed:\n" + gccOut + "\n--- assembly ---\n" + Files.readString(s));
        }
        Process prog = new ProcessBuilder(List.of(exe.toString())).redirectErrorStream(true).start();
        String out = new String(prog.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!prog.waitFor(60, TimeUnit.SECONDS)) {
            prog.destroyForcibly();
            throw new AssertionError("the program did not finish");
        }
        return new Run(prog.exitValue(), out);
    }
}
