#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
CHECK(1, (int) 3.9 == 3 && (double) 3 == 3.0 && (int) -3.9 == -3);
CHECK(2, (char) 200 == -56 && (unsigned char) 200 == 200 && (short) 70000 == 4464);
int x = 7; void *v = (void *) &x;
CHECK(3, *(int *) v == 7 && (char *) v == (char *) &x);
long long l = 0x1'0000'0001LL;
CHECK(4, (int) l == 1 && (unsigned) l == 1u && l != 1);
double d = 2.7;
CHECK(5, (long long) d == 2 && (unsigned) d == 2u && (bool) d == 1 && (float) d != d);
CHECK(6, ((void) x, 1));
void *addr = (void *) 4096;
CHECK(7, (long long) addr == 4096 && (int *) addr == (int *) 4096);
CHECK(8, (unsigned long long) -1 == 18446744073709551615ULL && (unsigned) -1 == 4294967295u);
    return 0;
}
