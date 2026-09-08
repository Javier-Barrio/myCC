// Record types, layout, Member (. and ->), anonymous members, bit-fields, Materialize, struct copy
#define OFF(T, m) ((long)&((T *)0)->m)
struct P { char tag; union { int i; float f; }; struct { short a, b; } pair; };
struct B { unsigned lo : 4; unsigned hi : 4; int wide : 20; bool flag : 1; };
struct P make(void);
void f(void) {
    struct P p, q; struct P *pp = &p; struct B b;
    p.tag = 1;
    p.i = 2;
    pp->f = 1.5;
    q = p;
    p.pair.b = pp->pair.a;
    make().i = 3;
    b.lo = 15;
    b.hi = b.lo + 1;
    b.wide = -b.wide;
    b.flag = b.wide;
    long o = OFF(struct P, pair);
    unsigned long s = sizeof(struct P) + sizeof b + alignof(struct B);
}
