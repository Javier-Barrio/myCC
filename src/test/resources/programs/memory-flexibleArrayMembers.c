#define CHECK(n, c) if (!(c)) { return n; }
struct S { unsigned char a, b; unsigned char c[2]; };
struct W { struct S s; unsigned char n; unsigned char tail[]; };
/* GNU: a flexible array member initialized statically extends the object's storage. */
struct W gw = { { 1, 2, { 3, 4 } }, 5, { 6, 7, 8, 9, 10 } };
struct W gz = { { 1 }, 2 };
int after = 77;
struct Table { int count; int *items[]; };
int one = 1, two = 2, three = 3;
struct Table table = { 3, { &one, &two, &three } };
int main(void) {
    CHECK(1, sizeof(struct W) == 5 && sizeof gw == 5);
    CHECK(2, gw.s.a == 1 && gw.s.c[1] == 4 && gw.n == 5);
    CHECK(3, gw.tail[0] == 6 && gw.tail[4] == 10);
    unsigned char *p = (unsigned char *) &gw;
    CHECK(4, p[5] == 6 && p[9] == 10);
    CHECK(5, gz.s.a == 1 && gz.n == 2 && after == 77);
    CHECK(6, table.count == 3 && *table.items[0] == 1 && *table.items[2] == 3);
    gw.tail[2] = 42;
    CHECK(7, gw.tail[2] == 42 && p[7] == 42 && after == 77);
    return 0;
}
