// _BitInt types, wb constants, no promotion, ranks against standard types, sizes from the target
#define BITS(n) _BitInt(n)
BITS(7) b7;
unsigned BITS(3) u3 = 5uwb;
BITS(40) b40;
void f(int i, unsigned u, long l) {
    b7 = b7 + b7;
    i = b7 + i;
    b40 = b40 + u;
    l = b40 * l;
    u3 = u3 << 1;
    b7 = -b7;
    static_assert(sizeof(BITS(7)) == 1 && sizeof(BITS(40)) == 8 && alignof(BITS(9)) == 2);
    static_assert(2wb + 1wb == 3wb && (unsigned _BitInt(3))9 == 1);
}
