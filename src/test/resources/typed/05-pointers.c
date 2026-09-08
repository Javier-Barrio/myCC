// Deref, AddrOf, PtrAdd, PtrDiff, subscripting, pointer to pointer, function pointers, FuncDeref
#define AT(a, i) (*((a) + (i)))
int arr[4];
int m[2][3];
int f(int);
void g(void) {
    int *p = arr; int **pp = &p; long n; int (*fp)(int) = f;
    *p = 1;
    p = &arr[2];
    p = arr + n;
    p = p - 2;
    n = p - arr;
    arr[1] = AT(arr, 3);
    m[1][2] = **pp;
    p = m[1];
    fp(3);
    (*fp)(4);
    (**fp)(5);
    p = &*p;
    n = sizeof arr / sizeof arr[0];
}
