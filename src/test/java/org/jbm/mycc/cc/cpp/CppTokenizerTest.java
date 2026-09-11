package org.jbm.mycc.cc.cpp;

import org.jbm.mycc.cc.cpp.CppTokenizer;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CppTokenizerTest {

    private static List<Token> tokenize(String source) {
        return CppTokenizer.tokenize(source);
    }

    private static void assertTokens(String source, Object... typeTextPairs) {
        List<Token> tokens = tokenize(source);
        assertEquals(typeTextPairs.length / 2 + 1, tokens.size(), "token count for: " + source);
        for (int i = 0; i < typeTextPairs.length; i += 2) {
            Token t = tokens.get(i / 2);
            assertEquals(typeTextPairs[i], t.type, "type at index " + (i / 2) + " for: " + source);
            assertEquals(typeTextPairs[i + 1], t.text, "text at index " + (i / 2) + " for: " + source);
        }
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type);
    }

    @Test
    void emptySourceProducesOnlyEof() {
        List<Token> tokens = tokenize("");
        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tokens.get(0).type);
    }

    @Test
    void everyKeywordIsRecognizedIndividually() {
        for (String keyword : CppTokenizer.keywords()) {
            List<Token> tokens = tokenize(keyword);
            assertEquals(2, tokens.size(), "token count for keyword: " + keyword);
            assertEquals(TokenType.KEYWORD, tokens.get(0).type, "type for keyword: " + keyword);
            assertEquals(keyword, tokens.get(0).text, "text for keyword: " + keyword);
            assertEquals(TokenType.EOF, tokens.get(1).type);
        }
    }

    @Test
    void identifiersAndKeywords() {
        assertTokens("int foo_bar _baz42",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "foo_bar",
                TokenType.IDENTIFIER, "_baz42");
    }

    @Test
    void integerConstantSpellingsLexAsSinglePpNumbers() {
        assertTokens("42 0x1F 0b101 100ULL",
                TokenType.PP_NUMBER, "42",
                TokenType.PP_NUMBER, "0x1F",
                TokenType.PP_NUMBER, "0b101",
                TokenType.PP_NUMBER, "100ULL");
    }

    @Test
    void ppNumberWithDigitSeparators() {
        assertTokens("1'000'000", TokenType.PP_NUMBER, "1'000'000");
    }

    @Test
    void floatingConstantSpellingsLexAsSinglePpNumbers() {
        assertTokens("3.14 1e10 2.5f 0x1.8p3",
                TokenType.PP_NUMBER, "3.14",
                TokenType.PP_NUMBER, "1e10",
                TokenType.PP_NUMBER, "2.5f",
                TokenType.PP_NUMBER, "0x1.8p3");
    }

    @Test
    void ppNumberMayStartWithADot() {
        assertTokens(".5", TokenType.PP_NUMBER, ".5");
    }

    @Test
    void ppNumberSwallowsASignAfterAnExponentLetter() {
        // The standard's canonical example: 0xE+2 is ONE pp-number (which
        // phase 7 then rejects), not the addition 0xE + 2.
        assertTokens("0xE+2", TokenType.PP_NUMBER, "0xE+2");
    }

    @Test
    void ppNumberSwallowsTrailingIdentifierCharacters() {
        assertTokens("123abc", TokenType.PP_NUMBER, "123abc");
    }

    @Test
    void ppNumberSwallowsRepeatedDots() {
        assertTokens("1.2.3", TokenType.PP_NUMBER, "1.2.3");
    }

    @Test
    void signIsOnlySwallowedDirectlyAfterAnExponentLetter() {
        assertTokens("1+2",
                TokenType.PP_NUMBER, "1",
                TokenType.PUNCTUATOR, "+",
                TokenType.PP_NUMBER, "2");
    }

    @Test
    void stringLiteral() {
        assertTokens("\"hello\\nworld\"", TokenType.STRING_LITERAL, "\"hello\\nworld\"");
    }

    @Test
    void prefixedStringLiterals() {
        assertTokens("u8\"a\" u\"b\" U\"c\" L\"d\"",
                TokenType.STRING_LITERAL, "u8\"a\"",
                TokenType.STRING_LITERAL, "u\"b\"",
                TokenType.STRING_LITERAL, "U\"c\"",
                TokenType.STRING_LITERAL, "L\"d\"");
    }

    @Test
    void rawStringLiteral() {
        assertTokens("R\"(a\"b)\"", TokenType.STRING_LITERAL, "R\"(a\"b)\"");
    }

    @Test
    void rawStringLiteralWithDelimiter() {
        // Content contains a `)` that doesn't form the real terminator `)xyz"`.
        assertTokens("R\"xyz(text with ) paren)xyz\"",
                TokenType.STRING_LITERAL, "R\"xyz(text with ) paren)xyz\"");
    }

    @Test
    void charLiterals() {
        assertTokens("u8'x'", TokenType.CHARACTER_LITERAL, "u8'x'");
        assertTokens("'a' '\\n' L'x'",
                TokenType.CHARACTER_LITERAL, "'a'",
                TokenType.CHARACTER_LITERAL, "'\\n'",
                TokenType.CHARACTER_LITERAL, "L'x'");
    }

    @Test
    void punctuatorsPreferLongestMatch() {
        assertTokens("<<= >> -> ... :: <=",
                TokenType.PUNCTUATOR, "<<=",
                TokenType.PUNCTUATOR, ">>",
                TokenType.PUNCTUATOR, "->",
                TokenType.PUNCTUATOR, "...",
                TokenType.PUNCTUATOR, "::",
                TokenType.PUNCTUATOR, "<=");
    }

    @Test
    void cPlusPlusOnlyPunctuatorsAreNotSingleTokens() {
        assertTokens("a->*b",
                TokenType.IDENTIFIER, "a",
                TokenType.PUNCTUATOR, "->",
                TokenType.PUNCTUATOR, "*",
                TokenType.IDENTIFIER, "b");
    }

    @Test
    void digraphsAreNormalizedToTheirPrimarySpelling() {
        // 6.4.7p3: <: :> <% %> behave exactly like [ ] { }.
        assertTokens("a<:0:> <% %>",
                TokenType.IDENTIFIER, "a",
                TokenType.PUNCTUATOR, "[",
                TokenType.PP_NUMBER, "0",
                TokenType.PUNCTUATOR, "]",
                TokenType.PUNCTUATOR, "{",
                TokenType.PUNCTUATOR, "}");
    }

    @Test
    void digraphStringizeAndPasteOperators() {
        assertTokens("x %: y %:%: z",
                TokenType.IDENTIFIER, "x",
                TokenType.STRINGIZE, "#",
                TokenType.IDENTIFIER, "y",
                TokenType.PASTE, "##",
                TokenType.IDENTIFIER, "z");
    }

    @Test
    void backslashNewlineSplicesPhysicalLines() {
        // Translation phase 2: the pair is deleted, even inside a token.
        assertTokens("int x\\\n= 1;\nab\\\ncd",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, "=",
                TokenType.PP_NUMBER, "1",
                TokenType.PUNCTUATOR, ";",
                TokenType.IDENTIFIER, "abcd");
    }

    @Test
    void tokensAfterASpliceStayOnTheLogicalLine() {
        // The spliced text is one logical line; the expander relies on
        // this to know where a #define ends.
        List<Token> tokens = tokenize("a \\\nb\nc");
        assertEquals("a@1:1 b@1:3 c@2:1", tokens.stream()
                .filter(t -> t.type != TokenType.EOF)
                .map(t -> t.text + "@" + t.line + ":" + t.column)
                .reduce((x, y) -> x + " " + y).orElseThrow());
    }

    @Test
    void aSplicedDefineIsOneLogicalLine() {
        List<Token> tokens = tokenize("#define A 1 + \\\n    2\nint x = A;");
        Token a = tokens.stream().filter(t -> t.type == TokenType.OBJECT_MACRO).findFirst().orElseThrow();
        assertEquals(List.of("1", "+", "2"), a.expansion.stream().map(t -> t.text).toList());
        // and a '#' on the continuation is not a directive: it is inside the line
        assertTokens("x \\\n# y", TokenType.IDENTIFIER, "x", TokenType.STRINGIZE, "#", TokenType.IDENTIFIER, "y");
    }

    @Test
    void tokensRememberWhetherWhiteSpacePrecededThem() {
        List<Token> tokens = tokenize("a b+c /*x*/d\n e");
        assertEquals(List.of("a:false", "b:true", "+:false", "c:false", "d:true", "e:true"),
                tokens.stream().filter(t -> t.type != TokenType.EOF)
                        .map(t -> t.text + ":" + t.spaceBefore).toList());
    }

    @Test
    void lineCommentsAreSkipped() {
        assertTokens("int x; // trailing comment\nint y;",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, ";",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "y",
                TokenType.PUNCTUATOR, ";");
    }

    @Test
    void blockCommentsAreSkipped() {
        assertTokens("int /* comment */ x;",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, ";");
    }

    @Test
    void unterminatedBlockCommentThrows() {
        assertThrows(CppTokenizer.LexException.class, () -> tokenize("int x; /* never closed"));
    }

    @Test
    void unterminatedStringLiteralThrows() {
        assertThrows(CppTokenizer.LexException.class, () -> tokenize("\"never closed"));
    }

    @Test
    void hashDoesNotSpanTheWholeLineAsADirectiveToken() {
        // '#' and 'define' are still ordinary tokens; only the macro name is reclassified.
        assertTokens("#define FOO 1",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.OBJECT_MACRO, "FOO",
                TokenType.PP_NUMBER, "1");
    }

    @Test
    void objectMacroTokenCarriesItsExpansionTokens() {
        List<Token> tokens = tokenize("#define A 1 + 2");
        Token objectMacro = tokens.get(2);
        assertEquals(TokenType.OBJECT_MACRO, objectMacro.type);
        assertEquals("A", objectMacro.text);

        List<Token> expansion = objectMacro.expansion;
        assertEquals(3, expansion.size());
        assertEquals(TokenType.PP_NUMBER, expansion.get(0).type);
        assertEquals("1", expansion.get(0).text);
        assertEquals(TokenType.PUNCTUATOR, expansion.get(1).type);
        assertEquals("+", expansion.get(1).text);
        assertEquals(TokenType.PP_NUMBER, expansion.get(2).type);
        assertEquals("2", expansion.get(2).text);
    }

    @Test
    void objectMacroExpansionTokensMatchTheOnesInTheMainStream() {
        // Same tokenizer position -> the expansion tokens should be
        // value-equal to what the main scan later produces for that text.
        List<Token> tokens = tokenize("#define A 1 + 2");
        Token objectMacro = tokens.get(2);
        assertEquals(tokens.get(3), objectMacro.expansion.get(0));
        assertEquals(tokens.get(4), objectMacro.expansion.get(1));
        assertEquals(tokens.get(5), objectMacro.expansion.get(2));
    }

    @Test
    void callMacroTokenCarriesItsExpansionTokensAfterTheParamList() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y");
        Token callMacro = tokens.get(2);
        assertEquals(TokenType.CALL_MACRO, callMacro.type);
        assertEquals("ADD", callMacro.text);

        List<Token> expansion = callMacro.expansion;
        assertEquals(3, expansion.size());
        assertEquals(TokenType.IDENTIFIER, expansion.get(0).type);
        assertEquals("x", expansion.get(0).text);
        assertEquals(TokenType.PUNCTUATOR, expansion.get(1).type);
        assertEquals("+", expansion.get(1).text);
        assertEquals(TokenType.IDENTIFIER, expansion.get(2).type);
        assertEquals("y", expansion.get(2).text);
    }

    @Test
    void callMacroTokenCarriesItsFormalParams() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y");
        Token callMacro = tokens.get(2);
        assertEquals(TokenType.CALL_MACRO, callMacro.type);

        List<Token> params = callMacro.params;
        assertEquals(2, params.size());
        assertEquals(TokenType.IDENTIFIER, params.get(0).type);
        assertEquals("x", params.get(0).text);
        assertEquals(TokenType.IDENTIFIER, params.get(1).type);
        assertEquals("y", params.get(1).text);
    }

    @Test
    void callMacroWithNoParamsHasEmptyParamList() {
        List<Token> tokens = tokenize("#define THUNK() 42");
        Token callMacro = tokens.get(2);
        assertEquals(List.of(), callMacro.params);
    }

    @Test
    void variadicCallMacroCapturesEllipsisAsAParam() {
        List<Token> tokens = tokenize("#define LOG(fmt, ...) fmt");
        Token callMacro = tokens.get(2);

        List<Token> params = callMacro.params;
        assertEquals(2, params.size());
        assertEquals(TokenType.IDENTIFIER, params.get(0).type);
        assertEquals("fmt", params.get(0).text);
        assertEquals(TokenType.PUNCTUATOR, params.get(1).type);
        assertEquals("...", params.get(1).text);
    }

    @Test
    void callMacroExpansionTokensMatchTheOnesInTheMainStream() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y");
        Token callMacro = tokens.get(2);
        // tokens: #(0) define(1) ADD(2) ((3) x(4) ,(5) y(6) )(7) x(8) +(9) y(10)
        assertEquals(tokens.get(8), callMacro.expansion.get(0));
        assertEquals(tokens.get(9), callMacro.expansion.get(1));
        assertEquals(tokens.get(10), callMacro.expansion.get(2));
    }

    @Test
    void callMacroInvocationCapturesActualArguments() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y\nADD(1, 2)");
        // line 2 tokens: ADD(11) ((12) 1(13) ,(14) 2(15) )(16)
        Token invocation = tokens.get(11);
        assertEquals(TokenType.CALL_MACRO, invocation.type);

        List<List<Token>> arguments = invocation.arguments;
        assertEquals(2, arguments.size());
        assertEquals(1, arguments.get(0).size());
        assertEquals(TokenType.PP_NUMBER, arguments.get(0).get(0).type);
        assertEquals("1", arguments.get(0).get(0).text);
        assertEquals(1, arguments.get(1).size());
        assertEquals(TokenType.PP_NUMBER, arguments.get(1).get(0).type);
        assertEquals("2", arguments.get(1).get(0).text);

        // The actual '(', '1', ',', '2', ')' still show up as ordinary
        // tokens in the main stream too, just like a definition's params.
        assertEquals(TokenType.PUNCTUATOR, tokens.get(12).type);
        assertEquals("(", tokens.get(12).text);
        assertEquals(TokenType.PUNCTUATOR, tokens.get(16).type);
        assertEquals(")", tokens.get(16).text);
    }

    @Test
    void callMacroInvocationWithNoArgumentsHasEmptyArgumentList() {
        List<Token> tokens = tokenize("#define THUNK() 42\nTHUNK()");
        Token invocation = tokens.get(6);
        assertEquals(TokenType.CALL_MACRO, invocation.type);
        assertEquals(List.of(), invocation.arguments);
    }

    @Test
    void callMacroInvocationArgumentsRespectNestedParens() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y\nADD((1,2), 3)");
        Token invocation = tokens.get(11);

        List<List<Token>> arguments = invocation.arguments;
        assertEquals(2, arguments.size());

        List<Token> first = arguments.get(0);
        assertEquals(5, first.size());
        assertEquals(TokenType.PUNCTUATOR, first.get(0).type);
        assertEquals("(", first.get(0).text);
        assertEquals(TokenType.PP_NUMBER, first.get(1).type);
        assertEquals("1", first.get(1).text);
        assertEquals(TokenType.PUNCTUATOR, first.get(2).type);
        assertEquals(",", first.get(2).text);
        assertEquals(TokenType.PP_NUMBER, first.get(3).type);
        assertEquals("2", first.get(3).text);
        assertEquals(TokenType.PUNCTUATOR, first.get(4).type);
        assertEquals(")", first.get(4).text);

        List<Token> second = arguments.get(1);
        assertEquals(1, second.size());
        assertEquals(TokenType.PP_NUMBER, second.get(0).type);
        assertEquals("3", second.get(0).text);
    }

    @Test
    void callMacroInvocationAllowsWhitespaceBeforeParen() {
        // Unlike a #define, where a space before '(' makes it object-like,
        // whitespace before the '(' at a call site is just ordinary
        // formatting and doesn't change that it's an invocation.
        List<Token> tokens = tokenize("#define ADD(x, y) x + y\nADD (1, 2)");
        Token invocation = tokens.get(11);
        assertEquals(TokenType.CALL_MACRO, invocation.type);
        assertEquals(2, invocation.arguments.size());
    }

    @Test
    void bareCallMacroNameWithoutParensIsNotAnInvocation() {
        List<Token> tokens = tokenize("#define ADD(x, y) x + y\nADD");
        Token last = tokens.get(11);
        assertEquals(TokenType.IDENTIFIER, last.type);
        assertEquals(List.of(), last.arguments);
    }

    @Test
    void plainFunctionCallOfAnUndefinedNameIsNotAnInvocation() {
        List<Token> tokens = tokenize("foo(1, 2)");
        assertEquals(TokenType.IDENTIFIER, tokens.get(0).type);
        assertEquals(List.of(), tokens.get(0).arguments);
    }

    @Test
    void hashDefineObjectMacroName() {
        assertTokens("#define A 1",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.OBJECT_MACRO, "A",
                TokenType.PP_NUMBER, "1");
    }

    @Test
    void hashDefineCallMacroNameWhenParenImmediatelyFollows() {
        assertTokens("#define B(x) x",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.CALL_MACRO, "B",
                TokenType.PUNCTUATOR, "(",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, ")",
                TokenType.IDENTIFIER, "x");
    }

    @Test
    void hashDefineWithSpaceBeforeParenIsStillObjectMacro() {
        // A space before '(' means it's an object-like macro whose value happens to start with '('.
        assertTokens("#define A (1)",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.OBJECT_MACRO, "A",
                TokenType.PUNCTUATOR, "(",
                TokenType.PP_NUMBER, "1",
                TokenType.PUNCTUATOR, ")");
    }

    @Test
    void hashNotAtLineStartIsStringizeOperator() {
        assertTokens("x # y",
                TokenType.IDENTIFIER, "x",
                TokenType.STRINGIZE, "#",
                TokenType.IDENTIFIER, "y");
    }

    @Test
    void doubleHashIsPasteOperator() {
        assertTokens("a ## b",
                TokenType.IDENTIFIER, "a",
                TokenType.PASTE, "##",
                TokenType.IDENTIFIER, "b");
    }

    @Test
    void stringizeAndPasteInsideMacroBody() {
        assertTokens("#define CAT(a, b) a ## b\n#define STR(x) #x",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.CALL_MACRO, "CAT",
                TokenType.PUNCTUATOR, "(",
                TokenType.IDENTIFIER, "a",
                TokenType.PUNCTUATOR, ",",
                TokenType.IDENTIFIER, "b",
                TokenType.PUNCTUATOR, ")",
                TokenType.IDENTIFIER, "a",
                TokenType.PASTE, "##",
                TokenType.IDENTIFIER, "b",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.CALL_MACRO, "STR",
                TokenType.PUNCTUATOR, "(",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, ")",
                TokenType.STRINGIZE, "#",
                TokenType.IDENTIFIER, "x");
    }

    @Test
    void dollarIsAnIdentifierCharacter() {
        assertTokens("$1 + a$b",
                TokenType.IDENTIFIER, "$1",
                TokenType.PUNCTUATOR, "+",
                TokenType.IDENTIFIER, "a$b");
    }

    @Test
    void nonDefineDirectiveDoesNotReclassifyFollowingIdentifiers() {
        assertTokens("#pragma foo",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "pragma",
                TokenType.IDENTIFIER, "foo");
    }

    @Test
    void secondLineDefineIsRecognizedIndependently() {
        assertTokens("int x;\n#define C 2",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "x",
                TokenType.PUNCTUATOR, ";",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "define",
                TokenType.OBJECT_MACRO, "C",
                TokenType.PP_NUMBER, "2");
    }

    @Test
    void lineAndColumnTracking() {
        List<Token> tokens = tokenize("int\n  x;");
        Token intTok = tokens.get(0);
        Token xTok = tokens.get(1);
        assertEquals(1, intTok.line);
        assertEquals(1, intTok.column);
        assertEquals(2, xTok.line);
        assertEquals(3, xTok.column);
    }

    @Test
    void functionSnippetTokenizesEndToEnd() {
        assertTokens("int add(int a, int b) { return a + b; }",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "add",
                TokenType.PUNCTUATOR, "(",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "a",
                TokenType.PUNCTUATOR, ",",
                TokenType.KEYWORD, "int",
                TokenType.IDENTIFIER, "b",
                TokenType.PUNCTUATOR, ")",
                TokenType.PUNCTUATOR, "{",
                TokenType.KEYWORD, "return",
                TokenType.IDENTIFIER, "a",
                TokenType.PUNCTUATOR, "+",
                TokenType.IDENTIFIER, "b",
                TokenType.PUNCTUATOR, ";",
                TokenType.PUNCTUATOR, "}");
    }

    @Test
    void tokenSetBuildsAMacroTableWhilePreTokenizing() {
        var set = CppTokenizer.tokenSet("""
                #define A 1 + 2
                #define ADD(x, y) x + y
                int r = A;
                """);

        assertEquals(2, set.macros.size());

        Token a = set.macros.get("A");
        assertEquals(TokenType.OBJECT_MACRO, a.type);
        assertEquals(3, a.expansion.size());
        assertEquals("1", a.expansion.get(0).text);

        Token add = set.macros.get("ADD");
        assertEquals(TokenType.CALL_MACRO, add.type);
        assertEquals(2, add.params.size());
        assertEquals("x", add.params.get(0).text);
        assertEquals("y", add.params.get(1).text);
        assertEquals(3, add.expansion.size());
    }

    @Test
    void laterRedefinitionOverwritesTheMacroTableEntry() {
        var set = CppTokenizer.tokenSet("""
                #define A 1
                #define A 2
                """);

        assertEquals(1, set.macros.size());
        assertEquals("2", set.macros.get("A").expansion.get(0).text);
    }

    @Test
    void macroTableHasNoEntryForUndefinedNames() {
        var set = CppTokenizer.tokenSet("#define A 1");
        assertEquals(null, set.macros.get("B"));
    }

    @Test
    void undefRemovesTheMacroTableEntry() {
        var set = CppTokenizer.tokenSet("""
                #define A 1
                #undef A
                """);
        assertEquals(0, set.macros.size());
    }

    @Test
    void undefOfAnUnknownNameIsANoop() {
        var set = CppTokenizer.tokenSet("#undef NEVER_DEFINED");
        assertEquals(0, set.macros.size());
    }

    @Test
    void redefineAfterUndefIsRecognizedAgain() {
        var set = CppTokenizer.tokenSet("""
                #define A 1
                #undef A
                #define A 2
                """);
        assertEquals(1, set.macros.size());
        assertEquals("2", set.macros.get("A").expansion.get(0).text);
    }

    @Test
    void undefDoesNotReclassifyTheFollowingIdentifier() {
        assertTokens("#undef A",
                TokenType.PUNCTUATOR, "#",
                TokenType.IDENTIFIER, "undef",
                TokenType.IDENTIFIER, "A");
    }
}
