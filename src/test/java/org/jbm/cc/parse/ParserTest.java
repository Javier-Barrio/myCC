package org.jbm.cc.parse;

import org.jbm.mycc.cc.parse.ParseException;
import org.jbm.mycc.cc.parse.Parser;
import org.jbm.mycc.cc.parse.ast.AstPrinter;
import org.jbm.mycc.cc.parse.ast.Decl;
import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.Scanner;
import org.jbm.mycc.cc.cpp.TokenConversion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests, organized by the Annex A section they exercise. Every test
 * runs the full pipeline (lex, expand, phase 7, parse) and compares the
 * {@link AstPrinter} rendering, so macros are in play and expectations read
 * as S-expressions.
 */
class ParserTest {

    private static TokenSet preprocess(String source) {
        return TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source)));
    }

    /** Parses one standalone expression. */
    private static String expr(String source) {
        return AstPrinter.print(new Parser(preprocess(source)).parseStandaloneExpression());
    }

    /** Parses a translation unit; one line per external declaration. */
    private static String unit(String source) {
        return AstPrinter.print(Parser.parse(preprocess(source)));
    }

    private static ParseException fails(String source) {
        return assertThrows(ParseException.class, () -> unit(source));
    }

    /** Parses `{ statements }` as one compound statement and returns its block items, space separated. */
    private static String block(String statements) {
        var parser = new Parser(preprocess("{\n" + statements + "\n}"));
        String out = AstPrinter.print(parser.parseStandaloneStatement());
        assertTrue(out.startsWith("(block ") && out.endsWith(")"), out);
        return out.substring("(block ".length(), out.length() - 1);
    }

    // ---- A.3.1 expressions (6.5) ---------------------------------------

    @Nested
    class Expressions {

        @Test
        void binaryOperatorPrecedence() {
            assertEquals("(+ a (* b c))", expr("a + b * c"));
            assertEquals("(+ (* a b) c)", expr("a * b + c"));
            assertEquals("(<< a (+ b c))", expr("a << b + c"));
            assertEquals("(== (< a b) (< c d))", expr("a < b == c < d"));
            assertEquals("(& a (== b c))", expr("a & b == c"));
            assertEquals("(| (^ a (& b c)) d)", expr("a ^ b & c | d"));
            assertEquals("(|| a (&& b c))", expr("a || b && c"));
        }

        @Test
        void binaryOperatorsAreLeftAssociative() {
            assertEquals("(- (- a b) c)", expr("a - b - c"));
            assertEquals("(% (/ a b) c)", expr("a / b % c"));
        }

        @Test
        void parenthesesOverridePrecedence() {
            assertEquals("(* (+ a b) c)", expr("(a + b) * c"));
        }

        @Test
        void assignmentIsRightAssociativeAndLowest() {
            assertEquals("(= a (= b c))", expr("a = b = c"));
            assertEquals("(+= a (* b 2))", expr("a += b * 2"));
            assertEquals("(= a (?: b c d))", expr("a = b ? c : d"));
        }

        @Test
        void conditionalExpression() {
            assertEquals("(?: a b (?: c d e))", expr("a ? b : c ? d : e"));
            assertEquals("(?: a (, b c) d)", expr("a ? b, c : d"));
        }

        @Test
        void commaExpression() {
            assertEquals("(, (, a b) c)", expr("a, b, c"));
            assertEquals("(, (= a 1) (= b 2))", expr("a = 1, b = 2"));
        }

        @Test
        void unaryOperators() {
            assertEquals("(* (- a) b)", expr("-a * b"));
            assertEquals("(&& (! a) b)", expr("!a && b"));
            assertEquals("(* (post++ p))", expr("*p++"));
            assertEquals("(++ (* p))", expr("++*p"));
            assertEquals("(- (- a))", expr("- -a"));
            assertEquals("(~ (& x))", expr("~&x"));
        }

        @Test
        void postfixOperators() {
            assertEquals("([] ([] a i) j)", expr("a[i][j]"));
            assertEquals("(call (call f a b) c)", expr("f(a, b)(c)"));
            assertEquals("(call f)", expr("f()"));
            assertEquals("(. (-> p x) y)", expr("p->x.y"));
            assertEquals("(+ (post++ a) (++ b))", expr("a++ + ++b"));
            assertEquals("(call (-> p f) (, a b))", expr("p->f((a, b))"));
        }

        @Test
        void literals() {
            assertEquals("42", expr("42"));
            assertEquals("1.5e3", expr("1.5e3"));
            assertEquals("'c'", expr("'c'"));
            assertEquals("true", expr("true"));
            assertEquals("nullptr", expr("nullptr"));
            assertEquals("\"a\"", expr("\"a\""));
        }

        @Test
        void adjacentStringLiteralsConcatenate() {
            assertEquals("\"a\" u8\"b\"", expr("\"a\" u8\"b\""));
            assertEquals("(call f \"x\" \"y\" z)", expr("f(\"x\" \"y\", z)"));
        }

        @Test
        void sizeofAlignofCountof() {
            assertEquals("(sizeof x)", expr("sizeof x"));
            assertEquals("(sizeof x)", expr("sizeof(x)"));
            assertEquals("(sizeof (type int))", expr("sizeof(int)"));
            assertEquals("(+ (sizeof x) 1)", expr("sizeof x + 1"));
            assertEquals("(* (sizeof (type int)) 2)", expr("sizeof (int) * 2"));
            assertEquals("(sizeof (type (ptr (const char))))", expr("sizeof(const char *)"));
            assertEquals("(alignof (type double))", expr("alignof(double)"));
            assertEquals("(_Countof arr)", expr("_Countof arr"));
            assertEquals("(_Countof (type (array int 3)))", expr("_Countof(int[3])"));
        }

        @Test
        void casts() {
            assertEquals("(cast int x)", expr("(int)x"));
            assertEquals("(cast int (- x))", expr("(int)-x"));
            assertEquals("(cast (ptr void) (cast long x))", expr("(void *)(long)x"));
            assertEquals("(* (cast int a) b)", expr("(int)a * b"));
            assertEquals("(cast (ptr (fn int ((int)))) f)", expr("(int (*)(int))f"));
            assertEquals("(cast (ptr (struct S)) p)", expr("(struct S *)p"));
        }

        @Test
        void aParenthesizedNonTypeIsAnExpressionNotACast() {
            assertEquals("(* a b)", expr("(a) * b"));
            assertThrows(ParseException.class, () -> expr("(a) b"));
        }

        @Test
        void compoundLiterals() {
            assertEquals("(compound (array int) {1 2})", expr("(int[]){1, 2}"));
            assertEquals("(compound (struct p) {(.x 1) (.y 2)})", expr("(struct p){.x = 1, .y = 2}"));
            assertEquals("(compound static int {3})", expr("(static int){3}"));
            assertEquals("(+ (compound int {1}) 1)", expr("(int){1} + 1"));
            assertEquals("(. (compound (struct p) {1}) x)", expr("(struct p){1}.x"));
            assertEquals("(sizeof (compound (array int) {1 2 3}))", expr("sizeof (int[]){1, 2, 3}"));
            assertEquals("(compound (struct (a int) (b int)) {1 2})", expr("(struct { int a, b; }){1, 2}"));
        }

        @Test
        void genericSelection() {
            assertEquals("(_Generic x (int 1) (default 2))", expr("_Generic(x, int: 1, default: 2)"));
            assertEquals("(_Generic (type int) ((ptr char) 1))", expr("_Generic(int, char *: 1)"));
        }

        @Test
        void staticAssertionAsExpression() {
            assertEquals("(static_assert 1 \"m\")", expr("static_assert(1, \"m\")"));
            assertEquals("(static_assert (> 2 1))", expr("static_assert(2 > 1)"));
        }

        @Test
        void macrosExpandBeforeParsing() {
            assertEquals("(* (+ a 1) (+ a 1))", expr("""
                    #define SQUARE(v) ((v) * (v))
                    SQUARE(a + 1)
                    """));
        }

        @Test
        void syntaxErrorsAreReported() {
            assertThrows(ParseException.class, () -> expr("a +"));
            assertThrows(ParseException.class, () -> expr("(a"));
            assertThrows(ParseException.class, () -> expr("a b"));
            assertThrows(ParseException.class, () -> expr("a[1"));
            assertThrows(ParseException.class, () -> expr("f(a,)"));
            var e = assertThrows(ParseException.class, () -> expr("1 +\n  )"));
            assertTrue(e.getMessage().contains("2:3"), e.getMessage());
        }
    }

    // ---- A.3.2 declarations (6.7) --------------------------------------

    @Nested
    class Declarations {

        @Test
        void simpleObjectDeclarations() {
            assertEquals("(decl (x int))", unit("int x;"));
            assertEquals("(decl (x int 1) (p (ptr int) (& x)))", unit("int x = 1, *p = &x;"));
            assertEquals("(decl static (s (const (ptr (const char))) \"hi\"))",
                    unit("static const char *const s = \"hi\";"));
            assertEquals("(decl extern (n int))", unit("extern int n;"));
            assertEquals("(decl constexpr (k int 3))", unit("constexpr int k = 3;"));
        }

        @Test
        void typeSpecifiersFoldInAnyOrder() {
            assertEquals("(decl (x unsigned long long))", unit("unsigned long long x;"));
            assertEquals("(decl (y unsigned long long))", unit("long unsigned int long y;"));
            assertEquals("(decl (s short))", unit("short s;"));
            assertEquals("(decl (s unsigned short))", unit("unsigned short int s;"));
            assertEquals("(decl (c signed char))", unit("signed char c;"));
            assertEquals("(decl (c unsigned char))", unit("char unsigned c;"));
            assertEquals("(decl (d long double))", unit("long double d;"));
            assertEquals("(decl (z _Complex double))", unit("double _Complex z;"));
            assertEquals("(decl (u unsigned int))", unit("unsigned u;"));
            assertEquals("(decl (l long))", unit("long l;"));
            assertEquals("(decl (b bool))", unit("bool b;"));
            assertEquals("(decl (d _Decimal64))", unit("_Decimal64 d;"));
        }

        @Test
        void bitPreciseAndAtomicTypes() {
            assertEquals("(decl (b (unsigned _BitInt 7)))", unit("unsigned _BitInt(7) b;"));
            assertEquals("(decl (b (_BitInt (+ 8 8))))", unit("_BitInt(8 + 8) b;"));
            assertEquals("(decl (a (_Atomic int)))", unit("_Atomic(int) a;"));
            assertEquals("(decl (a (_Atomic int)))", unit("_Atomic int a;"));
            assertEquals("(decl (p (ptr (_Atomic int))))", unit("_Atomic(int) *p;"));
        }

        @Test
        void invalidSpecifierCombinationsAreRejected() {
            fails("short long x;");
            fails("signed float f;");
            fails("long long long x;");
            fails("int char c;");
            fails("_Complex c;");
            fails("unsigned bool b;");
        }

        @Test
        void qualifiers() {
            assertEquals("(decl (p (ptr (const volatile int))))", unit("const volatile int *p;"));
            assertEquals("(decl (p (const (ptr int))))", unit("int *const p;"));
            assertEquals("(decl (p (restrict (ptr (const char)))))", unit("const char *restrict p;"));
            assertEquals("(decl (pp (ptr (const (ptr char)))))", unit("char *const *pp;"));
        }

        @Test
        void declaratorsComposeInsideOut() {
            assertEquals("(decl (a (array (ptr int) 3)))", unit("int *a[3];"));
            assertEquals("(decl (a (ptr (array int 3))))", unit("int (*a)[3];"));
            assertEquals("(decl (a (array (array int 3) 2)))", unit("int a[2][3];"));
            assertEquals("(decl (fp (ptr (fn int ((int) (char))))))", unit("int (*fp)(int, char);"));
            assertEquals("(decl (f (fn (ptr int) ())))", unit("int *f(void);"));
            assertEquals("(decl (fa (fn (ptr (array int 4)) ((n int)))))", unit("int (*fa(int n))[4];"));
            assertEquals("(decl (signal (fn (ptr (fn void ((int)))) ((int) ((ptr (fn void ((int)))))))))",
                    unit("void (*signal(int, void (*)(int)))(int);"));
            assertEquals("(decl (t (array (ptr (fn int ())) 2)))", unit("int (*t[2])(void);"));
        }

        @Test
        void parameterLists() {
            assertEquals("(decl (f (fn int ((a int) (b (ptr char))) ...)))", unit("int f(int a, char *b, ...);"));
            assertEquals("(decl (f (fn int ())))", unit("int f(void);"));
            assertEquals("(decl (f (fn int ())))", unit("int f();"));
            assertEquals("(decl (f (fn int () ...)))", unit("int f(...);"));
            assertEquals("(decl (f (fn int (((array static int 4))))))", unit("int f(int [static 4]);"));
            assertEquals("(decl (f (fn int ((a (const (array int)))))))", unit("int f(int a[const]);"));
            assertEquals("(decl (f (fn int (((array int *))))))", unit("int f(int[*]);"));
            assertEquals("(decl (f (fn int ((register n int)))))", unit("int f(register int n);"));
            assertEquals("(decl (f (fn void ((cb (ptr (fn int (((ptr void))))))))))", unit("void f(int (*cb)(void *));"));
        }

        @Test
        void typedefNamesBecomeTypeSpecifiers() {
            assertEquals("(decl typedef (T int))\n(decl (x T) (p (ptr T)))", unit("typedef int T; T x, *p;"));
            assertEquals("(decl typedef (F (fn int ((int)))))\n(decl (g (ptr F)))", unit("typedef int F(int); F *g;"));
            assertEquals("(decl typedef (A (array int 3)))\n(decl (m (array A 2)))", unit("typedef int A[3]; A m[2];"));
        }

        @Test
        void aTypedefNameAfterATypeSpecifierIsTheDeclarator() {
            // 6.7.3.1p2: once a type specifier has been seen, T is redeclared.
            assertEquals("(decl typedef (T int))\n(decl (T unsigned int))", unit("typedef int T; unsigned T;"));
        }

        @Test
        void typedefNamesDistinguishCastsFromParenthesizedExpressions() {
            assertEquals("(decl typedef (T int))\n(decl (x int (cast T y)))", unit("typedef int T; int x = (T)y;"));
            assertEquals("(decl (x int (* a y)))", unit("int x = (a) * y;"));
        }

        @Test
        void structAndUnionSpecifiers() {
            assertEquals("(decl (struct S (a int) (b (ptr char))))", unit("struct S { int a; char *b; };"));
            assertEquals("(decl (struct S (a int : 3) (unsigned int : 2) ((struct (x int)))))",
                    unit("struct S { int a : 3; unsigned : 2; struct { int x; }; };"));
            assertEquals("(decl (p (ptr (struct S))))", unit("struct S *p;"));
            assertEquals("(decl (u (union U (i int) (f float))))", unit("union U { int i; float f; } u;"));
            assertEquals("(decl (v (struct (x int) (y int))))", unit("struct { int x, y; } v;"));
            assertEquals("(decl (struct S (a int) (static_assert 1)))", unit("struct S { int a; static_assert(1); };"));
            assertEquals("(decl (struct N (next (ptr (struct N)))))", unit("struct N { struct N *next; };"));
        }

        @Test
        void enumSpecifiers() {
            assertEquals("(decl (enum E (A) (B 2) (C)))", unit("enum E { A, B = 2, C };"));
            assertEquals("(decl (enum E (A) (B)))", unit("enum E { A, B, };"));
            assertEquals("(decl (enum F : unsigned char (X)))", unit("enum F : unsigned char { X };"));
            assertEquals("(decl (e (enum E)))", unit("enum E e;"));
            assertEquals("(decl (e (enum : long (Z))))", unit("enum : long { Z } e;"));
        }

        @Test
        void enumeratorsAreOrdinaryIdentifiers() {
            // A hides the typedef, so `A * 2` is a multiplication.
            assertEquals("(decl typedef (A int))\n(decl (enum E (A)))\n(decl (y int (* A 2)))",
                    unit("typedef int A; enum E { A }; int y = A * 2;"));
        }

        @Test
        void initializers() {
            assertEquals("(decl (a (array int) {1 2 3}))", unit("int a[] = {1, 2, 3};"));
            assertEquals("(decl (a (array int 2) {1 2}))", unit("int a[2] = {1, 2,};"));
            assertEquals("(decl (m (array (array int 2) 2) {{1 2} {3 4}}))", unit("int m[2][2] = {{1, 2}, {3, 4}};"));
            assertEquals("(decl (p (struct P) {(.x 1) ([2] 3) (.a .b 4) ([0] .c 5)}))",
                    unit("struct P p = {.x = 1, [2] = 3, .a.b = 4, [0].c = 5};"));
            assertEquals("(decl (z (struct P) {}))", unit("struct P z = {};"));
            assertEquals("(decl (n int (?: a b c)))", unit("int n = a ? b : c;"));
        }

        @Test
        void autoRequiresAnInitializer() {
            assertEquals("(decl auto (x _ 1))", unit("auto x = 1;"));
            fails("auto x;");
        }

        @Test
        void typeofAndAlignas() {
            assertEquals("(decl (y (typeof x)))", unit("typeof(x) y;"));
            assertEquals("(decl (z (typeof (type (ptr int)))))", unit("typeof(int *) z;"));
            assertEquals("(decl (w (typeof_unqual (+ a b))))", unit("typeof_unqual(a + b) w;"));
            assertEquals("(decl (alignas 16) (a int))", unit("alignas(16) int a;"));
            assertEquals("(decl (alignas (type double)) (c char))", unit("alignas(double) char c;"));
        }

        @Test
        void staticAssertDeclaration() {
            assertEquals("(static_assert (== (sizeof (type int)) 4) \"int\")",
                    unit("static_assert(sizeof(int) == 4, \"int\");"));
        }

        @Test
        void attributesAreParsedWhereverTheGrammarAllows() {
            assertEquals("(decl (f (fn int ())))", unit("[[nodiscard]] int f(void);"));
            assertEquals("(decl static (y int))", unit("[[maybe_unused]] static int y;"));
            assertEquals("(decl (x int))", unit("int x [[gnu::aligned(8)]];"));
            assertEquals("(decl (p (ptr int)))", unit("int * [[deprecated]] p;"));
            assertEquals("(decl (struct S (a int)))", unit("struct [[packed]] S { int a [[x]]; };"));
            assertEquals("(decl (enum E (A) (B)))", unit("enum E { A [[deprecated(\"old\")]], B };"));
            assertEquals("(decl (a (array int 3)))", unit("int a[3] [[q]];"));
            assertEquals("(decl (f (fn void ((n int)))))", unit("void f([[maybe_unused]] int n);"));
        }

        @Test
        void attributeDeclaration() {
            assertEquals("(attrs [[deprecated(\"x\")]])", unit("[[deprecated(\"x\")]];"));
            assertEquals("(attrs [[a]] [[b::c(1 , ( 2 ))]])", unit("[[a, b::c(1, (2))]];"));
        }

        @Test
        void declarationErrors() {
            fails("x y;");
            fails("int x");
            fails("int (x;");
            fails("int f(a);");
            fails("struct;");
            fails("int a[3;");
            fails("typedef int T; T = 1;");
        }
    }

    // ---- A.3.3 statements (6.8) ----------------------------------------

    @Nested
    class Statements {

        @Test
        void expressionStatementsAndDeclarations() {
            assertEquals("(decl (x int 1)) (expr (= x 2)) (expr)", block("int x = 1; x = 2; ;"));
        }

        @Test
        void ifAndElseChain() {
            assertEquals("(if a (expr b) (if c (expr d) (expr e)))",
                    block("if (a) b; else if (c) d; else e;"));
            assertEquals("(if a (block (expr b)))", block("if (a) { b; }"));
            // The dangling else binds to the nearest if.
            assertEquals("(if a (if b (expr c) (expr d)))", block("if (a) if (b) c; else d;"));
        }

        @Test
        void selectionHeadersWithDeclarations() {
            assertEquals("(if (header (decl (n int (call g)))) (expr (call h n)))",
                    block("if (int n = g()) h(n);"));
            assertEquals("(if (header (decl (n int 1)) (> n 0)) (expr x))",
                    block("if (int n = 1; n > 0) x;"));
            assertEquals("(switch (header (decl (c int (call get)))) (block (case 1) (break)))",
                    block("switch (int c = get()) { case 1: break; }"));
        }

        @Test
        void switchWithCaseRangesAndDefault() {
            assertEquals("(switch x (block (case 1) (case 2 4) (expr y) (break) (default) (expr z)))",
                    block("switch (x) { case 1: case 2 ... 4: y; break; default: z; }"));
        }

        @Test
        void loops() {
            assertEquals("(while (< i n) (expr (post++ i)))", block("while (i < n) i++;"));
            assertEquals("(do (expr x) y)", block("do x; while (y);"));
            assertEquals("(do (block (expr x)) y)", block("do { x; } while (y);"));
            assertEquals("(for (decl (i int 0)) (< i n) (post++ i) (expr (+= s i)))",
                    block("for (int i = 0; i < n; i++) s += i;"));
            assertEquals("(for _ _ _ (break))", block("for (;;) break;"));
            assertEquals("(for (= i 0) (< i n) _ (expr))", block("for (i = 0; i < n;) ;"));
            assertEquals("(for (decl (i int 0) (j int 9)) _ (, (post++ i) (post-- j)) (block))",
                    block("for (int i = 0, j = 9;; i++, j--) {}"));
        }

        @Test
        void labelsAndJumps() {
            assertEquals("(label L) (expr x) (goto L)", block("L: x; goto L;"));
            assertEquals("(if c (label L2 (expr y)))", block("if (c) L2: y;"));
            assertEquals("(label outer) (while 1 (block (break outer) (continue outer)))",
                    block("outer: while (1) { break outer; continue outer; }"));
            assertEquals("(return)", block("return;"));
            assertEquals("(return (+ x 1))", block("return x + 1;"));
            // In a block a label is its own block-item (6.8.3), not a prefix of the statement.
            assertEquals("(label end) (return)", block("end: return;"));
        }

        @Test
        void nestedBlocksAndScopes() {
            assertEquals("(block (block))", block("{ { } }"));
            // The inner `int T;` hides the typedef only inside its block.
            assertEquals("(decl typedef (T int)) (block (decl (T int)) (expr (* T x))) (decl (y (ptr T)))",
                    block("typedef int T; { int T; T * x; } T *y;"));
        }

        @Test
        void typedefNamesDistinguishDeclarationsFromExpressions() {
            assertEquals("(decl typedef (T int)) (decl (p (ptr T))) (expr (* a b))",
                    block("typedef int T; T *p; a * b;"));
            // T followed by ':' is a label, not a declaration.
            assertEquals("(decl typedef (T int)) (label T) (expr x)", block("typedef int T; T: x;"));
        }

        @Test
        void attributesAndStaticAssertsInBlocks() {
            assertEquals("(attrs [[fallthrough]])", block("[[fallthrough]];"));
            assertEquals("(static_assert 1)", block("static_assert(1);"));
            assertEquals("(expr (call f))", block("[[likely]] f();"));
        }

        @Test
        void statementErrors() {
            for (String s : List.of("return 0", "if a b;", "do x; while (y)", "case 1 ;",
                    "for (int i = 0, i < 3;) ;", "if (int x = 1, y = 2) ;", "{ x;", "x; }")) {
                assertThrows(ParseException.class, () -> block(s), s);
            }
        }
    }

    // ---- A.3.4 external definitions (6.9) ------------------------------

    @Nested
    class ExternalDefinitions {

        @Test
        void functionDefinition() {
            assertEquals("(fundef main (fn int ()) (block (return 0)))", unit("int main(void) { return 0; }"));
            assertEquals("(fundef static inline f (fn (ptr char) ((s (ptr (const char))) (n int))) (block (return s)))",
                    unit("static inline char *f(const char *s, int n) { return s; }"));
        }

        @Test
        void declarationThenDefinition() {
            assertEquals("(decl (f (fn int ((int)))))\n(fundef f (fn int ((x int))) (block (return x)))",
                    unit("int f(int); int f(int x) { return x; }"));
        }

        @Test
        void parametersAreInScopeInTheBodyAndHideTypedefs() {
            assertEquals("(decl typedef (T int))\n(fundef f (fn void ((T int))) (block (expr (= T 1))))",
                    unit("typedef int T; void f(int T) { T = 1; }"));
            // ...but only inside that function.
            assertEquals("(decl typedef (T int))\n(fundef f (fn void ((T int))) (block))\n(decl (x T))",
                    unit("typedef int T; void f(int T) {} T x;"));
        }

        @Test
        void fileScopeTypedefsAreVisibleInBodies() {
            assertEquals("(decl typedef (T int))\n(fundef f (fn void ()) (block (decl (p (ptr T))) (expr (* a b))))",
                    unit("typedef int T; void f(void) { T *p; a * b; }"));
        }

        @Test
        void mixedTranslationUnit() {
            assertEquals(String.join("\n",
                            "(decl (values (array int 8)))",
                            "(decl typedef (P (ptr (struct S))))",
                            "(fundef get (fn P ()) (block (return nullptr)))",
                            "(decl (struct S (n int)))"),
                    unit("int values[8]; typedef struct S *P; P get(void) { return nullptr; } struct S { int n; };"));
        }

        @Test
        void externalDefinitionErrors() {
            fails("int x = 1 { }");
            fails("int f(void) { } }");
            fails("int f(void) { return 0; ");
            fails("int (*p)(void) { }");
        }
    }

    // ---- end to end ----------------------------------------------------

    @Test
    void mainDriverProgramParses() {
        List<Decl> decls = Parser.parse(preprocess(org.jbm.Main.SOURCE));
        assertEquals(4, decls.size());
        assertInstanceOf(Decl.Declaration.class, decls.get(0));
        assertInstanceOf(Decl.Declaration.class, decls.get(1));
        assertInstanceOf(Decl.FunctionDefinition.class, decls.get(2));
        assertInstanceOf(Decl.FunctionDefinition.class, decls.get(3));

        String printed = AstPrinter.print(decls);
        assertTrue(printed.startsWith("(decl (values (array int 8)))\n"), printed);
        assertTrue(printed.contains("(decl (size_str (ptr (const char)) \"8\"))"), printed);
        assertTrue(printed.contains("(fundef get_count (fn int ()) (block (return 8)))"), printed);
        assertTrue(printed.contains("(while (< i (call get_count)) (block (expr (= ([] values i) (* i i)))"), printed);
        assertTrue(printed.contains("(expr (= total (?: (> total ([] values i)) total ([] values i))))"), printed);
    }
}
