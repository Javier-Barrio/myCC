#define CHECK(n, c) if (!(c)) { return n; }
int collatzLength(int n) { int len = 1; while (n != 1) { n = n % 2 ? 3 * n + 1 : n / 2; len++; } return len; }
int lengths[30];
int longest(int limit, int *argmax) { int best = 0; *argmax = 0; for (int i = 1; i <= limit; i++) { lengths[i] = collatzLength(i); if (lengths[i] > best) { best = lengths[i]; *argmax = i; } } return best; }
int classify(int len) { if (len < 5) { return 0; } else if (len < 15) { return 1; } return 2; }
int main(void) {
int which; int best = longest(29, &which);
CHECK(1, best == 112 && which == 27);
CHECK(2, lengths[1] == 1 && lengths[2] == 2 && lengths[6] == 9);
int buckets[3] = { 0 };
for (int i = 1; i <= 29; i++) { buckets[classify(lengths[i])]++; }
CHECK(3, buckets[0] + buckets[1] + buckets[2] == 29);
CHECK(4, buckets[0] == 4 && buckets[1] == 12 && buckets[2] == 13);
int i = 1; int firstLong = 0;
do { if (classify(lengths[i]) == 2) { firstLong = i; break; } i++; } while (i <= 29);
CHECK(5, firstLong == 7);
int found = -1;
for (int j = 29; j > 0; j--) { switch (classify(lengths[j])) { case 0: found = j; goto out; default: continue; } }
out:
CHECK(6, found == 8);
    return 0;
}
