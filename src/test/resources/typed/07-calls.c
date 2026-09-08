// Call: prototypes, argument conversion as if by assignment, variadic promotions, function pointers
#define CALL(f, a, b) f(a, b)
int add(int, int);
double half(double);
int printf(const char *, ...);
void noargs(void);
int (*table[2])(int, int);
struct S { int x; } mk(int);
void f(void) {
    char c; float fl; int i;
    i = add(c, 2);
    fl = half(c);
    printf("%d %f %s", c, fl, "x");
    noargs();
    i = table[1](1, 2);
    i = mk(1).x;
    i = CALL(add, i, i);
}
