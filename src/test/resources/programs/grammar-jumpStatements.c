#define CHECK(n, c) if (!(c)) { return n; }
int pos(int x) { if (x > 0) { return 3; } return 4; }
void set(int *p) { *p = 1; return; *p = 2; }
int seven(void) { for (int i = 0; ; i++) { if (i == 7) { return i; } } }
double five(void) { return 5; }
char narrow(void) { return 300; }
int main(void) {
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
    return 0;
}
