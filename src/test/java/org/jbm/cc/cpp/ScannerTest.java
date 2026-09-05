package org.jbm.cc.cpp;

import org.jbm.cc.CppTokenizer;
import org.jbm.cc.CppTokenizer.Token;
import org.jbm.cc.CppTokenizer.TokenSet;
import org.jbm.cc.CppTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests check Scanner's macro-expansion machinery against the
 * expand/subst/glue/hsadd algorithm described in Dave Prosser's C
 * preprocessing algorithm, as reproduced in
 * https://www.spinellis.gr/blog/20060626/cpp.algo.pdf. Each test states the
 * behavior the algorithm calls for; some currently fail, which is expected
 * for a work-in-progress implementation and pinpoints what's still missing.
 */
class ScannerTest {

    private final Scanner scanner = new Scanner();

    private static CppToken tok(TokenType type, String text) {
        return new CppToken(new Token(type, text, 1, 1));
    }

    private static TokenSet setOf(CppToken... tokens) {
        return new TokenSet(new ArrayList<>(List.of(tokens)));
    }

    private static List<String> texts(TokenSet set) {
        return set.tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.text)
                .toList();
    }

    private static TokenSet expand(String source) {
        return new Scanner().expand(CppTokenizer.tokenSet(source));
    }

    private static void assertExpandsTo(String source, String... expected) {
        assertEquals(List.of(expected), texts(expand(source)));
    }

    // ---- glue() -------------------------------------------------------
    // glue(LS, RS) pastes the last token of LS with the first token of RS
    // into a single token, keeping everything else in place around it.

    @Test
    void glueConcatenatesLastOfLhsWithFirstOfRhs() {
        var lhs = setOf(tok(TokenType.IDENTIFIER, "foo"));
        var rhs = setOf(tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.glue(lhs, rhs);

        assertEquals(List.of("foobar"), texts(result));
    }

    @Test
    void glueKeepsSurroundingTokensAroundThePastedBoundary() {
        // a, b • ## • c, d  ->  a, bc, d
        var lhs = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.IDENTIFIER, "b"));
        var rhs = setOf(tok(TokenType.IDENTIFIER, "c"), tok(TokenType.IDENTIFIER, "d"));

        var result = scanner.glue(lhs, rhs);

        assertEquals(List.of("a", "bc", "d"), texts(result));
    }

    @Test
    void gluePastesIntegerLiteralsIntoAnIntegerToken() {
        var result = scanner.glue(setOf(tok(TokenType.INTEGER_LITERAL, "1")),
                setOf(tok(TokenType.INTEGER_LITERAL, "2")));

        assertEquals(List.of("12"), texts(result));
        assertEquals(TokenType.INTEGER_LITERAL, result.tokens.get(0).token.type);
    }

    @Test
    void gluePastedIdentifiersFormAnIdentifierToken() {
        var result = scanner.glue(setOf(tok(TokenType.IDENTIFIER, "foo")),
                setOf(tok(TokenType.IDENTIFIER, "bar")));

        assertEquals(TokenType.IDENTIFIER, result.tokens.get(0).token.type);
    }

    @Test
    void glueWithEmptyLhsReturnsRhsUnchanged() {
        var result = scanner.glue(TokenSet.empty(),
                setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.IDENTIFIER, "b")));

        assertEquals(List.of("a", "b"), texts(result));
    }

    @Test
    void glueWithEmptyRhsReturnsLhsUnchanged() {
        var result = scanner.glue(setOf(tok(TokenType.IDENTIFIER, "a")), TokenSet.empty());

        assertEquals(List.of("a"), texts(result));
    }

    @Test
    void glueOnlyPastesAtTheBoundary() {
        var result = scanner.glue(
                setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.IDENTIFIER, "b")),
                setOf(tok(TokenType.IDENTIFIER, "c")));

        assertEquals(List.of("a", "bc"), texts(result));
    }

    @Test
    void glueKeepsTheRhsTailAfterThePastedToken() {
        var result = scanner.glue(
                setOf(tok(TokenType.IDENTIFIER, "a")),
                setOf(tok(TokenType.IDENTIFIER, "b"), tok(TokenType.IDENTIFIER, "c")));

        assertEquals(List.of("ab", "c"), texts(result));
    }

    @Test
    void gluePastedTokenHideSetIsTheIntersectionOfBothOperandsByName() {
        var l = tok(TokenType.IDENTIFIER, "foo");
        l.hideSet.add(new Token(TokenType.IDENTIFIER, "A", 1, 1));
        l.hideSet.add(new Token(TokenType.IDENTIFIER, "B", 1, 1));
        var r = tok(TokenType.IDENTIFIER, "bar");
        // Same name from a different source position must still intersect.
        r.hideSet.add(new Token(TokenType.IDENTIFIER, "B", 9, 9));
        r.hideSet.add(new Token(TokenType.IDENTIFIER, "C", 9, 9));

        var result = scanner.glue(setOf(l), setOf(r));

        assertEquals(List.of("B"),
                result.tokens.get(0).hideSet.stream().map(t -> t.text).toList());
    }

    // ---- hsAdd() --------------------------------------------------------
    // hsadd(HS, TS) unions HS into every token's hide set, without changing
    // TS's own tokens, order, or length.

    @Test
    void hsAddUnionsHideSetIntoEveryTokenWithoutChangingOrder() {
        var guard = new Token(TokenType.IDENTIFIER, "GUARD", 1, 1);
        var hs = setOf(new CppToken(guard));
        var ts = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.hsAdd(hs, ts);

        assertEquals(List.of("a", "b"), texts(result));
        assertTrue(result.tokens.get(0).hideSet.contains(guard));
        assertTrue(result.tokens.get(1).hideSet.contains(guard));
    }

    @Test
    void hsAddOfAnEmptyHideSetLeavesTokensUnchanged() {
        var ts = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.hsAdd(TokenSet.empty(), ts);

        assertEquals(List.of("a", "b"), texts(result));
    }

    @Test
    void hsAddOnAnEmptyTokenSetProducesAnEmptySet() {
        var hs = setOf(tok(TokenType.IDENTIFIER, "GUARD"));

        assertEquals(List.of(), texts(scanner.hsAdd(hs, TokenSet.empty())));
    }

    @Test
    void hsAddAccumulatesOntoExistingHideSets() {
        var existing = new Token(TokenType.IDENTIFIER, "OLD", 1, 1);
        var t = tok(TokenType.IDENTIFIER, "a");
        t.hideSet.add(existing);
        var guard = new Token(TokenType.IDENTIFIER, "NEW", 1, 1);

        var result = scanner.hsAdd(setOf(new CppToken(guard)), setOf(t));

        assertTrue(result.tokens.get(0).hideSet.contains(existing));
        assertTrue(result.tokens.get(0).hideSet.contains(guard));
    }

    @Test
    void hsAddAddsEveryTokenOfTheHideSet() {
        var g1 = new Token(TokenType.IDENTIFIER, "G1", 1, 1);
        var g2 = new Token(TokenType.IDENTIFIER, "G2", 1, 1);

        var result = scanner.hsAdd(setOf(new CppToken(g1), new CppToken(g2)),
                setOf(tok(TokenType.IDENTIFIER, "a")));

        assertTrue(result.tokens.get(0).hideSet.contains(g1));
        assertTrue(result.tokens.get(0).hideSet.contains(g2));
    }

    @Test
    void hsAddDoesNotMutateTheInputTokenSet() {
        var ts = setOf(tok(TokenType.IDENTIFIER, "a"));

        scanner.hsAdd(setOf(tok(TokenType.IDENTIFIER, "GUARD")), ts);

        assertTrue(ts.tokens.get(0).hideSet.isEmpty());
    }

    // ---- stringize() ----------------------------------------------------
    // The '#' operator joins its operand's tokens into a single string
    // literal, quoted.

    @Test
    void stringizeWrapsConcatenatedTextInQuotes() {
        var result = scanner.stringize(setOf(tok(TokenType.IDENTIFIER, "foo")));

        assertEquals(TokenType.STRING_LITERAL, result.token.type);
        assertEquals("\"foo\"", result.token.text);
    }

    @Test
    void stringizeOfMultipleTokensConcatenatesThemAll() {
        var set = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.stringize(set);

        assertEquals(TokenType.STRING_LITERAL, result.token.type);
        assertEquals("\"foo+bar\"", result.token.text);
    }

    @Test
    void stringizeOfAnEmptySetIsAnEmptyStringLiteral() {
        var result = scanner.stringize(TokenSet.empty());

        assertEquals(TokenType.STRING_LITERAL, result.token.type);
        assertEquals("\"\"", result.token.text);
    }

    @Test
    void stringizeEscapesQuotesInsideAStringLiteralArgument() {
        var result = scanner.stringize(setOf(tok(TokenType.STRING_LITERAL, "\"hi\"")));

        assertEquals("\"\\\"hi\\\"\"", result.token.text);
    }

    @Test
    void stringizeEscapesBackslashesInsideAStringLiteralArgument() {
        var result = scanner.stringize(setOf(tok(TokenType.STRING_LITERAL, "\"a\\nb\"")));

        assertEquals("\"\\\"a\\\\nb\\\"\"", result.token.text);
    }

    @Test
    void stringizeOfACharacterLiteralKeepsItsSingleQuotes() {
        var result = scanner.stringize(setOf(tok(TokenType.CHARACTER_LITERAL, "'a'")));

        assertEquals("\"'a'\"", result.token.text);
    }

    @Test
    void stringizeOfAParenthesizedExpression() {
        var result = scanner.stringize(setOf(
                tok(TokenType.PUNCTUATOR, "("), tok(TokenType.INTEGER_LITERAL, "1"),
                tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.INTEGER_LITERAL, "2"),
                tok(TokenType.PUNCTUATOR, ")")));

        assertEquals("\"(1+2)\"", result.token.text);
    }

    // ---- substitute() -----------------------------------------------
    // subst(IS, FP, AP, HS, OS) walks the replacement list IS, substituting
    // formal parameters with their actual arguments (applying # and ##
    // where they appear), and copying every other token straight through.

    @Test
    void substituteWithNoParamsCopiesTokensThrough() {
        var inSet = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.substitute(inSet, new ArrayList<>(), new ArrayList<>(), TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foo", "+", "bar"), texts(result));
    }

    @Test
    void substituteReplacesPlainParameterOccurrencesWithArguments() {
        // #define ADD(a, b) a + b  =>  ADD(1, 2) substitutes a->1, b->2.
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(
                setOf(tok(TokenType.INTEGER_LITERAL, "1")),
                setOf(tok(TokenType.INTEGER_LITERAL, "2"))
        ));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("1", "+", "2"), texts(result));
    }

    @Test
    void substituteLeavesNonParameterIdentifiersUntouched() {
        var inSet = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.substitute(inSet, new ArrayList<>(), new ArrayList<>(), TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foo", "+", "bar"), texts(result));
    }

    @Test
    void substituteStringizesAParameterOccurrence() {
        // #define STR(x) #x  =>  STR(foo) => "foo"
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(tok(TokenType.IDENTIFIER, "foo"))));
        var inSet = setOf(tok(TokenType.STRINGIZE, "#"), tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(1, result.tokens.size());
        assertEquals(TokenType.STRING_LITERAL, result.tokens.get(0).token.type);
        assertEquals("\"foo\"", result.tokens.get(0).token.text);
    }

    @Test
    void substitutePastesTwoParameterOccurrencesTogether() {
        // #define CAT(a, b) a ## b  =>  CAT(foo, bar) => foobar
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(
                setOf(tok(TokenType.IDENTIFIER, "foo")),
                setOf(tok(TokenType.IDENTIFIER, "bar"))
        ));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foobar"), texts(result));
    }

    @Test
    void substitutePastesAParameterWithATrailingLiteralToken() {
        // #define FOO(x) x ## bar  =>  FOO(baz) => bazbar
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(tok(TokenType.IDENTIFIER, "baz"))));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "x"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("bazbar"), texts(result));
    }

    @Test
    void substitutePastesALiteralTokenWithATrailingParameter() {
        // #define FOO(x) foo ## x  =>  FOO(bar) => foobar
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(tok(TokenType.IDENTIFIER, "bar"))));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foobar"), texts(result));
    }

    @Test
    void substituteOfAnEmptyReplacementListAppliesTheHideSetToTheOutput() {
        var guard = new Token(TokenType.IDENTIFIER, "GUARD", 1, 1);
        var outSet = setOf(tok(TokenType.IDENTIFIER, "a"));

        var result = scanner.substitute(TokenSet.empty(), new ArrayList<>(), new ArrayList<>(),
                setOf(new CppToken(guard)), outSet);

        assertEquals(List.of("a"), texts(result));
        assertTrue(result.tokens.get(0).hideSet.contains(guard));
    }

    @Test
    void substituteAppliesTheHideSetToEveryProducedToken() {
        var guard = new Token(TokenType.IDENTIFIER, "GUARD", 1, 1);
        var inSet = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.substitute(inSet, new ArrayList<>(), new ArrayList<>(),
                setOf(new CppToken(guard)), TokenSet.empty());

        assertTrue(result.tokens.get(0).hideSet.contains(guard));
        assertTrue(result.tokens.get(1).hideSet.contains(guard));
    }

    @Test
    void substituteReplacesRepeatedParameterOccurrences() {
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(tok(TokenType.IDENTIFIER, "y"))));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "x"), tok(TokenType.PUNCTUATOR, "*"), tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("y", "*", "y"), texts(result));
    }

    @Test
    void substituteSubstitutesAMultiTokenArgument() {
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(
                tok(TokenType.INTEGER_LITERAL, "1"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.INTEGER_LITERAL, "2"))));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("1", "+", "2"), texts(result));
    }

    @Test
    void substituteParameterWithAnEmptyArgumentVanishes() {
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(TokenSet.empty()));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of(), texts(result));
    }

    @Test
    void substituteStringizesAMultiTokenArgument() {
        var params = new ArrayList<>(List.of(TokenSet.from(tok(TokenType.IDENTIFIER, "x"))));
        var args = new ArrayList<>(List.of(setOf(
                tok(TokenType.INTEGER_LITERAL, "1"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.INTEGER_LITERAL, "2"))));
        var inSet = setOf(tok(TokenType.STRINGIZE, "#"), tok(TokenType.IDENTIFIER, "x"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("\"1+2\""), texts(result));
    }

    @Test
    void substituteStringizeOfANonParameterCopiesTheHashThrough() {
        var inSet = setOf(tok(TokenType.STRINGIZE, "#"), tok(TokenType.IDENTIFIER, "foo"));

        var result = scanner.substitute(inSet, new ArrayList<>(), new ArrayList<>(), TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("#", "foo"), texts(result));
    }

    @Test
    void substitutePasteOfTwoNonParameterTokensGluesThem() {
        // #define GLUED foo ## bar
        var inSet = setOf(tok(TokenType.IDENTIFIER, "foo"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "bar"));

        var result = scanner.substitute(inSet, new ArrayList<>(), new ArrayList<>(), TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foobar"), texts(result));
    }

    @Test
    void substitutePasteWithAnEmptyLeftArgumentUsesTheRightArgumentDirectly() {
        // #define CAT(a, b) a ## b  =>  CAT(, bar) => bar
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(TokenSet.empty(), setOf(tok(TokenType.IDENTIFIER, "bar"))));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("bar"), texts(result));
    }

    @Test
    void substitutePasteWithAnEmptyRightArgumentKeepsTheLeftArgument() {
        // CAT(foo, ) => foo
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(setOf(tok(TokenType.IDENTIFIER, "foo")), TokenSet.empty()));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foo"), texts(result));
    }

    @Test
    void substitutePasteWithBothArgumentsEmptyProducesNothing() {
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(TokenSet.empty(), TokenSet.empty()));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "b"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of(), texts(result));
    }

    @Test
    void substituteChainsPastesAcrossThreeParameters() {
        // #define CAT3(a, b, c) a ## b ## c  =>  CAT3(x, y, z) => xyz
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "c"))
        ));
        var args = new ArrayList<>(List.of(
                setOf(tok(TokenType.IDENTIFIER, "x")),
                setOf(tok(TokenType.IDENTIFIER, "y")),
                setOf(tok(TokenType.IDENTIFIER, "z"))
        ));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"),
                tok(TokenType.IDENTIFIER, "b"), tok(TokenType.PASTE, "##"), tok(TokenType.IDENTIFIER, "c"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("xyz"), texts(result));
    }

    @Test
    void substituteCopiesTokensFollowingAPaste() {
        // #define FOO(a, b) a ## b + 1
        var params = new ArrayList<>(List.of(
                TokenSet.from(tok(TokenType.IDENTIFIER, "a")),
                TokenSet.from(tok(TokenType.IDENTIFIER, "b"))
        ));
        var args = new ArrayList<>(List.of(
                setOf(tok(TokenType.IDENTIFIER, "foo")),
                setOf(tok(TokenType.IDENTIFIER, "bar"))
        ));
        var inSet = setOf(tok(TokenType.IDENTIFIER, "a"), tok(TokenType.PASTE, "##"),
                tok(TokenType.IDENTIFIER, "b"), tok(TokenType.PUNCTUATOR, "+"), tok(TokenType.INTEGER_LITERAL, "1"));

        var result = scanner.substitute(inSet, params, args, TokenSet.empty(), TokenSet.empty());

        assertEquals(List.of("foobar", "+", "1"), texts(result));
    }

    // ---- expand() ---------------------------------------------------
    // End-to-end cases from the paper: object-like and function-like macro
    // expansion, self-reference guarded by hide sets, and rescanning.

    @Test
    void expandPreservesOrderOfPlainNonMacroTokens() {
        assertExpandsTo("1 + 2", "1", "+", "2");
    }

    @Test
    void expandLeavesNonMacroIdentifiersUntouched() {
        assertExpandsTo("foo", "foo");
    }

    @Test
    void expandSimpleObjectMacro() {
        assertExpandsTo("""
                #define A 12
                A
                """, "12");
    }

    @Test
    void expandChainedObjectMacro() {
        assertExpandsTo("""
                #define A 12
                #define B A
                B
                """, "12");
    }

    @Test
    void expandDoesNotConsumeTokensFollowingAMacroInvocation() {
        assertExpandsTo("""
                #define A 1
                A + A
                """, "1", "+", "1");
    }

    @Test
    void expandSelfReferentialMacroPairStopsAtHideSet() {
        // The canonical example from the paper: expanding x must not loop
        // forever between x and y, because x is in the hide set by the
        // time y's expansion re-introduces it.
        assertExpandsTo("""
                #define x (4 + y)
                #define y (2 * x)
                x
                """, "(", "4", "+", "(", "2", "*", "x", ")", ")");
    }

    @Test
    void expandFunctionLikeMacroSubstitutesArguments() {
        assertExpandsTo("""
                #define ADD(a, b) a + b
                ADD(1, 2)
                """, "1", "+", "2");
    }

    @Test
    void expandFunctionLikeMacroWithNoArguments() {
        assertExpandsTo("""
                #define THUNK() 42
                THUNK()
                """, "42");
    }

    @Test
    void expandStringizeOperator() {
        assertExpandsTo("""
                #define STR(x) #x
                STR(foo)
                """, "\"foo\"");
    }

    @Test
    void expandPasteOperator() {
        assertExpandsTo("""
                #define CAT(a, b) a##b
                CAT(foo, bar)
                """, "foobar");
    }

    @Test
    void expandRescansPastedResultAsANewMacro() {
        // Pasting can produce a brand-new macro name, which must then be
        // expanded too (rescanning).
        assertExpandsTo("""
                #define AB 99
                #define CAT(a,b) a ## b
                CAT(A, B)
                """, "99");
    }

    @Test
    void expandFunctionLikeMacroArgumentIsExpandedBeforeSubstitution() {
        // Actual arguments are macro-expanded before being substituted in
        // (unless the parameter is used with # or ##).
        assertExpandsTo("""
                #define A 5
                #define ADD(x) x + 1
                ADD(A)
                """, "5", "+", "1");
    }

    @Test
    void expandOfEmptySourceProducesNothing() {
        assertExpandsTo("");
    }

    @Test
    void expandDirectiveLinesLeaveNoTokensBehind() {
        assertExpandsTo("#define A 1");
    }

    @Test
    void expandObjectMacroWithAMultiTokenBody() {
        assertExpandsTo("""
                #define A 1 + 2
                A
                """, "1", "+", "2");
    }

    @Test
    void expandObjectMacroWithAnEmptyBodyDisappears() {
        assertExpandsTo("""
                #define EMPTY
                EMPTY x
                """, "x");
    }

    @Test
    void expandChainOfFourObjectMacros() {
        assertExpandsTo("""
                #define D 3
                #define C D
                #define B C
                #define A B
                A
                """, "3");
    }

    @Test
    void expandSelfReferentialObjectMacroAppearsExactlyOnce() {
        assertExpandsTo("""
                #define A A + 1
                A
                """, "A", "+", "1");
    }

    @Test
    void expandRecursiveFunctionLikeMacroStopsViaTheHideSet() {
        assertExpandsTo("""
                #define F(x) F(x)
                F(1)
                """, "F", "(", "1", ")");
    }

    @Test
    void expandBareFunctionLikeMacroNameIsLeftAlone() {
        assertExpandsTo("""
                #define F(x) x
                F + 1
                """, "F", "+", "1");
    }

    @Test
    void expandFunctionLikeMacroWithAMultiTokenArgument() {
        assertExpandsTo("""
                #define NEG(x) -(x)
                NEG(1 + 2)
                """, "-", "(", "1", "+", "2", ")");
    }

    @Test
    void expandNestedInvocationsOfTheSameMacro() {
        assertExpandsTo("""
                #define ADD(a, b) a + b
                ADD(ADD(1, 2), 3)
                """, "1", "+", "2", "+", "3");
    }

    @Test
    void expandArgumentWithParenthesizedCommasStaysOneArgument() {
        assertExpandsTo("""
                #define FST(p) p
                FST((1, 2))
                """, "(", "1", ",", "2", ")");
    }

    @Test
    void expandTwoInvocationsOnOneLine() {
        assertExpandsTo("""
                #define INC(x) x + 1
                INC(1) INC(2)
                """, "1", "+", "1", "2", "+", "1");
    }

    @Test
    void expandEmptyArgumentToAFunctionLikeMacro() {
        assertExpandsTo("""
                #define WRAP(x) [x]
                WRAP()
                """, "[", "]");
    }

    @Test
    void expandStringizeReceivesTheRawUnexpandedArgument() {
        assertExpandsTo("""
                #define A 1
                #define STR(x) #x
                STR(A)
                """, "\"A\"");
    }

    @Test
    void expandPasteReceivesTheRawUnexpandedArguments() {
        assertExpandsTo("""
                #define A 1
                #define CAT(a, b) a##b
                CAT(A, A)
                """, "AA");
    }

    @Test
    void expandPastedIntegersFormOneIntegerToken() {
        var result = expand("""
                #define CAT(a, b) a##b
                CAT(1, 2)
                """);

        assertEquals(List.of("12"), texts(result));
        assertEquals(TokenType.INTEGER_LITERAL, result.tokens.get(0).token.type);
    }

    @Test
    void expandPastedPunctuatorsFormOnePunctuatorToken() {
        var result = expand("""
                #define CAT(a, b) a##b
                x CAT(<, <) y
                """);

        assertEquals(List.of("x", "<<", "y"), texts(result));
        assertEquals(TokenType.PUNCTUATOR, result.tokens.get(1).token.type);
    }

    @Test
    void expandedTokensCarryTheExpandingMacroNamesInTheirHideSets() {
        var result = expand("""
                #define A B
                #define B 1
                A
                """);

        assertEquals(List.of("1"), texts(result));
        var names = result.tokens.get(0).hideSet.stream().map(t -> t.text).toList();
        assertTrue(names.containsAll(List.of("A", "B")));
    }

    @Test
    void expandHideSetsAreScopedPerOccurrence() {
        // The first A being painted blue must not stop the second A's own
        // (single-step) expansion.
        assertExpandsTo("""
                #define A A
                A A
                """, "A", "A");
    }
}
