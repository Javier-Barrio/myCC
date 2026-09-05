package org.jbm.cc.parse;

import org.jbm.cc.ast.AstPrinter;
import org.jbm.cc.cpp.CppTokenizer;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.Scanner;
import org.jbm.cc.cpp.TokenConversion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
