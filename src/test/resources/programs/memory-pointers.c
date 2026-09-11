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
