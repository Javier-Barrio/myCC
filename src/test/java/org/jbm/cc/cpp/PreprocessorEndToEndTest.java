package org.jbm.cc.cpp;

import org.jbm.cc.CppTokenizer;
import org.jbm.cc.CppTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end tests that feed whole (small) C/C++ source snippets through
 * the tokenizer and Scanner.expand(), and check the resulting, fully
 * preprocessed token set - as opposed to ScannerTest, which exercises
 * expand/substitute/glue/hsAdd individually. Several of these mirror
 * idioms real headers use (version stringification, MAX/MIN, header-guard
 * style redefinition) rather than minimal isolated cases.
 */
class PreprocessorEndToEndTest {

    private static CppTokenizer.TokenSet expand(String source) {
        return new Scanner().expand(CppTokenizer.tokenSet(source));
    }

    private static List<String> texts(CppTokenizer.TokenSet set) {
        return set.tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.text)
                .toList();
    }

    private static void assertExpandsTo(String source, String... expected) {
        assertEquals(List.of(expected), texts(expand(source)));
    }

    @Test
    void objectMacrosCombineInAnArithmeticExpression() {
        assertExpandsTo("""
                #define WIDTH 80
                #define HEIGHT 24
                #define AREA WIDTH * HEIGHT

                int area = AREA;
                """,
                "int", "area", "=", "80", "*", "24", ";");
    }

    @Test
    void functionLikeMacroExpandsInAnAssignment() {
        assertExpandsTo("""
                #define MAX(a, b) ((a) > (b) ? (a) : (b))

                int best = MAX(3, 7);
                """,
                "int", "best", "=",
                "(", "(", "3", ")", ">", "(", "7", ")", "?", "(", "3", ")", ":", "(", "7", ")", ")",
                ";");
    }

    @Test
    void stringizeAndPasteSideBySide() {
        assertExpandsTo("""
                #define STRINGIFY(x) #x
                #define CONCAT(a, b) a##b
                #define GREETING hello

                const char* msg = STRINGIFY(GREETING);
                int CONCATenated = CONCAT(foo, bar);
                """,
                "const", "char", "*", "msg", "=", "\"GREETING\"", ";",
                "int", "CONCATenated", "=", "foobar", ";");
    }

    @Test
    void selfReferentialMacroPairDoesNotLoopForever() {
        assertExpandsTo("""
                #define x (4 + y)
                #define y (2 * x)

                int result = x;
                """,
                "int", "result", "=", "(", "4", "+", "(", "2", "*", "x", ")", ")", ";");
    }

    @Test
    void undefAndRedefineChangeSubsequentExpansions() {
        assertExpandsTo("""
                #define VALUE 1
                int a = VALUE;
                #undef VALUE
                #define VALUE 2
                int b = VALUE;
                """,
                "int", "a", "=", "1", ";",
                "int", "b", "=", "2", ";");
    }

    @Test
    void pasteCanProduceANewMacroNameThatIsThenRescanned() {
        assertExpandsTo("""
                #define AB 99
                #define CAT(a, b) a ## b

                int value = CAT(A, B);
                """,
                "int", "value", "=", "99", ";");
    }

    @Test
    void versionStringificationIdiomAcrossThreeStatements() {
        // Combines chained object macros, a function-like macro, and the
        // classic two-level STR/XSTR indirection idiom real headers use to
        // force an argument's macro expansion before stringizing it.
        assertExpandsTo("""
                #define VERSION_MAJOR 1
                #define VERSION_MINOR 2
                #define VERSION (VERSION_MAJOR * 100 + VERSION_MINOR)
                #define SQUARE(x) ((x) * (x))
                #define STR(x) #x
                #define XSTR(x) STR(x)

                int version = VERSION;
                int nine = SQUARE(3);
                const char* version_str = XSTR(VERSION);
                """,
                "int", "version", "=", "(", "1", "*", "100", "+", "2", ")", ";",
                "int", "nine", "=", "(", "(", "3", ")", "*", "(", "3", ")", ")", ";",
                "const", "char", "*", "version_str", "=", "\"(1*100+2)\"", ";");
    }

    // For long expected expansions: compare against the token spellings of
    // an equivalent, already-preprocessed source string.
    private static void assertExpandsLike(String source, String expectedSource) {
        var expected = CppTokenizer.tokenize(expectedSource).stream()
                .filter(t -> t.type != TokenType.EOF)
                .map(t -> t.text)
                .toList();
        assertEquals(expected, texts(expand(source)));
    }

    @Test
    void clampBuiltFromNestedMinAndMax() {
        assertExpandsLike("""
                #define MIN(a, b) ((a) < (b) ? (a) : (b))
                #define MAX(a, b) ((a) > (b) ? (a) : (b))
                #define CLAMP(x, lo, hi) MIN(MAX(x, lo), hi)

                int v = CLAMP(5, 0, 10);
                """,
                "int v = ((((5) > (0) ? (5) : (0))) < (10) ? (((5) > (0) ? (5) : (0))) : (10));");
    }

    @Test
    void pasteBuildsFunctionNamesIdiom() {
        assertExpandsTo("""
                #define HANDLER(name) handle_##name
                void HANDLER(click)(int x);
                """,
                "void", "handle_click", "(", "int", "x", ")", ";");
    }

    @Test
    void stringizeAppendsToAPrecedingStringLiteral() {
        assertExpandsTo("""
                #define ASSERT_MSG(cond) "check: " #cond
                const char* m = ASSERT_MSG(x > 0);
                """,
                "const", "char", "*", "m", "=", "\"check: \"", "\"x>0\"", ";");
    }

    @Test
    void objectMacroBodyIsAFunctionLikeInvocation() {
        // CALL's body references ADD before ADD is even defined; the
        // invocation only has to resolve at expansion (rescanning) time.
        assertExpandsTo("""
                #define CALL ADD(1, 2)
                #define ADD(a, b) a + b

                int r = CALL;
                """,
                "int", "r", "=", "1", "+", "2", ";");
    }

    @Test
    void invocationArgumentsMaySpanMultipleLines() {
        assertExpandsTo("""
                #define ADD(a, b) a + b
                int r = ADD(1,
                            2);
                """,
                "int", "r", "=", "1", "+", "2", ";");
    }

    @Test
    void nestedInvocationResultIsStringizedThroughXstr() {
        assertExpandsTo("""
                #define STR(x) #x
                #define XSTR(x) STR(x)
                #define CAT(a, b) a##b
                const char* s = XSTR(CAT(foo, bar));
                """,
                "const", "char", "*", "s", "=", "\"foobar\"", ";");
    }

    @Test
    void objectMacroInsideAnArrayDeclaration() {
        assertExpandsTo("""
                #define SIZE 10
                int buf[SIZE];
                """,
                "int", "buf", "[", "10", "]", ";");
    }

    @Test
    void macroUsesInterleaveAcrossOneLine() {
        assertExpandsTo("""
                #define A 1
                #define B 2
                A B A B
                """,
                "1", "2", "1", "2");
    }

    @Test
    void undefWithoutRedefineLeavesLaterUsesAlone() {
        assertExpandsTo("""
                #define A 1
                int x = A;
                #undef A
                int y = A;
                """,
                "int", "x", "=", "1", ";",
                "int", "y", "=", "A", ";");
    }

    @Test
    void emptyPasteArgumentsVanish() {
        assertExpandsTo("""
                #define CAT(a, b) a##b
                int CAT(, x) = CAT(x, );
                """,
                "int", "x", "=", "x", ";");
    }

    @Test
    void commaInsideAStringLiteralArgumentIsNotASeparator() {
        assertExpandsTo("""
                #define FST(a, b) a
                const char* s = FST("x,y", 2);
                """,
                "const", "char", "*", "s", "=", "\"x,y\"", ";");
    }

    @Test
    void stringizeConcatenatesArgumentTokensWithoutWhitespace() {
        assertExpandsTo("""
                #define STR(x) #x
                const char* s = STR(1 + 2);
                """,
                "const", "char", "*", "s", "=", "\"1+2\"", ";");
    }

    @Test
    void stringizeOfAStringLiteralEscapesItsQuotes() {
        assertExpandsTo("""
                #define STR(x) #x
                const char* s = STR("hi");
                """,
                "const", "char", "*", "s", "=", "\"\\\"hi\\\"\"", ";");
    }

    @Test
    void argumentsAreExpandedThroughMacroChainsBeforeSubstitution() {
        assertExpandsTo("""
                #define A B
                #define B 3
                #define TWICE(x) x x

                int r = TWICE(A);
                """,
                "int", "r", "=", "3", "3", ";");
    }

    @Test
    void functionDefinitionSnippetWithMacrosInSignatureAndBody() {
        assertExpandsTo("""
                #define RET int
                #define OK 0
                RET main() { return OK; }
                """,
                "int", "main", "(", ")", "{", "return", "0", ";", "}");
    }
}
