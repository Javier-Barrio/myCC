package org.jbm.vm;

import org.junit.jupiter.api.Test;

import static org.jbm.vm.VmTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One test per production of the C grammar the VM runs: expressions
 * (C2y 6.5) in precedence order, statements (6.8), declarations (6.7)
 * and function definitions (6.9). Each test is one C program that
 * asserts for itself: {@code CHECK(n, cond)} returns {@code n} from
 * {@code main} when {@code cond} fails, and the test expects 0.
 */
class VmGrammarTest {

    private static void checks(String decls, String body) {
        String source = "#define CHECK(n, c) if (!(c)) { return n; }\n" + decls + "\nint main(void) {\n" + body + "\nreturn 0;\n}\n";
        long failed = ((VM.IntValue) run(source)).value();
        assertEquals(0, failed, "CHECK " + failed + " failed");
    }

    // ---- 6.5.2 primary expressions -------------------------------------------------------------

    @Test
    void primaryExpressions() {
        checks("", """
                CHECK(1, 42 == 0x2A && 42 == 052 && 42 == 0b101010 && 1'000'000 == 1000000);
                CHECK(2, 'a' == 97 && '\\n' == 10 && '\\x41' == 65 && '\\0' == 0);
                CHECK(3, true == 1 && false == 0);
                int *np = nullptr;
                CHECK(4, np == 0 && !np);
                CHECK(5, 2.5 == 2.5 && 1.5e3 == 1500.0 && 0x1p-1 == 0.5 && 1e-1 < 1);
                int x = 7;
                CHECK(6, x == 7 && (x) == 7 && ((x)) == 7);
                CHECK(7, (2 + 3) * 4 == 20);
                CHECK(8, "hello"[0] == 'h' && "hello"[5] == 0 && sizeof "hello" == 6);
                int i = 0; double d = 0; long l = 0;
                CHECK(9, _Generic(i, int: 1, double: 2) == 1 && _Generic(d, int: 1, double: 2) == 2 && _Generic(l, int: 1, default: 3) == 3);
                """);
    }

    // ---- 6.5.3 postfix expressions -------------------------------------------------------------

    @Test
    void postfixExpressions() {
        checks("""
                int sq(int x) { return x * x; }
                int six(void) { return 6; }
                struct P { int x; int y; };
                struct S { struct { int a; int b; } in; int c; };
                """, """
                int a[3] = { 1, 2, 3 };
                CHECK(1, a[1] == 2 && 1[a] == 2 && a[0] + a[2] == 4);
                CHECK(2, sq(3) == 9 && sq(sq(2)) == 16);
                int (*p)(void) = six;
                CHECK(3, p() == 6 && (*p)() == 6 && (***p)() == 6);
                struct P s = { 4, 5 }; struct P *q = &s;
                CHECK(4, s.y == 5 && q->x == 4 && (*q).y == 5 && (&s)->x == 4);
                int i = 5; int j = i++; int k = i--;
                CHECK(5, i == 5 && j == 5 && k == 6);
                int *ap = a; ap++;
                CHECK(6, *ap == 2 && ap == &a[1]);
                unsigned char c = 255; c++;
                CHECK(7, c == 0);
                CHECK(8, ((struct P) { 8, 9 }).y == 9);
                int *cl = (int[3]) { 7, 8, 9 };
                CHECK(9, cl[0] + cl[2] == 16);
                struct S n = { { 1, 2 }, 3 };
                CHECK(10, n.in.b + n.c == 5 && n.in.a == 1);
                """);
    }

    // ---- 6.5.4 unary expressions ---------------------------------------------------------------

    @Test
    void unaryExpressions() {
        checks("struct S { char c; int i; }; struct A { char c; double d; };", """
                int i = 5; int j = ++i; int k = --i;
                CHECK(1, i == 5 && j == 6 && k == 5);
                int x = 3; int *p = &x; int arr[2] = { 1, 2 };
                CHECK(2, *p == 3 && p == &x && *arr == 1 && *(arr + 1) == 2);
                unsigned u = 1;
                CHECK(3, +5 == 5 && -x == -3 && (int) -u == -1 && ~5 == -6 && ~0 == -1);
                int zero = 0; int *np = 0;
                CHECK(4, !zero && !np && !0.0 && !!3 && !5 == 0);
                CHECK(5, sizeof(int) == 4 && sizeof(double) == 8 && sizeof p == 8 && sizeof arr == 8 && sizeof arr / sizeof arr[0] == 2);
                char ch;
                CHECK(6, sizeof ch == 1 && sizeof 'c' == 4 && sizeof(struct S) == 8 && sizeof(long double) == 16);
                CHECK(7, alignof(int) == 4 && alignof(struct A) == 8 && sizeof(struct A) == 16 && alignof(char) == 1);
                int side = 1;
                CHECK(8, sizeof(side++) == 4 && side == 1);
                """);
    }

    // ---- 6.5.5 cast expressions -----------------------------------------------------------------

    @Test
    void castExpressions() {
        checks("", """
                CHECK(1, (int) 3.9 == 3 && (double) 3 == 3.0 && (int) -3.9 == -3);
                CHECK(2, (char) 200 == -56 && (unsigned char) 200 == 200 && (short) 70000 == 4464);
                int x = 7; void *v = (void *) &x;
                CHECK(3, *(int *) v == 7 && (char *) v == (char *) &x);
                long long l = 0x1'0000'0001LL;
                CHECK(4, (int) l == 1 && (unsigned) l == 1u && l != 1);
                double d = 2.7;
                CHECK(5, (long long) d == 2 && (unsigned) d == 2u && (bool) d == 1 && (float) d != d);
                CHECK(6, ((void) x, 1));
                void *addr = (void *) 4096;
                CHECK(7, (long long) addr == 4096 && (int *) addr == (int *) 4096);
                CHECK(8, (unsigned long long) -1 == 18446744073709551615ULL && (unsigned) -1 == 4294967295u);
                """);
    }

    // ---- 6.5.6 - 6.5.11 arithmetic and shifts ----------------------------------------------------

    @Test
    void multiplicativeAdditiveAndShiftExpressions() {
        checks("", """
                int a = 7; int b = 2; unsigned ua = 7; unsigned ub = 2; double d = 3.5;
                CHECK(1, 6 * 7 == 42 && a * b == 14 && d * 3 == 10.5 && a * d == 24.5);
                CHECK(2, a / b == 3 && a % b == 1 && -a / b == -3 && -a % b == -1 && a / -b == -3 && a % -b == 1);
                CHECK(3, ua / ub == 3 && ua % ub == 1 && 7.0 / 2 == 3.5);
                CHECK(4, a + b == 9 && a - b == 5 && b - a == -5 && d + 1 == 4.5 && 1 - d == -2.5);
                int arr[4]; int *p = arr + 1;
                CHECK(5, p - arr == 1 && &arr[3] - p == 2 && p + 2 == &arr[3] && 2 + p == &arr[3] && p - 1 == arr);
                int one = 1; int n = 3;
                CHECK(6, one << 4 == 16 && 256 >> one == 128 && (1 << n) == 8 && (-64 >> n) == -8);
                unsigned u = 0x80000000u;
                CHECK(7, u >> 31 == 1 && (u << 1) == 0 && (u >> 4) == 0x08000000u);
                long long l = 1;
                CHECK(8, l << 62 == 4611686018427387904LL && (l << 63) >> 63 == -1);
                char c = 1; short s = 1;
                CHECK(9, c << 8 == 256 && s << 15 == 32768 && sizeof(c << 1) == 4);
                """);
    }

    // ---- 6.5.12 - 6.5.15 relational, equality, bitwise -------------------------------------------

    @Test
    void relationalEqualityAndBitwiseExpressions() {
        checks("", """
                int a = 1; int b = 2; double x = 1.5; double y = 2;
                CHECK(1, a < b && a <= b && b > a && b >= a && a <= a && a >= a && !(a > b) && !(b < a));
                CHECK(2, x < y && !(x > y) && x != y && x == 1.5 && y >= 2 && x <= 1.5);
                int arr[2]; int *p = arr; int *q = arr + 1;
                CHECK(3, p < q && q > p && p <= p && p == arr && q != arr && p >= arr);
                unsigned u = 1; int neg = -1;
                CHECK(4, neg < 0 && u < neg && (unsigned) neg > u);
                int t = 2 < 3; int f = 3 < 2;
                CHECK(5, t == 1 && f == 0 && (a == b) == 0 && (a != b) == 1);
                int m = 0b1100; int n = 0b1010;
                CHECK(6, (m & n) == 0b1000 && (m | n) == 0b1110 && (m ^ n) == 0b0110 && (m & ~n) == 0b0100);
                unsigned long long all = ~0ULL;
                CHECK(7, (all & 0xff) == 255 && (all ^ all) == 0 && (all | 0) == all && (all >> 60) == 15);
                CHECK(8, (1 & 2) == 0 && (1 | 2) == 3 && (3 ^ 1) == 2 && (0xf0 & 0x3c | 1) == 0x31);
                """);
    }

    // ---- 6.5.16 - 6.5.18 logical, conditional, assignment, comma --------------------------------

    @Test
    void logicalConditionalAssignmentAndComma() {
        checks("""
                int hits;
                int side(int r) { hits++; return r; }
                struct P { int x; int y; };
                struct P mk(int v) { struct P p = { v, v }; return p; }
                """, """
                int r1 = 0 && side(1); int r2 = 1 || side(1);
                CHECK(1, r1 == 0 && r2 == 1 && hits == 0);
                int r3 = 1 && side(0); int r4 = 0 || side(0);
                CHECK(2, r3 == 0 && r4 == 0 && hits == 2);
                int *np = 0; int x = 5;
                CHECK(3, (np && *np) == 0 && (np || x) == 1 && (x && 2.5) == 1);
                int t = 1;
                CHECK(4, (t ? 10 : 20) == 10 && (!t ? 10 : 20) == 20 && (t ? 2.5 : 1) == 2.5);
                CHECK(5, (t ? mk(1) : mk(2)).x == 1 && (!t ? mk(1) : mk(2)).y == 2);
                int a; int b; a = b = 7; int c = (c = a ? b : 3);
                CHECK(6, a == 7 && b == 7 && c == 7);
                int v = 10; v += 5; v -= 3; v *= 2; v /= 4; v %= 4;
                CHECK(7, v == 2);
                int w = 0b1100; w &= 0b1010; w |= 1; w ^= 0b11; w <<= 2; w >>= 1;
                CHECK(8, w == 20);
                double d = 1; d += 0.5; d *= 4; d /= 3;
                CHECK(9, d == 2.0);
                int arr[2] = { 1, 2 }; int *p = arr; p += 1; p -= 1;
                CHECK(10, *p == 1);
                char ch = 100; ch += 100;
                CHECK(11, ch == -56);
                struct P s1 = { 1, 2 }; struct P s2; s2 = s1; s1.x = 9;
                CHECK(12, s2.x == 1 && s2.y == 2 && s1.x == 9);
                int i = 1; int j = 2; int k = (i++, j++, i + j);
                CHECK(13, k == 5 && i == 2 && j == 3 && (i = 1, i + 2) == 3);
                """);
    }

    // ---- 6.8.1 - 6.8.3 labeled, compound and expression statements -----------------------------

    @Test
    void labeledCompoundAndExpressionStatements() {
        checks("", """
                int n = 0;
                top: n++; if (n < 3) { goto top; }
                CHECK(1, n == 3);
                int s = 0; switch (2) { case 1: s = 1; case 2: s += 2; case 3: s += 3; break; default: s = -1; }
                CHECK(2, s == 5);
                int x = 1; { int x = 2; { int x = 3; CHECK(3, x == 3); } x++; CHECK(4, x == 3); }
                CHECK(5, x == 1);
                { x = 2; }
                CHECK(6, x == 2);
                x; 3 + 4; ;
                CHECK(7, x == 2);
                int sum = 0; { int i = 1; sum += i; } { int i = 2; sum += i; }
                CHECK(8, sum == 3);
                """);
    }

    // ---- 6.8.4 selection statements ------------------------------------------------------------

    @Test
    void selectionStatements() {
        checks("", """
                int r = 0;
                if (1) { r = 1; }
                CHECK(1, r == 1);
                if (0) { r = 2; }
                CHECK(2, r == 1);
                if (0) { r = 3; } else { r = 4; }
                CHECK(3, r == 4);
                int x = 15;
                if (x < 10) { r = 1; } else if (x < 20) { r = 2; } else { r = 3; }
                CHECK(4, r == 2);
                r = 0; if (1) if (0) r = 1; else r = 2;
                CHECK(5, r == 2);
                int *np = 0; double d = 0.5; r = 0;
                if (np) { r = 1; } if (d) { r += 2; }
                CHECK(6, r == 2);
                r = 0; switch (5) { default: r = 9; break; case 1: r = 1; }
                CHECK(7, r == 9);
                r = 0; switch (1) { case 1: { int t = 4; r = t; } break; }
                CHECK(8, r == 4);
                r = 0; switch (3) { case 1: r = 1; break; }
                CHECK(9, r == 0);
                r = 0; switch (-1) { case -1: r = 1; break; case 1: r = 2; }
                CHECK(10, r == 1);
                r = 0; for (int i = 0; i < 4; i++) { switch (i) { case 0: continue; case 2: r += 10; break; default: r++; } }
                CHECK(11, r == 12);
                long long big = 1LL << 40; r = 0; switch (big) { case 1LL << 40: r = 1; break; case 1: r = 2; }
                CHECK(12, r == 1);
                """);
    }

    // ---- 6.8.5 iteration statements ------------------------------------------------------------

    @Test
    void iterationStatements() {
        checks("", """
                int i = 0; while (i < 10) { i++; }
                CHECK(1, i == 10);
                while (0) { i++; }
                CHECK(2, i == 10);
                i = 0; do { i++; } while (0);
                CHECK(3, i == 1);
                do { i++; } while (i < 5);
                CHECK(4, i == 5);
                int s = 0; for (int k = 0; k < 5; k++) { s += k; }
                CHECK(5, s == 10);
                int a = 0; int b = 10; for (; a < b; a++, b--) { }
                CHECK(6, a == 5 && b == 5);
                int n = 0; for (;;) { if (++n == 3) { break; } }
                CHECK(7, n == 3);
                int m; for (m = 0; m < 3; m++) { }
                CHECK(8, m == 3);
                s = 0; for (int p = 0, q = 10; p < q; p += 3) { s += p; }
                CHECK(9, s == 18);
                s = 0; i = 0; while (i++ < 10) { if (i % 2 == 0) { continue; } s += i; }
                CHECK(10, s == 25);
                s = 0; i = 0; do { i++; if (i == 2) { continue; } s += i; } while (i < 4);
                CHECK(11, s == 8);
                int c = 0; for (int x = 0; x < 3; x++) { for (int y = 0; y < 3; y++) { if (y == 1) { break; } c++; } }
                CHECK(12, c == 3);
                """);
    }

    // ---- 6.8.6 jump statements -----------------------------------------------------------------

    @Test
    void jumpStatements() {
        checks("""
                int pos(int x) { if (x > 0) { return 3; } return 4; }
                void set(int *p) { *p = 1; return; *p = 2; }
                int seven(void) { for (int i = 0; ; i++) { if (i == 7) { return i; } } }
                double five(void) { return 5; }
                char narrow(void) { return 300; }
                """, """
                int n = 0; goto skip; n = 5; skip:
                CHECK(1, n == 0);
                int i = 0; loop: if (i < 3) { i++; n += i; goto loop; }
                CHECK(2, n == 6);
                n = 0; for (int x = 0; x < 10; x++) { for (int y = 0; y < 10; y++) { if (x * y == 12) { goto done; } n++; } } done:
                CHECK(3, n == 26);
                n = 0; for (int x = 0; x < 5; x++) { if (x == 3) { continue; } n += x; }
                CHECK(4, n == 7);
                n = 0; while (1) { n++; if (n == 4) { break; } }
                CHECK(5, n == 4);
                CHECK(6, pos(1) == 3 && pos(0) == 4);
                int v = 0; set(&v);
                CHECK(7, v == 1);
                CHECK(8, seven() == 7 && five() / 2 == 2.5 && narrow() == 44);
                """);
    }

    // ---- 6.7 declarations ----------------------------------------------------------------------

    @Test
    void declarationsAndInitializers() {
        checks("""
                struct P { int x; int y; };
                struct L { int v[3]; struct { int a; } in; };
                struct F { int v[3]; int tail; };
                static int g = 4; extern int e; int e = 5;
                int tentative; int tentative;
                typedef int Pair[2];
                typedef struct Node { int v; struct Node *next; } Node;
                union U { int i; float f; };
                enum E { A = 3, B, C = 10, D }; int values[] = { A, B, C, D };
                static_assert(sizeof(int) == 4, "int is 32 bits");
                enum { K = 6 };
                constexpr int KC = 6;
                """, """
                int a, b = 2, *p = &b;
                CHECK(1, b == 2 && *p == 2 && p == &b);
                int arr[5] = { 1, 2 };
                CHECK(2, arr[1] == 2 && arr[2] == 0 && arr[4] == 0);
                int sized[] = { 1, 2, 3 };
                CHECK(3, sizeof sized == 12 && sized[2] == 3);
                int des[4] = { [2] = 5, [0] = 1 };
                CHECK(4, des[0] == 1 && des[1] == 0 && des[2] == 5 && des[3] == 0);
                struct P s = { .y = 2, .x = 1 }; struct P z = { 0 };
                CHECK(5, s.x == 1 && s.y == 2 && z.x == 0 && z.y == 0);
                struct L l = { { 1, 2, 3 }, { 4 } };
                CHECK(6, l.v[2] == 3 && l.in.a == 4);
                struct F f = { 1, 2, 3, 4 };
                CHECK(7, f.v[2] == 3 && f.tail == 4);
                int x = 3; int y = x * 2; int w = y + x;
                CHECK(8, w == 9);
                int k[K];
                CHECK(9, sizeof k == 24 && KC == 6);
                char str[8] = "hi";
                CHECK(10, str[1] == 'i' && str[2] == 0 && str[7] == 0 && sizeof str == 8);
                int (*pa)[3] = &sized;
                CHECK(11, (*pa)[2] == 3 && sizeof *pa == 12);
                CHECK(12, g + e == 9);
                tentative = 3;
                CHECK(13, tentative == 3);
                Pair pr = { 3, 4 };
                CHECK(14, pr[0] + pr[1] == 7 && sizeof(Pair) == 8);
                Node n2 = { 2, 0 }; Node n1 = { 1, &n2 };
                CHECK(15, n1.next->v == 2 && n1.next->next == 0);
                union U u = { .f = 1.0f };
                CHECK(16, u.i == 0x3f800000 && sizeof u == 4);
                CHECK(17, values[1] == 4 && values[3] == 11 && sizeof values == 16);
                auto ax = 5; auto ad = 1.5;
                CHECK(18, ax == 5 && sizeof ax == 4 && ad == 1.5 && sizeof ad == 8);
                typeof(x) tx = 2; typeof(&x) tp = &tx;
                CHECK(19, *tp == 2 && sizeof tx == 4);
                const int ci = 9; volatile int vi = 1; vi = vi + ci;
                CHECK(20, ci == 9 && vi == 10);
                """);
    }

    // ---- 6.9 function definitions ---------------------------------------------------------------

    @Test
    void functionDefinitions() {
        checks("""
                int noargs(void) { return 1; }
                int many(int a, char b, short c, long d, long long e, unsigned f, double g, float h) { return a + b + c + d + e + f + g + h; }
                int first(int a[], int n) { return a[0] + n; }
                int grid(int m[][2]) { return m[1][1]; }
                int dbl(int x) { return x * 2; }
                int viaPointer(int (*f)(int), int x) { return f(x); }
                int viaFunction(int f(int), int x) { return f(x); }
                static int hidden(void) { return 7; }
                int later(void); int use(void) { return later() + 1; } int later(void) { return 1; }
                int paramIsLocal(int x) { x = x + 1; return x; }
                struct Big { int v[10]; };
                int last(struct Big b) { b.v[9] = 0; return b.v[8]; }
                struct Big make(int seed) { struct Big b; for (int i = 0; i < 10; i++) { b.v[i] = seed + i; } return b; }
                void nothing(void) { }
                int depth(int n) { return n == 0 ? 0 : 1 + depth(n - 1); }
                """, """
                CHECK(1, noargs() == 1);
                CHECK(2, many(1, 2, 3, 4, 5, 6, 7.0, 8.0f) == 36);
                int a[3] = { 4, 5, 6 }; int m[2][2] = { { 1, 2 }, { 3, 4 } };
                CHECK(3, first(a, 3) == 7 && grid(m) == 4);
                CHECK(4, viaPointer(dbl, 21) == 42 && viaFunction(dbl, 21) == 42);
                CHECK(5, hidden() == 7 && use() == 2);
                int v = 1; int r = paramIsLocal(v);
                CHECK(6, v == 1 && r == 2);
                struct Big b = { { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 } }; int l = last(b);
                CHECK(7, l == 8 && b.v[9] == 9);
                struct Big made = make(5);
                CHECK(8, made.v[0] == 5 && made.v[9] == 14);
                nothing();
                CHECK(9, depth(1000) == 1000);
                """);
    }
}
