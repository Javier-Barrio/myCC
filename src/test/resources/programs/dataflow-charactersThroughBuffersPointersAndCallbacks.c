#define CHECK(n, c) if (!(c)) { return n; }
int length(const char *s) { const char *p = s; while (*p) { p++; } return p - s; }
void copy(char *dst, const char *src) { while ((*dst++ = *src++)) { } }
void reverse(char *s) { char *e = s + length(s) - 1; while (s < e) { char t = *s; *s++ = *e; *e-- = t; } }
int upper(int c) { return c >= 'a' && c <= 'z' ? c - 32 : c; }
void map(char *s, int (*f)(int)) { for (; *s; s++) { *s = f(*s); } }
unsigned hash(const char *s) { unsigned h = 5381; while (*s) { h = h * 33 + *s++; } return h; }
struct Word { char text[16]; int len; unsigned hash; };
void fill(struct Word *w, const char *s) { copy(w->text, s); w->len = length(s); w->hash = hash(w->text); }
int main(void) {
struct Word w;
fill(&w, "stressed");
CHECK(1, w.len == 8 && w.text[7] == 'd' && w.text[8] == 0);
unsigned before = w.hash;
reverse(w.text);
CHECK(2, w.text[0] == 'd' && w.text[7] == 's' && length(w.text) == 8);
map(w.text, upper);
CHECK(3, w.text[0] == 'D' && w.text[1] == 'E');
w.hash = hash(w.text);
CHECK(4, w.hash != before);
struct Word copy2 = w;
map(copy2.text, upper);
CHECK(5, hash(copy2.text) == w.hash);
char buf[16]; copy(buf, "DESSERTS");
CHECK(6, hash(buf) == w.hash);
int diff = 0; for (int i = 0; i < w.len; i++) { diff += buf[i] != w.text[i]; }
CHECK(7, diff == 0);
    return 0;
}
