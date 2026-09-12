#define CHECK(n, c) if (!(c)) { return n; }
alignas(32) char g1[3] = { 1, 2, 3 };
alignas(64) int g2 = 7;
static alignas(32) short g3[5];
unsigned long address(const void *p) { return (unsigned long) p; }
int main(void) {
    CHECK(1, address(g1) % 32 == 0 && address(&g2) % 64 == 0 && address(g3) % 32 == 0);
    CHECK(2, g1[0] == 1 && g1[2] == 3 && g2 == 7 && g3[4] == 0);
    alignas(16) char l1[3] = { 4, 5, 6 };
    alignas(16) int l2 = 8;
    char between = 9;
    static alignas(64) double l3 = 1.5;
    CHECK(3, address(l1) % 16 == 0 && address(&l2) % 16 == 0 && address(&l3) % 64 == 0);
    l1[1] = 50; l2 += 1; g3[4] = 44;
    CHECK(4, l1[0] == 4 && l1[1] == 50 && l1[2] == 6 && l2 == 9 && between == 9 && l3 == 1.5 && g3[4] == 44);
    CHECK(5, alignof(int) == 4 && sizeof l1 == 3 && sizeof g1 == 3);
    return 0;
}
