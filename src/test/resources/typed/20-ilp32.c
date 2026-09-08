// Typed under the ILP32 test target: widths, size_t, ptrdiff_t, layout and conversions differ
#define LONG_MAX 2147483647L
long l = LONG_MAX;
unsigned long ul = 4294967295ul;
struct S { char c; double d; long l; };
int sizes = sizeof(long) + sizeof(void *) + sizeof(struct S) + sizeof(long double) + sizeof(long long);
void f(unsigned u, long l, int *p, int *q) {
    l = u + l;
    unsigned long z = sizeof l;
    long diff = p - q;
    char c = '\xff';
    int ci = c;
}
