#define CHECK(n, c) if (!(c)) { return n; }
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
int main(void) {
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
    return 0;
}
