#define CHECK(n, c) if (!(c)) { return n; }
#include <stddef.h>
struct Packed { char a; char b; short c; int d; };
struct Padded { char a; int b; char c; double d; };
struct Nested { struct Padded in; struct Packed arr[2]; int tail; };
struct Bits { unsigned lo : 4; unsigned hi : 4; int wide : 20; unsigned flag : 1; };
union Pun { int i; float f; unsigned char b[4]; struct { short lo; short hi; } halves; };
int main(void) {
    CHECK(1, sizeof(struct Packed) == 8 && offsetof(struct Packed, c) == 2 && offsetof(struct Packed, d) == 4);
    CHECK(2, sizeof(struct Padded) == 24 && offsetof(struct Padded, b) == 4 && offsetof(struct Padded, c) == 8 && offsetof(struct Padded, d) == 16);
    struct Padded p = { 'a', 2, 'c', 4.5 };
    unsigned char *raw = (unsigned char *) &p;
    CHECK(3, raw[0] == 'a' && raw[4] == 2 && raw[8] == 'c' && *(double *) (raw + 16) == 4.5);
    raw[4] = 9;
    CHECK(4, p.b == 9);
    struct Nested n = { { 'x', 1, 'y', 2.0 }, { { 1, 2, 3, 4 }, { 5, 6, 7, 8 } }, 99 };
    CHECK(5, sizeof n == 24 + 16 + 4 + 4 && n.arr[1].d == 8 && n.tail == 99 && n.in.d == 2.0);
    struct Nested copy = n; copy.arr[0].a = 'Q'; copy.in.b = 77;
    CHECK(6, n.arr[0].a == 1 && n.in.b == 1 && copy.arr[0].a == 'Q' && copy.in.b == 77);
    struct Packed *pk = &n.arr[1]; pk->c = -1; (pk - 1)->d = 44;
    CHECK(7, n.arr[1].c == -1 && n.arr[0].d == 44 && (char *) pk - (char *) &n.arr[0] == 8);
    struct Bits b = { 0 }; b.lo = 15; b.hi = 1; b.wide = -12345; b.flag = 1;
    unsigned first = *(unsigned *) &b;
    CHECK(8, b.lo == 15 && b.hi == 1 && b.wide == -12345 && b.flag == 1 && sizeof b == 4 && (first & 0xff) == 0x1f);
    b.lo++; b.hi += 20;
    CHECK(9, b.lo == 0 && b.hi == 5);
    struct Bits *pb = &b; pb->wide = 1 << 19;
    CHECK(10, b.wide == -524288 && pb->flag == 1);
    union Pun u; u.f = 1.0f;
    CHECK(11, u.i == 0x3f800000 && u.b[3] == 0x3f && u.b[0] == 0 && u.halves.hi == 0x3f80 && u.halves.lo == 0 && sizeof u == 4);
    u.i = -1;
    CHECK(12, u.b[0] == 255 && u.halves.lo == -1 && u.f != u.f);
    u.b[0] = 0x78; u.b[1] = 0x56; u.b[2] = 0x34; u.b[3] = 0x12;
    CHECK(13, u.i == 0x12345678 && u.halves.lo == 0x5678);
    union Pun copyu = u; copyu.i = 0;
    CHECK(14, u.i == 0x12345678 && copyu.b[3] == 0);
    struct Padded zero = { 0 }; struct Padded assigned; assigned = zero; assigned.c = 'z';
    CHECK(15, assigned.a == 0 && assigned.b == 0 && assigned.c == 'z' && assigned.d == 0.0);
    return 0;
}
