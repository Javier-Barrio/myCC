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
import static org.junit.jupiter.api.Assertions.assertSame;
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

    private static org.jbm.cc.tast.TExpr exprTree(String decls, String src) {
        var unit = parse(decls + "\nvoid probe__(void) { " + src + "; }");
        var typer = Typer.run(unit, Resolver.resolve(unit), new Types(X86_64SysV.INSTANCE));
        return typer.expressionStatements.get(typer.expressionStatements.size() - 1);
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
    void integerOnlyOperators() {
        assertEquals("(rem:int (rv:int a:int) 2:int)", expr("int a;", "a % 2"));
        assertEquals("(bitand:unsigned int (rv:unsigned int u:unsigned int) (int-to-int:unsigned int 1:int))",
                expr("unsigned u;", "u & 1"));
        assertEquals("(bitor:int (int-to-int:int (rv:char c:char)) (int-to-int:int (rv:short s:short)))",
                expr("char c; short s;", "c | s"));
        assertEquals("(bitxor:long (rv:long l:long) (int-to-int:long 1:int))", expr("long l;", "l ^ 1"));
        assertTrue(exprFails("", "1.5 % 2").getMessage().contains("invalid operands to binary %"));
        assertTrue(exprFails("double d;", "d & 1").getMessage().contains("invalid operands"));
    }

    @Test
    void shiftsPromoteEachOperandAlone() {
        assertEquals("(shl:int (int-to-int:int (rv:char c:char)) (rv:long l:long))", expr("char c; long l;", "c << l"));
        assertEquals("(shr:unsigned long (rv:unsigned long u:unsigned long) 1:int)", expr("unsigned long u;", "u >> 1"));
        assertEquals("(shl:int (int-to-int:int (rv:short s:short)) (int-to-int:int (rv:short s:short)))",
                expr("short s;", "s << s"));
        assertTrue(exprFails("int *p;", "p << 1").getMessage().contains("invalid operands to binary <<"));
        assertTrue(exprFails("", "1 << 1.0").getMessage().contains("invalid operands"));
    }

    @Test
    void comparisonsYieldInt() {
        assertEquals("(lt:int (rv:int a:int) (int-to-int:int (rv:char b:char)))", expr("int a; char b;", "a < b"));
        assertEquals("(eq:int (int-to-float:double (rv:int a:int)) (rv:double d:double))", expr("int a; double d;", "a == d"));
        assertEquals("(ge:int (int-to-int:unsigned int (rv:int a:int)) (rv:unsigned int u:unsigned int))",
                expr("int a; unsigned u;", "a >= u"));
        assertEquals("(ne:int 1:int 2:int)", expr("", "1 != 2"));
        assertEquals("(le:int (rv:int a:int) (rv:int b:int))", expr("int a, b;", "a <= b"));
        assertEquals("(gt:int (rv:int a:int) (rv:int b:int))", expr("int a, b;", "a > b"));
        assertEquals("(eq:int (lt:int (rv:int a:int) (rv:int b:int)) (rv:int c:int))", expr("int a, b, c;", "a < b == c"));
    }

    @Test
    void logicalOperatorsTestAgainstZero() {
        assertEquals("(and:int (to-bool:bool (rv:int a:int)) (to-bool:bool (rv:double d:double)))",
                expr("int a; double d;", "a && d"));
        assertEquals("(or:int (to-bool:bool (rv:int * p:int *)) (rv:bool b:bool))", expr("int *p; bool b;", "p || b"));
        assertEquals("(and:int (to-bool:bool (rv:int a:int)) (to-bool:bool (or:int (to-bool:bool (rv:int b:int)) (to-bool:bool (rv:int c:int)))))",
                expr("int a, b, c;", "a && (b || c)"));
        assertTrue(exprFails("void f(void);", "f() && 1").getMessage().contains("not supported"));
    }

    @Test
    void unaryOperators() {
        assertEquals("(int-to-int:int (rv:char c:char))", expr("char c;", "+c"), "unary + is just the promotion");
        assertEquals("(rv:int a:int)", expr("int a;", "+a"));
        assertEquals("(neg:int (int-to-int:int (rv:short s:short)))", expr("short s;", "-s"));
        assertEquals("(neg:double (rv:double d:double))", expr("double d;", "-d"));
        assertEquals("(neg:unsigned int (rv:unsigned int u:unsigned int))", expr("unsigned u;", "-u"));
        assertEquals("(bitnot:int (int-to-int:int (rv:unsigned char c:unsigned char)))", expr("unsigned char c;", "~c"));
        assertEquals("(not:int (to-bool:bool (rv:int a:int)))", expr("int a;", "!a"));
        assertEquals("(not:int (rv:bool b:bool))", expr("bool b;", "!b"));
        assertEquals("(not:int (to-bool:bool (rv:int * p:int *)))", expr("int *p;", "!p"));
        assertEquals("(neg:int 1:int)", expr("", "-1"));
        assertTrue(exprFails("double d;", "~d").getMessage().contains("invalid operand to unary ~"));
        assertTrue(exprFails("int *p;", "-p").getMessage().contains("invalid operand to unary -"));
    }

    @Test
    void conditionalAndComma() {
        assertEquals("(cond:double (to-bool:bool (rv:int c:int)) (int-to-float:double (rv:int a:int)) (rv:double d:double))",
                expr("int c, a; double d;", "c ? a : d"));
        assertEquals("(cond:int (rv:bool b:bool) 1:int (int-to-int:int (rv:char ch:char)))",
                expr("bool b; char ch;", "b ? 1 : ch"));
        assertEquals("(comma:int (to-void:void (rv:int a:int)) (rv:int b:int))", expr("int a, b;", "a, b"));
        assertEquals("(comma:double (to-void:void (add:int (rv:int a:int) 1:int)) (rv:double d:double))",
                expr("int a; double d;", "a + 1, d"));
        assertEquals("(cond:int (to-bool:bool (rv:int * p:int *)) 1:int 2:int)", expr("int *p;", "p ? 1 : 2"));
        assertTrue(exprFails("int *p;", "1 ? p : 2").getMessage().contains("not supported"));
        assertTrue(exprFails("void f(void);", "f() ? 1 : 2").getMessage().contains("not supported"));
    }

    // ---- pointers -----------------------------------------------------------------------------

    @Test
    void dereferenceAndAddressOf() {
        assertEquals("(deref:int (rv:int * p:int *))", expr("int *p;", "*p"));
        assertEquals("(add:int (rv:int (deref:int (rv:int * p:int *))) 0:int)", expr("int *p;", "*p + 0"));
        assertEquals("(deref:const int (rv:const int * p:const int *))", expr("const int *p;", "*p"));
        assertEquals("(addr:int * a:int)", expr("int a;", "&a"));
        assertEquals("(addr:const int * c:const int)", expr("const int c;", "&c"));
        assertEquals("(addr:int (*)[3] arr:int [3])", expr("int arr[3];", "&arr"));
        assertEquals("(rv:int * p:int *)", expr("int *p;", "&*p"), "&*p is p");
        assertEquals("(fdecay:int (*)(void) f:int (void))", expr("int f(void);", "&f"), "&f is f's decay");
        assertEquals("(fderef:int (void) (rv:int (*)(void) fp:int (*)(void)))", expr("int (*fp)(void);", "*fp"));
        assertEquals("(deref:int (rv:int * (deref:int * (rv:int * * pp:int * *))))", expr("int **pp;", "**pp"));
        assertTrue(exprFails("int a;", "*a").getMessage().contains("indirection requires a pointer"));
        assertTrue(exprFails("void *v;", "*v").getMessage().contains("pointer to void"));
        assertTrue(exprFails("int a;", "&(a + 1)").getMessage().contains("address of an rvalue"));
    }

    @Test
    void subscriptingIsDereferencedPointerArithmetic() {
        assertEquals("(deref:int (ptradd:int * (decay:int * a:int [3]) (int-to-int:long (rv:int i:int))))",
                expr("int a[3]; int i;", "a[i]"));
        assertEquals("(deref:int (ptradd:int * (rv:int * p:int *) (int-to-int:long 2:int)))", expr("int *p;", "p[2]"));
        assertEquals("(deref:int (ptradd:int * (decay:int * a:int [3]) (int-to-int:long 1:int)))", expr("int a[3];", "1[a]"));
        assertEquals("(deref:int (ptradd:int * (decay:int * (deref:int [3] (ptradd:int (*)[3] (decay:int (*)[3] m:int [2][3]) (int-to-int:long 1:int)))) (int-to-int:long 2:int)))",
                expr("int m[2][3];", "m[1][2]"));
        assertEquals("(ptradd:int * (decay:int * a:int [3]) (int-to-int:long (rv:int i:int)))", expr("int a[3]; int i;", "&a[i]"));
        assertEquals("(deref:const char (ptradd:const char * (rv:const char * s:const char *) (rv:long l:long)))",
                expr("const char *s; long l;", "s[l]"));
        assertTrue(exprFails("int a;", "a[0]").getMessage().contains("not an array or pointer"));
        assertTrue(exprFails("int *p;", "p[1.5]").getMessage().contains("invalid operands"));
        assertTrue(exprFails("void *v;", "v[0]").getMessage().contains("incomplete type"));
        assertTrue(exprFails("int (*fp)(void);", "fp[0]").getMessage().contains("pointer to a function"));
    }

    @Test
    void pointerArithmetic() {
        assertEquals("(ptradd:int * (rv:int * p:int *) (int-to-int:long (rv:int i:int)))", expr("int *p; int i;", "p + i"));
        assertEquals("(ptradd:int * (rv:int * p:int *) (int-to-int:long (rv:int i:int)))", expr("int *p; int i;", "i + p"));
        assertEquals("(ptradd:int * (rv:int * p:int *) (neg:long (int-to-int:long (rv:int i:int))))",
                expr("int *p; int i;", "p - i"));
        assertEquals("(ptradd:int * (rv:int * p:int *) (rv:long l:long))", expr("int *p; long l;", "p + l"));
        assertEquals("(ptrdiff:long (rv:int * p:int *) (rv:int * q:int *))", expr("int *p, *q;", "p - q"));
        assertEquals("(ptrdiff:long (rv:const int * p:const int *) (rv:int * q:int *))", expr("const int *p; int *q;", "p - q"));
        assertEquals("(ptrdiff:long (decay:int * a:int [3]) (rv:int * q:int *))", expr("int a[3]; int *q;", "a - q"));
        assertTrue(exprFails("int *p; int *q;", "p + q").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p; int i;", "i - p").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p; long *q;", "p - q").getMessage().contains("invalid operands"));
        assertTrue(exprFails("void *v;", "v + 1").getMessage().contains("incomplete type"));
        assertTrue(exprFails("int *p; double d;", "p + d").getMessage().contains("invalid operands"));
    }

    @Test
    void pointerComparisonsAndNullPointerConstants() {
        assertEquals("(eq:int (rv:int * p:int *) (rv:int * q:int *))", expr("int *p, *q;", "p == q"));
        assertEquals("(lt:int (rv:int * p:int *) (rv:int * q:int *))", expr("int *p, *q;", "p < q"));
        assertEquals("(ne:int (rv:int * p:int *) (null:int * 0:int))", expr("int *p;", "p != 0"));
        assertEquals("(eq:int (null:int * 0:int) (rv:int * p:int *))", expr("int *p;", "0 == p"));
        assertEquals("(eq:int (rv:int * p:int *) (null:int * nullptr:nullptr_t))", expr("int *p;", "p == nullptr"));
        assertEquals("(eq:int (ptr-to-ptr:void * (rv:int * p:int *)) (rv:void * v:void *))", expr("int *p; void *v;", "p == v"));
        assertEquals("(eq:int (rv:const int * p:const int *) (ptr-to-ptr:const int * (rv:int * q:int *)))",
                expr("const int *p; int *q;", "p == q"));
        assertEquals("(eq:int (ptr-to-ptr:const void * (rv:int * p:int *)) (rv:const void * v:const void *))",
                expr("int *p; const void *v;", "p == v"));
        assertEquals("(eq:int (fdecay:int (*)(void) f:int (void)) (null:int (*)(void) 0:int))", expr("int f(void);", "f == 0"));
        assertTrue(exprFails("int *p; long *q;", "p == q").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p; void *v;", "p < v").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p;", "p < 0").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p;", "p == 1").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int (*f)(void); void *v;", "f == v").getMessage().contains("invalid operands"));
    }

    @Test
    void conditionalWithPointerArms() {
        assertEquals("(cond:int * (to-bool:bool (rv:int c:int)) (rv:int * p:int *) (rv:int * q:int *))",
                expr("int c; int *p, *q;", "c ? p : q"));
        assertEquals("(cond:int * (to-bool:bool (rv:int c:int)) (rv:int * p:int *) (null:int * 0:int))",
                expr("int c; int *p;", "c ? p : 0"));
        assertEquals("(cond:const int * (to-bool:bool (rv:int c:int)) (rv:const int * p:const int *) (ptr-to-ptr:const int * (rv:int * q:int *)))",
                expr("int c; const int *p; int *q;", "c ? p : q"));
        assertEquals("(cond:void * (to-bool:bool (rv:int c:int)) (ptr-to-ptr:void * (rv:int * p:int *)) (rv:void * v:void *))",
                expr("int c; int *p; void *v;", "c ? p : v"));
        assertEquals("(cond:int * (to-bool:bool (rv:int c:int)) (decay:int * a:int [2]) (rv:int * p:int *))",
                expr("int c; int a[2]; int *p;", "c ? a : p"));
        assertEquals("(cond:nullptr_t (to-bool:bool (rv:int c:int)) nullptr:nullptr_t nullptr:nullptr_t)",
                expr("int c;", "c ? nullptr : nullptr"));
        assertTrue(exprFails("int c; int *p; long *q;", "c ? p : q").getMessage().contains("not supported"));
    }

    // ---- assignment ---------------------------------------------------------------------------

    @Test
    void simpleAssignmentConvertsAsIfByAssignment() {
        assertEquals("(assign:int a:int (rv:int b:int))", expr("int a, b;", "a = b"));
        assertEquals("(assign:char c:char (int-to-int:char 300:int))", expr("char c;", "c = 300"));
        assertEquals("(assign:double d:double (int-to-float:double (rv:int a:int)))", expr("double d; int a;", "d = a"));
        assertEquals("(assign:int a:int (float-to-int:int (rv:double d:double)))", expr("double d; int a;", "a = d"));
        assertEquals("(assign:bool b:bool (to-bool:bool (rv:int * p:int *)))", expr("bool b; int *p;", "b = p"));
        assertEquals("(assign:int * p:int * (null:int * 0:int))", expr("int *p;", "p = 0"));
        assertEquals("(assign:int * p:int * (null:int * nullptr:nullptr_t))", expr("int *p;", "p = nullptr"));
        assertEquals("(assign:const int * p:const int * (ptr-to-ptr:const int * (rv:int * q:int *)))",
                expr("const int *p; int *q;", "p = q"));
        assertEquals("(assign:void * v:void * (ptr-to-ptr:void * (rv:int * q:int *)))", expr("void *v; int *q;", "v = q"));
        assertEquals("(assign:int * q:int * (ptr-to-ptr:int * (rv:void * v:void *)))", expr("void *v; int *q;", "q = v"));
        assertEquals("(assign:int * p:int * (decay:int * a:int [3]))", expr("int *p; int a[3];", "p = a"));
        assertEquals("(assign:int (deref:int (rv:int * p:int *)) 1:int)", expr("int *p;", "*p = 1"));
        assertEquals("(assign:int (deref:int (ptradd:int * (decay:int * a:int [3]) (int-to-int:long 1:int))) 2:int)",
                expr("int a[3];", "a[1] = 2"));
        assertEquals("(assign:int a:int (assign:int b:int 3:int))", expr("int a, b;", "a = b = 3"), "right associative");
        assertEquals("(assign:int (*)(void) fp:int (*)(void) (fdecay:int (*)(void) f:int (void)))",
                expr("int (*fp)(void); int f(void);", "fp = f"));
    }

    @Test
    void assignmentConstraints() {
        assertTrue(exprFails("int a;", "1 = a").getMessage().contains("not an lvalue"));
        assertTrue(exprFails("int a, b;", "a + b = 1").getMessage().contains("not an lvalue"));
        assertTrue(exprFails("const int c;", "c = 1").getMessage().contains("const-qualified"));
        assertTrue(exprFails("int a[3];", "a = 0").getMessage().contains("array"));
        assertTrue(exprFails("int *p; long *q;", "p = q").getMessage().contains("incompatible types"));
        assertTrue(exprFails("int *p;", "p = 1").getMessage().contains("incompatible types"));
        assertTrue(exprFails("int *p; double d;", "p = d").getMessage().contains("incompatible types"));
        assertTrue(exprFails("int a; int *p;", "a = p").getMessage().contains("incompatible types"));
        assertTrue(exprFails("int *p; const int *q;", "p = q").getMessage().contains("discards qualifiers"));
        assertTrue(exprFails("int (*fp)(void); void *v;", "fp = v").getMessage().contains("incompatible types"));
        assertTrue(exprFails("int f(void);", "f = 0").getMessage().contains("not an lvalue"));
        assertTrue(exprFails("const char *s;", "*s = 'a'").getMessage().contains("const-qualified"));
    }

    @Test
    void compoundAssignmentComputesOverTheTargetValue() {
        assertEquals("(compound-assign:int i:int (add:int (target:int) 2:int))", expr("int i;", "i += 2"));
        assertEquals("(compound-assign:char c:char (float-to-int:char (add:double (int-to-float:double (target:char)) 1.5:double)))",
                expr("char c;", "c += 1.5"));
        assertEquals("(compound-assign:short s:short (int-to-int:short (sub:int (int-to-int:int (target:short)) 1:int)))",
                expr("short s;", "s -= 1"));
        assertEquals("(compound-assign:unsigned int u:unsigned int (shl:unsigned int (target:unsigned int) (rv:int n:int)))",
                expr("unsigned u; int n;", "u <<= n"));
        assertEquals("(compound-assign:int i:int (rem:int (target:int) (int-to-int:int (rv:char c:char))))",
                expr("int i; char c;", "i %= c"));
        assertEquals("(compound-assign:long l:long (bitor:long (target:long) (int-to-int:long 1:int)))", expr("long l;", "l |= 1"));
        assertEquals("(compound-assign:int * p:int * (ptradd:int * (target:int *) (int-to-int:long (rv:int n:int))))",
                expr("int *p; int n;", "p += n"));
        assertEquals("(compound-assign:int * p:int * (ptradd:int * (target:int *) (neg:long (int-to-int:long 1:int))))",
                expr("int *p;", "p -= 1"));
        assertEquals("(compound-assign:double d:double (mul:double (target:double) (int-to-float:double (rv:int i:int))))",
                expr("double d; int i;", "d *= i"));
        assertEquals("(compound-assign:int (deref:int (rv:int * p:int *)) (add:int (target:int) 1:int))",
                expr("int *p;", "*p += 1"));
        assertEquals("(compound-assign:int a:int (add:int (target:int) (compound-assign:int b:int (add:int (target:int) 1:int))))",
                expr("int a, b;", "a += b += 1"));
        assertTrue(exprFails("double d;", "d %= 2").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int i; int *p;", "i += p").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p, *q;", "p -= q").getMessage().contains("invalid operands"));
        assertTrue(exprFails("int *p;", "p *= 2").getMessage().contains("invalid operands"));
        assertTrue(exprFails("const int c;", "c += 1").getMessage().contains("const-qualified"));
        assertTrue(exprFails("int a, b;", "a + b += 1").getMessage().contains("not an lvalue"));
    }

    @Test
    void prefixIncrementIsCompoundAssignmentAlready() {
        // Desugar rewrote ++i to i += 1 before typing (6.5.4.1p2).
        assertEquals("(compound-assign:int i:int (add:int (target:int) 1:int))", expr("int i;", "++i"));
        assertEquals("(compound-assign:int * p:int * (ptradd:int * (target:int *) (neg:long (int-to-int:long 1:int))))",
                expr("int *p;", "--p"));
    }

    @Test
    void postfixIncrementYieldsTheOldValue() {
        assertEquals("(assign:int j:int (postfix-assign:int i:int (add:int (target:int) 1:int)))", expr("int i, j;", "j = i++"));
        assertEquals("(assign:int * q:int * (postfix-assign:int * p:int * (ptradd:int * (target:int *) (int-to-int:long 1:int))))",
                expr("int *p, *q;", "q = p++"));
        assertEquals("(assign:int j:int (int-to-int:int (postfix-assign:char c:char (int-to-int:char (sub:int (int-to-int:int (target:char)) 1:int)))))",
                expr("char c; int j;", "j = c--"));
        assertEquals("(assign:double e:double (postfix-assign:double d:double (add:double (target:double) (int-to-float:double 1:int))))",
                expr("double d, e;", "e = d++"));
        assertTrue(exprFails("bool b; int j;", "j = b++").getMessage().contains("increment"));
        assertTrue(exprFails("int a, b, j;", "j = (a + b)++").getMessage().contains("not an lvalue"));
        assertTrue(exprFails("const int c; int j;", "j = c++").getMessage().contains("const-qualified"));
    }

    @Test
    void targetValueSharesTheTargetNode() {
        var tree = exprTree("int a[4]; int k;", "a[k++] += 1");
        var outer = (org.jbm.cc.tast.TExpr.CompoundAssign) tree;
        assertEquals("(compound-assign:int (deref:int (ptradd:int * (decay:int * a:int [4]) (int-to-int:long (postfix-assign:int k:int (add:int (target:int) 1:int))))) (add:int (target:int) 1:int))",
                TypedPrinter.print(tree));
        var outerAdd = (org.jbm.cc.tast.TExpr.Add) outer.newValue();
        var outerTarget = (org.jbm.cc.tast.TExpr.TargetValue) outerAdd.left();
        assertSame(outer.target(), outerTarget.target(), "the TargetValue refers to the assignment's own target node");
        var deref = (org.jbm.cc.tast.TExpr.Deref) outer.target();
        var ptradd = (org.jbm.cc.tast.TExpr.PtrAdd) deref.pointer();
        var conv = (org.jbm.cc.tast.TExpr.IntToInt) ptradd.index();
        var inner = (org.jbm.cc.tast.TExpr.PostfixAssign) conv.operand();
        var innerTarget = (org.jbm.cc.tast.TExpr.TargetValue) ((org.jbm.cc.tast.TExpr.Add) inner.newValue()).left();
        assertSame(inner.target(), innerTarget.target(), "and the inner one to k, not to the outer target");
        assertNotSame(outer.target(), innerTarget.target());
    }

    @Test
    void invalidOperands() {
        assertTrue(exprFails("int *p;", "p * 2").getMessage().contains("invalid operands to binary *"));
        assertTrue(exprFails("int f(void);", "f + 1").getMessage().contains("pointer to a function"));
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
