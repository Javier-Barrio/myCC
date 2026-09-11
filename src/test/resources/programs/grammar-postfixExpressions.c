#define CHECK(n, c) if (!(c)) { return n; }
int sq(int x) { return x * x; }
int six(void) { return 6; }
struct P { int x; int y; };
struct S { struct { int a; int b; } in; int c; };
int main(void) {
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
    return 0;
}
