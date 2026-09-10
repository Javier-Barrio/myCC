package org.jbm.vm;

import org.jbm.cc.arch.X86_64SysV;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.cpp.BundledHeaders;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.jbm.cc.lower.Lower;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Desugar;
import org.jbm.cc.sema.Resolver;
import org.jbm.cc.sema.Typer;
import org.jbm.cc.tac.Module;
import org.jbm.cc.tast.TUnit;
import org.jbm.cc.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole C programs through the compiler and the VM: the module is
 * loaded and {@code main} is called, and what it returns is what
 * {@code call} hands back. Only what the VM runs today is used:
 * registers, control flow and calls between defined functions; no
 * memory.
 */
class VmTest {

    static Module module(String source) {
        Types types = new Types(X86_64SysV.INSTANCE);
        CppTokenizer.TokenSet tokens = CppTokenizer.tokenSet(source, BundledHeaders.INSTANCE, "test.c");
        List<Decl> unit = Desugar.desugar(Parser.parse(TokenConversion.convert(new Scanner().expand(tokens))));
        TUnit typed = Typer.type(unit, Resolver.resolve(unit), types);
        return Lower.lower(typed, types);
    }

    static VM.Value run(String source) {
        VM vm = new VM();
        vm.step(module(source));
        return vm.call("main", List.of());
    }

    /** {@code expr} evaluated after {@code body} in a {@code long long main} with {@code decls} in scope. */
    static long integer(String decls, String body, String expr) {
        String source = decls + "\nlong long main(void) {\n" + body + "\nreturn " + expr + ";\n}\n";
        return ((VM.IntValue) run(source)).value();
    }

    static long integer(String expr) {
        return integer("", "", expr);
    }

    /** The same in a {@code double main}. */
    static double floating(String decls, String body, String expr) {
        String source = decls + "\ndouble main(void) {\n" + body + "\nreturn " + expr + ";\n}\n";
        return ((VM.FloatValue) run(source)).value();
    }

    @Test
    void integerArithmetic() {
        assertEquals(14, integer("2 + 3 * 4"));
        assertEquals(20, integer("(2 + 3) * 4"));
        assertEquals(3, integer("7 / 2"));
        assertEquals(-3, integer("-7 / 2"));
        assertEquals(-1, integer("-7 % 3"));
        assertEquals(1, integer("7 % -3"));
        assertEquals(1024, integer("1 << 10"));
        assertEquals(-4, integer("-16 >> 2"));
        assertEquals(0x30, integer("0xF0 & 0x3C"));
        assertEquals(0xFF, integer("0xF0 | 0x0F"));
        assertEquals(0xF0, integer("0xFF ^ 0x0F"));
        assertEquals(-1, integer("~0"));
        assertEquals(5, integer("-(-5)"));
        assertEquals(42, integer("", "int a = 6; int b = 7;", "a * b"));
        assertEquals(5, integer("", "int a = 5; a += 3; a *= 2; a -= 1; a /= 3;", "a"));
        assertEquals(757, integer("", "int a = 5; int b = a++; int c = ++a;", "a * 100 + b * 10 + c"));
    }

    @Test
    void widthsAndSignedness() {
        assertEquals(-56, integer("", "int v = 200;", "(char) v"));
        assertEquals(44, integer("", "int v = 300;", "(unsigned char) v"));
        assertEquals(4464, integer("", "int v = 70000;", "(short) v"));
        assertEquals(4464, integer("", "int v = 70000;", "(unsigned short) v"));
        assertEquals(1333333333, integer("", "unsigned u = 4000000000u;", "u / 3u"));
        assertEquals(3, integer("", "unsigned u = 4000000000u;", "u % 7u"));
        assertEquals(1, integer("", "int m = -1;", "(unsigned) m > 0u"));
        assertEquals(2147483647, integer("", "int m = -1;", "(unsigned) m / 2"));
        assertEquals(2147483647, integer("", "int m = -1;", "(unsigned) m >> 1"));
        assertEquals(-1, integer("", "int m = -1;", "m >> 1"));
        assertEquals(-2147483648L, integer("", "int big = 2147483647;", "big + 1"));
        assertEquals(0, integer("", "unsigned big = 4294967295u;", "big + 1u"));
        assertEquals(1099511627776L, integer("", "long long l = 1;", "l << 40"));
        assertEquals(-1, integer("", "long long l = -1;", "(unsigned long long) l"), "held as 64 bits");
        assertEquals(1, integer("", "long long l = -1;", "(unsigned long long) l == 18446744073709551615ULL"));
        assertEquals(Long.MAX_VALUE, integer("", "unsigned long long u = 18446744073709551615ULL;", "u / 2"));
        assertEquals(-128, integer("", "char c = 127; c++;", "c"));
        assertEquals(0, integer("", "unsigned char c = 255; c++;", "c"));
        assertEquals(255, integer("", "signed char c = -1;", "(unsigned char) c"));
        assertEquals(-1, integer("", "unsigned char c = 255;", "(signed char) c"));
        assertEquals(1, integer("", "bool b = 5;", "b"));
        assertEquals(4294967295L, integer("", "unsigned u = 4294967295u;", "u"), "an unsigned int is zero-extended");
        assertEquals(-1, integer("", "int i = -1;", "i"), "an int is sign-extended");
    }

    @Test
    void floatingArithmetic() {
        assertEquals(3.75, floating("", "double d = 1.5;", "d + 2.25"));
        assertEquals(2.5, floating("", "double d = 10;", "d / 4"));
        assertEquals(3.5, floating("", "int i = 7;", "i / 2.0"));
        assertEquals(3, integer("", "double d = 3.99;", "(int) d"));
        assertEquals(-3, integer("", "double d = -3.99;", "(int) d"));
        assertEquals(3, integer("", "double d = 3.5;", "(unsigned) d"));
        assertEquals(-3.0, floating("", "int i = -3;", "(double) i"));
        assertEquals(4294967295.0, floating("", "unsigned u = 4294967295u;", "(double) u"));
        assertEquals(1.8446744073709552E19, floating("", "unsigned long long u = 18446744073709551615ULL;", "(double) u"));
        assertEquals(1, integer("", "double d = 0.1;", "(float) d != d"));
        assertEquals((double) (float) 0.1, floating("", "double d = 0.1;", "(float) d"));
        assertEquals(0.5, floating("", "double d = 0.5;", "(double) (float) d"));
        assertEquals((double) (1.0f / 3.0f), floating("", "float f = 1.0f;", "f / 3.0f"));
        assertEquals(Double.POSITIVE_INFINITY, floating("", "double z = 0.0;", "1.0 / z"));
        assertEquals(0, integer("", "double z = 0.0;", "z / z == z / z"), "NaN is not equal to itself");
        assertEquals(1, integer("", "double z = 0.0;", "z / z != z / z"));
        assertEquals(1, integer("", "double a = 2.5; double b = 2.5;", "a <= b && !(a < b) && a >= b"));
        assertEquals(9007199254740992.0, floating("", "long long l = 9007199254740993LL;", "(double) l"));
        assertEquals(9007199254740992L, integer("", "double d = 9007199254740992.0;", "(long long) d"));
    }

    @Test
    void comparisonsAndLogic() {
        assertEquals(1, integer("3 < 5 && 5 > 3 && 3 <= 3 && 3 >= 3 && 3 != 5 && 3 == 3"));
        assertEquals(1, integer("!(5 < 3) && !0 == 1 && !7 == 0"));
        assertEquals(1, integer("", "int a = 0; int b = 1;", "(a && b) == 0 && (a || b) == 1 && (a || 0) == 0"));
        assertEquals(1, integer("", "int a = -1; unsigned u = 1;", "a < 0 && (unsigned) a > u"));
        assertEquals(10, integer("", "int t = 1;", "t ? 10 : 20"));
        assertEquals(20, integer("", "int t = 1;", "!t ? 10 : 20"));
        assertEquals(0, integer("int id(int v) { return v; }", "int x = id(0);", "x && 1 / x"), "short circuit");
        assertEquals(1, integer("int id(int v) { return v; }", "int x = id(0);", "1 || 1 / x"));
    }

    @Test
    void loops() {
        assertEquals(55, integer("", "int s = 0; int i = 1; while (i <= 10) { s += i; i++; }", "s"));
        assertEquals(30, integer("", "int s = 0; for (int i = 0; i < 5; i++) { s += i * i; }", "s"));
        assertEquals(3, integer("", "int n = 0; do { n++; } while (n < 3);", "n"));
        assertEquals(1, integer("", "int n = 0; do { n++; } while (0);", "n"));
        assertEquals(30, integer("", "int s = 0; for (int i = 0; i < 100; i++) { if (i % 2) { continue; } if (i > 10) { break; } s += i; }", "s"));
        assertEquals(6, integer("", "int i = 0; int s = 0; again: s += i; i++; if (i < 4) { goto again; }", "s"));
        assertEquals(12, integer("", "int n = 0; for (int i = 0; i < 3; i++) { for (int j = 0; j < 4; j++) { n++; } }", "n"));
    }

    @Test
    void switches() {
        String f = """
                int classify(int v) {
                    switch (v) {
                        case 1: return 10;
                        case 2:
                        case 3: return 23;
                        case 4: v += 100;
                        case 5: return v;
                        default: return -1;
                    }
                }
                int ranged(int v) {
                    switch (v) {
                        case 10 ... 19: return 1;
                        case -5: return 2;
                        default: return 0;
                    }
                }
                """;
        assertEquals(10, integer(f, "", "classify(1)"));
        assertEquals(23, integer(f, "", "classify(2)"));
        assertEquals(23, integer(f, "", "classify(3)"));
        assertEquals(104, integer(f, "", "classify(4)"));
        assertEquals(5, integer(f, "", "classify(5)"));
        assertEquals(-1, integer(f, "", "classify(9)"));
        assertEquals(1, integer(f, "", "ranged(10) == 1 && ranged(19) == 1 && ranged(9) == 0 && ranged(20) == 0"));
        assertEquals(2, integer(f, "", "ranged(-5)"));
        assertEquals(1, integer("", "int n = 0; switch (3) { case 3: n = 1; break; case 4: n = 2; }", "n"));
        assertEquals(0, integer("", "int n = 0; switch (7) { case 3: n = 1; break; }", "n"));
    }

    @Test
    void calls() {
        String f = """
                int fact(int n) { if (n <= 1) { return 1; } return n * fact(n - 1); }
                int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
                int is_odd(int n);
                int is_even(int n) { return n == 0 ? 1 : is_odd(n - 1); }
                int is_odd(int n) { return n == 0 ? 0 : is_even(n - 1); }
                int sum6(int a, int b, int c, int d, int e, int f) { return a + b + c + d + e + f; }
                int add(int a, int b) { return a + b; }
                int mul(int a, int b) { return a * b; }
                void nothing(int x) { x = x + 1; }
                double avg(double a, double b) { return (a + b) / 2; }
                long long wide(long long a, int b) { return a * b; }
                unsigned char narrow(int v) { return v; }
                bool truth(int v) { return v; }
                """;
        assertEquals(3628800, integer(f, "", "fact(10)"));
        assertEquals(610, integer(f, "", "fib(15)"));
        assertEquals(1, integer(f, "", "is_even(10) && is_odd(7) && !is_even(3)"));
        assertEquals(21, integer(f, "", "sum6(1, 2, 3, 4, 5, 6)"));
        assertEquals(26, integer(f, "", "add(mul(2, 3), mul(4, 5))"));
        assertEquals(5, integer(f, "int x = 5; nothing(x);", "x"));
        assertEquals(1.5, floating(f, "", "avg(1.0, 2.0)"));
        assertEquals(3.5, floating(f, "", "avg(3, 4)"));
        assertEquals(17179869184L, integer(f, "", "wide(4294967296LL, 4)"));
        assertEquals(44, integer(f, "", "narrow(300)"));
        assertEquals(1, integer(f, "", "truth(9) == 1 && truth(0) == 0"));
        assertEquals(624, integer(f, "int a = fact(3); int b = fact(4);", "a * 100 + b"));
    }

    @Test
    void voidMainAndMissingFile() {
        assertNull(run("void main(void) { int x = 1; x++; }"));
        assertNull(run("void main(void) { return; }"));
        assertNull(new VM().step(module("int g(void) { return 1; }")), "no .file: step runs nothing");
    }

    @Test
    void callByNameWithArguments() {
        VM vm = new VM();
        vm.step(module("int add(int a, int b) { return a + b; }\ndouble half(double d) { return d / 2; }"));
        VM.Value sum = vm.call("add", List.of(new VM.IntValue(40), new VM.IntValue(2)));
        assertEquals(42, ((VM.IntValue) sum).value());
        VM.Value h = vm.call("half", List.of(new VM.FloatValue(5)));
        assertEquals(2.5, ((VM.FloatValue) h).value());
        String m = assertThrows(IllegalStateException.class, () -> vm.call("add", List.of())).getMessage();
        assertEquals("@add takes 2 arguments, given 0", m);
        String u = assertThrows(IllegalStateException.class, () -> vm.call("nope", List.of())).getMessage();
        assertEquals("no definition for @nope", u);
    }

    @Test
    void faultsCarryTheLocation() {
        String source = "int main(void) {\n    int z = 0;\n    return 1 / z;\n}\n";
        String message = assertThrows(IllegalStateException.class, () -> run(source)).getMessage();
        assertEquals("division by zero at test.c:3:14", message);

        String undefined = "int f(int);\nint main(void) { return f(1); }\n";
        String m2 = assertThrows(IllegalStateException.class, () -> run(undefined)).getMessage();
        assertEquals("no definition for @f at test.c:2:26", m2);
    }

    @Test
    void aFaultLeavesTheVmUsable() {
        VM vm = new VM();
        vm.step(module("int f(int n) { return n / 0 + f(n); }\nint main(void) { return f(1); }"));
        assertThrows(IllegalStateException.class, () -> vm.call("main", List.of()));
        vm.step(module("int main(void) { return 7; }"));
        assertEquals(7, ((VM.IntValue) vm.call("main", List.of())).value());
    }
}
