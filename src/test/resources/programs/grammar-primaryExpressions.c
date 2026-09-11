#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
CHECK(1, 42 == 0x2A && 42 == 052 && 42 == 0b101010 && 1'000'000 == 1000000);
CHECK(2, 'a' == 97 && '\n' == 10 && '\x41' == 65 && '\0' == 0);
CHECK(3, true == 1 && false == 0);
int *np = nullptr;
CHECK(4, np == 0 && !np);
CHECK(5, 2.5 == 2.5 && 1.5e3 == 1500.0 && 0x1p-1 == 0.5 && 1e-1 < 1);
int x = 7;
CHECK(6, x == 7 && (x) == 7 && ((x)) == 7);
CHECK(7, (2 + 3) * 4 == 20);
CHECK(8, "hello"[0] == 'h' && "hello"[5] == 0 && sizeof "hello" == 6);
int i = 0; double d = 0; long l = 0;
CHECK(9, _Generic(i, int: 1, double: 2) == 1 && _Generic(d, int: 1, double: 2) == 2 && _Generic(l, int: 1, default: 3) == 3);
    return 0;
}
