#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
int a = 1; int b = 2; double x = 1.5; double y = 2;
CHECK(1, a < b && a <= b && b > a && b >= a && a <= a && a >= a && !(a > b) && !(b < a));
CHECK(2, x < y && !(x > y) && x != y && x == 1.5 && y >= 2 && x <= 1.5);
int arr[2]; int *p = arr; int *q = arr + 1;
CHECK(3, p < q && q > p && p <= p && p == arr && q != arr && p >= arr);
unsigned u = 1; int neg = -1;
CHECK(4, neg < 0 && u < neg && (unsigned) neg > u);
int t = 2 < 3; int f = 3 < 2;
CHECK(5, t == 1 && f == 0 && (a == b) == 0 && (a != b) == 1);
int m = 0b1100; int n = 0b1010;
CHECK(6, (m & n) == 0b1000 && (m | n) == 0b1110 && (m ^ n) == 0b0110 && (m & ~n) == 0b0100);
unsigned long long all = ~0ULL;
CHECK(7, (all & 0xff) == 255 && (all ^ all) == 0 && (all | 0) == all && (all >> 60) == 15);
CHECK(8, (1 & 2) == 0 && (1 | 2) == 3 && (3 ^ 1) == 2 && (0xf0 & 0x3c | 1) == 0x31);
    return 0;
}
