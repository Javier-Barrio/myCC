package org.jbm.mycc.cc.backend.lower;

import org.jbm.mycc.cc.backend.arch.Ilp32;
import org.jbm.mycc.cc.backend.lower.Lower;
import org.jbm.mycc.cc.backend.arch.X86_64SysV;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.cpp.BundledHeaders;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.sema.Desugar;
import org.jbm.mycc.cc.sema.Resolver;
import org.jbm.mycc.cc.sema.Typer;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.TacInvariants;
import org.jbm.mycc.cc.backend.lower.tac.TacWriter;
import org.jbm.mycc.cc.sema.tast.TUnit;
import org.jbm.mycc.cc.sema.types.Types;
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
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, BundledHeaders.INSTANCE, "test.c");
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(tokens))));
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
                  u32 %e
                .entry:
                  ret 0
                }
                """, function("enum E { A }; int f(int a, double b, char *p, bool flag) { int x; char c; unsigned short us; long l; float fl; long double ld; enum E e; }"));
    }

    @Test
    void fallingOffTheEndYieldsAZero() {
        assertTrue(function("double f(void) { }").contains("  ret 0.0\n"), "a floating zero for a floating result");
        assertTrue(function("int *f(void) { }").contains("  ret 0\n"), "a null pointer for a pointer result");
        assertTrue(function("struct S { int a; }; struct S f(void) { }").contains("trap \"end of non-void function\""), "an aggregate has no zero");
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
        assertEquals("  i32 %t0\n.entry:\n  mov.s32 %t0, 5", expr("", "5"));
        assertEquals("  i64 %t0\n.entry:\n  mov.s64 %t0, 5", expr("", "5L"));
        assertEquals("  u32 %t0\n.entry:\n  mov.u32 %t0, 4294967295", expr("", "4294967295u"));
        assertEquals("  f64 %t0\n.entry:\n  mov.64 %t0, 1.5", expr("", "1.5"));
        assertEquals("  f32 %t0\n.entry:\n  mov.32 %t0, 1.5", expr("", "1.5f"));
        assertEquals("  i32 %t0\n.entry:\n  mov.s32 %t0, 97", expr("", "'a'"));
        assertEquals("  ptr %t0\n.entry:\n  mov.u64 %t0, 0", expr("", "nullptr"));
    }

    @Test
    void aLocalIsUsedDirectly() {
        assertEquals("  i32 %x\n.entry:", body("", "int x; x;"));
        assertEquals("  i32 %x\n  i32 %t0\n.entry:\n  mov.s32 %t0, 1\n  mov.s32 %x, %t0", body("", "int x; x = 1;"));
        assertEquals("  i32 %x\n  i32 %t0\n.entry:\n  mov.s32 %t0, 3\n  mov.s32 %x, %t0", body("", "int x = 3;"));
        assertEquals("  f64 %d\n  i32 %x\n  f64 %t0\n.entry:\n  mov.64 %t0, 2.0\n  mov.64 %d, %t0", body("", "double d; int x; d = 2.0;"));
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
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @g\n  mov.s32 %t1, 1\n  store.32 %t0, %t1", expr("int g;", "g = 1"));
        assertEquals("  ptr %t0\n  f32 %t1\n.entry:\n  %t0 = addrof @f\n  mov.32 %t1, 1.0\n  store.f32 %t0, %t1", expr("float f;", "f = 1.0f"));
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @v\n  %t1 = load.s32 %t0 volatile", expr("volatile int v;", "v"));
        assertEquals("  ptr %t0\n  i32 %t1\n.entry:\n  %t0 = addrof @v\n  mov.s32 %t1, 1\n  store.32 %t0, %t1 volatile", expr("volatile int v;", "v = 1"));
    }

    @Test
    void throughAPointer() {
        assertEquals("""
                define @f(ptr %p) -> void {
                  i32 %t0
                  i32 %t1
                .entry:
                  %t0 = load.s32 %p
                  mov.s32 %t1, 1
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
        assertEquals("  mov.u16 %t0, %s", instrs("short s;", "(unsigned short) s;"));
        assertEquals("  mov.u16 %t0, %sc", instrs("signed char sc;", "(unsigned short) sc;"));
        assertEquals("", instrs("unsigned char uc;", "(short) uc;"));
        assertEquals("", instrs("unsigned char uc;", "(int) uc;"));
        assertEquals("  mov.s8 %t0, %i", instrs("int i;", "(char) i;"));
        assertEquals("  mov.u8 %t0, %i", instrs("int i;", "(unsigned char) i;"));
        assertEquals("  mov.u32 %t0, %i", instrs("int i;", "(unsigned) i;"));
        assertEquals("  mov.s16 %t0, %us", instrs("unsigned short us;", "(short) us;"));
        assertEquals("", instrs("bool b;", "(int) b;"));
    }

    @Test
    void integerConversionsAcrossClasses() {
        assertEquals("", instrs("int i;", "(long) i;"));
        assertEquals("", instrs("unsigned u;", "(long) u;"));
        assertEquals("", instrs("int i;", "(unsigned long) i;"));
        assertEquals("", instrs("char c;", "(long) c;"));
        assertEquals("  mov.s32 %t0, %l", instrs("long l;", "(int) l;"));
        assertEquals("  mov.u32 %t0, %l", instrs("long l;", "(unsigned) l;"));
        assertEquals("  mov.s8 %t0, %l", instrs("long l;", "(char) l;"));
        assertEquals("  mov.u8 %t0, %l", instrs("long l;", "(unsigned char) l;"));
    }

    @Test
    void integerConversionsOnTheOtherTarget() {
        assertEquals("", instrsOn(ILP32, "int i;", "(long) i;"));
        assertEquals("", instrsOn(ILP32, "int i;", "(long long) i;"));
        assertEquals("  mov.s32 %t0, %ll", instrsOn(ILP32, "long long ll;", "(long) ll;"));
    }

    // ---- 6: floating conversions and ToBool ------------------------------------------------------

    @Test
    void floatingConversions() {
        assertEquals("  %t0 = i2f.64 %i", instrs("int i;", "(double) i;"));
        assertEquals("  %t0 = u2f.64 %u", instrs("unsigned u;", "(double) u;"));
        assertEquals("  %t0 = i2f.32 %l", instrs("long l;", "(float) l;"));
        assertEquals("  %t0 = i2f.64 %c", instrs("char c;", "(double) c;"));
        assertEquals("  %t0 = f2i.64 %d", instrs("double d;", "(int) d;"));
        assertEquals("  %t0 = f2u.64 %d", instrs("double d;", "(unsigned long) d;"));
        assertEquals("  %t0 = f2u.64 %d", instrs("double d;", "(unsigned char) d;"));
        assertEquals("  %t0 = f2i.32 %f", instrs("float f;", "(short) f;"));
        assertEquals("  mov.32 %t0, %d", instrs("double d;", "(float) d;"));
        assertEquals("", instrs("float f;", "(double) f;"));
        assertEquals("  f64 %d\n.entry:", body("", "double d; (long double) d;"));
    }

    @Test
    void toBool() {
        assertEquals("  %t0 = ne %i, 0\n  mov.u8 %b, %t0", instrs("bool b; int i;", "b = i;"));
        assertEquals("  %t0 = ne %p, 0\n  mov.u8 %b, %t0", instrs("bool b; int *p;", "b = p;"));
        assertEquals("  %t0 = fne %d, 0.0\n  mov.u8 %b, %t0", instrs("bool b; double d;", "b = d;"));
        assertEquals("  u8 %b\n  i64 %l\n  u8 %t0\n.entry:\n  %t0 = ne %l, 0\n  mov.u8 %b, %t0", body("", "bool b; long l; b = l;"));
    }

    // ---- 7: pointer conversions -------------------------------------------------------------------

    @Test
    void pointerConversions() {
        assertEquals("", instrs("int *p;", "(long) p;"));
        assertEquals("", instrs("int *p;", "(unsigned long) p;"));
        assertEquals("  mov.s32 %t0, %p", instrs("int *p;", "(int) p;"));
        assertEquals("  mov.u8 %t0, %p", instrs("int *p;", "(unsigned char) p;"));
        assertEquals("  mov.s32 %t0, 5", instrs("", "(void *) 5;"));
        assertEquals("", instrs("unsigned u;", "(void *) u;"));
        assertEquals("", instrs("long l;", "(char *) l;"));
        assertEquals("", instrs("void *vp;", "(int *) vp;"));
        assertEquals("  mov.u64 %t0, 0\n  mov.u64 %p, %t0", instrs("int *p;", "p = 0;"));
        assertEquals("  i32 %i\n.entry:", body("", "int i; (int *) i;"));
    }

    @Test
    void pointerConversionsOnTheOtherTarget() {
        assertEquals("  mov.s32 %t0, %p", instrsOn(ILP32, "int *p;", "(long) p;"));
        assertEquals("", instrsOn(ILP32, "int *p;", "(long long) p;"));
        assertEquals("  mov.u32 %t0, %i", instrsOn(ILP32, "int i;", "(int *) i;"));
    }

    // ---- 8: arithmetic -----------------------------------------------------------------------------

    @Test
    void arithmeticIsWrappingForUnsignedPlainForSignedFloatingForFloating() {
        assertEquals("  %t0 = add.s32 %a, %b", instrs("int a, b;", "a + b;"));
        assertEquals("  %t0 = wadd.u32 %a, %b", instrs("unsigned a, b;", "a + b;"));
        assertEquals("  %t0 = sub.s64 %a, %b", instrs("long a, b;", "a - b;"));
        assertEquals("  %t0 = wmul.u64 %a, %b", instrs("unsigned long a, b;", "a * b;"));
        assertEquals("  %t0 = fadd.64 %a, %b", instrs("double a, b;", "a + b;"));
        assertEquals("  %t0 = fmul.32 %a, %b", instrs("float a, b;", "a * b;"));
        assertEquals("  %t0 = fdiv.64 %a, %b", instrs("double a, b;", "a / b;"));
        assertEquals("  %t0 = sdiv.s32 %a, %b\n  %t1 = srem.s32 %a, %b", instrs("int a, b;", "a / b; a % b;"));
        assertEquals("  %t0 = udiv.u32 %a, %b\n  %t1 = urem.u32 %a, %b", instrs("unsigned a, b;", "a / b; a % b;"));
        assertEquals("  %t0 = and.s32 %a, %b\n  %t1 = or.s32 %a, %b\n  %t2 = xor.s32 %a, %b", instrs("int a, b;", "a & b; a | b; a ^ b;"));
    }

    @Test
    void promotedOperandsAreConvertedFirst() {
        assertEquals("  %t0 = add.s32 %c, %s", instrs("char c; short s;", "c + s;"));
        assertEquals("  %t0 = add.s64 %i, %l", instrs("int i; long l;", "i + l;"));
        assertEquals("  %t0 = i2f.64 %i\n  %t1 = fadd.64 %t0, %d", instrs("int i; double d;", "i + d;"));
    }

    @Test
    void bitPreciseArithmeticIsMadeCanonical() {
        assertEquals("  %t0 = add.s8 %a, %b\n  %t1 = shl.s64 %t0, 57\n  %t1 = ashr.s64 %t1, 57\n  mov.s8 %t0, %t1", instrs("_BitInt(7) a, b;", "a + b;"));
        assertEquals("  %t0 = wmul.u16 %a, %b\n  %t0 = and.u16 %t0, 4095", instrs("unsigned _BitInt(12) a, b;", "a * b;"));
    }

    // ---- 9: shifts ------------------------------------------------------------------------------

    @Test
    void shifts() {
        assertEquals("  %t0 = shl.s32 %i, %n", instrs("int i, n;", "i << n;"));
        assertEquals("  %t0 = ashr.s32 %i, %n", instrs("int i, n;", "i >> n;"));
        assertEquals("  %t0 = lshr.u32 %u, %n", instrs("unsigned u; int n;", "u >> n;"));
        assertEquals("  %t0 = ashr.s64 %l, %n", instrs("long l; int n;", "l >> n;"));
        assertEquals("  %t0 = shl.s32 %i, %l", instrs("int i; long l;", "i << l;"));
        assertEquals("  mov.s32 %t0, 2\n  %t1 = shl.s32 %c, %t0", instrs("char c;", "c << 2;"));
        assertEquals("  %t0 = lshr.u16 %b, %n\n  %t0 = and.u16 %t0, 4095", instrs("unsigned _BitInt(12) b; int n;", "b >> n;"));
    }

    // ---- 10: comparisons -----------------------------------------------------------------------

    @Test
    void comparisons() {
        assertEquals("  %t0 = eq %a, %b\n  %t1 = ne %a, %b", instrs("int a, b;", "a == b; a != b;"));
        assertEquals("  %t0 = slt %a, %b\n  %t1 = sle %a, %b", instrs("int a, b;", "a < b; a <= b;"));
        assertEquals("  %t0 = slt %b, %a\n  %t1 = sle %b, %a", instrs("int a, b;", "a > b; a >= b;"));
        assertEquals("  %t0 = ult %a, %b\n  %t1 = ule %b, %a", instrs("unsigned a, b;", "a < b; a >= b;"));
        assertEquals("  %t0 = ult %p, %q\n  %t1 = eq %p, %q", instrs("int *p, *q;", "p < q; p == q;"));
        assertEquals("  %t0 = flt %a, %b\n  %t1 = fle %b, %a\n  %t2 = feq %a, %b\n  %t3 = fne %a, %b", instrs("double a, b;", "a < b; a >= b; a == b; a != b;"));
        assertEquals("  %t0 = slt %a, %b", instrs("long a, b;", "a < b;"));
        assertEquals("  i32 %a\n  i32 %b\n  i32 %t0\n.entry:\n  %t0 = slt %a, %b", body("", "int a, b; a < b;"));
    }

    @Test
    void swappedOperandsAreStillEvaluatedLeftToRight() {
        assertEquals("  %t0 = load.s32 %p\n  %t1 = load.s32 %q\n  %t2 = slt %t1, %t0", instrs("int *p, *q;", "*p > *q;"));
    }

    // ---- 11: unary and logical ------------------------------------------------------------------

    @Test
    void unary() {
        assertEquals("  %t0 = sub.s32 0, %i", instrs("int i;", "-i;"));
        assertEquals("  %t0 = wsub.u32 0, %u", instrs("unsigned u;", "-u;"));
        assertEquals("  %t0 = fsub.64 -0.0, %d", instrs("double d;", "-d;"));
        assertEquals("  %t0 = xor.s32 %i, -1", instrs("int i;", "~i;"));
        assertEquals("  %t0 = xor.u16 %b, -1\n  %t0 = and.u16 %t0, 4095", instrs("unsigned _BitInt(12) b;", "~b;"));
        assertEquals("  %t0 = ne %i, 0\n  %t1 = eq %t0, 0", instrs("int i;", "!i;"));
        assertEquals("  %t0 = slt %a, %b\n  %t1 = eq %t0, 0", instrs("int a, b;", "!(a < b);"));
        assertEquals("  %t0 = slt %a, %b\n  mov.u8 %f, %t0", instrs("int a, b; bool f;", "f = a < b;"));
        assertEquals("  %t0 = sub.s32 0, %c", instrs("char c;", "-c;"));
    }

    @Test
    void logicalAndIsTwoBlocks() {
        assertEquals("""
                  i32 %a
                  i32 %b
                  i32 %t0
                  u8 %t1
                  u8 %t2
                .entry:
                  mov.s32 %t0, 0
                  %t1 = ne %a, 0
                  condbr %t1, .and, .and.done
                .and:
                  %t2 = ne %b, 0
                  mov.s32 %t0, %t2
                  br .and.done
                .and.done:""", body("", "int a, b; a && b;"));
    }

    @Test
    void logicalOrIsTwoBlocks() {
        assertEquals("""
                  i32 %a
                  i32 %b
                  i32 %t0
                  u8 %t1
                  u8 %t2
                .entry:
                  mov.s32 %t0, 1
                  %t1 = ne %a, 0
                  condbr %t1, .or.done, .or
                .or:
                  %t2 = ne %b, 0
                  mov.s32 %t0, %t2
                  br .or.done
                .or.done:""", body("", "int a, b; a || b;"));
    }

    @Test
    void nestedLogicalBlocksAreNamedUniquely() {
        String text = body("", "int a, b, c; a && b && c;");
        assertTrue(text.contains(".and:") && text.contains(".and.2:") && text.contains(".and.done.2:"), text);
    }

    // ---- 12: pointers and members --------------------------------------------------------------

    @Test
    void pointerArithmetic() {
        assertEquals("  %t0 = wmul.s64 %i, 4\n  %t1 = wadd.u64 %p, %t0\n  %t2 = load.s32 %t1", instrs("int *p; int i;", "p[i];"));
        assertEquals("  mov.s32 %t0, 3\n  %t1 = wmul.s64 %t0, 4\n  %t2 = wadd.u64 %p, %t1", instrs("int *p;", "p + 3;"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = wmul.s64 %t0, 1\n  %t2 = wadd.u64 %c, %t1", instrs("char *c;", "c + 1;"));
        assertEquals("  %t0 = wsub.s64 %p, %q\n  %t0 = sdiv.s64 %t0, 4", instrs("int *p, *q;", "p - q;"));
        assertEquals("  %t0 = wsub.s64 %p, %q\n  %t0 = sdiv.s64 %t0, 1", instrs("char *p, *q;", "p - q;"));
        assertEquals("  i32 %i\n  ptr %p\n  i32 %t0\n  i32 %t1\n  ptr %t2\n  i32 %t3\n.entry:\n  mov.s32 %t0, 2\n  %t1 = wmul.s32 %t0, 4\n  %t2 = wadd.u32 %p, %t1\n  %t3 = load.s32 %t2", bodyOn(ILP32, "", "int i; int *p; p[2];"));
    }

    @Test
    void membersFoldIntoTheAddress() {
        String decls = "struct In { int x, y; }; struct Out { struct In in; char name[8]; long tail; };";
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.s32 %t0", instrs(decls + " struct In *p;", "p->x;"));
        assertEquals("  %t0 = wadd.u64 %p, 4\n  %t1 = load.s32 %t0", instrs(decls + " struct In *p;", "p->y;"));
        assertEquals("  %t0 = wadd.u64 %o, 0\n  %t1 = wadd.u64 %t0, 4\n  %t2 = load.s32 %t1", instrs(decls + " struct Out *o;", "o->in.y;"));
        assertEquals("  %t0 = wadd.u64 %o, 16\n  %t1 = load.s64 %t0", instrs(decls + " struct Out *o;", "o->tail;"));
        assertEquals("  %t0 = addrof %s\n  %t1 = wadd.u64 %t0, 4\n  %t2 = load.s32 %t1", instrs(decls + " struct In s;", "s.y;"));
        assertEquals("  %t0 = addrof %s\n  %t1 = wadd.u64 %t0, 0\n  %t2 = load.s32 %t1", instrs(decls + " struct In s;", "s.x;"));
        assertEquals("  %t0 = addrof %s\n  %t1 = wadd.u64 %t0, 4\n  mov.s32 %t2, 1\n  store.32 %t1, %t2", instrs(decls + " struct In s;", "s.y = 1;"));
    }

    @Test
    void addressesOfPlaces() {
        assertEquals("  %t0 = addrof %x", instrs("int x;", "&x;"));
        assertEquals("  %t0 = addrof %s\n  %t1 = wadd.u64 %t0, 4", instrs("struct In { int x, y; }; struct In s;", "&s.y;"));
        assertEquals("  %t0 = addrof %a\n  mov.s32 %t1, 2\n  %t2 = wmul.s64 %t1, 4\n  %t3 = wadd.u64 %t0, %t2", instrs("int a[4];", "&a[2];"));
        assertEquals("  %t0 = addrof %a\n  mov.u64 %p, %t0", instrs("int a[4]; int *p;", "p = a;"));
        assertEquals("  %t0 = wadd.u64 %p, 4", instrs("struct In { int x, y; }; struct In *p;", "&p->y;"));
        assertEquals("  ptr %t0\n  ptr %t1\n.entry:\n  %t0 = addrof @g\n  %t1 = wadd.u64 %t0, 4", expr("struct In { int x, y; }; struct In g;", "&g.y"));
    }

    // ---- 13: bit-fields --------------------------------------------------------------------------

    static final String BITS = "struct B { unsigned lo : 4; unsigned hi : 4; int wide : 20; bool flag : 1; }; struct B *p;";

    @Test
    void bitFieldLoads() {
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t2 = lshr.u32 %t1, 0\n  %t2 = and.u32 %t2, 15", instrs(BITS, "p->lo;"));
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t2 = lshr.u32 %t1, 4\n  %t2 = and.u32 %t2, 15", instrs(BITS, "p->hi;"));
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t3 = shl.s64 %t1, 36\n  %t3 = ashr.s64 %t3, 44\n  mov.s32 %t2, %t3", instrs(BITS, "p->wide;"));
        assertEquals("  %t0 = wadd.u64 %p, 3\n  %t1 = load.u8 %t0\n  %t2 = lshr.u8 %t1, 4\n  %t2 = and.u8 %t2, 1", instrs(BITS, "p->flag;"));
        assertTrue(body("", BITS + " p->lo;").contains("  ptr %t0\n  u32 %t1\n  u32 %t2\n.entry:"));
    }

    @Test
    void bitFieldStores() {
        assertEquals("  %t0 = wadd.u64 %p, 0\n  mov.s32 %t1, 3\n  mov.u32 %t2, %t1\n  %t3 = load.u32 %t0\n  %t3 = and.u32 %t3, -241\n  %t4 = and.u32 %t2, 15\n  %t4 = shl.u32 %t4, 4\n  %t3 = or.u32 %t3, %t4\n  store.32 %t0, %t3", instrs(BITS, "p->hi = 3;"));
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t1 = and.u32 %t1, -16\n  %t2 = and.u32 %u, 15\n  %t2 = shl.u32 %t2, 0\n  %t1 = or.u32 %t1, %t2\n  store.32 %t0, %t1", instrs(BITS + " unsigned u;", "p->lo = u;"));
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t1 = and.u32 %t1, -268435201\n  %t2 = and.u32 %i, 1048575\n  %t2 = shl.u32 %t2, 8\n  %t1 = or.u32 %t1, %t2\n  store.32 %t0, %t1", instrs(BITS + " int i;", "p->wide = i;"));
        assertEquals("  %t0 = wadd.u64 %p, 3\n  mov.s32 %t1, 1\n  %t2 = ne %t1, 0\n  %t3 = load.u8 %t0\n  %t3 = and.u8 %t3, -17\n  %t4 = and.u8 %t2, 1\n  %t4 = shl.u8 %t4, 4\n  %t3 = or.u8 %t3, %t4\n  store.8 %t0, %t3", instrs(BITS, "p->flag = 1;"));
    }

    @Test
    void bitFieldsOnTheOtherTarget() {
        assertEquals("  %t0 = wadd.u32 %p, 0\n  %t1 = load.u32 %t0\n  %t3 = shl.s64 %t1, 36\n  %t3 = ashr.s64 %t3, 44\n  mov.s32 %t2, %t3", instrsOn(ILP32, BITS, "p->wide;"));
    }

    // ---- 14: assignments ---------------------------------------------------------------------

    @Test
    void anAssignmentYieldsWhatTheTargetHolds() {
        assertEquals("  mov.s32 %x, %y\n  mov.s32 %z, %y", instrs("int x, y, z;", "z = x = y;"));
        assertEquals("  %t0 = wadd.u64 %p, 0\n  mov.s32 %t1, 300\n  mov.u32 %t2, %t1\n  %t3 = load.u32 %t0\n  %t3 = and.u32 %t3, -16\n  %t4 = and.u32 %t2, 15\n  %t4 = shl.u32 %t4, 0\n  %t3 = or.u32 %t3, %t4\n  store.32 %t0, %t3\n  mov.u32 %t5, %t2\n  %t5 = and.u32 %t5, 15\n  mov.u32 %u, %t5",
                instrs(BITS + " unsigned u;", "u = p->lo = 300;"));
    }

    @Test
    void compoundAssignmentOnAVariable() {
        assertEquals("  mov.s32 %t0, 1\n  %t1 = add.s32 %x, %t0\n  mov.s32 %x, %t1", instrs("int x;", "x += 1;"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = add.s32 %x, %t0\n  mov.s32 %x, %t1", instrs("int x;", "++x;"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = add.s32 %x, %t0\n  mov.s32 %x, %t1", instrs("int x;", "x++;"));
        assertEquals("  mov.s32 %t0, %x\n  mov.s32 %t1, 1\n  %t2 = add.s32 %t0, %t1\n  mov.s32 %x, %t2\n  mov.s32 %y, %t0", instrs("int x, y;", "y = x++;"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = wmul.s64 %t0, 4\n  %t2 = wadd.u64 %p, %t1\n  mov.u64 %p, %t2", instrs("int *p;", "p++;"));
        assertEquals("  mov.u64 %t0, %p\n  mov.s32 %t1, 1\n  %t2 = wmul.s64 %t1, 4\n  %t3 = wadd.u64 %t0, %t2\n  mov.u64 %p, %t3\n  mov.u64 %q, %t0", instrs("int *p, *q;", "q = p++;"));
        assertEquals("  %t0 = i2f.64 %x\n  %t1 = fadd.64 %t0, %d\n  %t2 = f2i.64 %t1\n  mov.s32 %x, %t2", instrs("int x; double d;", "x += d;"));
    }

    @Test
    void compoundAssignmentThroughAPointerEvaluatesTheTargetOnce() {
        assertEquals("  %t0 = wadd.u64 %p, 4\n  %t1 = load.s32 %t0\n  mov.s32 %t2, 1\n  %t3 = add.s32 %t1, %t2\n  store.32 %t0, %t3",
                instrs("struct In { int x, y; }; struct In *p;", "p->y += 1;"));
        assertEquals("  mov.s32 %t0, %k\n  mov.s32 %t1, 1\n  %t2 = add.s32 %t0, %t1\n  mov.s32 %k, %t2\n  %t3 = wmul.s64 %t0, 4\n  %t4 = wadd.u64 %a, %t3\n  %t5 = load.s32 %t4\n  mov.s32 %t6, 1\n  %t7 = add.s32 %t5, %t6\n  store.32 %t4, %t7",
                instrs("int *a; int k;", "a[k++] += 1;"));
    }

    @Test
    void compoundAssignmentOnABitField() {
        assertEquals("  %t0 = wadd.u64 %p, 0\n  %t1 = load.u32 %t0\n  %t2 = lshr.u32 %t1, 0\n  %t2 = and.u32 %t2, 15\n  mov.s32 %t3, 1\n  mov.u32 %t4, %t3\n  %t5 = wadd.u32 %t2, %t4\n  %t6 = load.u32 %t0\n  %t6 = and.u32 %t6, -16\n  %t7 = and.u32 %t5, 15\n  %t7 = shl.u32 %t7, 0\n  %t6 = or.u32 %t6, %t7\n  store.32 %t0, %t6",
                instrs(BITS, "p->lo += 1;"));
    }

    // ---- 15: aggregates ----------------------------------------------------------------------

    /** Instructions with the typer's temporary numbers normalized to {@code N}. */
    static String instrsN(String decls, String body) {
        return instrs(decls, body).replaceAll("(tmp|lit)\\d+", "$1N");
    }

    @Test
    void aggregateAssignmentIsACopyYieldingTheTarget() {
        String decls = "struct In { int x, y; }; struct In s, t;";
        assertEquals("  %t0 = addrof %s\n  %t1 = addrof %t\n  store.%In %t0, %t1", instrs(decls, "s = t;"));
        assertEquals("  %t0 = addrof %tmpN\n  %t1 = addrof %s\n  %t2 = addrof %t\n  store.%In %t1, %t2\n  store.%In %t0, %t1\n  %t3 = wadd.u64 %t0, 4\n  %t4 = load.s32 %t3",
                instrsN(decls, "(s = t).y;"));
    }

    @Test
    void sizeofIsAlreadyAConstant() {
        assertEquals("  mov.u64 %t0, 8", instrs("struct In { int x, y; }; struct In s;", "sizeof s;"));
        assertEquals("  u64 %t0\n.entry:\n  mov.u64 %t0, 4", body("", "sizeof(int);"));
    }

    @Test
    void aggregateLocalInitializers() {
        assertEquals("  %t0 = addrof %v\n  store.[3 x i32] %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  mov.s32 %t2, 1\n  store.32 %t1, %t2\n  %t3 = wadd.u64 %t0, 4\n  mov.s32 %t4, 2\n  store.32 %t3, %t4",
                instrs("", "int v[3] = {1, 2};"));
        assertEquals("  %t0 = addrof %p\n  store.%P %t0, 0\n  %t1 = wadd.u64 %t0, 4\n  mov.s32 %t2, 2\n  store.32 %t1, %t2",
                instrs("struct P { int x, y; };", "struct P p = {.y = 2};"));
        assertEquals("  %t0 = addrof %s\n  store.[3 x i8] %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  mov.s8 %t2, 97\n  store.8 %t1, %t2\n  %t3 = wadd.u64 %t0, 1\n  mov.s8 %t4, 98\n  store.8 %t3, %t4",
                instrs("", "char s[] = \"ab\";"));
        assertEquals("  %t0 = addrof %o\n  store.%Out %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  %t2 = addrof %in\n  store.%In %t1, %t2\n  %t3 = wadd.u64 %t0, 8\n  mov.s8 %t4, 122\n  store.8 %t3, %t4",
                instrs("struct In { int x, y; }; struct Out { struct In in; char name[8]; }; struct In in;", "struct Out o = {in, \"z\"};"));
    }

    @Test
    void compoundLiterals() {
        assertEquals("  %t0 = addrof %litN\n  store.%P %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  mov.s32 %t2, 1\n  store.32 %t1, %t2\n  %t3 = wadd.u64 %t0, 4\n  mov.s32 %t4, 2\n  store.32 %t3, %t4\n  %t5 = wadd.u64 %t0, 0\n  %t6 = load.s32 %t5",
                instrsN("struct P { int x, y; };", "(struct P){1, 2}.x;"));
        assertEquals("  mov.s32 %t0, 7\n  mov.s32 %litN, %t0", instrsN("", "(int){7};"));
        assertEquals("  %t0 = addrof @.lit.1\n  %t1 = load.s32 %t0", instrs("", "(static const int){7};"));
        assertEquals("  %t0 = addrof %litN\n  store.[2 x i32] %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  mov.s32 %t2, 3\n  store.32 %t1, %t2\n  mov.u64 %p, %t0", instrsN("int *p;", "p = (int[2]){3};"));
    }

    // ---- 16: calls ---------------------------------------------------------------------------

    @Test
    void directCalls() {
        assertEquals("  mov.s32 %t0, 1\n  mov.64 %t1, 2.0\n  %t2 = call (i32, f64) -> i32 @f(%t0, %t1)", instrs("int f(int, double);", "f(1, 2.0);"));
        assertEquals("  call () -> void @g()", instrs("void g(void);", "g();"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = call (i32, ...) -> i32 @v(%t0, %c)", instrs("int v(int, ...); char c;", "v(1, c);"));
        assertEquals("  %t0 = call (f64) -> f64 @half(%f)", instrs("double half(double); float f;", "half(f);"));
        assertEquals("  %t0 = call () -> i32 @f()\n  mov.s32 %x, %t0", instrs("int f(void); int x;", "x = f();"));
    }

    @Test
    void indirectCalls() {
        assertEquals("  mov.s32 %t0, 3\n  %t1 = icall (i32) -> i32 %fp(%t0)", instrs("int (*fp)(int);", "fp(3);"));
        assertEquals("  mov.s32 %t0, 3\n  %t1 = icall (i32) -> i32 %fp(%t0)", instrs("int (*fp)(int);", "(*fp)(3);"));
        assertEquals("  %t0 = addrof @f\n  mov.u64 %fp, %t0", instrs("int f(int); int (*fp)(int);", "fp = f;"));
        assertEquals("  mov.s32 %t0, 1\n  %t1 = call (i32) -> i32 @f(%t0)", instrs("int f(int);", "(&f)(1);"));
        assertEquals("  %t0 = addrof @f\n  mov.s32 %t1, 1\n  %t2 = icall (i32) -> i32 %t0(%t1)", instrs("int f(int);", "((int (*)(int)) (void *) f)(1);"));
    }

    @Test
    void variableArgumentsAreInstructions() {
        String tac = function("void f(int n, ...) { char *ap; __builtin_va_start(ap, n); int x = __builtin_va_arg(ap, int); double d = __builtin_va_arg(ap, double); }");
        assertTrue(tac.contains("define @f(i32 %n, ...) -> void {"), tac);
        assertTrue(tac.contains("  vastart %ap\n"), tac);
        assertTrue(tac.contains("  %t0 = vaarg %ap\n  mov.s32 %x, %t0\n"), tac);
        assertTrue(tac.contains("  %t1 = vaarg %ap\n  mov.64 %d, %t1\n"), tac);
    }

    @Test
    void alignasReachesTheVariableAndTheGlobal() {
        assertEquals("""
                global @g : i32 align 32 = { 0 : i32 1 }
                define @f() -> void {
                  i32 %a align 16
                  i8 %c align 8
                .entry:
                  ret
                }
                """, unit("alignas(32) int g = 1; void f(void) { alignas(16) int a; alignas(double) char c; }"));
    }

    @Test
    void aFunctionWhoseAddressAGlobalInitializerTakesIsDeclared() {
        assertEquals("""
                global @p : ptr align 8 = { 0 : addr @abs }
                declare @abs(i32) -> i32
                """, unit("#include <stdlib.h>\nint (*p)(int) = &abs;"));
    }

    @Test
    void calledFunctionsTheUnitDoesNotDefineAreDeclared() {
        assertEquals("""
                declare @h() -> void
                declare @f(i32, f64) -> i32
                define @g() -> void {
                  ptr %t0
                  i32 %t1
                  f64 %t2
                  i32 %t3
                .entry:
                  %t0 = addrof @h
                  mov.s32 %t1, 1
                  mov.64 %t2, 2.0
                  %t3 = call (i32, f64) -> i32 @f(%t1, %t2)
                  ret
                }
                """, unit("int f(int, double); void h(void); void g(void) { &h; f(1, 2.0); }"));
        assertEquals("""
                define @f() -> void {
                .entry:
                  ret
                }
                define @g() -> void {
                .entry:
                  call () -> void @f()
                  ret
                }
                """, unit("void f(void) {} void g(void) { f(); }"));
    }

    // ---- 17: aggregate calls -------------------------------------------------------------------

    static final String AGG = "struct P { int x, y; }; struct P mk(void); void g(struct P); struct P s;";

    @Test
    void aggregateArgumentsAndResults() {
        assertEquals("  %t0 = addrof %s\n  call (%P) -> void @g(%t0)", instrs(AGG, "g(s);"));
        assertEquals("  %t0 = addrof %s\n  %t1 = addrof %call.1\n  call () -> %P @mk() into %t1\n  store.%P %t0, %t1", instrs(AGG, "s = mk();"));
        assertEquals("  %t0 = addrof %tmpN\n  call () -> %P @mk() into %t0\n  %t1 = wadd.u64 %t0, 0\n  %t2 = load.s32 %t1", instrsN(AGG, "mk().x;"));
        assertEquals("  %t0 = addrof %call.1\n  call () -> %P @mk() into %t0", instrs(AGG, "mk();"));
        assertEquals("  %t0 = addrof %call.1\n  call () -> %P @mk() into %t0\n  call (%P) -> void @g(%t0)", instrs(AGG, "g(mk());"));
        assertTrue(body("", AGG + " mk();").contains("  %P %call.1\n"));
    }

    // ---- 18: conditional, comma, void ------------------------------------------------------------

    @Test
    void conditionalIsThreeBlocksWithAResultVariable() {
        assertEquals("""
                  i32 %c
                  i32 %x
                  i32 %t0
                  u8 %t1
                  i32 %t2
                  i32 %t3
                .entry:
                  %t1 = ne %c, 0
                  condbr %t1, .then, .else
                .then:
                  mov.s32 %t2, 1
                  mov.s32 %t0, %t2
                  br .cond.done
                .else:
                  mov.s32 %t3, 2
                  mov.s32 %t0, %t3
                  br .cond.done
                .cond.done:
                  mov.s32 %x, %t0""", body("", "int c; int x; x = c ? 1 : 2;"));
    }

    @Test
    void conditionalOfAggregatesCopiesIntoATemporaryObject() {
        String decls = "struct P { int x, y; }; struct P s, t; int c;";
        assertEquals("""
                  %t0 = addrof %tmpN
                  %t1 = addrof %cond.1
                  %t2 = ne %c, 0
                  condbr %t2, .then, .else
                .then:
                  %t3 = addrof %s
                  store.%P %t1, %t3
                  br .cond.done
                .else:
                  %t4 = addrof %t
                  store.%P %t1, %t4
                  br .cond.done
                .cond.done:
                  store.%P %t0, %t1
                  %t5 = wadd.u64 %t0, 0
                  %t6 = load.s32 %t5""", instrsN(decls, "(c ? s : t).x;"));
        assertTrue(body("", decls + " (c ? s : t).x;").contains("  %P %cond.1\n"));
    }

    @Test
    void conditionalOfVoidHasNoResult() {
        assertEquals("""
                  %t0 = ne %c, 0
                  condbr %t0, .then, .else
                .then:
                  call () -> void @f()
                  br .cond.done
                .else:
                  call () -> void @g()
                  br .cond.done
                .cond.done:""", instrs("void f(void); void g(void); int c;", "c ? f() : g();"));
    }

    @Test
    void commaEvaluatesTheLeftForItsEffect() {
        assertEquals("  mov.s32 %t0, 1\n  mov.s32 %a, %t0\n  mov.s32 %x, %b", instrs("int a, b, x;", "x = (a = 1, b);"));
        assertEquals("  mov.s32 %t0, 1\n  mov.s32 %a, %t0\n  mov.s32 %t1, 2\n  mov.s32 %b, %t1", instrs("int a, b;", "a = 1, b = 2;"));
    }

    // ---- 20: if -------------------------------------------------------------------------------

    @Test
    void ifWithoutElse() {
        assertEquals("""
                  %t0 = ne %c, 0
                  condbr %t0, .then, .if.done
                .then:
                  mov.s32 %t1, 1
                  mov.s32 %x, %t1
                  br .if.done
                .if.done:""", instrs("int c, x;", "if (c) x = 1;"));
    }

    @Test
    void ifWithElse() {
        assertEquals("""
                  %t0 = ne %c, 0
                  condbr %t0, .then, .else
                .then:
                  mov.s32 %t1, 1
                  mov.s32 %x, %t1
                  br .if.done
                .else:
                  mov.s32 %t2, 2
                  mov.s32 %x, %t2
                  br .if.done
                .if.done:""", instrs("int c, x;", "if (c) x = 1; else x = 2;"));
    }

    @Test
    void nestedAndEmptyBranches() {
        assertEquals("""
                  %t0 = ne %a, 0
                  condbr %t0, .then, .if.done
                .then:
                  %t1 = ne %b, 0
                  condbr %t1, .then.2, .if.done.2
                .then.2:
                  mov.s32 %t2, 1
                  mov.s32 %x, %t2
                  br .if.done.2
                .if.done.2:
                  br .if.done
                .if.done:""", instrs("int a, b, x;", "if (a) { if (b) x = 1; }"));
        assertEquals("""
                  %t0 = ne %a, 0
                  condbr %t0, .then, .if.done
                .then:
                  br .if.done
                .if.done:""", instrs("int a;", "if (a) {}"));
    }

    // ---- 21: loops -------------------------------------------------------------------------------

    @Test
    void whileLoop() {
        assertEquals("""
                  br .while.cond
                .while.cond:
                  %t0 = ne %n, 0
                  condbr %t0, .while.body, .while.done
                .while.body:
                  mov.s32 %t1, 1
                  %t2 = sub.s32 %n, %t1
                  mov.s32 %n, %t2
                  br .while.cond
                .while.done:""", instrs("int n;", "while (n) n--;"));
    }

    @Test
    void doWhileLoop() {
        assertEquals("""
                  br .do.body
                .do.body:
                  mov.s32 %t0, 1
                  %t1 = sub.s32 %n, %t0
                  mov.s32 %n, %t1
                  br .do.cond
                .do.cond:
                  %t2 = ne %n, 0
                  condbr %t2, .do.body, .do.done
                .do.done:""", instrs("int n;", "do n--; while (n);"));
    }

    @Test
    void forLoopWithADeclaration() {
        assertEquals("""
                  mov.s32 %t0, 0
                  mov.s32 %i, %t0
                  br .for.cond
                .for.cond:
                  %t1 = slt %i, %n
                  condbr %t1, .for.body, .for.done
                .for.body:
                  mov.s32 %t2, 1
                  %t3 = add.s32 %s, %t2
                  mov.s32 %s, %t3
                  br .for.step
                .for.step:
                  mov.s32 %t4, 1
                  %t5 = add.s32 %i, %t4
                  mov.s32 %i, %t5
                  br .for.cond
                .for.done:""", instrs("int n, s;", "for (int i = 0; i < n; i++) s += 1;"));
    }

    @Test
    void forLoopWithoutClauses() {
        assertEquals("""
                  br .for.body
                .for.body:
                  br .for.done
                .for.step:
                  br .for.body
                .for.done:""", instrs("", "for (;;) break;"));
    }

    @Test
    void breakAndContinue() {
        assertEquals("""
                  br .while.cond
                .while.cond:
                  %t0 = ne %n, 0
                  condbr %t0, .while.body, .while.done
                .while.body:
                  %t1 = ne %c, 0
                  condbr %t1, .then, .if.done
                .then:
                  br .while.cond
                .if.done:
                  %t2 = ne %d, 0
                  condbr %t2, .then.2, .if.done.2
                .then.2:
                  br .while.done
                .if.done.2:
                  mov.s32 %t3, 1
                  %t4 = sub.s32 %n, %t3
                  mov.s32 %n, %t4
                  br .while.cond
                .while.done:""", instrs("int n, c, d;", "while (n) { if (c) continue; if (d) break; n--; }"));
    }

    // ---- 22: labels and goto ---------------------------------------------------------------------

    @Test
    void forwardAndBackwardGoto() {
        assertEquals("""
                  br .again
                .again:
                  mov.s32 %t0, 1
                  %t1 = sub.s32 %n, %t0
                  mov.s32 %n, %t1
                  %t2 = ne %n, 0
                  condbr %t2, .then, .if.done
                .then:
                  br .again
                .if.done:
                  br .out
                .out:""", instrs("int n;", "again: n--; if (n) goto again; goto out; out: ;"));
    }

    @Test
    void deadCodeAfterAJumpIsDropped() {
        assertEquals("""
                define @f(i32 %n) -> i32 {
                  i32 %t1
                .entry:
                  ret %n
                .after:
                  mov.s32 %t1, 1
                  ret %t1
                }
                """, function("int f(int n) { return n; n = 2; after: return 1; }"));
        assertEquals("""
                define @g(i32 %n) -> i32 {
                .entry:
                  br .out
                .out:
                  ret %n
                }
                """, function("int g(int n) { goto out; n = 2; out: return n; }"));
    }

    @Test
    void gotoIntoALoopBody() {
        assertEquals("""
                  br .in
                .while.cond:
                  %t0 = ne %n, 0
                  condbr %t0, .while.body, .while.done
                .while.body:
                  br .in
                .in:
                  mov.s32 %t1, 1
                  %t2 = sub.s32 %n, %t1
                  mov.s32 %n, %t2
                  br .while.cond
                .while.done:""", instrs("int n;", "goto in; while (n) { in: n--; }"));
    }

    // ---- 23: switch ------------------------------------------------------------------------------

    @Test
    void switchWithCasesAndDefault() {
        assertEquals("""
                  switch %c, .default, [ 1 -> .case.1, 2 -> .case.2 ]
                .case.1:
                  mov.s32 %t0, 10
                  mov.s32 %x, %t0
                  br .switch.done
                .case.2:
                  mov.s32 %t1, 20
                  mov.s32 %x, %t1
                  br .default
                .default:
                  mov.s32 %t2, 0
                  mov.s32 %x, %t2
                  br .switch.done
                .switch.done:""", instrs("int c, x;", "switch (c) { case 1: x = 10; break; case 2: x = 20; default: x = 0; }"));
    }

    @Test
    void switchWithoutDefaultAndWithDeadCodeBeforeTheFirstCase() {
        assertEquals("""
                  switch %c, .switch.done, [ 1 -> .case.1 ]
                .case.1:
                  mov.s32 %t1, 1
                  mov.s32 %x, %t1
                  br .switch.done
                .switch.done:""", instrs("int c, x;", "switch (c) { x = 5; case 1: x = 1; }"));
    }

    @Test
    void switchWithARangeIsAComparisonChain() {
        assertEquals("""
                  %t0 = wsub.u32 %c, 97
                  %t1 = ule %t0, 5
                  condbr %t1, .case.97.102, .case.next
                .case.next:
                  switch %c, .switch.done, [ 120 -> .case.120 ]
                .case.97.102:
                  mov.s32 %t2, 1
                  mov.s32 %x, %t2
                  br .switch.done
                .case.120:
                  mov.s32 %t3, 2
                  mov.s32 %x, %t3
                  br .switch.done
                .switch.done:""", instrs("int c, x;", "switch (c) { case 'a' ... 'f': x = 1; break; case 'x': x = 2; }"));
    }

    @Test
    void switchOnALongAndNested() {
        assertEquals("""
                  switch %l, .switch.done, [ 5 -> .case.5 ]
                .case.5:
                  switch %c, .switch.done.2, [ 1 -> .case.1 ]
                .case.1:
                  mov.s32 %t0, 1
                  mov.s32 %x, %t0
                  br .switch.done.2
                .switch.done.2:
                  br .switch.done
                .switch.done:""", instrs("long l; int c, x;", "switch (l) { case 5: switch (c) { case 1: x = 1; } }"));
    }

    // ---- 24: return ----------------------------------------------------------------------------

    @Test
    void returns() {
        assertEquals("""
                define @f(i32 %n) -> i32 {
                  i32 %t0
                .entry:
                  mov.s32 %t0, 1
                  %t1 = add.s32 %n, %t0
                  ret %t1
                }
                """.replace("  i32 %t0\n.entry", "  i32 %t0\n  i32 %t1\n.entry"), function("int f(int n) { return n + 1; }"));
        assertEquals("""
                define @g(%P %p) -> %P {
                  ptr %t0
                .entry:
                  %t0 = addrof %p
                  ret %t0
                }
                """, function("struct P { int x, y; }; struct P g(struct P p) { return p; }"));
        assertEquals("""
                define @h(i32 %n) -> void {
                  u8 %t0
                .entry:
                  %t0 = ne %n, 0
                  condbr %t0, .then, .if.done
                .then:
                  ret
                .if.done:
                  ret
                }
                """, function("void h(int n) { if (n) return; }"));
        assertEquals("""
                define @main() -> i32 {
                .entry:
                  br .while.cond
                .while.cond:
                  %t0 = ne %n, 0
                  condbr %t0, .while.body, .while.done
                .while.body:
                  ret %n
                .while.done:
                  ret 0
                }
                """.replace(".entry:\n  br", "  i32 %n\n  u8 %t0\n.entry:\n  br"), function("int main(void) { int n; while (n) return n; }"));
    }

    // ---- 25: bit-field initializer items -------------------------------------------------------

    @Test
    void bitFieldInitializersAtRunTime() {
        assertEquals("  %t0 = addrof %b\n  store.%B %t0, 0\n  %t1 = wadd.u64 %t0, 0\n  mov.s32 %t2, 3\n  mov.u32 %t3, %t2\n  %t4 = load.u32 %t1\n  %t4 = and.u32 %t4, -241\n  %t5 = and.u32 %t3, 15\n  %t5 = shl.u32 %t5, 4\n  %t4 = or.u32 %t4, %t5\n  store.32 %t1, %t4",
                instrs("struct B { unsigned lo : 4; unsigned hi : 4; };", "struct B b = {.hi = 3};"));
    }

    @Test
    void bitFieldInitializersInGlobals() {
        assertEquals("global @g : %B align 4 = { 0 : 4/4 : 3, 3 : 4/1 : 1 }\n",
                unit("struct B { unsigned lo : 4; unsigned hi : 4; int wide : 20; bool flag : 1; }; struct B g = {.hi = 3, .flag = 1};").replaceFirst("(?s)^type[^\\n]*\\n", ""));
    }

    // ---- 26: globals ---------------------------------------------------------------------------

    @Test
    void globalInitializersAreTheTypersItems() {
        assertEquals("""
                type %P = { i32 @0, i32 @4 } size 8 align 4
                type %U = { i8 @0, i32 @0 } size 4 align 4
                global @g : %P align 4 = { 4 : i32 2 }
                global @u : %U align 4 = { 0 : i32 1 }
                global @arr : [3 x i32] align 4 = { 0 : i32 1, 4 : i32 2 }
                global @q : ptr align 8 = { 0 : addr @arr + 4 }
                global @name : [4 x i8] align 1 = { 0 : i8 97, 1 : i8 98 }
                global @m : [2 x [2 x i32]] align 4 = { 0 : i32 1, 4 : i32 2, 8 : i32 3 }
                """, unit("struct P { int x, y; }; union U { char c; int i; }; struct P g = {.y = 2}; union U u = {.i = 1}; int arr[3] = {1, 2}; int *q = &arr[1]; char name[4] = \"ab\"; int m[2][2] = {1, 2, 3};"));
    }

    @Test
    void tentativeDefinitionsExternsAndConst() {
        assertEquals("""
                global @t : i32 align 4
                global @a : [2 x i32] align 4
                global @k : f64 align 8 readonly = { 0 : f64 1.5 }
                global @later : f64 align 8 = { 0 : f64 2.5 }
                declare @e : i32
                declare @never : [0 x i32]
                """, unit("extern int e; int t; int t; int a[]; int a[2]; const double k = 1.5; extern int never[]; double later = 2.5;"));
    }

    @Test
    void addressConstantsInStaticInitializers() {
        assertEquals("""
                global @arr : [3 x i32] align 4
                global internal @p.static : ptr align 8 = { 0 : addr @arr + 4 }
                global internal @q.static : ptr align 8 = { 0 : addr @f }
                global internal @n.static : ptr align 8 = { 0 : u64 16 }
                define @f() -> void {
                .entry:
                  ret
                }
                """, unit("int arr[3]; void f(void) { static int *p = &arr[1]; static void (*q)(void) = f; static int *n = (int *) 16; }"));
    }

    // ---- 27: strings and statics -----------------------------------------------------------------

    static String normalizeStrings(String text) {
        return text.replaceAll("\\.str\\.[0-9a-f]{16}", ".str.H");
    }

    @Test
    void stringLiteralsAreReadOnlyGlobalsNamedByContent() {
        assertEquals("""
                global internal @.str.H : [3 x i8] align 1 readonly = { 0 : bytes "hi\\00" }
                global @p : ptr align 8 = { 0 : addr @.str.H }
                define @f() -> ptr {
                  ptr %t0
                .entry:
                  %t0 = addrof @.str.H
                  ret %t0
                }
                """, normalizeStrings(unit("const char *p = \"hi\"; const char *f(void) { return \"hi\"; }")));
        assertEquals("  %t0 = addrof @.str.H", normalizeStrings(instrs("", "\"x\";")));
        assertEquals("  %t0 = addrof @.str.H\n  mov.s32 %t1, 1\n  %t2 = wmul.s64 %t1, 1\n  %t3 = wadd.u64 %t0, %t2\n  %t4 = load.s8 %t3", normalizeStrings(instrs("", "\"xy\"[1];")));
    }

    @Test
    void wideStringsAreOneItemPerUnit() {
        assertEquals("""
                global internal @.str.H : [2 x i32] align 4 readonly = { 0 : i32 97, 4 : i32 0 }
                global internal @.str.H : [2 x u16] align 2 readonly = { 0 : u16 98, 2 : u16 0 }
                global @w : ptr align 8 = { 0 : addr @.str.H }
                global @s : ptr align 8 = { 0 : addr @.str.H }
                """.replace("H :", "H :"), normalizeStrings(unit("typedef int wchar_t; wchar_t *w = L\"a\"; unsigned short *s = u\"b\";")).replaceAll("@\\.str\\.H", "@.str.H"));
    }

    @Test
    void staticLocalsAndFileScopeLiterals() {
        assertEquals("""
                global internal @count.static : i32 align 4 = { 0 : i32 0 }
                global internal @count.static.2 : i32 align 4 = { 0 : i32 7 }
                define @f() -> void {
                  ptr %t0
                  i32 %t1
                  i32 %t2
                  i32 %t3
                .entry:
                  %t0 = addrof @count.static
                  %t1 = load.s32 %t0
                  mov.s32 %t2, 1
                  %t3 = add.s32 %t1, %t2
                  store.32 %t0, %t3
                  ret
                }
                define @g() -> i32 {
                  ptr %t0
                  i32 %t1
                .entry:
                  %t0 = addrof @count.static.2
                  %t1 = load.s32 %t0
                  ret %t1
                }
                """, unit("void f(void) { static int count = 0; count++; } int g(void) { static int count = 7; return count; }"));
        assertEquals("""
                global internal @.lit.1 : [2 x i32] align 4 = { 0 : i32 1, 4 : i32 2 }
                global @p : ptr align 8 = { 0 : addr @.lit.1 }
                """, unit("int *p = (int[]){1, 2};"));
    }

    // ---- a small program end to end ---------------------------------------------------------------

    @Test
    void aStructPointerProgram() {
        assertEquals("""
                type %bar = { i32 @0 } size 4 align 4
                define @foo(ptr %p) -> i32 {
                  ptr %t0
                  i32 %t1
                  i32 %t2
                  i32 %t3
                  ptr %t4
                  i32 %t5
                  ptr %t6
                  i32 %t7
                .entry:
                  %t0 = wadd.u64 %p, 0
                  %t1 = load.s32 %t0
                  mov.s32 %t2, 5
                  %t3 = slt %t2, %t1
                  condbr %t3, .then, .if.done
                .then:
                  %t4 = wadd.u64 %p, 0
                  mov.s32 %t5, 120
                  store.32 %t4, %t5
                  br .if.done
                .if.done:
                  %t6 = wadd.u64 %p, 0
                  %t7 = load.s32 %t6
                  ret %t7
                }
                define @other() -> i32 {
                  %bar %b
                  ptr %t0
                  ptr %t1
                  i32 %t2
                  ptr %t3
                  i32 %t4
                .entry:
                  %t0 = addrof %b
                  %t1 = wadd.u64 %t0, 0
                  mov.s32 %t2, 5
                  store.32 %t1, %t2
                  %t3 = addrof %b
                  %t4 = call (ptr) -> i32 @foo(%t3)
                  ret %t4
                }
                """, unit("""
                struct bar {
                  int a;
                };

                int foo(struct bar *p) {
                  if (p->a > 5) {
                    p->a = 120;
                  }
                  return p->a;
                }

                int other() {
                  struct bar b;
                  b.a = 5;
                  return foo(&b);
                }
                """));
    }

    @Test
    void aFunctionPointerProgram() {
        assertEquals("""
                global internal @.str.H : [9 x i8] align 1 readonly = { 0 : bytes "Add: %d\\0a\\00" }
                global internal @.str.H : [14 x i8] align 1 readonly = { 0 : bytes "Multiply: %d\\0a\\00" }
                declare @stdin : ptr
                declare @stdout : ptr
                declare @stderr : ptr
                declare @printf(ptr, ...) -> i32
                define @add(i32 %a, i32 %b) -> i32 {
                  i32 %t0
                .entry:
                  %t0 = add.s32 %a, %b
                  ret %t0
                }
                define @multiply(i32 %a, i32 %b) -> i32 {
                  i32 %t0
                .entry:
                  %t0 = mul.s32 %a, %b
                  ret %t0
                }
                define @main() -> i32 {
                  ptr %operation
                  ptr %t0
                  ptr %t1
                  i32 %t2
                  i32 %t3
                  i32 %t4
                  i32 %t5
                  ptr %t6
                  ptr %t7
                  i32 %t8
                  i32 %t9
                  i32 %t10
                  i32 %t11
                  i32 %t12
                .entry:
                  %t0 = addrof @add
                  mov.u64 %operation, %t0
                  %t1 = addrof @.str.H
                  mov.s32 %t2, 3
                  mov.s32 %t3, 4
                  %t4 = icall (i32, i32) -> i32 %operation(%t2, %t3)
                  %t5 = call (ptr, ...) -> i32 @printf(%t1, %t4)
                  %t6 = addrof @multiply
                  mov.u64 %operation, %t6
                  %t7 = addrof @.str.H
                  mov.s32 %t8, 3
                  mov.s32 %t9, 4
                  %t10 = icall (i32, i32) -> i32 %operation(%t8, %t9)
                  %t11 = call (ptr, ...) -> i32 @printf(%t7, %t10)
                  mov.s32 %t12, 0
                  ret %t12
                }
                """, normalizeStrings(unit("""
                #include <stdio.h>

                int add(int a, int b)
                {
                    return a + b;
                }

                int multiply(int a, int b)
                {
                    return a * b;
                }

                int main(void)
                {
                    // Function pointer:
                    // points to a function taking two ints and returning an int
                    int (*operation)(int, int);

                    operation = add;
                    printf("Add: %d\\n", operation(3, 4));

                    operation = multiply;
                    printf("Multiply: %d\\n", operation(3, 4));

                    return 0;
                }
                """)));
    }
}
