package org.jbm.mycc.cc.cpp;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenSet;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;

import java.util.ArrayList;
import java.util.Optional;
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
            super(message + ": '" + token.text + "' at " + token.location());
        }
    }

    // integer-literal (6.4.5.2): decimal, octal (unprefixed or 0o), hex, or
    // binary digits (with ' separators), then an optional integer suffix -
    // an unsigned suffix combined either way with a long / long long /
    // bit-precise (wb) suffix.
    private static final String INT_SUFFIX = "([uU](ll|LL|l|L|wb|WB)?|(ll|LL|l|L|wb|WB)[uU]?)?";
    private static final Pattern INTEGER = Pattern.compile(
            "(0[xX][0-9a-fA-F]['0-9a-fA-F]*|0[bB][01]['01]*|0[oO][0-7]['0-7]*|[0-9]['0-9]*)"
                    + INT_SUFFIX);

    // floating-suffix (6.4.5.3): a real suffix (f l df dd dl, any case) and
    // a complex suffix (i j, any case), either alone or in either order.
    private static final String REAL_SUFFIX = "(f|l|F|L|df|dd|dl|DF|DD|DL)";
    private static final String FLOAT_SUFFIX =
            "(" + REAL_SUFFIX + "[iIjJ]?|[iIjJ]" + REAL_SUFFIX + "?)?";

    // floating-literal (decimal): a fractional literal with an optional
    // exponent, or a digit sequence with a mandatory exponent.
    private static final String DIGITS = "[0-9]['0-9]*";
    private static final Pattern DECIMAL_FLOAT = Pattern.compile(
            "((" + DIGITS + "\\.(" + DIGITS + ")?|\\." + DIGITS + ")([eE][+-]?" + DIGITS + ")?"
                    + "|" + DIGITS + "[eE][+-]?" + DIGITS + ")" + FLOAT_SUFFIX);

    // floating-literal (hex): the binary exponent is mandatory.
    private static final String HEX_DIGITS = "[0-9a-fA-F]['0-9a-fA-F]*";
    private static final Pattern HEX_FLOAT = Pattern.compile(
            "0[xX](" + HEX_DIGITS + "(\\.(" + HEX_DIGITS + ")?)?|\\." + HEX_DIGITS + ")"
                    + "[pP][+-]?" + DIGITS + FLOAT_SUFFIX);

    private TokenConversion() {
    }

    public static TokenSet convert(@NonNull TokenSet set) {
        var tokens = new ArrayList<CppToken>();
        for (var t : set.tokens) {
            if (t.token.type != TokenType.PP_NUMBER) {
                tokens.add(t);
                continue;
            }
            var type = classifyPpNumber(t.token.text).orElseThrow(() -> new ConversionException(
                    "pp-number is not a valid integer or floating constant", t.token));
            Token constant = new Token(type, t.token.text, t.token.line, t.token.column);
            constant.file = t.token.file;
            var converted = new CppToken(constant);
            converted.hideSet.addAll(t.hideSet);
            tokens.add(converted);
        }
        var result = new TokenSet(tokens);
        result.macros = set.macros;
        return result;
    }

    // The constant type this pp-number converts to, or empty if it is not a
    // valid constant (e.g. `0xE+2`, `123abc`, `0x1.8` without exponent).
    static Optional<TokenType> classifyPpNumber(String text) {
        if (INTEGER.matcher(text).matches()) {
            return Optional.of(TokenType.INTEGER_CONSTANT);
        }
        if (DECIMAL_FLOAT.matcher(text).matches() || HEX_FLOAT.matcher(text).matches()) {
            return Optional.of(TokenType.FLOATING_CONSTANT);
        }
        return Optional.empty();
    }
}
