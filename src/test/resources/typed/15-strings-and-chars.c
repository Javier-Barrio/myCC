// String objects with every prefix, concatenation, escapes, character constants, sizeof of strings
#define GREETING "hello" ", " "world"
const char *g = GREETING;
const unsigned char *u8 = u8"é";
const unsigned short *u16 = u"\xFFFF" "z";
const unsigned int *u32 = U"a";
const int *w = L"wide";
int lens = sizeof GREETING + sizeof u8"é" + sizeof L"wide";
int chars = 'a' + '\n' + '\0' + '\x7f' + L'w' + u'y' + U'z' + u8'q';
void f(void) { "unused"; (void)sizeof "not an object"; }
