package org.jbm.mycc.cc.backend.codegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Programs compiled to assembly, built with gcc, and run: the exit status is the check. */
class NativeTest {

    static java.util.stream.Stream<java.nio.file.Path> programs() throws java.io.IOException {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/test/resources/programs"))) {
            return files.filter(p -> p.toString().endsWith(".c")).sorted().toList().stream();
        }
    }

    /** The corpus the VM runs, natively: each program exits 0. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("programs")
    void corpusExitsWithZero(java.nio.file.Path program) throws Exception {
        Native.Run r = Native.run(java.nio.file.Files.readString(program));
        assertEquals(0, r.exit(), "CHECK " + r.exit() + " failed in " + program.getFileName() + "\n" + r.out());
    }

    @Test
    void aConstantReturnRuns() throws Exception {
        assertEquals(42, Native.run("int main(void) { return 42; }").exit());
    }

    @Test
    void arithmeticRuns() throws Exception {
        assertEquals(42, Native.run("int main(void) { int a = 6; int b = 7; return a * b; }").exit());
        assertEquals(3, Native.run("int main(void) { int a = 7; int b = 2; return a / b + (a % b) * 0; }").exit());
        assertEquals(1, Native.run("int main(void) { int a = -7; int b = 2; return a / b == -3 && a % b == -1; }").exit());
        assertEquals(1, Native.run("int main(void) { unsigned a = 4000000000u; return a / 3u == 1333333333u && a % 7u == 3u; }").exit());
        assertEquals(1, Native.run("int main(void) { int m = -1; return (unsigned) m >> 1 == 2147483647u && m >> 1 == -1 && (1 << 10) == 1024; }").exit());
        assertEquals(1, Native.run("int main(void) { int big = 2147483647; big = big + 1; return big == -2147483648; }").exit());
        assertEquals(1, Native.run("int main(void) { char c = 127; c++; unsigned char u = 255; u++; return c == -128 && u == 0; }").exit());
        assertEquals(1, Native.run("int main(void) { long long l = 1; return (l << 40) == 1099511627776LL && (0xF0 & 0x3C) == 0x30 && (0xFF ^ 0x0F) == 0xF0; }").exit());
    }

    @Test
    void comparisonsAndConversionsRun() throws Exception {
        assertEquals(1, Native.run("int main(void) { int a = 1; int b = 2; unsigned u = 1; int neg = -1; return a < b && b > a && a <= a && a != b && neg < 0 && u < neg; }").exit());
        assertEquals(1, Native.run("int main(void) { double a = 1.5; double b = 2; double z = 0.0; double n = z / z; return a < b && !(a > b) && a != b && a == 1.5 && !(n == n) && n != n && !(n < 1); }").exit());
        assertEquals(10, Native.run("int main(void) { double d = 2.5; return (int) (d * 4); }").exit());
        assertEquals(1, Native.run("int main(void) { double d = -3.99; unsigned u = 4294967295u; return (int) d == -3 && (double) u == 4294967295.0 && (unsigned) 3.5 == 3u; }").exit());
        assertEquals(1, Native.run("int main(void) { unsigned long long u = 18446744073709551615ULL; double d = u; return d > 1.8e19 && (unsigned long long) 1.8446744073709552E19 == 0; }").exit() > 0 ? 1 : 0);
        assertEquals(1, Native.run("int main(void) { float f = 1.0f; double q = f / 3.0f; return q != 1.0 / 3.0 && (double) (float) 0.5 == 0.5; }").exit());
    }

    @Test
    void controlFlowRuns() throws Exception {
        assertEquals(55, Native.run("int main(void) { int s = 0; int i = 1; while (i <= 10) { s += i; i++; } return s; }").exit());
        assertEquals(30, Native.run("int main(void) { int s = 0; for (int i = 0; i < 100; i++) { if (i % 2) { continue; } if (i > 10) { break; } s += i; } return s; }").exit());
        assertEquals(23, Native.run("int main(void) { int v = 3; switch (v) { case 1: return 10; case 2: case 3: return 23; default: return -1; } }").exit());
        assertEquals(1, Native.run("int main(void) { long long big = 1LL << 40; int r = 0; switch (big) { case 1LL << 40: r = 1; break; case 1: r = 2; } return r; }").exit());
        assertEquals(6, Native.run("int main(void) { int i = 0; int s = 0; again: s += i; i++; if (i < 4) { goto again; } return s; }").exit());
        assertEquals(2, Native.run("int main(void) { int t = 0; return t ? 1 : 2; }").exit());
    }

    @Test
    void memoryRuns() throws Exception {
        assertEquals(5, Native.run("int main(void) { int x = 1; int *p = &x; *p = 5; return x; }").exit());
        assertEquals(1, Native.run("int main(void) { int a[3] = { 1, 2, 3 }; int *p = a + 2; *p = 7; return a[2] == 7 && p - a == 2 && a[0] + a[1] == 3; }").exit());
        assertEquals(12, Native.run("struct P { int x; int y; }; int main(void) { struct P p; p.x = 3; p.y = 4; struct P q = p; q.x = 9; return p.x * p.y * (q.x == 9); }").exit());
        assertEquals(1, Native.run("int main(void) { char buf[4]; buf[0] = 200; unsigned char *u = (unsigned char *) buf; return buf[0] == -56 && u[0] == 200; }").exit());
        assertEquals(1, Native.run("int main(void) { double d[2]; d[0] = 1.5; d[1] = 2; float f = d[0]; return d[0] + d[1] == 3.5 && f == 1.5f; }").exit());
        assertEquals(1, Native.run("struct B { unsigned a : 3; unsigned b : 5; int c : 4; }; int main(void) { struct B b; b.a = 7; b.b = 31; b.c = -3; return b.a == 7 && b.b == 31 && b.c == -3; }").exit());
        assertEquals(1, Native.run("union U { int i; unsigned char c[4]; }; int main(void) { union U u; u.i = 0x04030201; return u.c[0] == 1 && u.c[3] == 4; }").exit());
        assertEquals(1, Native.run("int main(void) { int m[2][3]; m[1][2] = 6; int *flat = &m[0][0]; return flat[5] == 6; }").exit());
        assertEquals(0, Native.run("struct P { int a; int b; }; int main(void) { struct P z = { 0 }; return z.a + z.b; }").exit());
    }

    @Test
    void callsRun() throws Exception {
        assertEquals(120, Native.run("int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }\nint main(void) { return fact(5); }").exit());
        assertEquals(45, Native.run("int sum9(int a, int b, int c, int d, int e, int f, int g, int h, int i) { return a + b + c + d + e + f + g + h + i; }\nint main(void) { return sum9(1, 2, 3, 4, 5, 6, 7, 8, 9); }").exit());
        assertEquals(1, Native.run("double avg(double a, double b) { return (a + b) / 2; } float half(float f) { return f / 2; }\nint main(void) { return avg(1, 2) == 1.5 && half(3.0f) == 1.5f; }").exit());
        assertEquals(36, Native.run("int many(int a, char b, short c, long d, long long e, unsigned f, double g, float h, double i, double j, double k, double l, double m, double n, double o) { return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o; }\nint main(void) { return many(1, 2, 3, 4, 5, 6, 7.0, 8.0f, 0, 0, 0, 0, 0, 0, 0); }").exit());
        assertEquals(5, Native.run("int add(int a, int b) { return a + b; } int apply(int (*f)(int, int), int x) { return f(x, x + 1); }\nint main(void) { int (*g)(int, int) = add; return apply(g, 2); }").exit());
        assertEquals(1, Native.run("struct P { int x; int y; }; struct P mk(int x, int y) { struct P p; p.x = x; p.y = y; return p; } int area(struct P p) { return p.x * p.y; }\nint main(void) { struct P p = mk(3, 4); return area(p) == 12 && area(mk(2, 5)) == 10 && p.x == 3; }").exit());
        assertEquals(1, Native.run("struct Big { int v[10]; }; struct Big make(int seed) { struct Big b; for (int i = 0; i < 10; i++) { b.v[i] = seed + i; } return b; } int last(struct Big b) { b.v[9] = 0; return b.v[8]; }\nint main(void) { struct Big b = make(5); int l = last(b); return l == 13 && b.v[9] == 14; }").exit());
        assertEquals(3, Native.run("int next(void) { static int n = 0; return ++n; }\nint main(void) { next(); next(); return next(); }").exit());
    }

    @Test
    void theLibraryIsTheRealOne() throws Exception {
        Native.Run r = Native.run("""
                #include <stdio.h>
                #include <string.h>
                #include <stdlib.h>
                int main(void) {
                    char buf[32];
                    strcpy(buf, "hello");
                    strcat(buf, ", world");
                    int *p = malloc(3 * sizeof(int));
                    p[0] = 1; p[1] = 2; p[2] = 3;
                    printf("%s %d %5.2f %c %x %lld|%-4d|\\n", buf, (int) strlen(buf), 3.14159, 'z', 255, 1LL << 40, 7);
                    printf("%d\\n", p[0] + p[1] + p[2]);
                    free(p);
                    return atoi("42");
                }
                """);
        assertEquals("hello, world 12  3.14 z ff 1099511627776|7   |\n6\n", r.out());
        assertEquals(42, r.exit());
    }

    @Test
    void globalsRun() throws Exception {
        assertEquals(1, Native.run("int a[4] = { 1, 2, 3, 4 }; int *p = a + 1; const char *s = \"hi\"; double d = 2.5; int z; struct B { unsigned lo : 4; int hi : 4; } b = { 15, -3 }; int (*fp)(void); int f(void) { return 9; }\nint main(void) { fp = f; return *p == 2 && s[1] == 'i' && d == 2.5 && z == 0 && b.lo == 15 && b.hi == -3 && fp() == 9 && a[3] == 4; }").exit());
        assertEquals(49, Native.run(org.jbm.mycc.Main.SOURCE).exit());
    }

    @Test
    void aMovedValueRuns() throws Exception {
        assertEquals(7, Native.run("int main(void) { char c = 7; int i = c; return i; }").exit());
        assertEquals(200, Native.run("int main(void) { unsigned char c = 200; return c; }").exit());
    }
}
