#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
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
    return 0;
}
