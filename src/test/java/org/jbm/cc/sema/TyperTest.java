package org.jbm.cc.sema;

import org.jbm.cc.ast.Decl;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.types.Ilp32;
import org.jbm.cc.types.Types;
import org.jbm.cc.types.X86_64SysV;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typing pass over the full pipeline. Declared types are rendered as
 * {@code name: type} in declaration order.
 */
class TyperTest {

    private static List<Decl> parse(String source) {
        return Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source)))));
    }

    private static Bindings type(String source, Types types) {
        var unit = parse(source);
        var bindings = Resolver.resolve(unit);
        Typer.type(unit, bindings, types);
        return bindings;
    }

    private static Bindings type(String source) {
        return type(source, new Types(X86_64SysV.INSTANCE));
    }

    /** File-scope symbols as {@code name: type}. */
    private static List<String> declaredTypes(String source) {
        return type(source).fileScope.stream().map(s -> s.name + ": " + s.type().spelling()).toList();
    }

    /** Every declarator and named parameter in the unit, in source order. */
    private static List<String> allTypes(String source) {
        var b = type(source);
        var symbols = new java.util.ArrayList<Symbol>();
        symbols.addAll(b.declarators.values());
        symbols.addAll(b.functions.values());
        symbols.addAll(b.parameters.values());
        return symbols.stream().distinct()
                .sorted(Comparator.comparingInt((Symbol s) -> s.declaredAt.line).thenComparingInt(s -> s.declaredAt.column))
                .map(s -> s.name + ": " + s.type().spelling()).toList();
    }

    private static SemaException fails(String source) {
        return assertThrows(SemaException.class, () -> type(source));
    }

    // ---- scalar declarations --------------------------------------------------------

    @Test
    void keywordTypesAndQualifiers() {
        assertEquals(List.of("a: int", "b: unsigned short", "c: signed char", "d: char", "e: long double",
                        "f: bool", "g: unsigned long long", "h: const int", "i: const volatile int"),
                declaredTypes("int a; unsigned short b; signed char c; char d; long double e; "
                        + "bool f; unsigned long long g; const int h; const volatile int i;"));
    }

    @Test
    void pointersArraysAndFunctions() {
        assertEquals(List.of("p: char *", "q: const char *", "r: char * const", "arr: int [3]", "m: int [2][3]",
                        "fp: int (*)(char)", "ap: int *[4]", "pa: int (*)[4]", "f: int (int *, int (*)(void), int)",
                        "v: void (void)", "va: int (const char *, ...)", "inc: int []"),
                declaredTypes("char *p; const char *q; char *const r; int arr[3]; int m[2][3]; int (*fp)(char); "
                        + "int *ap[4]; int (*pa)[4]; int f(int a[], int (*g)(void), const int c); void v(void); "
                        + "int va(const char *fmt, ...); int inc[];"));
    }

    @Test
    void typedefsAreResolvedOnceAndQualified() {
        assertEquals(List.of("T: int *", "t: int *", "ct: int * const", "A: int [2]", "ca: const int [2]",
                        "F: int (char)", "fp: int (*)(char)", "g: int (int (*)(char))"),
                declaredTypes("typedef int *T; T t; const T ct; typedef int A[2]; const A ca; "
                        + "typedef int F(char); F *fp; int g(F f);"));
    }

    @Test
    void typeofOfATypeName() {
        assertEquals(List.of("x: int", "y: int", "z: const int *", "w: const int"),
                declaredTypes("typeof(int) x; typeof_unqual(const int) y; typeof(const int *) z; const typeof(int) w;"));
    }

    @Test
    void parametersAndLocalsGetTypes() {
        // The function type drops the parameters' top-level qualifiers
        // (6.7.7.4p15); the parameter symbol itself keeps them.
        assertEquals(List.of("f: int (int *, int *, int (*)(int))", "a: int * const", "b: int *",
                        "h: int (*)(int)", "n: int", "l: char [2]", "inner: int (long)", "k: long"),
                allTypes("int f(int a[const 3], int b[static 2], int h(int n)) { char l[2]; int inner(long k); return 0; }"));
    }

    // ---- redeclarations ------------------------------------------------------------------

    @Test
    void compatibleRedeclarationsCompose() {
        assertEquals(List.of("a: int [3]"), declaredTypes("int a[]; int a[3]; int a[];"));
        assertEquals(List.of("g: int (int (*)[3], int (*)[3])"),
                declaredTypes("int g(int (*)[], int (*)[3]); int g(int (*)[3], int (*)[]);"));
        assertEquals(List.of("f: int (void)"), declaredTypes("int f(); int f(void) { return 0; }"));
        assertEquals(List.of("T: int"), declaredTypes("typedef int T; typedef int T;"));
        assertEquals(List.of("e: int"), declaredTypes("extern int e; int e; int e = 1;"));
    }

    @Test
    void conflictingRedeclarationsAreErrors() {
        assertTrue(fails("int x; long x;").getMessage().contains("conflicting types for 'x'"));
        fails("int f(void); int f(int);");
        fails("int a[2]; int a[3];");
        fails("typedef int T; typedef long T;");
        fails("int p; int *p;");
        fails("int f(int); int f(const int *);");
    }

    // ---- constraints ------------------------------------------------------------------------

    @Test
    void invalidTypesAreErrors() {
        assertTrue(fails("int a[0];").getMessage().contains("positive"));
        assertTrue(fails("int f(void)[3];").getMessage().contains("return an array"));
        assertTrue(fails("int f(void)(int);").getMessage().contains("return a function"));
        assertTrue(fails("void a[3];").getMessage().contains("incomplete element type"));
        assertTrue(fails("int m[][3]; int n[3][];").getMessage().contains("incomplete element type"));
        assertTrue(fails("int a[const 2];").getMessage().contains("only allowed in a parameter"));
        assertTrue(fails("_Complex float c;").getMessage().contains("not supported"));
        assertTrue(fails("struct S { int a; } s;").getMessage().contains("not supported"));
        assertTrue(fails("int n = 3; int a[n];").getMessage().contains("not supported"));
        assertTrue(fails("void f(void x);").getMessage().contains("'void'"));
    }

    @Test
    void typesAskTheTargetOnlyForNumbers() {
        var b = type("long l; int *p;", new Types(Ilp32.INSTANCE));
        assertEquals("long", b.fileScope.get(0).type().spelling());
        var t = new Types(Ilp32.INSTANCE);
        assertEquals(4, t.size(b.fileScope.get(0).type()));
        assertEquals(4, t.size(b.fileScope.get(1).type()));
    }
}
