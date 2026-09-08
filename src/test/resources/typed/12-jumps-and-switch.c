// Switch with Case/CaseRange/default, Labeled, Goto, Break, Continue, labeled break/continue
#define DONE 99
int f(int c, char ch) {
    switch (ch) { case 'a': case 'b' ... 'f': c = 1; break; default: c = 0; }
    switch (char k = ch) { case 1: return k; }
    switch (c) { case DONE: c = 0; }
retry:
    if (c < 3) { c++; goto retry; }
outer: while (c) {
        for (int i = 0; i < c; i++) {
            if (i == 1) continue;
            if (i == 2) continue outer;
            if (i == 3) break outer;
            break;
        }
        c--;
    }
    goto end;
end:
    return c;
}
