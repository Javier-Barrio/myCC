// TUnit globals: definitions vs extern references, tentative definitions, statics, block-scope statics
extern int e;
int t;
int t;
static int s = 1;
int a[];
int a[2];
void f(void) {
    static int count = 0;
    extern int e;
    count += e;
    extern double later;
    later = count;
}
double later = 2.5;
extern int never_defined[];
