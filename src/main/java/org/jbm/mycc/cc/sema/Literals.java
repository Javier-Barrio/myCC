package org.jbm.mycc.cc.sema;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.cpp.CppTokenizer.TokenType;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Types;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes constants (C2y 6.4.5): the parser keeps their spelling and this
 * turns it into a value and a type. Integer constants get the first type
 * of 6.4.5.2p6's list that holds them; character constants are decoded
 * with their escapes and typed by their prefix; floating constants take
 * their type from the suffix; string literals are concatenated (6.4.5p5)
 * and encoded into the code units of their element type.
 */
final class Literals {

    private final Types types;

    Literals(@NonNull Types types) {
        this.types = types;
    }

    /** The code units of a string literal, terminating null included, and their type. */
    record StringValue(CType elementType, int[] units) {
    }

    /** The text of a string value, for diagnostics; the terminating null is dropped. */
    static String text(StringValue v) {
        int n = v.units().length - 1;
        if (v.elementType() instanceof CType.Int i && i.rank() == CType.Int.Rank.CHAR) {
            byte[] bytes = new byte[n];
            for (int k = 0; k < n; k++) bytes[k] = (byte) v.units()[k];
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (v.elementType() instanceof CType.Int i && i.rank() == CType.Int.Rank.SHORT) {
            char[] chars = new char[n];
            for (int k = 0; k < n; k++) chars[k] = (char) v.units()[k];
            return new String(chars);
        }
        return new String(v.units(), 0, n);
    }

    // ---- dispatch --------------------------------------------------------------------

    TExpr.Constant constant(@NonNull Token t) {
        return switch (t.type) {
            case INTEGER_CONSTANT -> integer(t);
            case FLOATING_CONSTANT -> floating(t);
            case CHARACTER_LITERAL -> character(t);
            case KEYWORD -> switch (t.text) {
                case "true" -> new TExpr.IntConst(1, types.bool_(), t);
                case "false" -> new TExpr.IntConst(0, types.bool_(), t);
                case "nullptr" -> new TExpr.NullptrConst(types.nullptrT(), t);
                default -> throw new SemaException("'" + t.text + "' is not supported yet", t);
            };
            default -> throw new IllegalArgumentException(t.type + " is not a constant");
        };
    }

    // ---- integer constants (6.4.5.2) ------------------------------------------------------

    private static final java.util.regex.Pattern INTEGER_SUFFIX = java.util.regex.Pattern.compile("(?i)(?:u|ll|l|wb)*$");

    TExpr.IntConst integer(Token t) {
        String text = t.text.replace("'", "");
        String lower = text.toLowerCase();
        int base = 10;
        String digits = text;
        if (lower.startsWith("0x")) {
            base = 16;
            digits = text.substring(2);
        } else if (lower.startsWith("0b")) {
            base = 2;
            digits = text.substring(2);
        } else if (lower.startsWith("0o")) {
            base = 8;
            digits = text.substring(2);
        } else if (text.length() > 1 && text.charAt(0) == '0') {
            base = 8;
            digits = text.substring(1);
        }
        // The suffix is made of u, l, ll and wb, so a trailing b or B on
        // its own is a hex digit: 0xBB is 187.
        java.util.regex.Matcher m = INTEGER_SUFFIX.matcher(digits);
        int end = m.find() ? m.start() : digits.length();
        String suffix = digits.substring(end).toLowerCase();
        digits = digits.substring(0, end);
        if (digits.isEmpty()) digits = "0";

        boolean unsigned = suffix.contains("u");
        boolean isLL = suffix.contains("ll");
        boolean isL = !isLL && suffix.contains("l");

        BigInteger value = new BigInteger(digits, base);
        if (value.bitLength() > 64) throw new SemaException("integer constant is too large", t);

        // wb: a _BitInt just wide enough for the value (6.4.5.2p6), with a
        // sign bit unless unsigned.
        if (suffix.contains("wb")) {
            int width = unsigned ? Math.max(1, value.bitLength()) : Math.max(2, value.bitLength() + 1);
            if (width > 64) throw new SemaException("bit-precise constants wider than 64 bits are not supported", t);
            return new TExpr.IntConst(value.longValue(), types.bitInt(width, unsigned), t);
        }

        // 6.4.5.2p6: the list of candidate types by suffix, with the
        // unsigned alternatives only for non-decimal constants.
        boolean decimal = base == 10;
        List<CType.Int> candidates = new ArrayList<>();
        if (!unsigned && !isL && !isLL) {
            candidates.add(types.int_());
            if (!decimal) candidates.add(types.uint());
            candidates.add(types.long_());
            if (!decimal) candidates.add(types.ulong());
            candidates.add(types.llong());
            if (!decimal) candidates.add(types.ullong());
        } else if (unsigned && !isL && !isLL) {
            candidates.add(types.uint());
            candidates.add(types.ulong());
            candidates.add(types.ullong());
        } else if (!unsigned && isL) {
            candidates.add(types.long_());
            if (!decimal) candidates.add(types.ulong());
            candidates.add(types.llong());
            if (!decimal) candidates.add(types.ullong());
        } else if (unsigned && isL) {
            candidates.add(types.ulong());
            candidates.add(types.ullong());
        } else if (!unsigned) {
            candidates.add(types.llong());
            if (!decimal) candidates.add(types.ullong());
        } else {
            candidates.add(types.ullong());
        }
        for (CType.Int c : candidates) {
            int bits = types.isSigned(c) ? types.width(c) - 1 : types.width(c);
            if (value.bitLength() <= bits) return new TExpr.IntConst(value.longValue(), c, t);
        }
        throw new SemaException("integer constant is too large for its type", t);
    }

    // ---- floating constants (6.4.5.3) ------------------------------------------------------

    TExpr.FloatConst floating(Token t) {
        String text = t.text.replace("'", "");
        int end = text.length();
        while (end > 0 && "fFlLdDiIjJ".indexOf(text.charAt(end - 1)) >= 0) {
            // A hex float's mantissa can end in d or f; the exponent is mandatory
            // there, so a suffix letter is one that follows the exponent digits.
            if (text.toLowerCase().startsWith("0x") && text.lastIndexOf('p') < 0 && text.lastIndexOf('P') < 0) break;
            end--;
        }
        String suffix = text.substring(end).toLowerCase();
        String number = text.substring(0, end);
        if (suffix.contains("i") || suffix.contains("j")) {
            throw new SemaException("complex constants are not supported yet", t);
        }
        if (suffix.startsWith("d")) throw new SemaException("decimal floating constants are not supported yet", t);
        CType.Float type = switch (suffix) {
            case "" -> types.double_();
            case "f" -> types.float_();
            case "l" -> types.longDouble();
            default -> throw new SemaException("invalid floating suffix '" + suffix + "'", t);
        };
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw new SemaException("invalid floating constant", t);
        }
        if (type == types.float_()) value = (float) value;
        return new TExpr.FloatConst(value, type, t);
    }

    // ---- character constants (6.4.5.4) -------------------------------------------------------

    TExpr.IntConst character(Token t) {
        int quote = t.text.indexOf('\'');
        String prefix = t.text.substring(0, quote);
        String body = t.text.substring(quote + 1, t.text.length() - 1);
        List<Unit> units = decode(body, t);
        if (units.size() != 1) {
            throw new SemaException(units.isEmpty() ? "empty character constant"
                    : "multi-character constants are not supported", t);
        }
        Unit u = units.get(0);
        return switch (prefix) {
            case "" -> {
                int v = u.value;
                if (u.isCodePoint && v > 0x7F) throw new SemaException("character cannot be encoded as a single byte", t);
                if (v > 0xFF) throw new SemaException("character too large for its type", t);
                // The value is the char converted to int: sign-extended on
                // targets where char is signed (6.4.5.4p10).
                yield new TExpr.IntConst(types.isSigned(types.char_()) ? (byte) v : v, types.int_(), t);
            }
            case "u8" -> {
                if (u.value > 0x7F) throw new SemaException("character cannot be encoded as a single UTF-8 byte", t);
                yield new TExpr.IntConst(u.value, types.uchar(), t);
            }
            case "u" -> {
                if (u.value > 0xFFFF) throw new SemaException("character cannot be encoded as a single UTF-16 unit", t);
                yield new TExpr.IntConst(u.value, types.ushort(), t);
            }
            case "U" -> new TExpr.IntConst(u.value & 0xFFFFFFFFL, types.uint(), t);
            case "L" -> new TExpr.IntConst(u.value, types.wcharT(), t);
            default -> throw new IllegalStateException(prefix);
        };
    }

    // ---- string literals (6.4.5) ------------------------------------------------------------------

    StringValue string(@NonNull List<Token> parts) {
        String prefix = "";
        var units = new ArrayList<Unit>();
        for (Token part : parts) {
            int quote = part.text.indexOf('"');
            String p = part.text.substring(0, quote);
            boolean raw = p.endsWith("R");
            if (raw) p = p.substring(0, p.length() - 1);
            if (!p.isEmpty()) {
                if (!prefix.isEmpty() && !prefix.equals(p)) {
                    throw new SemaException("cannot concatenate " + prefix + " and " + p + " string literals", part);
                }
                prefix = p;
            }
            if (raw) {
                int open = part.text.indexOf('(');
                String delimiter = part.text.substring(quote + 1, open);
                String body = part.text.substring(open + 1, part.text.length() - 2 - delimiter.length());
                body.codePoints().forEach(cp -> units.add(new Unit(cp, true)));
            } else {
                units.addAll(decode(part.text.substring(quote + 1, part.text.length() - 1), part));
            }
        }
        Token at = parts.get(0);
        return switch (prefix) {
            case "" -> new StringValue(types.char_(), encodeUtf8(units, at));
            case "u8" -> new StringValue(types.uchar(), encodeUtf8(units, at));
            case "u" -> new StringValue(types.ushort(), encodeUtf16(units, at));
            case "U" -> new StringValue(types.uint(), encode32(units));
            case "L" -> new StringValue(types.wcharT(), encode32(units));
            default -> throw new IllegalStateException(prefix);
        };
    }

    // A decoded unit: a code point from a source character or a universal
    // character name, or a raw value from an octal or hex escape, which is
    // stored as one code unit rather than encoded.
    private record Unit(int value, boolean isCodePoint) {
    }

    private static List<Unit> decode(String body, Token at) {
        var out = new ArrayList<Unit>();
        int[] cps = body.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] != '\\') {
                out.add(new Unit(cps[i], true));
                continue;
            }
            if (++i >= cps.length) throw new SemaException("incomplete escape sequence", at);
            int c = cps[i];
            switch (c) {
                case '\'', '"', '?', '\\' -> out.add(new Unit(c, true));
                case 'a' -> out.add(new Unit(7, true));
                case 'b' -> out.add(new Unit(8, true));
                case 'f' -> out.add(new Unit(12, true));
                case 'n' -> out.add(new Unit(10, true));
                case 'r' -> out.add(new Unit(13, true));
                case 't' -> out.add(new Unit(9, true));
                case 'v' -> out.add(new Unit(11, true));
                case 'x' -> {
                    int start = ++i;
                    long v = 0;
                    while (i < cps.length && Character.digit(cps[i], 16) >= 0) {
                        v = v * 16 + Character.digit(cps[i], 16);
                        if (v > 0xFFFFFFFFL) throw new SemaException("hex escape sequence out of range", at);
                        i++;
                    }
                    if (i == start) throw new SemaException("\\x used with no following hex digits", at);
                    i--;
                    out.add(new Unit((int) v, false));
                }
                case 'u', 'U' -> {
                    int n = c == 'u' ? 4 : 8;
                    if (i + n >= cps.length) throw new SemaException("incomplete universal character name", at);
                    int v = 0;
                    for (int k = 1; k <= n; k++) {
                        int d = Character.digit(cps[i + k], 16);
                        if (d < 0) throw new SemaException("incomplete universal character name", at);
                        v = v * 16 + d;
                    }
                    i += n;
                    out.add(new Unit(v, true));
                }
                default -> {
                    if (c >= '0' && c <= '7') {
                        int v = 0, k = 0;
                        while (k < 3 && i < cps.length && cps[i] >= '0' && cps[i] <= '7') {
                            v = v * 8 + (cps[i] - '0');
                            i++;
                            k++;
                        }
                        i--;
                        out.add(new Unit(v, false));
                    } else {
                        throw new SemaException("unknown escape sequence '\\" + new String(Character.toChars(c)) + "'", at);
                    }
                }
            }
        }
        return out;
    }

    private static int[] encodeUtf8(List<Unit> units, Token at) {
        var bytes = new ArrayList<Integer>();
        for (Unit u : units) {
            if (!u.isCodePoint) {
                if (u.value > 0xFF) throw new SemaException("escape sequence out of range for a byte", at);
                bytes.add(u.value);
            } else {
                for (byte b : new String(Character.toChars(u.value)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    bytes.add(b & 0xFF);
                }
            }
        }
        bytes.add(0);
        return bytes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] encodeUtf16(List<Unit> units, Token at) {
        var out = new ArrayList<Integer>();
        for (Unit u : units) {
            if (!u.isCodePoint) {
                if (u.value > 0xFFFF) throw new SemaException("escape sequence out of range for char16_t", at);
                out.add(u.value);
            } else {
                for (char ch : Character.toChars(u.value)) out.add((int) ch);
            }
        }
        out.add(0);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] encode32(List<Unit> units) {
        int[] out = new int[units.size() + 1];
        for (int i = 0; i < units.size(); i++) out[i] = units.get(i).value;
        return out;
    }

    static boolean isConstantToken(Token t) {
        return t.type == TokenType.INTEGER_CONSTANT || t.type == TokenType.FLOATING_CONSTANT
                || t.type == TokenType.CHARACTER_LITERAL || t.type == TokenType.KEYWORD;
    }
}
