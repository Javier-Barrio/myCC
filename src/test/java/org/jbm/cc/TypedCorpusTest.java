package org.jbm.cc;

import org.jbm.cc.lower.arch.Ilp32;
import org.jbm.cc.lower.arch.X86_64SysV;
import org.jbm.cc.parse.ast.Decl;
import org.jbm.cc.cpp.BundledHeaders;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.sema.tast.TUnit;
import org.jbm.cc.sema.tast.TypedPrinter;
import org.jbm.cc.sema.tast.TypedTreeInvariants;
import org.jbm.cc.sema.types.Types;
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
 * End-to-end golden tests: every {@code src/test/resources/typed/*.c}
 * goes through the whole pipeline (lexing, macro expansion, phase 7,
 * parsing, desugaring, resolution, typing) and its printed typed unit
 * must equal the {@code .typed} file beside it. Each file exercises one
 * family of typed constructs, named in its first line. The typed tree
 * is also checked against the structural invariants of every node kind.
 * <p>
 * To regenerate an expectation after an intended change, run with
 * {@code -Dtyped.update=true} and review the diff.
 */
class TypedCorpusTest {

    static final Path DIR = Path.of("src/test/resources/typed");

    static Stream<Path> sources() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".c")).sorted().toList().stream();
        }
    }

    static Types typesFor(Path source) {
        return new Types(source.getFileName().toString().contains("ilp32") ? Ilp32.INSTANCE : X86_64SysV.INSTANCE);
    }

    static TUnit type(String source, String file, Types types) {
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, BundledHeaders.INSTANCE, file);
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(tokens))));
        return Typer.type(unit, Resolver.resolve(unit), types);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void typedOutputMatchesTheGoldenFile(Path source) throws IOException {
        Types types = typesFor(source);
        TUnit unit = type(Files.readString(source), source.toString(), types);
        String actual = TypedPrinter.print(unit) + "\n";
        Path golden = Path.of(source.toString().replaceAll("\\.c$", ".typed"));
        if (Boolean.getBoolean("typed.update")) Files.writeString(golden, actual);
        assertEquals(Files.readString(golden), actual, "typed output of " + source.getFileName());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    void typedTreeSatisfiesTheInvariants(Path source) throws IOException {
        Types types = typesFor(source);
        TUnit unit = type(Files.readString(source), source.toString(), types);
        int nodes = TypedTreeInvariants.check(unit, types);
        assertTrue(nodes > 0, "the checker visited the tree");
    }

    @Test
    void theCorpusCoversEveryTypedNodeKind() throws IOException {
        // Every record in TExpr and TStmt must print at least once across the
        // corpus, so a new node kind needs a program that exercises it. The one
        // exception is FuncDeref: `(*fp)(x)` collapses to the pointer (6.5.3.3),
        // so it never survives into a finished tree.
        var printed = new StringBuilder();
        for (Path source : sources().toList()) printed.append(Files.readString(Path.of(source.toString().replaceAll("\\.c$", ".typed"))));
        String all = printed.toString();
        for (String name : List.of("rv:", "decay:", "fdecay:", "int-to-int:", "int-to-float:", "float-to-int:", "float-to-float:",
                "to-bool:", "to-void:", "ptr-to-ptr:", "int-to-ptr:", "ptr-to-int:", "null:", "add:", "sub:", "mul:", "div:", "rem:",
                "bitand:", "bitor:", "bitxor:", "shl:", "shr:", "eq:", "ne:", "lt:", "le:", "gt:", "ge:", "and:", "or:", "neg:",
                "bitnot:", "not:", "deref:", "member:", "materialize:", "lit:", "addr:", "ptradd:", "ptrdiff:", "(call:", "(icall:",
                "assign:", "compound-assign:", "postfix-assign:", "(target:", "cond:", "comma:", "nullptr:", "&", "(local ",
                "(global ", "(extern ", "(string ", "(function ", "(if ", "(while ", "(do ", "(for ", "(switch ", "(cases ",
                "...", "default", "(label ", "(goto ", "(break ", "(continue ", "(return", "(init", "(block")) {
            assertTrue(all.contains(name), "the corpus exercises " + name);
        }
    }

    @Test
    void theMainProgramPassesTheInvariants() {
        var types = new Types(X86_64SysV.INSTANCE);
        TypedTreeInvariants.check(type(org.jbm.Main.SOURCE, "<source>", types), types);
    }
}
