package org.jbm.cc.cpp;

import org.jbm.cc.arch.Ilp32;
import org.jbm.cc.arch.X86_64SysV;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.tast.TUnit;
import org.jbm.cc.tast.TypedPrinter;
import org.jbm.cc.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every bundled header is found, goes through the whole front end on both targets, and can be included twice. */
class BundledHeadersTest {

    static final Types X64 = new Types(X86_64SysV.INSTANCE);
    static final Types ILP32 = new Types(Ilp32.INSTANCE);

    static Stream<String> names() {
        return BundledHeaders.NAMES.stream();
    }

    static String typed(String source, Types types) {
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, BundledHeaders.INSTANCE, "test.c");
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(tokens))));
        TUnit typed = Typer.type(unit, Resolver.resolve(unit), types);
        return TypedPrinter.print(typed);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    void isFoundInEitherForm(String name) {
        Header angled = BundledHeaders.INSTANCE.find(name, false, "main.c").orElseThrow();
        Header quoted = BundledHeaders.INSTANCE.find(name, true, "main.c").orElseThrow();
        assertEquals("<" + name + ">", angled.name());
        assertEquals(angled, quoted);
        assertTrue(angled.text().contains("#ifndef _"), name + " is guarded");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("names")
    void typesOnBothTargetsIncludedTwice(String name) {
        String source = "#include <" + name + ">\n#include \"" + name + "\"\nint end;\n";
        assertTrue(typed(source, X64).contains("(global end:int)"));
        assertTrue(typed(source, ILP32).contains("(global end:int)"));
    }

    @Test
    void unknownNameIsNotBundled() {
        assertTrue(BundledHeaders.INSTANCE.find("unistd.h", false, "main.c").isEmpty());
    }

    @Test
    void allHeadersTogether() {
        StringBuilder source = new StringBuilder();
        for (String name : BundledHeaders.NAMES) {
            source.append("#include <").append(name).append(">\n");
        }
        source.append("int end;\n");
        assertTrue(typed(source.toString(), X64).contains("(global end:int)"));
        assertTrue(typed(source.toString(), ILP32).contains("(global end:int)"));
    }

    @Test
    void targetDependentTypesFollowTheTarget() {
        String source = "#include <stddef.h>\n#include <stdint.h>\nsize_t s = sizeof(int); ptrdiff_t d = 0; intptr_t i = 0; uintptr_t u = 0;\n";
        String x64 = typed(source, X64);
        assertTrue(x64.contains("(global s:unsigned long "), x64);
        assertTrue(x64.contains("(global d:long "), x64);
        assertTrue(x64.contains("(global i:long "), x64);
        assertTrue(x64.contains("(global u:unsigned long "), x64);
        String ilp32 = typed(source, ILP32);
        assertTrue(ilp32.contains("(global s:unsigned int "), ilp32);
        assertTrue(ilp32.contains("(global d:int "), ilp32);
        assertTrue(ilp32.contains("(global i:int "), ilp32);
        assertTrue(ilp32.contains("(global u:unsigned int "), ilp32);
    }

    @Test
    void limitsAndMacrosEvaluate() {
        String source = "#include <limits.h>\n#include <stdint.h>\n#include <stdio.h>\n"
                + "int m = INT_MAX; int n = INT_MIN; unsigned u = UINT_MAX; long long ll = LLONG_MIN;\n"
                + "int big = INT64_MAX > 0; int eof = EOF; void *np = NULL; int sixty4 = (int) INT64_C(64);\n";
        String x64 = typed(source, X64);
        assertTrue(x64.contains("(global m:int 2147483647:int)"), x64);
        assertTrue(x64.contains("(global n:int -2147483648:int)"), x64);
        assertTrue(x64.contains("(global u:unsigned int 4294967295:unsigned int)"), x64);
        assertTrue(x64.contains("(global ll:long long -9223372036854775808:long long)"), x64);
        assertTrue(x64.contains("(global big:int 1:int)"), x64);
        assertTrue(x64.contains("(global eof:int -1:int)"), x64);
        assertTrue(x64.contains("(global sixty4:int 64:int)"), x64);
    }
}
