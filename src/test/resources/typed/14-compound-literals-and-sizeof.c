// CompoundLit (static and automatic), sizeof/alignof/_Countof folding, static_assert
#define POINT(x, y) ((struct Pt){x, y})
struct Pt { int x, y; };
struct Pt *origin = &(struct Pt){0, 0};
int *tbl = (int[]){1, 2, 3};
static_assert(sizeof(struct Pt) == 8, "two ints");
static_assert(_Countof((int[]){1, 2, 3}) == 3);
int f(int k) {
    struct Pt p = POINT(k, k + 1);
    int *q = (int[]){k, 2};
    (static const int){7};
    return POINT(1, 2).y + sizeof POINT(k, k) + sizeof (int[2]){0};
}
