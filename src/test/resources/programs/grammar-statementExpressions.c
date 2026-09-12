#define CHECK(n, c) if (!(c)) { return n; }
/* GNU: ({ ... }) is an expression whose value is its last expression statement's. */
#define MAX(a, b) ({ int a_ = (a); int b_ = (b); a_ > b_ ? a_ : b_; })
#define SWAP(x, y) ({ int t = x; x = y; y = t; })
int g;
int side(void) { g++; return g; }
struct P { int x, y; };
void nothing(void) {}
int main(void) {
    int a = ({ 1; 2; 3; });
    CHECK(1, a == 3);
    int m = MAX(side(), 2);
    CHECK(2, m == 2 && g == 1);
    CHECK(3, MAX(m + 5, m) == 7);
    int x = 1, y = 2;
    SWAP(x, y);
    CHECK(4, x == 2 && y == 1);
    int v = ({ int i = 0; int s = 0; while (i < 5) { s += i; i++; } s; });
    CHECK(5, v == 10);
    int n = 0;
    ({ n = 7; });
    CHECK(6, n == 7);
    struct P p = ({ struct P q = { 4, 5 }; q; });
    CHECK(7, p.x == 4 && p.y == 5);
    int w = ({ int k = 3; k++; });
    CHECK(8, w == 3 && ({ int z = 4; z; }) == 4);
    long e = __builtin_expect(n == 7, 1);
    CHECK(9, e == 1 && __builtin_expect(!!(n == 0), 0) == 0);
    int r = ({ int q = 0; if (n == 7) goto inner; q = 100; inner: ; q + 1; });
    CHECK(10, r == 1);
    n ? (void) 0 : ({ n = 9; });
    CHECK(11, n == 7);
    int taken = 0;
    void (*f)(void) = 0;
    f ? f() : ({ taken = 1; });
    CHECK(12, taken == 1);
    f = nothing;
    f ? f() : ({ taken = 2; });
    CHECK(13, taken == 1);
    int last = ({ nothing(); 5; }) + ({ 6; });
    CHECK(14, last == 11);
    return 0;
}
