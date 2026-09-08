// Enumerators as constants, enum underlying types, _Generic, typeof, casts folding
#define TYPE_NAME(x) _Generic((x), int: 1, double: 2, char *: 3, default: 0)
enum Color { RED, GREEN = 5, BLUE };
enum Small : unsigned char { S1 = 200 };
enum Big { HUGE = 1L << 40 };
typeof(RED) c = BLUE;
enum Small s = S1;
int which = TYPE_NAME(1.5);
typeof_unqual(const int) plain;
void f(void) {
    int i; double d; char *p;
    i = TYPE_NAME(i) + TYPE_NAME(p) + TYPE_NAME(d) + TYPE_NAME(s);
    i = (int)d + (char)i + (int)(long)p;
    d = (double)HUGE;
    unsigned long z = sizeof(typeof(p + 1)) + sizeof(enum Big);
}
