#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
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
    return 0;
}
