#define CHECK(n, c) if (!(c)) { return n; }
struct S { char c; int i; }; struct A { char c; double d; };
int main(void) {
int i = 5; int j = ++i; int k = --i;
CHECK(1, i == 5 && j == 6 && k == 5);
int x = 3; int *p = &x; int arr[2] = { 1, 2 };
CHECK(2, *p == 3 && p == &x && *arr == 1 && *(arr + 1) == 2);
unsigned u = 1;
CHECK(3, +5 == 5 && -x == -3 && (int) -u == -1 && ~5 == -6 && ~0 == -1);
int zero = 0; int *np = 0;
CHECK(4, !zero && !np && !0.0 && !!3 && !5 == 0);
CHECK(5, sizeof(int) == 4 && sizeof(double) == 8 && sizeof p == 8 && sizeof arr == 8 && sizeof arr / sizeof arr[0] == 2);
char ch;
CHECK(6, sizeof ch == 1 && sizeof 'c' == 4 && sizeof(struct S) == 8 && sizeof(long double) == 16);
CHECK(7, alignof(int) == 4 && alignof(struct A) == 8 && sizeof(struct A) == 16 && alignof(char) == 1);
int side = 1;
CHECK(8, sizeof(side++) == 4 && side == 1);
    return 0;
}
