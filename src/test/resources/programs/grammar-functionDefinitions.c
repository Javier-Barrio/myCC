#define CHECK(n, c) if (!(c)) { return n; }
int noargs(void) { return 1; }
int many(int a, char b, short c, long d, long long e, unsigned f, double g, float h) { return a + b + c + d + e + f + g + h; }
int first(int a[], int n) { return a[0] + n; }
int grid(int m[][2]) { return m[1][1]; }
int dbl(int x) { return x * 2; }
int viaPointer(int (*f)(int), int x) { return f(x); }
int viaFunction(int f(int), int x) { return f(x); }
static int hidden(void) { return 7; }
int later(void); int use(void) { return later() + 1; } int later(void) { return 1; }
int paramIsLocal(int x) { x = x + 1; return x; }
struct Big { int v[10]; };
int last(struct Big b) { b.v[9] = 0; return b.v[8]; }
struct Big make(int seed) { struct Big b; for (int i = 0; i < 10; i++) { b.v[i] = seed + i; } return b; }
void nothing(void) { }
int depth(int n) { return n == 0 ? 0 : 1 + depth(n - 1); }
int main(void) {
CHECK(1, noargs() == 1);
CHECK(2, many(1, 2, 3, 4, 5, 6, 7.0, 8.0f) == 36);
int a[3] = { 4, 5, 6 }; int m[2][2] = { { 1, 2 }, { 3, 4 } };
CHECK(3, first(a, 3) == 7 && grid(m) == 4);
CHECK(4, viaPointer(dbl, 21) == 42 && viaFunction(dbl, 21) == 42);
CHECK(5, hidden() == 7 && use() == 2);
int v = 1; int r = paramIsLocal(v);
CHECK(6, v == 1 && r == 2);
struct Big b = { { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 } }; int l = last(b);
CHECK(7, l == 8 && b.v[9] == 9);
struct Big made = make(5);
CHECK(8, made.v[0] == 5 && made.v[9] == 14);
nothing();
CHECK(9, depth(1000) == 1000);
    return 0;
}
