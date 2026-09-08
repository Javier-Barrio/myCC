// Add Sub Mul Div Rem BitAnd BitOr BitXor, Shl Shr, Neg BitNot, with the usual arithmetic conversions
#define SQUARE(x) ((x) * (x))
void f(void) {
    char c; unsigned char uc; short s; int i; unsigned u; long l; unsigned long ul; float fl; double d;
    i = c + s;
    u = i - u;
    l = l * i;
    ul = l / ul;
    i = i % c;
    u = u & i;
    l = l | 1;
    i = uc ^ c;
    i = c << l;
    ul = ul >> 3;
    d = fl + i;
    d = d - fl;
    i = -c;
    u = -u;
    i = ~uc;
    i = +c;
    i = SQUARE(c);
}
