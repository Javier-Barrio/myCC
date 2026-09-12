package org.jbm.mycc.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.cpp.Scanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code #if} and its family, executed by the tokenizer: what survives, what is never seen, and the errors. */
class ConditionalDirectivesTest {

    private static List<String> expand(String source) {
        CppTokenizer.TokenSet set = new Scanner().expand(CppTokenizer.tokenSet(source));
        return set.tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.text)
                .toList();
    }

    private static void assertExpandsTo(String source, String... expected) {
        assertEquals(List.of(expected), expand(source));
    }

    private static String fails(String source) {
        return assertThrows(LexException.class, () -> expand(source)).getMessage();
    }

    @Test
    void ifKeepsOrDropsAGroup() {
        assertExpandsTo("#if 1\nkept\n#endif\n", "kept");
        assertExpandsTo("#if 0\ndropped\n#endif\nafter", "after");
        assertExpandsTo("before\n#if 2 > 1\nkept\n#endif\nafter", "before", "kept", "after");
    }

    @Test
    void ifdefAndIfndef() {
        assertExpandsTo("#define X\n#ifdef X\nyes\n#endif\n#ifdef Y\nno\n#endif", "yes");
        assertExpandsTo("#define X\n#ifndef X\nno\n#endif\n#ifndef Y\nyes\n#endif", "yes");
        assertExpandsTo("#define X\n#undef X\n#ifdef X\nno\n#endif\ndone", "done");
    }

    @Test
    void elseAndElifTakeTheFirstTrueBranchOnly() {
        assertExpandsTo("#if 0\na\n#else\nb\n#endif", "b");
        assertExpandsTo("#if 1\na\n#else\nb\n#endif", "a");
        assertExpandsTo("#if 0\na\n#elif 0\nb\n#elif 1\nc\n#elif 1\nd\n#else\ne\n#endif", "c");
        assertExpandsTo("#if 1\na\n#elif 1\nb\n#else\nc\n#endif", "a");
        assertExpandsTo("#define X\n#if 0\na\n#elifdef X\nb\n#endif", "b");
        assertExpandsTo("#if 0\na\n#elifndef X\nb\n#else\nc\n#endif", "b");
    }

    @Test
    void nestedGroups() {
        assertExpandsTo("#if 1\n#if 0\na\n#else\nb\n#endif\n#endif", "b");
        assertExpandsTo("#if 0\n#if 1\na\n#else\nb\n#endif\n#else\nc\n#endif", "c");
        assertExpandsTo("#if 0\n#ifdef X\n#elif 1\n#else\n#endif\n#endif\nd", "d");
    }

    @Test
    void conditionsAreMacroExpanded() {
        assertExpandsTo("#define A 2\n#if A * 2 == 4\nyes\n#endif", "yes");
        assertExpandsTo("#define SQ(x) ((x) * (x))\n#if SQ(3) == 9\nyes\n#endif", "yes");
        assertExpandsTo("#define A B\n#define B 1\n#if A\nyes\n#endif", "yes");
        assertExpandsTo("#if UNDEFINED\nno\n#else\nyes\n#endif", "yes");
        assertExpandsTo("#if UNDEFINED + 1\nyes\n#endif", "yes");
        assertExpandsTo("#if true\nyes\n#endif\n#if false\nno\n#endif", "yes");
    }

    @Test
    void definedOperator() {
        assertExpandsTo("#define X 0\n#if defined X\nyes\n#endif", "yes");
        assertExpandsTo("#define X 0\n#if defined(X) && !defined(Y)\nyes\n#endif", "yes");
        assertExpandsTo("#if defined X\nno\n#else\nyes\n#endif", "yes");
        assertExpandsTo("#define F(a) a\n#if defined F && defined(F)\nyes\n#endif", "yes");
    }

    @Test
    void skippedGroupsAreNeitherExecutedNorEvaluated() {
        assertExpandsTo("#if 0\n#define X 1\n#endif\nX", "X");
        assertExpandsTo("#define X 1\n#if 0\n#undef X\n#endif\nX", "1");
        assertExpandsTo("#if 1\na\n#elif 1 / 0\nb\n#endif", "a");
        assertExpandsTo("#if 0\n#if 1 / 0\n#endif\n#endif\nd", "d");
        assertExpandsTo("#if 0\ndon't lex this \" \n#endif\nd", "d");
        assertExpandsTo("#if 0\n/* a comment with\n#endif\ninside */\n#endif\nd", "d");
    }

    @Test
    void headerGuardIdiom() {
        assertExpandsTo("""
                #ifndef GUARD
                #define GUARD
                int a;
                #endif
                #ifndef GUARD
                #define GUARD
                int b;
                #endif
                """, "int", "a", ";");
    }

    @Test
    void directiveLinesEmitNothingAndKeepLineNumbers() {
        CppTokenizer.TokenSet set = new Scanner().expand(CppTokenizer.tokenSet("#if 1 // c\nx\n#endif\ny"));
        assertEquals(2, set.tokens.get(0).token.line);
        assertEquals(4, set.tokens.get(1).token.line);
    }

    @Test
    void lineAndFileMacros() {
        assertExpandsTo("__LINE__\n\n__LINE__", "1", "3");
        assertExpandsTo("#line 100\n__LINE__\n__LINE__", "100", "101");
        assertExpandsTo("#define N 7\n#line N\n__LINE__", "7");
        assertExpandsTo("#line 5 \"other.c\"\n__FILE__ __LINE__", "\"other.c\"", "5");
        assertExpandsTo("__FILE__", "\"<source>\"");
        assertExpandsTo("#line 20\n#if __LINE__ == 20\nyes\n#endif", "yes");
        assertTrue(fails("#line x\n").contains("#line needs a line number"));
    }

    @Test
    void errorDirective() {
        assertTrue(fails("#error this build is wrong\n").contains("#error this build is wrong"));
        assertExpandsTo("#if 0\n#error not here\n#endif\nok", "ok");
        assertTrue(fails("#if 1\n#error yes\n#endif").contains("#error yes"));
    }

    @Test
    void aCommentMayRunPastTheEndOfADirectiveLine() {
        assertExpandsTo("#define SA 4 /* what it\n   does */\nSA", "4");
        assertExpandsTo("#define F(x) (x /* the\n   argument */ + 1)\nF(2)", "(", "2", "+", "1", ")");
        assertExpandsTo("#define G(x /* the\n   parameter */) x\nG(3)", "3");
        assertExpandsTo("#if 1 /* yes\n   */\nok\n#endif\n#undef SA /* gone\n   */\nSA", "ok", "SA");
        assertExpandsTo("#define S \"/*\" /* a\n   comment */\nS", "\"/*\"");
    }

    @Test
    void aNameIsAMacroOnlyAfterItsDefinition() {
        assertExpandsTo("X\n#define X 1\nX", "X", "1");
        assertExpandsTo("enum { A =\n#define A 0\n A, B =\n#define B 1\n B };", "enum", "{", "A", "=", "0", ",", "B", "=", "1", "}", ";");
        assertExpandsTo("#define P Q\n#define Q 2\nP", "2");
        assertExpandsTo("#define F(x) x + G\nF(1)\n#define G 3\nF(1)", "1", "+", "G", "1", "+", "3");
        assertExpandsTo("#define X 1\n#undef X\nX\n#define X 2\nX", "X", "2");
        assertExpandsTo("#define M(x) T x\n#define T double\nM(a);\n#undef T\n#define T float\nM(b);\n#undef M\nM(c)",
                "double", "a", ";", "float", "b", ";", "M", "(", "c", ")");
    }

    @Test
    void literalsInSkippedGroupsAndMacroArgumentsAreNotComments() {
        assertExpandsTo("#if 0\nconst char *a = \"Accept: */*\";\n#endif\n#if 0\nint b = '/' + '*';\n#endif\nok", "ok");
        assertExpandsTo("#if 0\n// a comment with /* in it\n#endif\nok", "ok");
        assertExpandsTo("#define F(x) x\nF(\")\")", "\")\"");
        assertExpandsTo("#define G(a, b) a b\nG(\", not IAC SE) \", ')')", "\", not IAC SE) \"", "')'");
        assertExpandsTo("#define H(x) x\nH((1 /* ) */ + 2))", "(", "1", "+", "2", ")");
    }

    @Test
    void tokensReportTheirPhysicalLine() {
        CppTokenizer.TokenSet set = new Scanner().expand(CppTokenizer.tokenSet("#define A \\\n  1\n#define B \\\n\\\n 2\nint x = A + B;\nint y;"));
        var physical = set.tokens.stream().filter(t -> t.token.type != TokenType.EOF && (t.token.text.equals("x") || t.token.text.equals("y"))).map(t -> t.token.physicalLine).toList();
        assertEquals(List.of(6, 7), physical, "a continuation is deleted, but the physical line survives for diagnostics");
        var logical = set.tokens.stream().filter(t -> t.token.type != TokenType.EOF && (t.token.text.equals("x") || t.token.text.equals("y"))).map(t -> t.token.line).toList();
        assertEquals(List.of(3, 4), logical, "the logical line is what the stream is organized by");
    }

    @Test
    void pushAndPopMacro() {
        assertExpandsTo("#define A 1\n#pragma push_macro(\"A\")\n#undef A\n#define A 2\nA\n#pragma pop_macro(\"A\")\nA", "2", "1");
        assertExpandsTo("#pragma push_macro(\"A\")\n#define A 2\nA\n#pragma pop_macro(\"A\")\nA", "2", "A");
        assertExpandsTo("#define A 1\n#pragma push_macro(\"A\")\n#pragma push_macro(\"A\")\n#undef A\n#define A 3\n"
                + "#pragma pop_macro(\"A\")\nA\n#pragma pop_macro(\"A\")\nA\n#pragma pop_macro(\"A\")\nA", "1", "1", "1");
        assertExpandsTo("#define push_macro x\n#define A 1\n#pragma push_macro(\"A\")\n#undef A\n#pragma pop_macro(\"A\")\nA",
                "1");
        assertExpandsTo("#pragma once\nok", "ok");
    }

    @Test
    void errors() {
        assertTrue(fails("#if 1\n#else\n#else\n#endif").contains("#else after #else"));
        assertTrue(fails("#if 1\n#else\n#elif 1\n#endif").contains("#elif after #else"));
        assertTrue(fails("#endif").contains("#endif without #if"));
        assertTrue(fails("#else").contains("#else without #if"));
        assertTrue(fails("#elif 1").contains("#elif without #if"));
        assertTrue(fails("#if 1\nx").contains("unterminated #if"));
        assertTrue(fails("#if 0\nx").contains("unterminated #if"));
        assertTrue(fails("#if 1 / 0\n#endif").contains("division by zero"));
        assertTrue(fails("#if\n#endif").contains("no expression"));
        assertTrue(fails("#if 1.5\n#endif").contains("not an integer constant"));
        assertTrue(fails("#ifdef\n#endif").contains("expected a macro name"));
        assertTrue(fails("#ifdef X Y\n#endif").contains("expected a macro name"));
        assertTrue(fails("#if defined\n#endif").contains("expected an identifier after 'defined'"));
        assertTrue(fails("#if defined(X\n#endif").contains("expected ')'"));
    }
}
