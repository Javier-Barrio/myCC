// Block, ExprStmt, LocalDecl, If, While, DoWhile, For, Return, selection headers with declarations
#define FOREVER for (;;)
int f(int n) {
    int total = 0;
    if (n) total = 1; else total = 2;
    if (int m = n * 2) total += m;
    if (int m = n; m > 3) { total += m; }
    while (n > 0) n = n - 1;
    do total++; while (total < 10);
    for (int i = 0, j = 1; i < n; i++, j *= 2) total += j;
    for (total = 0; ; ) break;
    FOREVER { break; }
    ;
    return total;
}
void v(void) { return; }
