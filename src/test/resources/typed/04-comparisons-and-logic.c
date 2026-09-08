// Eq Ne Lt Le Gt Ge, And Or Not, ToBool on scalars, Cond, Comma
#define MAX(a, b) ((a) > (b) ? (a) : (b))
void f(void) {
    int i; unsigned u; double d; int *p, *q; bool b; char c;
    b = i == u;
    b = i != d;
    b = c < 'z';
    b = p <= q;
    b = p == 0;
    b = i >= 0u;
    b = p && d;
    b = i || b;
    b = !p;
    i = MAX(i, c);
    d = b ? i : d;
    p = b ? p : 0;
    i = (i = 1, c);
}
