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
