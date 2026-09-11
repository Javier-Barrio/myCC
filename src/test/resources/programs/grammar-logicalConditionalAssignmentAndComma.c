#define CHECK(n, c) if (!(c)) { return n; }
int hits;
int side(int r) { hits++; return r; }
struct P { int x; int y; };
struct P mk(int v) { struct P p = { v, v }; return p; }
int main(void) {
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
    return 0;
}
