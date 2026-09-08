// Every Conversion node: LvalueToRvalue, ArrayDecay, FunctionDecay, IntToInt,
// IntToFloat, FloatToInt, FloatToFloat, ToBool, ToVoid, PtrToPtr, IntToPtr, PtrToInt, NullToPtr
#define AS(T, x) ((T)(x))
void f(void) {
    char c; short s; int i; long l; float fl; double d; int a[2]; int *p; void *v; bool b;
    i = c;              // IntToInt (widening)
    c = i;              // IntToInt (narrowing)
    d = i;              // IntToFloat
    i = d;              // FloatToInt
    d = fl;             // FloatToFloat
    fl = d;             // FloatToFloat (narrowing)
    b = p;              // ToBool
    (void)i;            // ToVoid
    v = p;              // PtrToPtr
    p = AS(int *, l);   // IntToPtr
    l = AS(long, p);    // PtrToInt
    p = 0;              // NullToPtr
    p = a;              // ArrayDecay
    void (*fp)(void) = f;   // FunctionDecay
    s = c + s;          // promotions inside arithmetic
}
