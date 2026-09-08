// A small realistic program: string handling with char pointers, character classes, and a lookup table
#define IS_DIGIT(c) ((c) >= '0' && (c) <= '9')
static const char *const names[] = {"zero", "one", "two"};
unsigned long strlen_(const char *s) {
    const char *p = s;
    while (*p) p++;
    return p - s;
}
int atoi_(const char *s) {
    int sign = 1, value = 0;
    if (*s == '-') { sign = -1; s++; }
    for (; IS_DIGIT(*s); s++) value = value * 10 + (*s - '0');
    return sign * value;
}
const char *name(int n) {
    return n >= 0 && n < (int)_Countof(names) ? names[n] : "?";
}
