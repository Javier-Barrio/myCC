#define CHECK(n, c) if (!(c)) { return n; }
struct Stats { int sum; int max; int count; double mean; };
void stats(const int *v, int n, struct Stats *out) {
    out->sum = 0; out->max = v[0]; out->count = n;
    for (int i = 0; i < n; i++) { out->sum += v[i]; if (v[i] > out->max) { out->max = v[i]; } }
    out->mean = (double) out->sum / n;
}
int grade(const struct Stats *s) {
    switch (s->max / 10) { case 9: case 10: return 'A'; case 8: return 'B'; case 7: return 'C'; default: return 'F'; }
}
int main(void) {
int v[6];
for (int i = 0; i < 6; i++) { v[i] = 50 + i * 9; }
CHECK(1, v[0] == 50 && v[5] == 95);
struct Stats s;
stats(v, 6, &s);
CHECK(2, s.sum == 435 && s.max == 95 && s.count == 6);
CHECK(3, s.mean == 72.5);
int g = grade(&s);
CHECK(4, g == 'A');
v[5] = 88; stats(v, 6, &s); g = grade(&s);
CHECK(5, s.max == 88 && g == 'B' && s.sum == 428);
int byGrade[3] = { 0 };
for (int i = 0; i < 6; i++) { struct Stats one; stats(&v[i], 1, &one); byGrade[grade(&one) - 'A' < 3 ? grade(&one) - 'A' : 2]++; }
CHECK(6, byGrade[0] == 0 && byGrade[1] == 2 && byGrade[2] == 4);
    return 0;
}
