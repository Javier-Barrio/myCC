#define CHECK(n, c) if (!(c)) { return n; }
#include <stdarg.h>
#include <stdio.h>
long sum(int n, ...) {
    va_list ap;
    va_start(ap, n);
    long s = 0;
    for (int i = 0; i < n; i++) { s += va_arg(ap, int); }
    va_end(ap);
    return s;
}
double avg(int n, ...) {
    va_list ap;
    va_start(ap, n);
    double s = 0;
    for (int i = 0; i < n; i++) { s += va_arg(ap, double); }
    va_end(ap);
    return n ? s / n : 0;
}
/* i: int, l: long, d: double, s: the first char of a string, p: the int a pointer points at, c: a char, promoted */
long mixed(const char *kinds, ...) {
    va_list ap;
    va_start(ap, kinds);
    long acc = 0;
    for (const char *k = kinds; *k; k++) {
        switch (*k) {
            case 'i': acc = acc * 10 + va_arg(ap, int); break;
            case 'l': acc = acc * 10 + va_arg(ap, long); break;
            case 'd': acc = acc * 10 + (long) va_arg(ap, double); break;
            case 's': acc = acc * 10 + (va_arg(ap, const char *)[0] - '0'); break;
            case 'p': acc = acc * 10 + *va_arg(ap, int *); break;
            case 'c': acc = acc * 10 + (va_arg(ap, int) - '0'); break;
            default: return -1;
        }
    }
    va_end(ap);
    return acc;
}
int copied(int n, ...) {
    va_list a, b;
    va_start(a, n);
    va_copy(b, a);
    int x = va_arg(a, int);
    int y = va_arg(b, int);
    int z = va_arg(b, int);
    va_end(a);
    va_end(b);
    return x == y && z == x + 1;
}
int format(char *out, int n, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = vsnprintf(out, n, fmt, ap);
    va_end(ap);
    return r;
}
/* Ten pairs of int and double: past the six integer and eight floating registers, natively. */
double tally(int pairs, ...) {
    va_list ap;
    va_start(ap, pairs);
    double t = 0;
    for (int i = 0; i < pairs; i++) {
        int k = va_arg(ap, int);
        double d = va_arg(ap, double);
        t += k * d;
    }
    va_end(ap);
    return t;
}
long through(int a, int b, int c, int d, int e, int f, int g, ...) {
    va_list ap;
    va_start(ap, g);
    long r = va_arg(ap, long) + a + b + c + d + e + f + g;
    va_end(ap);
    return r;
}
int main(void) {
    CHECK(1, sum(0) == 0 && sum(3, 1, 2, 3) == 6);
    CHECK(2, sum(9, 1, 2, 3, 4, 5, 6, 7, 8, 9) == 45);
    CHECK(3, avg(10, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0) == 5.5 && avg(0) == 0);
    int seven = 7;
    CHECK(4, mixed("ildspc", 1, 2L, 3.5, "4", &seven, '8') == 123478);
    CHECK(5, copied(3, 5, 6, 7) == 1);
    char buf[32];
    int r = format(buf, sizeof buf, "%d-%s-%.1f", 42, "ab", 2.5);
    CHECK(6, r == 9 && buf[0] == '4' && buf[3] == 'a' && buf[8] == '5' && buf[9] == 0);
    r = format(buf, 4, "%d", 123456);
    CHECK(7, r == 6 && buf[2] == '3' && buf[3] == 0);
    CHECK(8, tally(10, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 9.5, 10, 10.5) == 412.5);
    CHECK(9, through(1, 2, 3, 4, 5, 6, 7, 100L) == 128);
    return 0;
}
