#define CHECK(n, c) if (!(c)) { return n; }

int main(void) {
int n = 0;
top: n++; if (n < 3) { goto top; }
CHECK(1, n == 3);
int s = 0; switch (2) { case 1: s = 1; case 2: s += 2; case 3: s += 3; break; default: s = -1; }
CHECK(2, s == 5);
int x = 1; { int x = 2; { int x = 3; CHECK(3, x == 3); } x++; CHECK(4, x == 3); }
CHECK(5, x == 1);
{ x = 2; }
CHECK(6, x == 2);
x; 3 + 4; ;
CHECK(7, x == 2);
int sum = 0; { int i = 1; sum += i; } { int i = 2; sum += i; }
CHECK(8, sum == 3);
    return 0;
}
