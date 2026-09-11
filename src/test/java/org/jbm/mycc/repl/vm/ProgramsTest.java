package org.jbm.mycc.repl.vm;

import org.jbm.mycc.repl.vm.builtins.Libc;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The program corpus on the VM: every {@code src/test/resources/programs/*.c}
 * asserts for itself with {@code CHECK(n, cond)} and exits 0. The same
 * programs run natively in {@code NativeTest}.
 */
class ProgramsTest {

    static final Path DIR = Path.of("src/test/resources/programs");

    static Stream<Path> programs() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".c")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    void exitsWithZero(Path program) throws IOException {
        VM vm = new VM();
        Libc.bind(vm);
        vm.step(VmTest.module(Files.readString(program)));
        long exit = ((VM.IntValue) vm.call("main", List.of())).value();
        assertEquals(0, exit, "CHECK " + exit + " failed in " + program.getFileName());
    }
}
