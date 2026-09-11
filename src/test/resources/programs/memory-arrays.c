#define CHECK(n, c) if (!(c)) { return n; }
int grid[3][4];
int main(void) {
    for (int i = 0; i < 3; i++) { for (int j = 0; j < 4; j++) { grid[i][j] = i * 10 + j; } }
    int *flat = &grid[0][0];
    CHECK(1, flat[5] == 11 && flat[11] == 23 && sizeof grid == 48 && sizeof grid[0] == 16);
    CHECK(2, &grid[1][0] - &grid[0][0] == 4 && (char *) &grid[2] - (char *) grid == 32);
    int (*row)[4] = grid + 1;
    CHECK(3, (*row)[2] == 12 && row[1][3] == 23 && **row == 10);
    char text[3][6] = { "one", "two", "three" };
    CHECK(4, text[2][4] == 'e' && text[1][3] == 0 && sizeof text == 18 && text[0][5] == 0);
    char *rows[3] = { text[0], text[1], text[2] };
    CHECK(5, rows[1][0] == 't' && rows[2] - rows[0] == 12 && *rows[0] == 'o');
    struct P { int x; int y; } pts[3] = { { 1, 2 }, { 3, 4 }, { 5, 6 } };
    struct P *last = pts + 2; int *ys = &pts[0].y;
    CHECK(6, last->x == 5 && ys[2] == 4 && ys[4] == 6 && (char *) last - (char *) pts == 16);
    pts[1] = pts[2]; pts[2].x = 0;
    CHECK(7, pts[1].x == 5 && pts[1].y == 6 && pts[2].x == 0);
    int big[100]; for (int i = 0; i < 100; i++) { big[i] = i * i; }
    long long sum = 0; for (int *p = big; p < big + 100; p++) { sum += *p; }
    CHECK(8, sum == 328350 && big[99] == 9801);
    double m[2][2] = { { 1.5, 2.5 }, { 3.5, 4.5 } }; double *dp = &m[1][0];
    CHECK(9, dp[-1] == 2.5 && dp[1] == 4.5 && *(dp - 2) == 1.5);
    unsigned char raw[16] = { 0 }; int *ints = (int *) raw; ints[1] = 0x01020304; ints[3] = -1;
    CHECK(10, raw[4] == 4 && raw[7] == 1 && raw[12] == 255 && raw[0] == 0 && raw[11] == 0);
    return 0;
}
