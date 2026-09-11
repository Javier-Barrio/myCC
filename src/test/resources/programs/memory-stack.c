#define CHECK(n, c) if (!(c)) { return n; }
long long addressOfLocal(int depth) { int local = 0; return depth == 0 ? (long long) &local : addressOfLocal(depth - 1); }
int sumTo(int n, int *acc) { int mine = n; if (n == 0) { return *acc; } *acc += mine; return sumTo(n - 1, acc); }
void fillFrame(void) { int junk[8]; for (int i = 0; i < 8; i++) { junk[i] = 0x7f7f7f7f; } }
int main(void) {
    long long a0 = addressOfLocal(0); long long a3 = addressOfLocal(3);
    CHECK(1, a0 != 0 && a3 != 0 && a0 % 4 == 0 && a3 % 4 == 0);
    CHECK(2, a3 < a0);
    int acc = 0; int total = sumTo(10, &acc);
    CHECK(3, total == 55 && acc == 55);
    int x = 1; int y = 2; int z = 3;
    CHECK(4, &x != &y && &y != &z && (char *) &x - (char *) &y != 0);
    long long before = addressOfLocal(0); fillFrame(); long long after = addressOfLocal(0);
    CHECK(5, before == after);
    double d = 2.5; char c = 'q'; long long l = 7;
    CHECK(6, (long long) &d % 8 == 0 && (long long) &l % 8 == 0 && *&c == 'q');
    return 0;
}
