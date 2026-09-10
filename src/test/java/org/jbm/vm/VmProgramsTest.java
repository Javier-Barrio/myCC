package org.jbm.vm;

import org.jbm.cc.lower.arch.Ilp32;
import org.jbm.cc.sema.types.Types;
import org.jbm.repl.vm.VM;
import org.junit.jupiter.api.Test;

import static org.jbm.vm.VmTest.floating;
import static org.jbm.vm.VmTest.integer;
import static org.jbm.vm.VmTest.module;
import static org.jbm.vm.VmTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Larger programs and the remaining language features the VM already
 * runs without a library: memory idioms, compound literals, enums,
 * typedefs, long double, the ILP32 target, and whole algorithms.
 */
class VmProgramsTest {

    @Test
    void memoryIdioms() {
        assertEquals(1, integer("", "int a[3] = { 1, 2, 3 }; a[1] += 10; a[2] *= a[1];", "a[1] == 12 && a[2] == 36"));
        assertEquals(1, integer("struct P { int x; };", "struct P p = { 4 }; struct P *q = &p; q->x *= 3; q->x++;", "p.x == 13"));
        assertEquals(1, integer("", "int a[3] = { 5, 6, 7 }; int *p = a; int first = *p++; int second = *++p;", "first == 5 && second == 7 && p == a + 2"));
        assertEquals(1, integer("", "int a[4]; int *p = a; int *q = a + 3;", "q > p && q - p == 3 && p != q && !(p == q)"));
        assertEquals(1, integer("", "int a[2] = { 1, 2 }; int *p = a + 2;", "p[-1] == 2 && *(p - 2) == 1"));
        assertEquals(1, integer("", "int x = 3; void *v = &x; int *p = v;", "*p == 3 && (char *) v == (char *) &x"));
        assertEquals(1, integer("", "int *p = 0;", "!p && p == 0 && (p ? 1 : 2) == 2"));
        assertEquals(5, integer("", "const char *s = \"hello\"; int n = 0; while (s[n]) { n++; }", "n"));
        assertEquals(1, integer("", "char s[] = \"hey\";", "s[2] == 'y' && s[3] == 0 && sizeof s == 4"));
        assertEquals(1, integer("", "char s[] = \"a\\n\\t\\\\\";", "s[1] == 10 && s[2] == 9 && s[3] == 92"));
        assertEquals(1, integer("", "int m[2][2] = { { 1, 2 }, { 3, 4 } }; int *flat = &m[0][0];", "flat[3] == 4 && m[1][0] == 3"));
        assertEquals(1, integer("", "unsigned char b[4] = { 0x78, 0x56, 0x34, 0x12 }; unsigned *w = (unsigned *) b;", "*w == 0x12345678u"),
                "little-endian view of bytes");
        assertEquals(1, integer("", "long long l = -1; unsigned char *b = (unsigned char *) &l;", "b[0] == 255 && b[7] == 255 && sizeof l == 8"));
        assertEquals(1, integer("", "short s = -2;", "(unsigned short) s == 65534 && sizeof s == 2"));
    }

    @Test
    void compoundLiteralsEnumsAndTypedefs() {
        assertEquals(1, integer("struct P { int x; int y; };", "", "(struct P) { 1, 2 }.y == 2"));
        assertEquals(3, integer("", "int *p = (int[]) { 1, 2, 3 };", "p[2]"));
        assertEquals(1, integer("struct P { int x; int y; }; int sum(struct P p) { return p.x + p.y; }", "", "sum((struct P) { .y = 5, .x = 6 }) == 11"));
        assertEquals(1, integer("enum Color { RED, GREEN = 5, BLUE };", "enum Color c = BLUE;", "c == 6 && RED == 0 && GREEN + 1 == BLUE"));
        assertEquals(1, integer("typedef struct { int v[2]; } Pair; typedef int (*Op)(int, int); int add(int a, int b) { return a + b; }",
                "Pair p = { { 3, 4 } }; Op op = add;", "op(p.v[0], p.v[1]) == 7 && sizeof(Pair) == 8"));
        assertEquals(1, integer("typedef unsigned char byte;", "byte b = 250; b += 10;", "b == 4"));
    }

    @Test
    void longDoubleAndMixedFloating() {
        assertEquals(3, integer("", "long double x = 1.5L;", "(int) (x * 2)"));
        assertEquals(1, integer("", "long double x = 1.5L; double d = x; float f = x;", "d == 1.5 && f == 1.5f && sizeof x == 16"));
        assertEquals(2.5, floating("", "long double a[2] = { 1.0L, 1.5L };", "a[0] + a[1]"));
        assertEquals(1, integer("", "float f = 1.5f; double d = f; int i = f;", "d == 1.5 && i == 1 && f * 2 == 3.0"));
        assertEquals(1, integer("", "double d = -0.0;", "d == 0.0 && 1 / d < 0"));
        assertEquals(1, integer("", "unsigned u = 7; double d = u / 2.0;", "d == 3.5"));
        assertEquals(1, integer("", "double big = 1e10; int truncated = (int) 1e3;", "big > 2147483647 && truncated == 1000"));
    }

    @Test
    void controlFlowAtScale() {
        String gcd = "int gcd(int a, int b) { while (b) { int t = a % b; a = b; b = t; } return a; }";
        assertEquals(12, integer(gcd, "", "gcd(48, 36)"));
        assertEquals(1, integer(gcd, "", "gcd(17, 5)"));
        String collatz = "int steps(long long n) { int s = 0; while (n != 1) { n = n % 2 ? 3 * n + 1 : n / 2; s++; } return s; }";
        assertEquals(111, integer(collatz, "", "steps(27)"));
        String pow = "long long ipow(long long b, int e) { long long r = 1; for (; e > 0; e >>= 1) { if (e & 1) { r *= b; } b *= b; } return r; }";
        assertEquals(1L << 40, integer(pow, "", "ipow(2, 40)"));
        assertEquals(3486784401L, integer(pow, "", "ipow(3, 20)"));
        String nested = """
                int find(int v) {
                    for (int i = 0; i < 10; i++) {
                        for (int j = 0; j < 10; j++) {
                            if (i * j == v) { goto found; }
                        }
                    }
                    return -1;
                found:
                    return v;
                }
                """;
        assertEquals(42, integer(nested, "", "find(42)"));
        assertEquals(-1, integer(nested, "", "find(43)"));
        String sw = "int days(int m) { switch (m) { case 2: return 28; case 4: case 6: case 9: case 11: return 30; default: return 31; } }";
        assertEquals(1, integer(sw, "", "days(2) == 28 && days(9) == 30 && days(12) == 31"));
        String chars = "int kind(char c) { switch (c) { case 'a' ... 'z': return 1; case '0' ... '9': return 2; default: return 0; } }";
        assertEquals(1, integer(chars, "", "kind('q') == 1 && kind('7') == 2 && kind('!') == 0"));
    }

    @Test
    void sortingAndSearching() {
        String bubble = """
                void sort(int *a, int n) {
                    for (int i = 0; i < n - 1; i++) {
                        for (int j = 0; j < n - 1 - i; j++) {
                            if (a[j] > a[j + 1]) { int t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
                        }
                    }
                }
                int sorted(int *a, int n) { for (int i = 1; i < n; i++) { if (a[i - 1] > a[i]) { return 0; } } return 1; }
                """;
        assertEquals(1, integer(bubble, "int a[8] = { 5, 3, 9, 1, 7, 2, 8, 6 }; sort(a, 8);", "sorted(a, 8) && a[0] == 1 && a[7] == 9"));
        String quick = """
                void swap(int *a, int *b) { int t = *a; *a = *b; *b = t; }
                void qs(int *a, int lo, int hi) {
                    if (lo >= hi) { return; }
                    int p = a[hi]; int i = lo;
                    for (int j = lo; j < hi; j++) { if (a[j] < p) { swap(&a[i], &a[j]); i++; } }
                    swap(&a[i], &a[hi]);
                    qs(a, lo, i - 1);
                    qs(a, i + 1, hi);
                }
                int check(int *a, int n) { int s = 0; for (int i = 0; i < n; i++) { if (a[i] != i) { return -1; } s += a[i]; } return s; }
                """;
        assertEquals(45, integer(quick, "int a[10] = { 9, 3, 7, 0, 5, 1, 8, 2, 6, 4 }; qs(a, 0, 9);", "check(a, 10)"));
        String bsearch = """
                int find(const int *a, int n, int key) {
                    int lo = 0; int hi = n - 1;
                    while (lo <= hi) {
                        int mid = lo + (hi - lo) / 2;
                        if (a[mid] == key) { return mid; }
                        if (a[mid] < key) { lo = mid + 1; } else { hi = mid - 1; }
                    }
                    return -1;
                }
                """;
        assertEquals(1, integer(bsearch, "int a[7] = { 1, 3, 5, 7, 9, 11, 13 };", "find(a, 7, 9) == 4 && find(a, 7, 1) == 0 && find(a, 7, 4) == -1"));
    }

    @Test
    void dataStructuresWithoutAHeap() {
        String list = """
                struct Node { int value; struct Node *next; };
                struct Node pool[16];
                int used;
                struct Node *push(struct Node *head, int v) { struct Node *n = &pool[used++]; n->value = v; n->next = head; return n; }
                int sum(struct Node *n) { int s = 0; for (; n; n = n->next) { s += n->value; } return s; }
                struct Node *reverse(struct Node *n) { struct Node *r = 0; while (n) { struct Node *next = n->next; n->next = r; r = n; n = next; } return r; }
                """;
        assertEquals(1, integer(list, "struct Node *h = 0; for (int i = 1; i <= 5; i++) { h = push(h, i); }",
                "sum(h) == 15 && h->value == 5 && reverse(h)->value == 1 && used == 5"));
        String stack = """
                struct Stack { int items[8]; int top; };
                void push(struct Stack *s, int v) { s->items[s->top++] = v; }
                int pop(struct Stack *s) { return s->items[--s->top]; }
                int rpn(void) { struct Stack s = { 0 }; push(&s, 3); push(&s, 4); push(&s, pop(&s) * pop(&s)); push(&s, 5); return pop(&s) + pop(&s); }
                """;
        assertEquals(17, integer(stack, "", "rpn()"));
        String sieve = """
                int primes(int n) {
                    char composite[100] = { 0 };
                    int count = 0;
                    for (int i = 2; i < n; i++) {
                        if (composite[i]) { continue; }
                        count++;
                        for (int j = i * 2; j < n; j += i) { composite[j] = 1; }
                    }
                    return count;
                }
                """;
        assertEquals(25, integer(sieve, "", "primes(100)"));
        String matrix = """
                void mul(int n, int a[3][3], int b[3][3], int c[3][3]) {
                    for (int i = 0; i < n; i++) { for (int j = 0; j < n; j++) { c[i][j] = 0; for (int k = 0; k < n; k++) { c[i][j] += a[i][k] * b[k][j]; } } }
                }
                """;
        assertEquals(1, integer(matrix, "int a[3][3] = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 } }; int id[3][3] = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } }; int c[3][3]; mul(3, a, id, c);",
                "c[0][0] == 1 && c[1][1] == 5 && c[2][0] == 7 && c[2][2] == 9"));
        String strings = """
                int length(const char *s) { int n = 0; while (s[n]) { n++; } return n; }
                void reverse(char *s) { int i = 0; int j = length(s) - 1; while (i < j) { char t = s[i]; s[i] = s[j]; s[j] = t; i++; j--; } }
                int equal(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
                """;
        assertEquals(1, integer(strings, "char s[] = \"stressed\"; reverse(s);", "equal(s, \"desserts\") && length(s) == 8"));
        String fnTable = "int add(int a, int b) { return a + b; } int sub(int a, int b) { return a - b; } int mul(int a, int b) { return a * b; }\n"
                + "int (*ops[3])(int, int) = { add, sub, mul };";
        assertEquals(1, integer(fnTable, "int r = 0; for (int i = 0; i < 3; i++) { r = r * 100 + ops[i](7, 3); }", "r == 100421"));
    }

    @Test
    void staticsAndGlobalsAcrossCalls() {
        String counter = "int calls(void) { static int n; return ++n; } int twice(void) { calls(); return calls(); }";
        assertEquals(1, integer(counter, "int a = twice(); int b = calls();", "a == 2 && b == 3"));
        String table = "static int squares[5]; static void fill(void) { for (int i = 0; i < 5; i++) { squares[i] = i * i; } }";
        assertEquals(30, integer(table, "fill();", "squares[1] + squares[2] + squares[3] + squares[4]"));
        String state = """
                struct Config { int width; int height; const char *name; } cfg = { 80, 24, "vt" };
                int area(void) { return cfg.width * cfg.height; }
                """;
        assertEquals(1, integer(state, "cfg.width = 100;", "area() == 2400 && cfg.name[1] == 't'"));
        String swapper = "int a = 1, b = 2; void swap(void) { int t = a; a = b; b = t; }";
        assertEquals(1, integer(swapper, "swap(); swap(); swap();", "a == 2 && b == 1"));
    }

    @Test
    void theMainProgramExitsWith49() {
        assertEquals(49, ((VM.IntValue) run(org.jbm.Main.SOURCE)).value());
    }

    @Test
    void ilp32Target() {
        Types ilp32 = new Types(Ilp32.INSTANCE);
        String source = """
                int a[3] = { 1, 2, 3 };
                long long main(void) {
                    long l = 2147483647; l += 1;
                    int *p = a + 2;
                    return (l == -2147483648L) * 1000 + sizeof(long) * 100 + sizeof(void *) * 10 + (p - a);
                }
                """;
        assertEquals(1442, ((VM.IntValue) run(source, ilp32)).value());
        String pointers = """
                struct P { char c; int *p; };
                long long main(void) { int x = 9; struct P s = { 'a', &x }; return sizeof s * 100 + *s.p; }
                """;
        assertEquals(809, ((VM.IntValue) run(pointers, ilp32)).value());
    }

    @Test
    void aModuleForAnotherTargetIsRefused() {
        VM vm = new VM();
        vm.step(module("int x;"));
        String m = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> vm.step(module("int y;", new Types(Ilp32.INSTANCE)))).getMessage();
        assertEquals("module for ilp32 loaded into a VM running x86_64-sysv", m);
    }
}
