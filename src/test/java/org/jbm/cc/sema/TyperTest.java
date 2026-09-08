package org.jbm.cc.sema;

import org.jbm.cc.ast.Decl;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.tast.TypedPrinter;
import org.jbm.cc.types.Ilp32;
import org.jbm.cc.types.Types;
import org.jbm.cc.types.X86_64SysV;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    /** Types {@code src} as an expression statement in a function after {@code decls}; prints the tree. */
    private static String expr(String decls, String src) {
        return exprOn(new Types(X86_64SysV.INSTANCE), decls, src);
    }

    private static String exprOn(Types types, String decls, String src) {
        var unit = parse(decls + "\nvoid probe__(void) { " + src + "; }");
        var typer = Typer.run(unit, Resolver.resolve(unit), types);
        return TypedPrinter.print(typer.expressionStatements.get(typer.expressionStatements.size() - 1));
    }

    private static SemaException exprFails(String decls, String src) {
        return assertThrows(SemaException.class, () -> expr(decls, src));
    }

    // ---- expressions: references, literals, arithmetic -----------------------------------

    @Test
    void referencesAndLiterals() {
        assertEquals("a:int", expr("int a;", "a"));
        assertEquals("f:int (void)", expr("int f(void);", "f"));
        assertEquals("5:int", expr("", "5"));
        assertEquals("2147483648:long", expr("", "2147483648"));
        assertEquals("9223372036854775807:long", expr("", "9223372036854775807"));
        assertTrue(exprFails("", "9223372036854775808").getMessage().contains("too large"));
    }

    @Test
    void integerConstantsTakeTheFirstTypeThatHoldsThem() {
        // 6.4.5.2p6: decimal constants never become unsigned by size; others do.
        assertEquals("4294967295:long", expr("", "4294967295"));
        assertEquals("4294967295:unsigned int", expr("", "0xFFFFFFFF"));
        assertEquals("-9223372036854775808:unsigned long", expr("", "0x8000000000000000"));
        assertEquals("-1:unsigned long", expr("", "18446744073709551615u"));
        assertEquals("1:unsigned int", expr("", "1u"));
        assertEquals("1:long", expr("", "1l"));
        assertEquals("1:unsigned long", expr("", "1UL"));
        assertEquals("1:unsigned long", expr("", "1lu"));
        assertEquals("1:long long", expr("", "1ll"));
        assertEquals("1:unsigned long long", expr("", "1ull"));
        assertEquals("7:int", expr("", "07"));
        assertEquals("16:int", expr("", "0x10"));
        assertEquals("5:int", expr("", "0b101"));
        assertEquals("8:int", expr("", "0o10"));
        assertEquals("1000000:int", expr("", "1'000'000"));
        assertEquals("0:int", expr("", "0"));
        assertEquals("2147483648:unsigned int", expr("", "0x80000000"));
        assertTrue(exprFails("", "0x10000000000000000").getMessage().contains("too large"));
        assertTrue(exprFails("", "18446744073709551615").getMessage().contains("too large for its type"));
        assertTrue(exprFails("", "1wb").getMessage().contains("not supported"));
    }

    @Test
    void floatingConstants() {
        assertEquals("1.5:double", expr("", "1.5"));
        assertEquals("1.5:float", expr("", "1.5f"));
        assertEquals("1.5:long double", expr("", "1.5L"));
        assertEquals("1000.0:double", expr("", "1e3"));
        assertEquals("16.0:double", expr("", "0x1p4"));
        assertEquals("1.5:double", expr("", "0x1.8p0"));
        assertEquals("0.1:double", expr("", ".1"));
        assertEquals("0.10000000149011612:float", expr("", "0.1f"), "rounded to float precision");
        assertTrue(exprFails("", "1.0i").getMessage().contains("not supported"));
        assertTrue(exprFails("", "1.0df").getMessage().contains("not supported"));
    }

    @Test
    void characterConstants() {
        assertEquals("97:int", expr("", "'a'"));
        assertEquals("10:int", expr("", "'\\n'"));
        assertEquals("39:int", expr("", "'\\''"));
        assertEquals("0:int", expr("", "'\\0'"));
        assertEquals("-1:int", expr("", "'\\xff'"), "char is signed on x86-64");
        assertEquals("255:int", exprOn(new Types(Ilp32.INSTANCE), "", "'\\xff'"), "unsigned on the ILP32 target");
        assertEquals("120:int", expr("", "L'x'"), "wchar_t is int");
        assertEquals("120:unsigned short", expr("", "u'x'"));
        assertEquals("120:unsigned int", expr("", "U'x'"));
        assertEquals("120:unsigned char", expr("", "u8'x'"));
        assertEquals("233:unsigned int", expr("", "U'\\u00e9'"));
        assertEquals("1:bool", expr("", "true"));
        assertEquals("0:bool", expr("", "false"));
        assertTrue(exprFails("", "'ab'").getMessage().contains("multi-character"));
        assertTrue(exprFails("", "'\\q'").getMessage().contains("unknown escape"));
    }

    @Test
    void stringLiteralsAreAnonymousStaticArrays() {
        assertEquals("\"ab\":char [3]", expr("", "\"ab\""));
        assertEquals("\"a\" \"b\":char [3]", expr("", "\"a\" \"b\""));
        assertEquals("\"a\\n\":char [3]", expr("", "\"a\\n\""));
        assertEquals("u8\"a\":unsigned char [2]", expr("", "u8\"a\""));
        assertEquals("u\"a\":unsigned short [2]", expr("", "u\"a\""));
        assertEquals("U\"a\":unsigned int [2]", expr("", "U\"a\""));
        assertEquals("L\"ab\":int [3]", expr("", "L\"ab\""));
        assertEquals("\"\":char [1]", expr("", "\"\""));
        assertTrue(exprFails("", "u\"a\" U\"b\"").getMessage().contains("cannot concatenate"));

        var unit = parse("void f(void) { \"h\\xffi\"; \"\\u00e9\"; L\"\\xffff\"; R\"x(a\\n)x\"; \"\" \"\"; }");
        var typer = Typer.run(unit, Resolver.resolve(unit), new Types(X86_64SysV.INSTANCE));
        var strings = typer.strings();
        assertEquals(5, strings.size());
        assertEquals(List.of(104, 255, 105, 0), units(strings.get(0)));
        assertEquals(List.of(0xC3, 0xA9, 0), units(strings.get(1)), "UTF-8 encoded");
        assertEquals(List.of(0xFFFF, 0), units(strings.get(2)), "one wchar_t unit");
        assertEquals(List.of(97, 92, 110, 0), units(strings.get(3)), "raw: backslash and n stay");
        assertEquals(List.of(0), units(strings.get(4)));
        assertEquals("char [4]", strings.get(0).symbol().type().spelling());
        assertTrue(strings.get(0).symbol() instanceof Symbol.Variable v && v.storage == Symbol.Variable.Storage.STATIC);
        assertNotSame(strings.get(4).symbol(), strings.get(3).symbol(), "each literal is its own object");
    }

    private static List<Integer> units(org.jbm.cc.tast.StringData s) {
        return java.util.Arrays.stream(s.units()).boxed().toList();
    }

    @Test
    void floatingConversions() {
        assertEquals("(add:double (int-to-float:double 1:int) 1.5:double)", expr("", "1 + 1.5"));
        assertEquals("(add:float (rv:float f:float) (int-to-float:float 1:int))", expr("float f;", "f + 1"));
        assertEquals("(mul:double (float-to-float:double (rv:float f:float)) (rv:double d:double))",
                expr("float f; double d;", "f * d"));
        assertEquals("(div:long double (rv:long double l:long double) (float-to-float:long double 2.0:double))",
                expr("long double l;", "l / 2.0"));
        // 6.3.2.2: with a floating operand the integer one converts directly,
        // without an intermediate integer promotion.
        assertEquals("(sub:float (rv:float f:float) (int-to-float:float (rv:char c:char)))",
                expr("float f; char c;", "f - c"));
    }

    @Test
    void additionPromotesAndConverts() {
        assertEquals("(add:int (rv:int a:int) (int-to-int:int (rv:char b:char)))", expr("int a; char b;", "a + b"));
        assertEquals("(add:int (rv:int a:int) 1:int)", expr("int a;", "a + 1"));
        assertEquals("(add:int (int-to-int:int (rv:short s:short)) (int-to-int:int (rv:unsigned char c:unsigned char)))",
                expr("short s; unsigned char c;", "s + c"));
        assertEquals("(add:unsigned int (int-to-int:unsigned int (rv:int a:int)) (rv:unsigned int u:unsigned int))",
                expr("int a; unsigned u;", "a + u"));
        assertEquals("(add:long (rv:long l:long) (int-to-int:long (rv:unsigned int u:unsigned int)))",
                expr("long l; unsigned u;", "l + u"));
        assertEquals("(add:unsigned long (int-to-int:unsigned long (rv:long l:long)) (rv:unsigned long u:unsigned long))",
                expr("long l; unsigned long u;", "l + u"));
        // The harness returns the statement's operand as typed; lvalue
        // conversion for the void context is the statement's job (step 13).
        assertEquals("c:const int", expr("const int c;", "c"));
        assertEquals("(add:int (rv:int c:const int) 1:int)", expr("const int c;", "c + 1"));
    }

    @Test
    void theFourArithmeticOperatorsAndPrecedence() {
        assertEquals("(sub:int (rv:int a:int) (mul:int (rv:int b:int) (rv:int c:int)))",
                expr("int a, b, c;", "a - b * c"));
        assertEquals("(div:int (mul:int (rv:int a:int) (rv:int b:int)) (rv:int c:int))",
                expr("int a, b, c;", "a * b / c"));
        assertEquals("(mul:int (add:int (rv:int a:int) (rv:int b:int)) 2:int)", expr("int a, b;", "(a + b) * 2"));
    }

    @Test
    void parametersAreLvaluesToo() {
        var unit = parse("int f(int p, char q) { p + q; return 0; }");
        var typer = Typer.run(unit, Resolver.resolve(unit), new Types(X86_64SysV.INSTANCE));
        assertEquals("(add:int (rv:int p:int) (int-to-int:int (rv:char q:char)))",
                TypedPrinter.print(typer.expressionStatements.get(0)));
    }

    @Test
    void invalidOperands() {
        assertTrue(exprFails("int *p;", "p * 2").getMessage().contains("invalid operands to binary *"));
        assertTrue(exprFails("int f(void);", "f + 1").getMessage().contains("invalid operands"));
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
