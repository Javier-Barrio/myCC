package org.jbm.cc.cpp;

import org.jbm.cc.cpp.CppTokenizer.LexException;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
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
