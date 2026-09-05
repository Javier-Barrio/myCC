package org.jbm.cc.cpp;

import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.cc.cpp.CppTokenizer.TokenType;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Translation phase 7 (C2y 5.1.1.2): converts each preprocessing token
 * into a token the parser can consume. Runs after Scanner.expand().
 * <p>
 * Today the only pp-token needing conversion is the pp-number, whose
 * grammar (6.4.9) is a greedy superset of the constant grammars: `0xE+2`
 * or `123abc` lex and macro-expand fine as single pp-numbers, but have no
 * corresponding token and are diagnosed here.
 */
public final class TokenConversion {

    public static final class ConversionException extends RuntimeException {
        public ConversionException(String message, Token token) {
            super(message + ": '" + token.text + "' at " + token.line + ":" + token.column);
        }
    }

    // integer-constant: decimal/octal, hex, or binary digits (with C23 '
    // separators), then an optional integer suffix.
    private static final String INT_SUFFIX = "([uU](ll|LL|l|L)?|(ll|LL|l|L)[uU]?)?";
    private static final Pattern INTEGER = Pattern.compile(
            "(0[xX][0-9a-fA-F]['0-9a-fA-F]*|0[bB][01]['01]*|[0-9]['0-9]*)" + INT_SUFFIX);

    // floating-constant (decimal): a fractional constant with an optional
    // exponent, or a digit sequence with a mandatory exponent.
    private static final String DIGITS = "[0-9]['0-9]*";
    private static final Pattern DECIMAL_FLOAT = Pattern.compile(
            "(" + DIGITS + "\\.(" + DIGITS + ")?|\\." + DIGITS + ")([eE][+-]?" + DIGITS + ")?[flFL]?"
                    + "|" + DIGITS + "[eE][+-]?" + DIGITS + "[flFL]?");

    // floating-constant (hex): the binary exponent is mandatory.
    private static final String HEX_DIGITS = "[0-9a-fA-F]['0-9a-fA-F]*";
    private static final Pattern HEX_FLOAT = Pattern.compile(
            "0[xX](" + HEX_DIGITS + "(\\.(" + HEX_DIGITS + ")?)?|\\." + HEX_DIGITS + ")"
                    + "[pP][+-]?" + DIGITS + "[flFL]?");

    private TokenConversion() {
    }

    public static TokenSet convert(TokenSet set) {
        var tokens = new ArrayList<CppToken>();
        for (var t : set.tokens) {
            if (t.token.type != TokenType.PP_NUMBER) {
                tokens.add(t);
                continue;
            }
            var type = classifyPpNumber(t.token.text);
            if (type == null) {
                throw new ConversionException(
                        "pp-number is not a valid integer or floating constant", t.token);
            }
            var converted = new CppToken(
                    new Token(type, t.token.text, t.token.line, t.token.column));
            converted.hideSet.addAll(t.hideSet);
            tokens.add(converted);
        }
        var result = new TokenSet(tokens);
        result.macros = set.macros;
        return result;
    }

    // The constant type this pp-number converts to, or null if it is not a
    // valid constant (e.g. `0xE+2`, `123abc`, `0x1.8` without exponent).
    static TokenType classifyPpNumber(String text) {
        if (INTEGER.matcher(text).matches()) {
            return TokenType.INTEGER_CONSTANT;
        }
        if (DECIMAL_FLOAT.matcher(text).matches() || HEX_FLOAT.matcher(text).matches()) {
            return TokenType.FLOATING_CONSTANT;
        }
        return null;
    }
}
