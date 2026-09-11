#define CHECK(n, c) if (!(c)) { return n; }
enum State { IDLE, RUNNING, DONE, STATES };
enum Event { START, TICK, STOP };
int ticks; int transitions;
enum State onIdle(enum Event e) { return e == START ? RUNNING : IDLE; }
enum State onRunning(enum Event e) { if (e == TICK) { ticks++; return RUNNING; } return e == STOP ? DONE : RUNNING; }
enum State onDone(enum Event e) { return DONE; }
enum State (*handlers[STATES])(enum Event) = { onIdle, onRunning, onDone };
enum State step(enum State s, enum Event e) { enum State next = handlers[s](e); if (next != s) { transitions++; } return next; }
int main(void) {
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
    return 0;
}
