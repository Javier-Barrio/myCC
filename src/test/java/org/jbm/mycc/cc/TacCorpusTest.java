package org.jbm.mycc.cc;

import org.jbm.mycc.Main;
import org.jbm.mycc.cc.lower.Lower;
import org.jbm.mycc.cc.lower.arch.X86_64SysV;
import org.jbm.mycc.cc.lower.tac.Instr;
import org.jbm.mycc.cc.lower.tac.Module;
import org.jbm.mycc.cc.lower.tac.TacInvariants;
import org.jbm.mycc.cc.lower.tac.TacWriter;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lowering goldens: every program of the typed corpus is lowered on its
 * target and the printed module must equal {@code src/test/resources/tac/<name>.tac}.
 * Every module passes {@link TacInvariants}. To regenerate after an
 * intended change, run with {@code -Dtac.update=true} and review the diff.
 */
class TacCorpusTest {

    static final Path DIR = Path.of("src/test/resources/tac");

    static Stream<Path> sources() throws IOException {
        return TypedCorpusTest.sources();
    }

    static Module lower(Path source) throws IOException {
        Types types = TypedCorpusTest.typesFor(source);
        TUnit unit = TypedCorpusTest.type(Files.readString(source), source.toString(), types);
        return Lower.lower(unit, types);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void loweredOutputMatchesTheGoldenFile(Path source) throws IOException {
        String actual = TacWriter.print(lower(source));
        Path golden = DIR.resolve(source.getFileName().toString().replaceAll("\\.c$", ".tac"));
        if (Boolean.getBoolean("tac.update")) Files.writeString(golden, actual);
        assertEquals(Files.readString(golden), actual, "lowered output of " + source.getFileName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void loweredModuleSatisfiesTheInvariants(Path source) throws IOException {
        assertTrue(TacInvariants.check(lower(source)) > 0, "the checker visited the module");
    }

    @Test
    void theCorpusCoversEveryInstructionKind() throws IOException {
        var seen = new java.util.HashSet<Class<?>>();
        var ops = new java.util.HashSet<String>();
        for (Path source : sources().toList()) {
            for (var f : lower(source).functions) for (var b : f.blocks) for (Instr i : b.instrs) {
                seen.add(i.getClass());
                if (i instanceof Instr.Bin bin) ops.add(bin.op().name());
                if (i instanceof Instr.Cmp cmp) ops.add(cmp.op().name());
                if (i instanceof Instr.Cvt cvt) ops.add(cvt.op().name());
            }
        }
        for (Class<?> kind : Instr.class.getPermittedSubclasses()) assertTrue(seen.contains(kind), "the corpus exercises " + kind.getSimpleName());
        for (var op : List.of("WADD", "WSUB", "WMUL", "ADD", "SUB", "MUL", "SDIV", "UDIV", "SREM", "UREM", "AND", "OR", "XOR",
                "SHL", "LSHR", "ASHR", "FADD", "FSUB", "FMUL", "FDIV", "EQ", "NE", "SLT", "SLE", "ULT", "ULE", "FEQ", "FNE", "FLT", "FLE",
                "I2F", "U2F", "F2I", "F2U")) {
            assertTrue(ops.contains(op), "the corpus exercises " + op.toLowerCase());
        }
    }

    @Test
    void theMainProgramLowers() {
        var types = new Types(X86_64SysV.INSTANCE);
        Module m = Lower.lower(TypedCorpusTest.type(Main.SOURCE, "<source>", types), types);
        assertTrue(TacInvariants.check(m) > 0);
        assertTrue(TacWriter.print(m).contains("define @main() -> i32 {"));
    }
}
