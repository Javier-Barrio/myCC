#define CHECK(n, c) if (!(c)) { return n; }
int a = 1, b = 2;
int *pa = &a; int *pb = &b;
int arr[5] = { 10, 20, 30, 40, 50 };
int *mid = arr + 2; int *end = &arr[4];
const char *words[] = { "alpha", "beta", "gamma" };
const char **second = &words[1];
struct Cfg { const char *name; int *target; int values[3]; } cfg = { "cfg", &b, { 7, 8, 9 } };
struct Cfg *pcfg = &cfg;
int *table[2] = { &arr[0], &arr[4] };
int (*fn)(void);
int hello(void) { return 42; }
static char buffer[16];
char *bp = buffer + 2;
int main(void) {
    CHECK(1, *pa == 1 && *pb == 2 && pa != pb);
    *pa = 5; b = 6;
    CHECK(2, a == 5 && *pb == 6);
    CHECK(3, *mid == 30 && *end == 50 && end - mid == 2 && mid[-2] == 10);
    CHECK(4, (*second)[0] == 'b' && words[2][0] == 'g' && words[0][4] == 'a' && words[0][5] == 0);
    CHECK(5, cfg.name[1] == 'f' && *cfg.target == 6 && pcfg->values[2] == 9 && pcfg->target == pb);
    *pcfg->target = 60;
    CHECK(6, b == 60 && *table[1] == 50 && table[0] == arr);
    fn = hello;
    CHECK(7, fn() == 42 && fn == hello && fn == &hello);
    bp[0] = 'x'; buffer[3] = 'y';
    CHECK(8, buffer[2] == 'x' && bp[1] == 'y' && buffer[0] == 0 && bp - buffer == 2);
    static int counter = 0; static int *pc = &counter;
    (*pc)++; counter++;
    CHECK(9, counter == 2 && *pc == 2);
    CHECK(10, (char *) &cfg.values - (char *) &cfg == 16 && sizeof cfg == 32);
    return 0;
}
