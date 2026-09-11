#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
int a = 7; int b = 2; unsigned ua = 7; unsigned ub = 2; double d = 3.5;
CHECK(1, 6 * 7 == 42 && a * b == 14 && d * 3 == 10.5 && a * d == 24.5);
CHECK(2, a / b == 3 && a % b == 1 && -a / b == -3 && -a % b == -1 && a / -b == -3 && a % -b == 1);
CHECK(3, ua / ub == 3 && ua % ub == 1 && 7.0 / 2 == 3.5);
CHECK(4, a + b == 9 && a - b == 5 && b - a == -5 && d + 1 == 4.5 && 1 - d == -2.5);
int arr[4]; int *p = arr + 1;
CHECK(5, p - arr == 1 && &arr[3] - p == 2 && p + 2 == &arr[3] && 2 + p == &arr[3] && p - 1 == arr);
int one = 1; int n = 3;
CHECK(6, one << 4 == 16 && 256 >> one == 128 && (1 << n) == 8 && (-64 >> n) == -8);
unsigned u = 0x80000000u;
CHECK(7, u >> 31 == 1 && (u << 1) == 0 && (u >> 4) == 0x08000000u);
long long l = 1;
CHECK(8, l << 62 == 4611686018427387904LL && (l << 63) >> 63 == -1);
char c = 1; short s = 1;
CHECK(9, c << 8 == 256 && s << 15 == 32768 && sizeof(c << 1) == 4);
    return 0;
}
