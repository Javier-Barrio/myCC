// A small realistic program: a linked list with a typedef'd struct, pointers, loops and a macro
#define NULL ((void *)0)
#define FOR_EACH(it, head) for (Node *it = (head); it != NULL; it = it->next)
typedef struct Node { int value; struct Node *next; } Node;
Node *push(Node *head, Node *node) { node->next = head; return node; }
int sum(Node *head) {
    int total = 0;
    FOR_EACH(it, head) total += it->value;
    return total;
}
int length(const Node *head) {
    int n = 0;
    while (head) { n++; head = head->next; }
    return n;
}
int main(void) {
    Node a = {1, NULL}, b = {2, NULL};
    Node *list = push(push(NULL, &a), &b);
    return sum(list) - length(list) * 2;
}
