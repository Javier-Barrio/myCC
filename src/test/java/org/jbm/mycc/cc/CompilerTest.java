package org.jbm.mycc.cc;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.parse.ParseException;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.AstPrinter;
import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.TacWriter;
import org.jbm.mycc.cc.sema.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one entry, and the script mode: file-scope statements into {@code .file}. */
class CompilerTest {

    static final Types X64 = new Types(X86_64SysV.INSTANCE);

    static org.jbm.mycc.cc.Compiler.Compiled script(String source) {
        return org.jbm.mycc.cc.Compiler.compileScript(source, BundledHeaders.INSTANCE, "line", X64);
    }

    static List<String> functionNames(org.jbm.mycc.cc.Compiler.Compiled c) {
        return c.tac().functions.stream().map(f -> f.name).toList();
    }

    @Test
    void theOldKeywordSpellingsArePredefined() {
        Compiler.Compiled c = Compiler.compile("_Static_assert(_Alignof(int) == 4, \"\"); _Bool b = 1; _Alignas(16) int a;",
                BundledHeaders.INSTANCE, "t.c", X64);
        String tac = TacWriter.print(c.tac());
        assertTrue(tac.contains("@b : u8 align 1 = { 0 : u8 1 }"), tac);
        assertTrue(tac.contains("@a : i32"), tac);
    }

    @Test
    void theTargetIsPredefined() {
        String p = Compiler.predefined(X64);
        assertTrue(p.contains("#define __LP64__ 1\n"), p);
        assertTrue(p.contains("#define __x86_64__ 1\n"), p);
        assertTrue(p.contains("#define __SIZEOF_LONG__ 8\n"), p);
        assertTrue(p.contains("#define __STDC_VERSION__ 202311L\n"), p);
        String c17 = Compiler.predefined(new Types(X86_64SysV.INSTANCE, org.jbm.mycc.cc.sema.types.Std.C17));
        assertTrue(c17.contains("#define __STDC_VERSION__ 201710L\n"), c17);
        Compiler.Compiled c = Compiler.compile("#if defined(__LP64__) && __SIZEOF_POINTER__ == 8\nint ok = 1;\n#else\nint ok = 0;\n#endif",
                BundledHeaders.INSTANCE, "t.c", X64);
        assertTrue(TacWriter.print(c.tac()).contains("@ok : i32 align 4 = { 0 : i32 1 }"));
        String ilp = Compiler.predefined(new Types(org.jbm.mycc.cc.backend.arch.Ilp32.INSTANCE));
        assertTrue(ilp.contains("#define __ILP32__ 1\n") && !ilp.contains("__LP64__"), ilp);
    }

    @Test
    void compileIsTheWholePipeline() {
        org.jbm.mycc.cc.Compiler.Compiled c = org.jbm.mycc.cc.Compiler.compile("int sq(int x) { return x * x; }", BundledHeaders.INSTANCE, "t.c", X64);
        assertEquals(1, c.ast().size());
        assertEquals(1, c.typed().functions().size());
        assertEquals(List.of("sq"), functionNames(c));
        assertTrue(TacWriter.print(c.tac()).contains("define @sq(i32 %x) -> i32"));
    }

    @Test
    void aStatementAtFileScopeIsRejectedOutsideScriptMode() {
        assertThrows(ParseException.class, () -> org.jbm.mycc.cc.Compiler.compile("int x; x = 1;", BundledHeaders.INSTANCE, "t.c", X64));
    }

    @Test
    void declarationsOnlyGiveNoFileFunction() {
        assertEquals(List.of(), functionNames(script("int x = 1;")));
        assertEquals(List.of("f"), functionNames(script("#include <stdio.h>\nint f(void) { return 1; }")));
    }

    @Test
    void fileScopeStatementsBecomeTheFileFunction() {
        org.jbm.mycc.cc.Compiler.Compiled c = script("int x = 1; x++; if (x > 1) { x = 10; } x");
        assertEquals(List.of(Parser.FILE_FUNCTION), functionNames(c));
        Function file = c.tac().functions.get(0);
        assertEquals("() -> void", file.sig.spelling());
        String ast = AstPrinter.print(c.ast());
        assertTrue(ast.contains("(fundef .file (fn void ())"), ast);
        assertTrue(ast.contains("(block (expr (post++ x)) (if"), ast);
    }

    @Test
    void theLastStatementNeedsNoSemicolon() {
        assertEquals(List.of(".file"), functionNames(script("int x = 1;\nx + 1")));
        assertEquals(List.of("f", ".file"), functionNames(script("int f(void) { return 2; }\nf()")));
        assertThrows(ParseException.class, () -> script("int x = 1; x + 1 x"), "only before the end of input");
    }

    @Test
    void statementsAndDeclarationsInterleave() {
        org.jbm.mycc.cc.Compiler.Compiled c = script("int a = 1; a += 2; int b = 5; b = a; b++; struct P { int x; } p; p.x = b;");
        assertEquals(List.of(".file"), functionNames(c));
        assertEquals(3, c.typed().globals().size(), "a, b, p");
        String ast = AstPrinter.print(c.ast());
        assertTrue(ast.indexOf("(fundef .file") > ast.indexOf("(decl (p"), "the file function comes last");
    }

    @Test
    void labelsAndBlocksAtFileScope() {
        org.jbm.mycc.cc.Compiler.Compiled c = script("int n = 0; again: n++; if (n < 3) goto again; { int local = n; n = local * 2; }");
        assertEquals(List.of(".file"), functionNames(c));
    }

    @Test
    void dollarNamesCompile() {
        Compiler.Compiled c = script("int $1 = 5; typeof_unqual(($1 * 2)) $2; $2 = $1 * 2;");
        assertEquals(List.of("$1", "$2"), c.tac().globals.stream().map(g -> g.name()).toList());
        assertTrue(TacWriter.print(c.tac()).contains("addrof @$2"));
    }
}
