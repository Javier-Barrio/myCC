// What lowering adds: falling off the end of a non-void function, unsigned and floating operations
int falls_off(int c) {
    if (c) return 1;
}
unsigned u(unsigned a, unsigned b, double d, float f) {
    unsigned q = a / b + a % b + (a >> 3) - a * b;
    bool r = a < b || a <= b || d == f || d != f || d < f || d <= f;
    double e = (double) a + (double) f - d / 2.0;
    unsigned char back = (unsigned char) e;
    return q ^ r ^ back ^ (unsigned) (f - 1.0f);
}
struct Pair { int a; int b; };
struct Pair falls_off_aggregate(int c) {
    if (c) { struct Pair p = { c, c }; return p; }
}
