#define CHECK(n, c) if (!(c)) { return n; }
#include <wchar.h>
wchar_t s[] = L"ab€";
const wchar_t *p = L"x" L"y";
int main(void) {
    CHECK(1, sizeof(wchar_t) == 4 && sizeof s == 16);
    CHECK(2, s[0] == 'a' && s[1] == 'b' && s[2] == 0x20AC && s[3] == 0);
    CHECK(3, p[0] == L'x' && p[1] == L'y' && p[2] == 0);
    wchar_t local[4] = L"€€";
    CHECK(4, local[1] == 0x20AC && local[2] == 0 && local[3] == 0);
    CHECK(5, L'\x20AC' == s[2] && WEOF == 0xFFFFFFFFu && WCHAR_MAX == 2147483647);
    return 0;
}
