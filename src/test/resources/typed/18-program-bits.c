// A small realistic program: bit manipulation with unsigned arithmetic, shifts, a switch on an enum
enum Op { OP_SET, OP_CLEAR, OP_TOGGLE, OP_TEST };
unsigned apply(unsigned word, enum Op op, unsigned bit) {
    unsigned mask = 1u << (bit & 31);
    switch (op) {
    case OP_SET: return word | mask;
    case OP_CLEAR: return word & ~mask;
    case OP_TOGGLE: return word ^ mask;
    case OP_TEST: return (word & mask) != 0;
    }
    return word;
}
int popcount(unsigned long x) {
    int n = 0;
    for (; x; x &= x - 1) n++;
    return n;
}
