package org.jbm.mycc.cc.cpp;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Path;
import java.nio.file.Files;
import org.jbm.mycc.cc.cpp.HeaderProvider;
import org.jbm.mycc.cc.backend.arch.Ilp32;
import org.jbm.mycc.cc.cpp.*;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.sema.Desugar;
import org.jbm.mycc.cc.sema.Resolver;
import org.jbm.mycc.cc.sema.Typer;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.tast.TypedPrinter;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void theCompilerHeadersAnswerTheLibraryHeadersProtocol() {
        String partial = typed("#define __need___va_list\n#include <stdarg.h>\n__gnuc_va_list a; int n = sizeof(__gnuc_va_list);\n", X64);
        assertTrue(partial.contains("(global n:int 24:int)"), partial);
        assertThrows(RuntimeException.class, () -> typed("#define __need___va_list\n#include <stdarg.h>\nva_list b;\n", X64), "only __gnuc_va_list was asked for");
        String full = typed("#define __need___va_list\n#include <stdarg.h>\n#include <stdarg.h>\nva_list b; int m = sizeof b;\n", X64);
        assertTrue(full.contains("(global m:int 24:int)"), full);
        String needs = typed("#define __need_size_t\n#include <stddef.h>\n#ifdef __need_size_t\nint still = 1;\n#endif\nsize_t s = 1;\n", X64);
        assertTrue(!needs.contains("still") && needs.contains("(global s:unsigned long "), needs);
        assertTrue(typed("#include <float.h>\nint d = DBL_DIG; double e = DBL_EPSILON;\n", X64).contains("(global d:int 15:int)"));
        assertTrue(typed("#include <iso646.h>\nint x = 1 and not 0;\n", X64).contains("(global x:int 1:int)"));
    }

    @Test
    void theSystemProviderTakesTheCompilerHeadersFromTheBundle() {
        HeaderProvider system = HeaderProvider.system(List.of());
        assertEquals("<stddef.h>", system.find("stddef.h", false, "x.c").orElseThrow().name());
        assertEquals("<stdarg.h>", system.find("stdarg.h", false, "x.c").orElseThrow().name());
        assumeTrue(Files.isRegularFile(Path.of("/usr/include/stdio.h")), "no system headers");
        assertTrue(system.find("stdio.h", false, "x.c").orElseThrow().name().startsWith("/usr/include"), "the library's from the system");
        assertTrue(system.find("sys/types.h", false, "x.c").isPresent());
    }

    @Test
    void wideCharacters() {
        String source = "#include <wchar.h>\nwchar_t s[] = L\"h\u20ac\"; size_t n = sizeof s; wint_t e = WEOF;\n";
        String x64 = typed(source, X64);
        assertTrue(x64.contains("(global s:int [3] (init (0 104:int) (4 8364:int)))"), x64);
        assertTrue(x64.contains("(global n:unsigned long 12:unsigned long)"), x64);
        assertTrue(x64.contains("(global e:unsigned int "), x64);
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
