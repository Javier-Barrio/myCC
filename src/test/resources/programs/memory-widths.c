#define CHECK(n, c) if (!(c)) { return n; }
int main(void) {
    unsigned char bytes[8] = { 0x01, 0x02, 0x03, 0x04, 0x85, 0x86, 0x87, 0x88 };
    signed char *sc = (signed char *) bytes;
    CHECK(1, bytes[4] == 0x85 && sc[4] == -123 && bytes[0] == 1);
    unsigned short *us = (unsigned short *) bytes; short *ss = (short *) bytes;
    CHECK(2, us[0] == 0x0201 && us[2] == 0x8685 && ss[2] == -31099);
    unsigned *ui = (unsigned *) bytes; int *si = (int *) bytes;
    CHECK(3, ui[0] == 0x04030201u && ui[1] == 0x88878685u && si[1] == -2004384123);
    unsigned long long *ul = (unsigned long long *) bytes; long long *sl = (long long *) bytes;
    CHECK(4, *ul == 0x8887868504030201ULL && *sl < 0);
    *si = -1;
    CHECK(5, bytes[0] == 255 && bytes[3] == 255 && bytes[4] == 0x85 && us[1] == 0xffff);
    ss[0] = -2;
    CHECK(6, bytes[0] == 0xfe && bytes[1] == 0xff && bytes[2] == 0xff);
    sc[0] = 5;
    CHECK(7, *si == (int) 0xffffff05u && bytes[1] == 0xff);
    *sl = 0;
    CHECK(8, ui[0] == 0 && ui[1] == 0 && *ul == 0);
    long long v = 0x1122334455667788LL; unsigned char *p = (unsigned char *) &v;
    CHECK(9, p[0] == 0x88 && p[7] == 0x11 && p[3] == 0x55);
    p[7] = 0x91;
    CHECK(10, v < 0 && (unsigned long long) v == 0x9122334455667788ULL);
    return 0;
}
