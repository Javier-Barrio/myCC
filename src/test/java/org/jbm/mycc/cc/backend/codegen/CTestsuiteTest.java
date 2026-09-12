package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.sema.types.Types;
import org.jbm.mycc.repl.vm.VM;
import org.jbm.mycc.repl.vm.builtins.Libc;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The c-testsuite single-file programs ({@code src/test/resources/c-testsuite},
 * MIT), each expected to exit 0 and print its {@code .expected} file.
 * {@code passing.txt} lists the ones that pass on the VM and natively;
 * those must keep passing, the rest are skipped. With
 * {@code -Dctestsuite.report=true} every program is tried and its
 * status printed, to grow the list.
 */
class CTestsuiteTest {

    static final Path DIR = Path.of("src/test/resources/c-testsuite");

    static Stream<Path> programs() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".c")).sorted().toList().stream();
        }
    }

    static Set<String> passing() throws IOException {
        Path list = DIR.resolve("passing.txt");
        return Files.exists(list) ? new HashSet<>(Files.readAllLines(list)) : Set.of();
    }

    static boolean report() {
        return Boolean.getBoolean("ctestsuite.report");
    }

    record Result(String stage, boolean ok, String detail) {
    }

    static Result onVm(String source, String expected) {
        try {
            var compiled = Compiler.compile(source, BundledHeaders.INSTANCE, "test.c", new Types(X86_64SysV.INSTANCE));
            VM vm = new VM();
            Libc.bind(vm);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            vm.out(new PrintStream(out, true, StandardCharsets.ISO_8859_1));
            vm.step(compiled.tac());
            long exit;
            try {
                VM.Value v = vm.main(List.of("test"));
                exit = v instanceof VM.IntValue i ? i.value() : 0;
            } catch (Libc.Exit e) {
                exit = e.status;
            }
            String printed = out.toString(StandardCharsets.ISO_8859_1);
            if (exit != 0) {
                return new Result("vm", false, "exit " + exit);
            }
            if (!printed.equals(expected)) {
                return new Result("vm", false, "output " + printed.replace("\n", "\\n") + " expected " + expected.replace("\n", "\\n"));
            }
            return new Result("vm", true, "");
        } catch (RuntimeException e) {
            return new Result("compile", false, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    static Result natively(String source, String expected) {
        try {
            ObjectTest.Run r = ObjectTest.link(source);
            if (r.exit() != 0) {
                return new Result("native", false, "exit " + r.exit());
            }
            if (!r.out().equals(expected)) {
                return new Result("native", false, "output " + r.out().replace("\n", "\\n"));
            }
            return new Result("native", true, "");
        } catch (Throwable e) {
            return new Result("native", false, String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void passesOnBothPaths(Path program) throws Exception {
        String name = program.getFileName().toString();
        boolean listed = passing().contains(name);
        if (!report() && !listed) {
            assumeTrue(false, "not in passing.txt");
        }
        String source = Files.readString(program);
        Path expectedFile = Path.of(program + ".expected");
        String expected = Files.exists(expectedFile) ? Files.readString(expectedFile) : "";
        Result vm = onVm(source, expected);
        Result nat = vm.ok() && Native.gccAvailable() ? natively(source, expected) : new Result("native", vm.ok(), "skipped");
        if (report()) {
            System.out.println("CTESTSUITE " + name + " " + (vm.ok() && nat.ok() ? "PASS" : "FAIL " + (vm.ok() ? nat.stage() + " " + nat.detail() : vm.stage() + " " + vm.detail())));
        }
        if (listed) {
            assertEquals("", vm.ok() ? "" : vm.stage() + ": " + vm.detail(), name + " regressed on the VM");
            assertEquals("", nat.ok() ? "" : nat.stage() + ": " + nat.detail(), name + " regressed natively");
        }
    }
}
