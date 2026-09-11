#define CHECK(n, c) if (!(c)) { return n; }
struct Sample { unsigned char raw; short scaled; float ratio; double total; };
void take(struct Sample *s, int reading) {
    s->raw = reading;
    s->scaled = s->raw * 100 - 5000;
    s->ratio = s->scaled / 1000.0f;
    s->total += s->ratio;
}
long long fixed(double d) { return (long long) (d * 1000 + (d < 0 ? -0.5 : 0.5)); }
int main(void) {
struct Sample s = { 0 };
int readings[4] = { 10, 300, 255, -1 };
for (int i = 0; i < 4; i++) { take(&s, readings[i]); }
CHECK(1, s.raw == 255);
CHECK(2, s.scaled == 20500);
CHECK(3, s.ratio == 20.5f);
CHECK(4, fixed(s.total) == 36400);
short history[4]; double sum = 0; struct Sample t = { 0 };
for (int i = 0; i < 4; i++) { take(&t, readings[i]); history[i] = t.scaled; sum += history[i] / 1000.0; }
CHECK(5, history[0] == -4000 && history[1] == -600 && history[3] == 20500);
CHECK(6, fixed(sum) == fixed(t.total));
unsigned char c = readings[1]; signed char sc = readings[2]; unsigned u = readings[3];
CHECK(7, c == 44 && sc == -1 && u == 4294967295u && (int) u == -1);
long long wide = u; wide *= u;
CHECK(8, wide == 18446744065119617025ULL && wide < 0 && (double) (unsigned long long) wide > 1.8e19);
    return 0;
}
