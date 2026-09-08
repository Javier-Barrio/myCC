// VarRef, FuncRef, IntConst, FloatConst, NullptrConst, AddrConst, string objects
#define ZERO 0
#define HALF 0.5f
int g = 3;
const char *msg = "hi";
int *np = ZERO;
int *nq = nullptr;
int (*fp)(void);
int f(void) { return g; }
double d = HALF;
long big = 1'000'000'000'000;
unsigned u = 0xFFu;
char c = 'x';
int *pg = &g;
int (*pf)(void) = f;
void use(void) {
    g; f; 42; 1.5; 'a'; "s"; nullptr; true;
}
