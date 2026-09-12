package org.jbm.mycc.cc.cpp;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.LexException;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;

import java.util.List;

/**
 * The constant expression of {@code #if} and {@code #elif} (C2y 6.10.2),
 * over a token list that has had {@code defined} rewritten and macros
 * expanded: every value is 64 bits plus a signedness, an unsigned
 * operand makes a binary operation unsigned, remaining identifiers are
 * 0, and {@code true}/{@code false} are 1 and 0.
 */
public final class PpExpr {

    /** A value of {@code intmax_t} or {@code uintmax_t}. */
    public record Value(long bits, boolean unsigned) {
        public boolean isTrue() {
            return bits != 0;
        }
    }

    private final List<Token> tokens;
    private int pos;
    private final Token at;

    // Above zero inside an operand that `&&`, `||` or `?:` does not
    // evaluate: it is still parsed, but a division by zero there is not
    // an error.
    private int unevaluated;

    private PpExpr(List<Token> tokens, Token at) {
        this.tokens = tokens;
        this.at = at;
    }

    /** Evaluates {@code tokens}; {@code at} locates errors when the list is empty. */
    public static Value evaluate(@NonNull List<Token> tokens, @NonNull Token at) {
        var p = new PpExpr(tokens, at);
        if (tokens.isEmpty()) {
            throw p.error("#if with no expression", at);
        }
        Value v = p.conditional();
        if (p.pos != tokens.size()) {
            throw p.error("unexpected '" + p.peek().text + "' in preprocessor expression", p.peek());
        }
        return v;
    }

    private LexException error(String message, Token t) {
        return new LexException(message, t);
    }

    private Token peek() {
        if (pos < tokens.size()) {
            return tokens.get(pos);
        }
        Token last = tokens.isEmpty() ? at : tokens.get(tokens.size() - 1);
        Token end = new Token(TokenType.EOF, "", last.line, last.column + last.text.length());
        end.file = last.file;
        return end;
    }

    private boolean accept(String punctuator) {
        Token t = peek();
        if (t.type == TokenType.PUNCTUATOR && t.text.equals(punctuator)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expect(String punctuator) {
        Token t = peek();
        if (!accept(punctuator)) {
            throw error("expected '" + punctuator + "' in preprocessor expression", t);
        }
        return t;
    }

    // ---- grammar ---------------------------------------------------------------------------------

    private Value conditional() {
        Value c = binary(0);
        if (!accept("?")) {
            return c;
        }
        Value a = operand(conditional_(), c.isTrue());
        expect(":");
        Value b = operand(conditional_(), !c.isTrue());
        boolean unsigned = a.unsigned || b.unsigned;
        Value chosen = c.isTrue() ? a : b;
        return new Value(chosen.bits, unsigned);
    }

    // Parses an operand, evaluated or not.
    private java.util.function.Supplier<Value> conditional_() {
        return this::conditional;
    }

    private Value operand(java.util.function.Supplier<Value> parse, boolean evaluated) {
        if (evaluated) {
            return parse.get();
        }
        unevaluated++;
        try {
            return parse.get();
        } finally {
            unevaluated--;
        }
    }

    private static final String[][] LEVELS = {
            {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
    };

    private Value binary(int level) {
        if (level == LEVELS.length) {
            return unary();
        }
        Value left = binary(level + 1);
        while (true) {
            Token op = peek();
            if (op.type != TokenType.PUNCTUATOR || !isOneOf(op.text, LEVELS[level])) {
                return left;
            }
            pos++;
            boolean skipped = (op.text.equals("&&") && !left.isTrue()) || (op.text.equals("||") && left.isTrue());
            int next = level + 1;
            Value right = operand(() -> binary(next), !skipped);
            left = apply(op, left, right);
        }
    }

    private static boolean isOneOf(String text, String[] options) {
        for (String o : options) {
            if (o.equals(text)) {
                return true;
            }
        }
        return false;
    }

    private Value unary() {
        Token t = peek();
        if (t.type == TokenType.PUNCTUATOR) {
            switch (t.text) {
                case "+" -> {
                    pos++;
                    return unary();
                }
                case "-" -> {
                    pos++;
                    Value v = unary();
                    return new Value(-v.bits, v.unsigned);
                }
                case "~" -> {
                    pos++;
                    Value v = unary();
                    return new Value(~v.bits, v.unsigned);
                }
                case "!" -> {
                    pos++;
                    Value v = unary();
                    return bool(!v.isTrue());
                }
                default -> {
                }
            }
        }
        return primary();
    }

    private Value primary() {
        Token t = peek();
        pos++;
        switch (t.type) {
            case PP_NUMBER -> {
                return number(t);
            }
            case CHARACTER_LITERAL -> {
                return new Value(character(t), false);
            }
            case IDENTIFIER, OBJECT_MACRO, CALL_MACRO -> {
                return new Value(0, false);
            }
            case KEYWORD -> {
                if (t.text.equals("true")) {
                    return new Value(1, false);
                }
                if (t.text.equals("false")) {
                    return new Value(0, false);
                }
                return new Value(0, false);
            }
            case PUNCTUATOR -> {
                if (t.text.equals("(")) {
                    Value v = conditional();
                    expect(")");
                    return v;
                }
                throw error("unexpected '" + t.text + "' in preprocessor expression", t);
            }
            case STRING_LITERAL -> throw error("a string literal in a preprocessor expression", t);
            case EOF -> throw error("preprocessor expression ends early", t);
            default -> throw error("unexpected '" + t.text + "' in preprocessor expression", t);
        }
    }

    // ---- values ----------------------------------------------------------------------------------

    private static Value bool(boolean b) {
        return new Value(b ? 1 : 0, false);
    }

    private Value apply(Token op, Value a, Value b) {
        boolean unsigned = a.unsigned || b.unsigned;
        long x = a.bits;
        long y = b.bits;
        switch (op.text) {
            case "||" -> {
                return bool(a.isTrue() || b.isTrue());
            }
            case "&&" -> {
                return bool(a.isTrue() && b.isTrue());
            }
            case "|" -> {
                return new Value(x | y, unsigned);
            }
            case "^" -> {
                return new Value(x ^ y, unsigned);
            }
            case "&" -> {
                return new Value(x & y, unsigned);
            }
            case "==" -> {
                return bool(x == y);
            }
            case "!=" -> {
                return bool(x != y);
            }
            case "<", ">", "<=", ">=" -> {
                int c = unsigned ? Long.compareUnsigned(x, y) : Long.compare(x, y);
                boolean r = switch (op.text) {
                    case "<" -> c < 0;
                    case ">" -> c > 0;
                    case "<=" -> c <= 0;
                    default -> c >= 0;
                };
                return bool(r);
            }
            case "<<", ">>" -> {
                if ((y < 0 || y >= 64) && unevaluated > 0) {
                    return new Value(0, a.unsigned);
                }
                if (y < 0 || y >= 64) {
                    throw error("shift count out of range in preprocessor expression", op);
                }
                if (op.text.equals("<<")) {
                    return new Value(x << y, a.unsigned);
                }
                return new Value(a.unsigned ? x >>> y : x >> y, a.unsigned);
            }
            case "+" -> {
                return new Value(x + y, unsigned);
            }
            case "-" -> {
                return new Value(x - y, unsigned);
            }
            case "*" -> {
                return new Value(x * y, unsigned);
            }
            case "/", "%" -> {
                if (y == 0 && unevaluated > 0) {
                    return new Value(0, unsigned);
                }
                if (y == 0) {
                    throw error("division by zero in preprocessor expression", op);
                }
                boolean div = op.text.equals("/");
                if (unsigned) {
                    return new Value(div ? Long.divideUnsigned(x, y) : Long.remainderUnsigned(x, y), true);
                }
                return new Value(div ? x / y : x % y, false);
            }
            default -> throw error("unexpected '" + op.text + "' in preprocessor expression", op);
        }
    }

    // An integer constant (6.4.4.2): base by prefix, digit separators
    // dropped, unsigned by a u suffix or by not fitting a signed value.
    private Value number(Token t) {
        String text = t.text.replace("'", "");
        if (TokenConversion.classifyPpNumber(text).orElse(TokenType.UNKNOWN) != TokenType.INTEGER_CONSTANT) {
            throw error("'" + t.text + "' is not an integer constant in a preprocessor expression", t);
        }
        int end = text.length();
        boolean unsignedSuffix = false;
        while (end > 0 && "uUlL".indexOf(text.charAt(end - 1)) >= 0) {
            char c = text.charAt(end - 1);
            if (c == 'u' || c == 'U') {
                unsignedSuffix = true;
            }
            end--;
        }
        String digits = text.substring(0, end);
        int radix = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            digits = digits.substring(2);
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            digits = digits.substring(2);
        } else if (digits.length() > 1 && digits.charAt(0) == '0') {
            radix = 8;
            digits = digits.substring(1);
        }
        long bits;
        try {
            bits = Long.parseUnsignedLong(digits, radix);
        } catch (NumberFormatException e) {
            throw error("integer constant '" + t.text + "' is too large", t);
        }
        boolean unsigned = unsignedSuffix || bits < 0;
        return new Value(bits, unsigned);
    }

    // A character constant: the prefix and quotes stripped, escapes decoded;
    // a multi-character constant is its characters as big-endian bytes.
    private long character(Token t) {
        String text = t.text;
        int open = text.indexOf('\'');
        String body = text.substring(open + 1, text.length() - 1);
        long value = 0;
        int i = 0;
        int count = 0;
        while (i < body.length()) {
            char c = body.charAt(i++);
            long unit;
            if (c != '\\') {
                unit = c;
            } else {
                char e = body.charAt(i++);
                switch (e) {
                    case 'n' -> unit = '\n';
                    case 't' -> unit = '\t';
                    case 'r' -> unit = '\r';
                    case 'a' -> unit = 7;
                    case 'b' -> unit = '\b';
                    case 'f' -> unit = '\f';
                    case 'v' -> unit = 11;
                    case 'e' -> unit = 27;
                    case '\\', '\'', '"', '?' -> unit = e;
                    case 'x' -> {
                        int start = i;
                        while (i < body.length() && Character.digit(body.charAt(i), 16) >= 0) {
                            i++;
                        }
                        unit = Long.parseLong(body.substring(start, i), 16);
                    }
                    default -> {
                        if (e < '0' || e > '7') {
                            throw error("unknown escape '\\" + e + "' in character constant", t);
                        }
                        int start = i - 1;
                        while (i < body.length() && i - start < 3 && body.charAt(i) >= '0' && body.charAt(i) <= '7') {
                            i++;
                        }
                        unit = Long.parseLong(body.substring(start, i), 8);
                    }
                }
            }
            if (open == 0) {
                unit = (byte) unit;   // a plain char constant has type int and char is signed here
            }
            value = count == 0 ? unit : (value << 8) | (unit & 0xff);
            count++;
        }
        return value;
    }
}
