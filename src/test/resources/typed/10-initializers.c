// TInit: scalars, arrays, strings, records, designators, brace elision, nested, unions, size completion
#define N 3
struct In { int x, y; };
struct Out { struct In in; char name[8]; int tail[N]; };
int a[] = {1, 2, N};
int b[N] = {[2] = 9};
char s[] = "abc";
char t[4] = {"ab"};
struct Out o = {{1, 2}, "hey", {7}};
struct Out p = {.tail[1] = 5, .in.y = 3, .name = "q"};
struct Out q[2] = {[1].in = {8, 9}, 10};
union U { char c; int i; } u = {.i = 1};
int m[2][2] = {1, 2, 3};
void f(int k) {
    int loc[] = {k, k + 1};
    struct In in = {.y = k};
    struct Out out = {in, "z"};
    char name[] = "loc";
    int zero[N] = {};
}
