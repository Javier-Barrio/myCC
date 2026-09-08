// Assign, CompoundAssign, PostfixAssign, TargetValue (shared target node), prefix ++ desugared
#define INC(x) ((x) += 1)
void f(void) {
    int i, j; char c; int *p; double d; unsigned u; int a[3];
    i = j = 2;
    c = 300;
    i += c;
    c -= 1.5;
    p += i;
    p -= 1;
    u <<= i;
    d *= 2;
    i %= 3;
    INC(i);
    ++i;
    --p;
    j = i++;
    j = i--;
    p = p++;
    a[i++] = i;
    a[j] += a[j];
}
