package org.jbm.mycc.repl.vm;

import org.jbm.mycc.repl.vm.VM;
import org.junit.jupiter.api.Test;

import static org.jbm.mycc.repl.vm.VmTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Values threaded through many constructs in one program: a loop
 * feeds an array, the array a function, the function a struct, the
 * struct a switch, and so on, with the state checked at every hand-off.
 * Each program asserts for itself with {@code CHECK(n, cond)}.
 */
class VmDataflowTest {

    private static void checks(String decls, String body) {
        String source = "#define CHECK(n, c) if (!(c)) { return n; }\n" + decls + "\nint main(void) {\n" + body + "\nreturn 0;\n}\n";
        long failed = ((VM.IntValue) run(source)).value();
        assertEquals(0, failed, "CHECK " + failed + " failed");
    }

    @Test
    void loopToArrayToFunctionToStructToSwitch() {
        checks("""
                struct Stats { int sum; int max; int count; double mean; };
                void stats(const int *v, int n, struct Stats *out) {
                    out->sum = 0; out->max = v[0]; out->count = n;
                    for (int i = 0; i < n; i++) { out->sum += v[i]; if (v[i] > out->max) { out->max = v[i]; } }
                    out->mean = (double) out->sum / n;
                }
                int grade(const struct Stats *s) {
                    switch (s->max / 10) { case 9: case 10: return 'A'; case 8: return 'B'; case 7: return 'C'; default: return 'F'; }
                }
                """, """
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
                """);
    }

    @Test
    void stateMachineThroughEnumsGlobalsAndFunctionPointers() {
        checks("""
                enum State { IDLE, RUNNING, DONE, STATES };
                enum Event { START, TICK, STOP };
                int ticks; int transitions;
                enum State onIdle(enum Event e) { return e == START ? RUNNING : IDLE; }
                enum State onRunning(enum Event e) { if (e == TICK) { ticks++; return RUNNING; } return e == STOP ? DONE : RUNNING; }
                enum State onDone(enum Event e) { return DONE; }
                enum State (*handlers[STATES])(enum Event) = { onIdle, onRunning, onDone };
                enum State step(enum State s, enum Event e) { enum State next = handlers[s](e); if (next != s) { transitions++; } return next; }
                """, """
                enum Event script[7] = { TICK, START, TICK, TICK, TICK, STOP, TICK };
                enum State s = IDLE;
                int i = 0;
                while (i < 7 && s != DONE) { s = step(s, script[i]); i++; }
                CHECK(1, s == DONE);
                CHECK(2, i == 6);
                CHECK(3, ticks == 3);
                CHECK(4, transitions == 2);
                enum State again = step(DONE, START);
                CHECK(5, again == DONE && transitions == 2);
                """);
    }

    @Test
    void charactersThroughBuffersPointersAndCallbacks() {
        checks("""
                int length(const char *s) { const char *p = s; while (*p) { p++; } return p - s; }
                void copy(char *dst, const char *src) { while ((*dst++ = *src++)) { } }
                void reverse(char *s) { char *e = s + length(s) - 1; while (s < e) { char t = *s; *s++ = *e; *e-- = t; } }
                int upper(int c) { return c >= 'a' && c <= 'z' ? c - 32 : c; }
                void map(char *s, int (*f)(int)) { for (; *s; s++) { *s = f(*s); } }
                unsigned hash(const char *s) { unsigned h = 5381; while (*s) { h = h * 33 + *s++; } return h; }
                struct Word { char text[16]; int len; unsigned hash; };
                void fill(struct Word *w, const char *s) { copy(w->text, s); w->len = length(s); w->hash = hash(w->text); }
                """, """
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
                """);
    }

    @Test
    void treesInAStaticPoolThroughRecursionAndIteration() {
        checks("""
                struct Node { int key; struct Node *left; struct Node *right; };
                struct Node pool[32]; int used;
                struct Node *insert(struct Node *root, int key) {
                    if (!root) { struct Node *n = &pool[used++]; n->key = key; n->left = n->right = 0; return n; }
                    if (key < root->key) { root->left = insert(root->left, key); } else { root->right = insert(root->right, key); }
                    return root;
                }
                int sumRecursive(const struct Node *n) { return n ? n->key + sumRecursive(n->left) + sumRecursive(n->right) : 0; }
                int height(const struct Node *n) { if (!n) { return 0; } int l = height(n->left); int r = height(n->right); return 1 + (l > r ? l : r); }
                int sumIterative(struct Node *root) {
                    struct Node *stack[32]; int sp = 0; int sum = 0;
                    if (root) { stack[sp++] = root; }
                    while (sp) { struct Node *n = stack[--sp]; sum += n->key; if (n->left) { stack[sp++] = n->left; } if (n->right) { stack[sp++] = n->right; } }
                    return sum;
                }
                void inorder(const struct Node *n, int *out, int *count) { if (!n) { return; } inorder(n->left, out, count); out[(*count)++] = n->key; inorder(n->right, out, count); }
                """, """
                int keys[9] = { 50, 30, 70, 20, 40, 60, 80, 35, 45 };
                struct Node *root = 0;
                for (int i = 0; i < 9; i++) { root = insert(root, keys[i]); }
                CHECK(1, used == 9 && root->key == 50 && root->left->key == 30 && root->right->right->key == 80);
                CHECK(2, sumRecursive(root) == 430);
                CHECK(3, sumIterative(root) == sumRecursive(root));
                CHECK(4, height(root) == 4);
                int sorted[9]; int count = 0;
                inorder(root, sorted, &count);
                CHECK(5, count == 9 && sorted[0] == 20 && sorted[8] == 80);
                int ascending = 1; for (int i = 1; i < 9; i++) { ascending = ascending && sorted[i - 1] < sorted[i]; }
                CHECK(6, ascending);
                root = insert(root, 10);
                CHECK(7, height(root) == 4 && used == 10 && sumIterative(root) == 440);
                """);
    }

    @Test
    void numericTypesThroughConversionsStructsAndArrays() {
        checks("""
                struct Sample { unsigned char raw; short scaled; float ratio; double total; };
                void take(struct Sample *s, int reading) {
                    s->raw = reading;
                    s->scaled = s->raw * 100 - 5000;
                    s->ratio = s->scaled / 1000.0f;
                    s->total += s->ratio;
                }
                long long fixed(double d) { return (long long) (d * 1000 + (d < 0 ? -0.5 : 0.5)); }
                """, """
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
                """);
    }

    @Test
    void controlFlowDrivenByComputedData() {
        checks("""
                int collatzLength(int n) { int len = 1; while (n != 1) { n = n % 2 ? 3 * n + 1 : n / 2; len++; } return len; }
                int lengths[30];
                int longest(int limit, int *argmax) { int best = 0; *argmax = 0; for (int i = 1; i <= limit; i++) { lengths[i] = collatzLength(i); if (lengths[i] > best) { best = lengths[i]; *argmax = i; } } return best; }
                int classify(int len) { if (len < 5) { return 0; } else if (len < 15) { return 1; } return 2; }
                """, """
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
                """);
    }
}
