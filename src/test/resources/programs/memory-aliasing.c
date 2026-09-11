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
