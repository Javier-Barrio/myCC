package org.jbm.mycc.cc;

import org.jbm.mycc.cc.Compiler;
import org.jbm.mycc.cc.backend.lower.arch.X86_64SysV;
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
