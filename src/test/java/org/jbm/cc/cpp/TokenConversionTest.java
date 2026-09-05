package org.jbm.cc.cpp;

import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for translation phase 7 (C2y 5.1.1.2): converting the
 * preprocessor's pp-tokens into parser tokens. pp-numbers become
 * INTEGER_CONSTANT/FLOATING_CONSTANT here, and spellings the greedy
 * pp-number grammar accepted but that are no valid constant (0xE+2,
 * 123abc) are diagnosed here - not in the lexer, not in the expander.
 */
class TokenConversionTest {

    // Full pipeline: lex -> expand -> phase 7.
    private static TokenSet preprocess(String source) {
        return TokenConversion.convert(new Scanner().expand(CppTokenizer.tokenSet(source)));
    }

    private static List<TokenType> types(TokenSet set) {
        return set.tokens.stream()
                .filter(t -> t.token.type != TokenType.EOF)
                .map(t -> t.token.type)
                .toList();
    }

    // ---- classifyPpNumber() ---------------------------------------------

    @Test
    void classifiesIntegerConstants() {
        for (String s : List.of("0", "42", "07", "0x1F", "0b101", "0o17", "0O7", "1'000'000",
                "42u", "42U", "42l", "42LL", "100ULL", "0xABuL", "5llu", "5wb", "5uWB", "0x1FWBu")) {
            assertEquals(TokenType.INTEGER_CONSTANT, TokenConversion.classifyPpNumber(s), s);
        }
    }

    @Test
    void classifiesFloatingConstants() {
        for (String s : List.of("1.23", "1.", ".5", "1e10", "1E-5", "1.e5",
                "2.5f", "3.14L", "1e3", "0x1p+2", "0x1.8p3", "0x.8p-1",
                "1.0df", "2.5DD", "3e1dl", "1.0i", "2.0fj", "3.0if", "0x1p1J")) {
            assertEquals(TokenType.FLOATING_CONSTANT, TokenConversion.classifyPpNumber(s), s);
        }
    }

    @Test
    void rejectsPpNumbersThatAreNoValidConstant() {
        for (String s : List.of("0xE+2", "123abc", "1.2.3", "1e", "0x",
                "0x1.8", "1.5x", "42uu", "1e+", "0o8", "5wbwb", "1.0ii", "1.0fl")) {
            assertNull(TokenConversion.classifyPpNumber(s), s);
        }
    }

    // ---- convert() ------------------------------------------------------

    @Test
    void convertClassifiesPpNumbersAndLeavesOtherTokensAlone() {
        var result = preprocess("int x = 42 + 1.5;");

        assertEquals(List.of(
                TokenType.KEYWORD,          // int
                TokenType.IDENTIFIER,       // x
                TokenType.PUNCTUATOR,       // =
                TokenType.INTEGER_CONSTANT, // 42
                TokenType.PUNCTUATOR,       // +
                TokenType.FLOATING_CONSTANT,// 1.5
                TokenType.PUNCTUATOR        // ;
        ), types(result));
    }

    @Test
    void convertKeepsSpellingAndSourcePosition() {
        var result = preprocess("42");
        var t = result.tokens.get(0).token;

        assertEquals("42", t.text);
        assertEquals(1, t.line);
        assertEquals(1, t.column);
    }

    @Test
    void convertDiagnosesTheStandardsGreedyExample() {
        // 0xE+2 is one pp-number; there is no token for it to become.
        var e = assertThrows(TokenConversion.ConversionException.class,
                () -> preprocess("int x = 0xE+2;"));

        assertTrue(e.getMessage().contains("0xE+2"));
    }

    @Test
    void convertDiagnosesAnInvalidPastedNumber() {
        // The paste is valid preprocessing (one pp-number pp-token);
        // conversion is where 123abc gets rejected.
        assertThrows(TokenConversion.ConversionException.class,
                () -> preprocess("""
                        #define CAT(a, b) a##b
                        int x = CAT(123, abc);
                        """));
    }

    @Test
    void convertPassesMacroBuiltNumbersThrough() {
        var result = preprocess("""
                #define CAT(a, b) a##b
                int x = CAT(4, 2);
                double d = CAT(1, .5);
                double e = CAT(1e, 3);
                """);

        assertEquals(List.of(
                TokenType.KEYWORD, TokenType.IDENTIFIER, TokenType.PUNCTUATOR,
                TokenType.INTEGER_CONSTANT, TokenType.PUNCTUATOR,
                TokenType.KEYWORD, TokenType.IDENTIFIER, TokenType.PUNCTUATOR,
                TokenType.FLOATING_CONSTANT, TokenType.PUNCTUATOR,
                TokenType.KEYWORD, TokenType.IDENTIFIER, TokenType.PUNCTUATOR,
                TokenType.FLOATING_CONSTANT, TokenType.PUNCTUATOR
        ), types(result));
    }

    @Test
    void aStringizedGreedyPpNumberIsFineBecauseItNeverBecomesAConstant() {
        // #x turns 0xE+2 into a string literal during preprocessing, so
        // phase 7 sees no pp-number at all.
        var result = preprocess("""
                #define STR(x) #x
                const char* s = STR(0xE+2);
                """);

        assertEquals(TokenType.STRING_LITERAL, result.tokens.get(5).token.type);
        assertEquals("\"0xE+2\"", result.tokens.get(5).token.text);
    }
}
