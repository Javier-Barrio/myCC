package org.jbm.cc.lower;

import org.jbm.cc.arch.Ilp32;
import org.jbm.cc.arch.X86_64SysV;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.tac.Module;
import org.jbm.cc.tac.TacInvariants;
import org.jbm.cc.tac.TacWriter;
import org.jbm.cc.tast.TUnit;
import org.jbm.cc.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lowering, node family by node family: each test states the exact TAC
 * text for a small input. Every module built here passes
 * {@link TacInvariants}.
 */
class LowerTest {

    static final Types X64 = new Types(X86_64SysV.INSTANCE);
    static final Types ILP32 = new Types(Ilp32.INSTANCE);

    static Module lower(String source, Types types) {
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source)))));
        TUnit typed = Typer.type(unit, Resolver.resolve(unit), types);
        Module m = Lower.lower(typed, types);
        TacInvariants.check(m);
        return m;
    }

    /** The whole module, without its target line. */
    static String unit(String source) {
        return unitOn(X64, source);
    }

    static String unitOn(Types types, String source) {
        String text = TacWriter.print(lower(source, types));
        return text.substring(text.indexOf('\n') + 1);
    }

    /** The last function of the unit. */
    static String function(String source) {
        return functionOn(X64, source);
    }

    static String functionOn(Types types, String source) {
        Module m = lower(source, types);
        return TacWriter.print(m.functions.get(m.functions.size() - 1));
    }

    /** The body of {@code void probe__(void) { body }} after {@code decls}: declarations and blocks, without the final ret. */
    static String body(String decls, String body) {
        return bodyOn(X64, decls, body);
    }

    static String bodyOn(Types types, String decls, String body) {
        String f = functionOn(types, decls + "\nvoid probe__(void) { " + body + " }");
        String inner = f.substring(f.indexOf("{\n") + 2, f.lastIndexOf("}\n"));
        if (inner.endsWith("  ret\n")) inner = inner.substring(0, inner.length() - "  ret\n".length());
        return inner.stripTrailing();
    }

    /** {@code src} as an expression statement in a function after {@code decls}. */
    static String expr(String decls, String src) {
        return body(decls, src + ";");
    }

    static String exprOn(Types types, String decls, String src) {
        return bodyOn(types, decls, src + ";");
    }

    // ---- 1: the skeleton ---------------------------------------------------------------------

    @Test
    void theTargetLineNamesTheTarget() {
        assertTrue(TacWriter.print(lower("void f(void) {}", X64)).startsWith("target x86_64-sysv\n"));
        assertTrue(TacWriter.print(lower("void f(void) {}", ILP32)).startsWith("target ilp32\n"));
    }

    @Test
    void anEmptyFunction() {
        assertEquals("""
                define @f() -> void {
                .entry:
                  ret
                }
                """, unit("void f(void) {}"));
    }

    @Test
    void parametersAndLocalsAreDeclaredWithTheirTypes() {
        assertEquals("""
                define @f(i32 %a, f64 %b, ptr %p, u8 %flag) -> i32 {
                  i32 %x
                  i8 %c
                  u16 %us
                  i64 %l
                  f32 %fl
                  f80 %ld
                  i32 %e
                .entry:
                  trap "end of non-void function"
                }
                """, function("enum E { A }; int f(int a, double b, char *p, bool flag) { int x; char c; unsigned short us; long l; float fl; long double ld; enum E e; }"));
    }

    @Test
    void shadowedAndVolatileLocals() {
        assertEquals("""
                define @f() -> void {
                  i32 %x
                  i32 %x.2
                  volatile i32 %v
                .entry:
                  ret
                }
                """, function("void f(void) { int x; { int x; volatile int v; } }"));
    }

    @Test
    void theEndOfTheBody() {
        assertEquals("""
                define @main() -> i32 {
                .entry:
                  ret 0
                }
                """, function("int main(void) {}"));
        assertEquals("""
                define internal @g(i32 %n, ...) -> void {
                .entry:
                  ret
                }
                """, function("static void g(int n, ...) {}"));
    }

    @Test
    void structTypesAreDefinedInTheModule() {
        assertEquals("""
                type %P = { i32 @0, i8 @4 } size 8 align 4
                type %Q = { %P @0, u64 @8 } size 16 align 8
                type %anon.1 = { i32 @0, i32 @0 } size 4 align 4
                define @f(%P %p, %Q %q) -> void {
                  %anon.1 %u
                .entry:
                  ret
                }
                """, unit("struct P { int x; char c; }; struct Q { struct P p; unsigned long n; }; void f(struct P p, struct Q q) { union { int a; int b; } u; }"));
    }

    @Test
    void theOtherTargetHasOtherWidths() {
        assertEquals("""
                define @f(i32 %l, ptr %p) -> void {
                  u8 %c
                  i64 %ll
                .entry:
                  ret
                }
                """, functionOn(ILP32, "void f(long l, int *p) { char c; long long ll; }"));
    }
}
