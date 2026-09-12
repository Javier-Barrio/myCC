/* Variable arguments: va_start, va_arg of scalars and pointers, va_copy, va_end. */
#include <stdarg.h>
long total(int n, ...) {
    va_list ap;
    va_start(ap, n);
    long s = 0;
    for (int i = 0; i < n; i++) {
        s += va_arg(ap, int);
    }
    va_end(ap);
    return s;
}
double first_double(const char *p, ...) {
    va_list ap, copy;
    va_start(ap, p);
    va_copy(copy, ap);
    const char *q = va_arg(copy, const char *);
    double d = va_arg(ap, double);
    va_end(copy);
    va_end(ap);
    return q == p ? d : -d;
}
