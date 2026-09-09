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

    // ---- 3: constants and variables --------------------------------------------------------

    @Test
    void constantsAreMovsInTheClassOfTheirType() {
        assertEquals("  i32 %t0\n.entry:\n  mov %t0, 5", expr("", "5"));
        assertEquals("  i64 %t0\n.entry:\n  mov %t0, 5", expr("", "5L"));
        assertEquals("  u32 %t0\n.entry:\n  mov %t0, 4294967295", expr("", "4294967295u"));
        assertEquals("  f64 %t0\n.entry:\n  mov %t0, 1.5", expr("", "1.5"));
        assertEquals("  f32 %t0\n.entry:\n  mov %t0, 1.5", expr("", "1.5f"));
        assertEquals("  i32 %t0\n.entry:\n  mov %t0, 97", expr("", "'a'"));
        assertEquals("  ptr %t0\n.entry:\n  mov %t0, 0", expr("", "nullptr"));
    }

    @Test
    void aLocalIsUsedDirectly() {
        assertEquals("  i32 %x\n.entry:", body("", "int x; x;"));
        assertEquals("  i32 %x\n  i32 %t0\n.entry:\n  mov %t0, 1\n  mov %x, %t0", body("", "int x; x = 1;"));
        assertEquals("  i32 %x\n  i32 %t0\n.entry:\n  mov %t0, 3\n  mov %x, %t0", body("", "int x = 3;"));
        assertEquals("  f64 %d\n  i32 %x\n  f64 %t0\n.entry:\n  mov %t0, 2.0\n  mov %d, %t0", body("", "double d; int x; d = 2.0;"));
    }

    // ---- 4: globals, loads and stores --------------------------------------------------------

    @Test
    void aGlobalIsLoadedThroughItsAddress() {
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @g\n  %t1 = load.s32 %t0", expr("int g;", "g"));
        assertEquals("  ptr %t0\n  i8 %t1\n.entry:\n  %t0 = addrof @c\n  %t1 = load.s8 %t0", expr("char c;", "c"));
        assertEquals("  ptr %t0\n  u16 %t1\n.entry:\n  %t0 = addrof @us\n  %t1 = load.u16 %t0", expr("unsigned short us;", "us"));
        assertEquals("  ptr %t0\n  i64 %t1\n.entry:\n  %t0 = addrof @l\n  %t1 = load.s64 %t0", expr("long l;", "l"));
        assertEquals("  ptr %t0\n  u8 %t1\n.entry:\n  %t0 = addrof @b\n  %t1 = load.u8 %t0", expr("bool b;", "b"));
        assertEquals("  ptr %t0\n  f32 %t1\n.entry:\n  %t0 = addrof @f\n  %t1 = load.f32 %t0", expr("float f;", "f"));
        assertEquals("  ptr %t0\n  f64 %t1\n.entry:\n  %t0 = addrof @d\n  %t1 = load.f64 %t0", expr("double d;", "d"));
        assertEquals("  ptr %t0\n  ptr %t1\n.entry:\n  %t0 = addrof @p\n  %t1 = load.u64 %t0", expr("int *p;", "p"));
        assertEquals("  ptr %t0\n  ptr %t1\n.entry:\n  %t0 = addrof @p\n  %t1 = load.u32 %t0", exprOn(ILP32, "int *p;", "p"));
    }

    @Test
    void aGlobalIsStoredThroughItsAddress() {
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @g\n  mov %t1, 1\n  store.32 %t0, %t1", expr("int g;", "g = 1"));
        assertEquals("  ptr %t0\n  f32 %t1\n.entry:\n  %t0 = addrof @f\n  mov %t1, 1.0\n  store.f32 %t0, %t1", expr("float f;", "f = 1.0f"));
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @v\n  %t1 = load.s32 %t0 volatile", expr("volatile int v;", "v"));
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @v\n  mov %t1, 1\n  store.32 %t0, %t1 volatile", expr("volatile int v;", "v = 1"));
    }

    @Test
    void throughAPointer() {
        assertEquals("""
                define @f(ptr %p) -> void {
                  i32 %t0
                  i32 %t1
                .entry:
                  %t0 = load.s32 %p
                  mov %t1, 1
                  store.32 %p, %t1
                  ret
                }
                """, function("void f(int *p) { *p; *p = 1; }"));
        assertEquals("  ptr %t0\n  ptr %t1\n  i8 %t2\n.entry:\n  %t0 = addrof @p\n  %t1 = load.u64 %t0\n  %t2 = load.s8 %t1", expr("char *p;", "*p"));
    }

    @Test
    void globalsAreEmitted() {
        assertEquals("""
                global @a : i32 align 4
                global @b : i32 align 4 = { 0 : i32 7 }
                global internal @c : f64 align 8 = { 0 : f64 2.5 }
                global @k : i32 align 4 readonly = { 0 : i32 1 }
                global @p : ptr align 8 = { 0 : addr @b }
                global @q : ptr align 8 = { 0 : u64 0 }
                global @arr : [3 x i32] align 4 = { 0 : i32 1, 4 : i32 2 }
                declare @e : i32
                """, unit("int a; int b = 7; static double c = 2.5; const int k = 1; int *p = &b; int *q = 0; int arr[3] = {1, 2}; extern int e;"));
    }

    // ---- 5: integer conversions ----------------------------------------------------------------

    /** The instructions of {@code body} over locals {@code decls}, without the declarations. */
    static String instrs(String decls, String body) {
        return afterEntry(body("", decls + " " + body));
    }

    static String instrsOn(Types types, String decls, String body) {
        return afterEntry(bodyOn(types, "", decls + " " + body));
    }

    private static String afterEntry(String body) {
        int at = body.indexOf(".entry:");
        String rest = body.substring(at + ".entry:".length());
        return rest.startsWith("\n") ? rest.substring(1) : rest;
    }

    @Test
    void integerConversionsWithinAClass() {
        assertEquals("  mov %t0, %s\n  %t0 = and %t0, 65535", instrs("short s;", "(unsigned short) s;"));
        assertEquals("  mov %t0, %sc\n  %t0 = and %t0, 65535", instrs("signed char sc;", "(unsigned short) sc;"));
        assertEquals("  mov %t0, %uc", instrs("unsigned char uc;", "(short) uc;"));
        assertEquals("  mov %t0, %uc", instrs("unsigned char uc;", "(int) uc;"));
        assertEquals("  mov %t0, %i\n  %t0 = shl %t0, 24\n  %t0 = ashr %t0, 24", instrs("int i;", "(char) i;"));
        assertEquals("  mov %t0, %i\n  %t0 = and %t0, 255", instrs("int i;", "(unsigned char) i;"));
        assertEquals("  mov %t0, %i", instrs("int i;", "(unsigned) i;"));
        assertEquals("  mov %t0, %us\n  %t0 = shl %t0, 16\n  %t0 = ashr %t0, 16", instrs("unsigned short us;", "(short) us;"));
        assertEquals("  mov %t0, %b", instrs("bool b;", "(int) b;"));
    }

    @Test
    void integerConversionsAcrossClasses() {
        assertEquals("  mov %t0, %i\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32", instrs("int i;", "(long) i;"));
        assertEquals("  mov %t0, %u", instrs("unsigned u;", "(long) u;"));
        assertEquals("  mov %t0, %i\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32", instrs("int i;", "(unsigned long) i;"));
        assertEquals("  mov %t0, %c\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32", instrs("char c;", "(long) c;"));
        assertEquals("  mov %t0, %l", instrs("long l;", "(int) l;"));
        assertEquals("  mov %t0, %l", instrs("long l;", "(unsigned) l;"));
        assertEquals("  mov %t0, %l\n  %t0 = shl %t0, 24\n  %t0 = ashr %t0, 24", instrs("long l;", "(char) l;"));
        assertEquals("  mov %t0, %l\n  %t0 = and %t0, 255", instrs("long l;", "(unsigned char) l;"));
    }

    @Test
    void integerConversionsOnTheOtherTarget() {
        assertEquals("", instrsOn(ILP32, "int i;", "(long) i;"));
        assertEquals("  mov %t0, %i\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32", instrsOn(ILP32, "int i;", "(long long) i;"));
        assertEquals("  mov %t0, %ll", instrsOn(ILP32, "long long ll;", "(long) ll;"));
    }

    // ---- 6: floating conversions and ToBool ------------------------------------------------------

    @Test
    void floatingConversions() {
        assertEquals("  %t0 = i2f %i", instrs("int i;", "(double) i;"));
        assertEquals("  %t0 = u2f %u", instrs("unsigned u;", "(double) u;"));
        assertEquals("  %t0 = i2f %l", instrs("long l;", "(float) l;"));
        assertEquals("  %t0 = i2f %c", instrs("char c;", "(double) c;"));
        assertEquals("  %t0 = f2i %d", instrs("double d;", "(int) d;"));
        assertEquals("  %t0 = f2u %d", instrs("double d;", "(unsigned long) d;"));
        assertEquals("  %t0 = f2u %d\n  %t0 = and %t0, 255", instrs("double d;", "(unsigned char) d;"));
        assertEquals("  %t0 = f2i %f\n  %t0 = shl %t0, 16\n  %t0 = ashr %t0, 16", instrs("float f;", "(short) f;"));
        assertEquals("  %t0 = fcvt %d", instrs("double d;", "(float) d;"));
        assertEquals("  %t0 = fcvt %f", instrs("float f;", "(double) f;"));
        assertEquals("  f64 %d\n  f80 %t0\n.entry:\n  %t0 = fcvt %d", body("", "double d; (long double) d;"));
    }

    @Test
    void toBool() {
        assertEquals("  %t0 = ne %i, 0\n  mov %b, %t0", instrs("bool b; int i;", "b = i;"));
        assertEquals("  %t0 = ne %p, 0\n  mov %b, %t0", instrs("bool b; int *p;", "b = p;"));
        assertEquals("  %t0 = fne %d, 0.0\n  mov %b, %t0", instrs("bool b; double d;", "b = d;"));
        assertEquals("  u8 %b\n  i64 %l\n  u8 %t0\n.entry:\n  %t0 = ne %l, 0\n  mov %b, %t0", body("", "bool b; long l; b = l;"));
    }

    // ---- 7: pointer conversions -------------------------------------------------------------------

    @Test
    void pointerConversions() {
        assertEquals("  mov %t0, %p", instrs("int *p;", "(long) p;"));
        assertEquals("  mov %t0, %p", instrs("int *p;", "(unsigned long) p;"));
        assertEquals("  mov %t0, %p", instrs("int *p;", "(int) p;"));
        assertEquals("  mov %t0, %p\n  %t0 = and %t0, 255", instrs("int *p;", "(unsigned char) p;"));
        assertEquals("  mov %t0, 5\n  mov %t1, %t0\n  %t1 = shl %t1, 32\n  %t1 = ashr %t1, 32", instrs("", "(void *) 5;"));
        assertEquals("  mov %t0, %u", instrs("unsigned u;", "(void *) u;"));
        assertEquals("  mov %t0, %l", instrs("long l;", "(char *) l;"));
        assertEquals("", instrs("void *vp;", "(int *) vp;"));
        assertEquals("  mov %t0, 0\n  mov %p, %t0", instrs("int *p;", "p = 0;"));
        assertEquals("  i32 %i\n  ptr %t0\n.entry:\n  mov %t0, %i\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32", body("", "int i; (int *) i;"));
    }

    @Test
    void pointerConversionsOnTheOtherTarget() {
        assertEquals("  mov %t0, %p", instrsOn(ILP32, "int *p;", "(long) p;"));
        assertEquals("  mov %t0, %p", instrsOn(ILP32, "int *p;", "(long long) p;"));
        assertEquals("  mov %t0, %i", instrsOn(ILP32, "int i;", "(int *) i;"));
    }

    // ---- 8: arithmetic -----------------------------------------------------------------------------

    @Test
    void arithmeticIsWrappingForUnsignedPlainForSignedFloatingForFloating() {
        assertEquals("  %t0 = add %a, %b", instrs("int a, b;", "a + b;"));
        assertEquals("  %t0 = wadd %a, %b", instrs("unsigned a, b;", "a + b;"));
        assertEquals("  %t0 = sub %a, %b", instrs("long a, b;", "a - b;"));
        assertEquals("  %t0 = wmul %a, %b", instrs("unsigned long a, b;", "a * b;"));
        assertEquals("  %t0 = fadd %a, %b", instrs("double a, b;", "a + b;"));
        assertEquals("  %t0 = fmul %a, %b", instrs("float a, b;", "a * b;"));
        assertEquals("  %t0 = fdiv %a, %b", instrs("double a, b;", "a / b;"));
        assertEquals("  %t0 = sdiv %a, %b\n  %t1 = srem %a, %b", instrs("int a, b;", "a / b; a % b;"));
        assertEquals("  %t0 = udiv %a, %b\n  %t1 = urem %a, %b", instrs("unsigned a, b;", "a / b; a % b;"));
        assertEquals("  %t0 = and %a, %b\n  %t1 = or %a, %b\n  %t2 = xor %a, %b", instrs("int a, b;", "a & b; a | b; a ^ b;"));
    }

    @Test
    void promotedOperandsAreConvertedFirst() {
        assertEquals("  mov %t0, %c\n  mov %t1, %s\n  %t2 = add %t0, %t1", instrs("char c; short s;", "c + s;"));
        assertEquals("  mov %t0, %i\n  %t0 = shl %t0, 32\n  %t0 = ashr %t0, 32\n  %t1 = add %t0, %l", instrs("int i; long l;", "i + l;"));
        assertEquals("  %t0 = i2f %i\n  %t1 = fadd %t0, %d", instrs("int i; double d;", "i + d;"));
    }

    @Test
    void bitPreciseArithmeticIsMadeCanonical() {
        assertEquals("  %t0 = add %a, %b\n  %t0 = shl %t0, 25\n  %t0 = ashr %t0, 25", instrs("_BitInt(7) a, b;", "a + b;"));
        assertEquals("  %t0 = wmul %a, %b\n  %t0 = and %t0, 4095", instrs("unsigned _BitInt(12) a, b;", "a * b;"));
    }
}
