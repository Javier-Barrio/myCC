package org.jbm.vm;

import org.jbm.repl.vm.VM;
import org.junit.jupiter.api.Test;

import static org.jbm.vm.VmTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Memory through C: loads and stores at every width, addrof on every
 * kind of object, aliasing, layout, arrays, the stack, globals with
 * address initializers, and pointers. Each program asserts for itself
 * with {@code CHECK(n, cond)} and was verified natively with gcc first.
 */
class VmMemoryTest {

    private static void program(String source) {
        long failed = ((VM.IntValue) run(source)).value();
        assertEquals(0, failed, "CHECK " + failed + " failed");
    }

    /** Every width and signedness through pointers into one byte array, and a long long seen as bytes. */
    @Test
    void loadsAndStoresAtEveryWidth() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                int main(void) {
                    unsigned char bytes[8] = { 0x01, 0x02, 0x03, 0x04, 0x85, 0x86, 0x87, 0x88 };
                    signed char *sc = (signed char *) bytes;
                    CHECK(1, bytes[4] == 0x85 && sc[4] == -123 && bytes[0] == 1);
                    unsigned short *us = (unsigned short *) bytes; short *ss = (short *) bytes;
                    CHECK(2, us[0] == 0x0201 && us[2] == 0x8685 && ss[2] == -31099);
                    unsigned *ui = (unsigned *) bytes; int *si = (int *) bytes;
                    CHECK(3, ui[0] == 0x04030201u && ui[1] == 0x88878685u && si[1] == -2004384123);
                    unsigned long long *ul = (unsigned long long *) bytes; long long *sl = (long long *) bytes;
                    CHECK(4, *ul == 0x8887868504030201ULL && *sl < 0);
                    *si = -1;
                    CHECK(5, bytes[0] == 255 && bytes[3] == 255 && bytes[4] == 0x85 && us[1] == 0xffff);
                    ss[0] = -2;
                    CHECK(6, bytes[0] == 0xfe && bytes[1] == 0xff && bytes[2] == 0xff);
                    sc[0] = 5;
                    CHECK(7, *si == (int) 0xffffff05u && bytes[1] == 0xff);
                    *sl = 0;
                    CHECK(8, ui[0] == 0 && ui[1] == 0 && *ul == 0);
                    long long v = 0x1122334455667788LL; unsigned char *p = (unsigned char *) &v;
                    CHECK(9, p[0] == 0x88 && p[7] == 0x11 && p[3] == 0x55);
                    p[7] = 0x91;
                    CHECK(10, v < 0 && (unsigned long long) v == 0x9122334455667788ULL);
                    return 0;
                }
                """);
    }

    /** addrof on locals, parameters, globals, statics, array elements, struct members, pointers to pointers and to arrays. */
    @Test
    void addressOfLocalsParamsGlobalsStaticsAndMembers() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                int g = 3; static int s = 4; int arr[4] = { 1, 2, 3, 4 };
                struct P { int x; int y; } gp = { 5, 6 };
                int *addrOfParam(int p) { int *q = &p; *q = 9; return (int *) 0 + (p == 9); }
                int *addrOfStatic(void) { static int local = 7; return &local; }
                void bump(int *p) { (*p)++; }
                int main(void) {
                    int x = 1; int *px = &x; *px = 2;
                    CHECK(1, x == 2 && *&x == 2 && px == &x);
                    int *pg = &g; int *ps = &s; *pg += 10; *ps += 10;
                    CHECK(2, g == 13 && s == 14 && pg == &g);
                    int *pa = &arr[2]; *pa = 30; int *pb = arr + 3;
                    CHECK(3, arr[2] == 30 && pa - arr == 2 && *pb == 4 && &arr[3] == pb && &*pb == pb);
                    int *py = &gp.y; *py = 60;
                    CHECK(4, gp.y == 60 && &gp.x == (int *) &gp && (char *) &gp.y - (char *) &gp == 4);
                    CHECK(5, addrOfParam(1) != 0);
                    int *st = addrOfStatic(); *st += 1;
                    CHECK(6, *addrOfStatic() == 8 && st == addrOfStatic());
                    bump(&x); bump(&arr[0]); bump(&gp.x); bump(pg);
                    CHECK(7, x == 3 && arr[0] == 2 && gp.x == 6 && g == 14);
                    int **ppx = &px; **ppx = 40;
                    CHECK(8, x == 40 && *ppx == &x && *(*ppx) == 40);
                    int a = 1; int b = 2; int c = 3; int *ps3[3] = { &a, &b, &c };
                    for (int i = 0; i < 3; i++) { *ps3[i] *= 10; }
                    CHECK(9, a == 10 && b == 20 && c == 30 && ps3[1] == &b);
                    int (*parr)[4] = &arr; (*parr)[1] = 99;
                    CHECK(10, arr[1] == 99 && *parr == arr && parr == &arr);
                    return 0;
                }
                """);
    }

    /** A variable written by name and through its address is one object, for every scalar type. */
    @Test
    void aVariableAndItsPointerAgree() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                int viaPointer(int *p, int *q) { *p = 1; *q = 2; return *p; }
                int main(void) {
                    int x = 5; int *p = &x;
                    x = x + 1; CHECK(1, *p == 6);
                    *p = *p * 2; CHECK(2, x == 12);
                    int y = x; *p = 0; CHECK(3, y == 12 && x == 0);
                    CHECK(4, viaPointer(&x, &x) == 2 && x == 2);
                    CHECK(5, viaPointer(&x, &y) == 1 && x == 1 && y == 2);
                    char c = 'a'; char *pc = &c; *pc += 1; c += 1;
                    CHECK(6, c == 'c' && *pc == 'c');
                    double d = 1.5; double *pd = &d; *pd *= 2; d += 0.5;
                    CHECK(7, d == 3.5 && *pd == 3.5);
                    int arr[3] = { 1, 2, 3 }; int *a1 = arr + 1; arr[1] = 20; (*a1)++;
                    CHECK(8, arr[1] == 21 && a1[0] == 21 && a1[-1] == 1 && a1[1] == 3);
                    struct S { int v; } s = { 1 }; struct S *ps = &s; s.v = 7; ps->v++;
                    CHECK(9, s.v == 8 && ps->v == 8 && (*ps).v == 8);
                    int i = 0; int *pi = &i;
                    for (*pi = 0; *pi < 5; (*pi)++) { }
                    CHECK(10, i == 5);
                    return 0;
                }
                """);
    }

    /** Offsets and padding seen through char pointers, nested copies, bit-fields through a pointer, union punning, aggregate zeroing. */
    @Test
    void structLayoutPaddingBitFieldsAndUnions() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                #include <stddef.h>
                struct Packed { char a; char b; short c; int d; };
                struct Padded { char a; int b; char c; double d; };
                struct Nested { struct Padded in; struct Packed arr[2]; int tail; };
                struct Bits { unsigned lo : 4; unsigned hi : 4; int wide : 20; unsigned flag : 1; };
                union Pun { int i; float f; unsigned char b[4]; struct { short lo; short hi; } halves; };
                int main(void) {
                    CHECK(1, sizeof(struct Packed) == 8 && offsetof(struct Packed, c) == 2 && offsetof(struct Packed, d) == 4);
                    CHECK(2, sizeof(struct Padded) == 24 && offsetof(struct Padded, b) == 4 && offsetof(struct Padded, c) == 8 && offsetof(struct Padded, d) == 16);
                    struct Padded p = { 'a', 2, 'c', 4.5 };
                    unsigned char *raw = (unsigned char *) &p;
                    CHECK(3, raw[0] == 'a' && raw[4] == 2 && raw[8] == 'c' && *(double *) (raw + 16) == 4.5);
                    raw[4] = 9;
                    CHECK(4, p.b == 9);
                    struct Nested n = { { 'x', 1, 'y', 2.0 }, { { 1, 2, 3, 4 }, { 5, 6, 7, 8 } }, 99 };
                    CHECK(5, sizeof n == 24 + 16 + 4 + 4 && n.arr[1].d == 8 && n.tail == 99 && n.in.d == 2.0);
                    struct Nested copy = n; copy.arr[0].a = 'Q'; copy.in.b = 77;
                    CHECK(6, n.arr[0].a == 1 && n.in.b == 1 && copy.arr[0].a == 'Q' && copy.in.b == 77);
                    struct Packed *pk = &n.arr[1]; pk->c = -1; (pk - 1)->d = 44;
                    CHECK(7, n.arr[1].c == -1 && n.arr[0].d == 44 && (char *) pk - (char *) &n.arr[0] == 8);
                    struct Bits b = { 0 }; b.lo = 15; b.hi = 1; b.wide = -12345; b.flag = 1;
                    unsigned first = *(unsigned *) &b;
                    CHECK(8, b.lo == 15 && b.hi == 1 && b.wide == -12345 && b.flag == 1 && sizeof b == 4 && (first & 0xff) == 0x1f);
                    b.lo++; b.hi += 20;
                    CHECK(9, b.lo == 0 && b.hi == 5);
                    struct Bits *pb = &b; pb->wide = 1 << 19;
                    CHECK(10, b.wide == -524288 && pb->flag == 1);
                    union Pun u; u.f = 1.0f;
                    CHECK(11, u.i == 0x3f800000 && u.b[3] == 0x3f && u.b[0] == 0 && u.halves.hi == 0x3f80 && u.halves.lo == 0 && sizeof u == 4);
                    u.i = -1;
                    CHECK(12, u.b[0] == 255 && u.halves.lo == -1 && u.f != u.f);
                    u.b[0] = 0x78; u.b[1] = 0x56; u.b[2] = 0x34; u.b[3] = 0x12;
                    CHECK(13, u.i == 0x12345678 && u.halves.lo == 0x5678);
                    union Pun copyu = u; copyu.i = 0;
                    CHECK(14, u.i == 0x12345678 && copyu.b[3] == 0);
                    struct Padded zero = { 0 }; struct Padded assigned; assigned = zero; assigned.c = 'z';
                    CHECK(15, assigned.a == 0 && assigned.b == 0 && assigned.c == 'z' && assigned.d == 0.0);
                    return 0;
                }
                """);
    }

    /** Two-dimensional arrays as flat memory, arrays of strings, of structs, of doubles, and an int view of bytes. */
    @Test
    void arraysOfArraysStructsAndBytes() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                int grid[3][4];
                int main(void) {
                    for (int i = 0; i < 3; i++) { for (int j = 0; j < 4; j++) { grid[i][j] = i * 10 + j; } }
                    int *flat = &grid[0][0];
                    CHECK(1, flat[5] == 11 && flat[11] == 23 && sizeof grid == 48 && sizeof grid[0] == 16);
                    CHECK(2, &grid[1][0] - &grid[0][0] == 4 && (char *) &grid[2] - (char *) grid == 32);
                    int (*row)[4] = grid + 1;
                    CHECK(3, (*row)[2] == 12 && row[1][3] == 23 && **row == 10);
                    char text[3][6] = { "one", "two", "three" };
                    CHECK(4, text[2][4] == 'e' && text[1][3] == 0 && sizeof text == 18 && text[0][5] == 0);
                    char *rows[3] = { text[0], text[1], text[2] };
                    CHECK(5, rows[1][0] == 't' && rows[2] - rows[0] == 12 && *rows[0] == 'o');
                    struct P { int x; int y; } pts[3] = { { 1, 2 }, { 3, 4 }, { 5, 6 } };
                    struct P *last = pts + 2; int *ys = &pts[0].y;
                    CHECK(6, last->x == 5 && ys[2] == 4 && ys[4] == 6 && (char *) last - (char *) pts == 16);
                    pts[1] = pts[2]; pts[2].x = 0;
                    CHECK(7, pts[1].x == 5 && pts[1].y == 6 && pts[2].x == 0);
                    int big[100]; for (int i = 0; i < 100; i++) { big[i] = i * i; }
                    long long sum = 0; for (int *p = big; p < big + 100; p++) { sum += *p; }
                    CHECK(8, sum == 328350 && big[99] == 9801);
                    double m[2][2] = { { 1.5, 2.5 }, { 3.5, 4.5 } }; double *dp = &m[1][0];
                    CHECK(9, dp[-1] == 2.5 && dp[1] == 4.5 && *(dp - 2) == 1.5);
                    unsigned char raw[16] = { 0 }; int *ints = (int *) raw; ints[1] = 0x01020304; ints[3] = -1;
                    CHECK(10, raw[4] == 4 && raw[7] == 1 && raw[12] == 255 && raw[0] == 0 && raw[11] == 0);
                    return 0;
                }
                """);
    }

    /** Frames get distinct aligned slots, deeper frames sit lower, and space is reused after a return. */
    @Test
    void theStackAcrossCalls() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                long long addressOfLocal(int depth) { int local = 0; return depth == 0 ? (long long) &local : addressOfLocal(depth - 1); }
                int sumTo(int n, int *acc) { int mine = n; if (n == 0) { return *acc; } *acc += mine; return sumTo(n - 1, acc); }
                void fillFrame(void) { int junk[8]; for (int i = 0; i < 8; i++) { junk[i] = 0x7f7f7f7f; } }
                int main(void) {
                    long long a0 = addressOfLocal(0); long long a3 = addressOfLocal(3);
                    CHECK(1, a0 != 0 && a3 != 0 && a0 % 4 == 0 && a3 % 4 == 0);
                    CHECK(2, a3 < a0);
                    int acc = 0; int total = sumTo(10, &acc);
                    CHECK(3, total == 55 && acc == 55);
                    int x = 1; int y = 2; int z = 3;
                    CHECK(4, &x != &y && &y != &z && (char *) &x - (char *) &y != 0);
                    long long before = addressOfLocal(0); fillFrame(); long long after = addressOfLocal(0);
                    CHECK(5, before == after);
                    double d = 2.5; char c = 'q'; long long l = 7;
                    CHECK(6, (long long) &d % 8 == 0 && (long long) &l % 8 == 0 && *&c == 'q');
                    return 0;
                }
                """);
    }

    /** Address items: pointers to globals, into arrays, to strings, to functions, and pointers inside structs and tables. */
    @Test
    void globalsInitializedWithAddresses() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                int a = 1, b = 2;
                int *pa = &a; int *pb = &b;
                int arr[5] = { 10, 20, 30, 40, 50 };
                int *mid = arr + 2; int *end = &arr[4];
                const char *words[] = { "alpha", "beta", "gamma" };
                const char **second = &words[1];
                struct Cfg { const char *name; int *target; int values[3]; } cfg = { "cfg", &b, { 7, 8, 9 } };
                struct Cfg *pcfg = &cfg;
                int *table[2] = { &arr[0], &arr[4] };
                int (*fn)(void);
                int hello(void) { return 42; }
                static char buffer[16];
                char *bp = buffer + 2;
                int main(void) {
                    CHECK(1, *pa == 1 && *pb == 2 && pa != pb);
                    *pa = 5; b = 6;
                    CHECK(2, a == 5 && *pb == 6);
                    CHECK(3, *mid == 30 && *end == 50 && end - mid == 2 && mid[-2] == 10);
                    CHECK(4, (*second)[0] == 'b' && words[2][0] == 'g' && words[0][4] == 'a' && words[0][5] == 0);
                    CHECK(5, cfg.name[1] == 'f' && *cfg.target == 6 && pcfg->values[2] == 9 && pcfg->target == pb);
                    *pcfg->target = 60;
                    CHECK(6, b == 60 && *table[1] == 50 && table[0] == arr);
                    fn = hello;
                    CHECK(7, fn() == 42 && fn == hello && fn == &hello);
                    bp[0] = 'x'; buffer[3] = 'y';
                    CHECK(8, buffer[2] == 'x' && bp[1] == 'y' && buffer[0] == 0 && bp - buffer == 2);
                    static int counter = 0; static int *pc = &counter;
                    (*pc)++; counter++;
                    CHECK(9, counter == 2 && *pc == 2);
                    CHECK(10, (char *) &cfg.values - (char *) &cfg == 16 && sizeof cfg == 32);
                    return 0;
                }
                """);
    }

    /** Pointers to pointers, void pointer round trips, char walks, integer round trips, and null tests. */
    @Test
    void pointersThroughEveryLevel() {
        program("""
                #define CHECK(n, c) if (!(c)) { return n; }
                void swap(int **a, int **b) { int *t = *a; *a = *b; *b = t; }
                int deref(int ***ppp) { return ***ppp; }
                void store(void *dst, int value) { *(int *) dst = value; }
                int main(void) {
                    int x = 1; int y = 2; int *px = &x; int *py = &y;
                    swap(&px, &py);
                    CHECK(1, *px == 2 && *py == 1 && px == &y);
                    int **ppx = &px; int ***pppx = &ppx;
                    CHECK(2, deref(pppx) == 2 && **ppx == 2 && *pppx == ppx);
                    **ppx = 20;
                    CHECK(3, y == 20);
                    store(&x, 30);
                    CHECK(4, x == 30);
                    void *v = &y; char *cv = v; int *iv = v;
                    CHECK(5, *iv == 20 && cv[0] == 20 && cv[1] == 0 && (void *) iv == v);
                    char text[] = "hello"; char *p = text; char *q = p + 4;
                    CHECK(6, *q == 'o' && q - p == 4 && p[q - p] == 'o' && q > p && p[5] == 0);
                    while (*p) { p++; }
                    CHECK(7, p - text == 5 && *p == 0 && p == text + 5);
                    int values[4] = { 5, 6, 7, 8 }; int *it = values; int s = 0;
                    do { s += *it; } while (++it < values + 4);
                    CHECK(8, s == 26 && it == values + 4);
                    long long addr = (long long) &values[1]; int *back = (int *) addr;
                    CHECK(9, *back == 6 && back == &values[1] && addr % 4 == 0);
                    int *null = 0; int *maybe = null ? null : &x;
                    CHECK(10, maybe == &x && !null && (null == 0) && (maybe != 0));
                    return 0;
                }
                """);
    }
}
