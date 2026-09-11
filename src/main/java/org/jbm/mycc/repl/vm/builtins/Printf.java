package org.jbm.mycc.repl.vm.builtins;

import org.jbm.mycc.repl.vm.Builtin;
import org.jbm.mycc.repl.vm.Memory;
import org.jbm.mycc.repl.vm.VM;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code printf} on Java's {@link java.util.Formatter}: each C
 * conversion is translated to the Java one and formatted with the
 * argument converted to the Java type it expects. What differs from
 * Java is handled here: {@code %u} and the length modifiers (the
 * argument is narrowed or read unsigned), {@code %p}, {@code %s} read
 * from the arena, a precision on an integer, {@code %g} without
 * trailing zeros, and {@code nan}/{@code inf}.
 */
public final class Printf implements Builtin {

    @Override
    public VM.Value call(VM vm, List<VM.Value> args) {
        String text = format(vm.memory(), vm.memory().string(Libc.integer(args, 0)), args.subList(1, args.size()));
        vm.out().print(text);
        vm.out().flush();
        return new VM.IntValue(text.length());
    }

    // %[flags][width][.precision][length]conversion
    private static final Pattern SPEC = Pattern.compile(
            "%([-+ 0#]*)(\\*|\\d+)?(?:\\.(\\*|\\d*))?(hh|h|ll|l|j|z|t|L)?([diuoxXcspfFeEgG%])");

    public static String format(Memory memory, String fmt, List<VM.Value> args) {
        StringBuilder out = new StringBuilder();
        Matcher m = SPEC.matcher(fmt);
        int[] next = {0};
        int last = 0;
        while (m.find()) {
            out.append(fmt, last, m.start());
            last = m.end();
            char conv = m.group(5).charAt(0);
            if (conv == '%') {
                out.append('%');
                continue;
            }
            String flags = m.group(1);
            String width = m.group(2);
            String precision = m.group(3);
            if ("*".equals(width)) {
                long w = integer(args, next, fmt);
                if (w < 0) {
                    flags += "-";
                    w = -w;
                }
                width = Long.toString(w);
            }
            if ("*".equals(precision)) {
                long p = integer(args, next, fmt);
                precision = p < 0 ? null : Long.toString(p);
            } else if ("".equals(precision)) {
                precision = "0";
            }
            String length = m.group(4) == null ? "" : m.group(4);
            out.append(one(memory, conv, flags, width, precision, length, args, next, fmt));
        }
        out.append(fmt.substring(last));
        return out.toString();
    }

    private static String one(Memory memory, char conv, String flags, String width, String precision, String length,
                              List<VM.Value> args, int[] next, String fmt) {
        switch (conv) {
            case 'd', 'i' -> {
                long v = narrow(integer(args, next, fmt), length, true);
                return integer(Long.toString(Math.abs(v)), v < 0, flags, width, precision);
            }
            case 'u' -> {
                long v = narrow(integer(args, next, fmt), length, false);
                return integer(Long.toUnsignedString(v), false, flags, width, precision);
            }
            case 'o', 'x', 'X' -> {
                long v = narrow(integer(args, next, fmt), length, false);
                String digits = Long.toUnsignedString(v, conv == 'o' ? 8 : 16);
                if (conv == 'X') {
                    digits = digits.toUpperCase();
                }
                if (flags.contains("#") && v != 0) {
                    digits = (conv == 'o' ? "0" : conv == 'x' ? "0x" : "0X") + digits;
                }
                return integer(digits, false, flags.replace("#", ""), width, precision);
            }
            case 'c' -> {
                return java("%" + keep(flags, "-") + w(width) + "c", (char) (integer(args, next, fmt) & 0xff));
            }
            case 's' -> {
                return java("%" + keep(flags, "-") + w(width) + p(precision) + "s", memory.string(integer(args, next, fmt)));
            }
            case 'p' -> {
                return java("%" + keep(flags, "-") + w(width) + "s", "0x" + Long.toHexString(integer(args, next, fmt)));
            }
            default -> {
                return floating(floating(args, next, fmt), conv, flags, width, precision);
            }
        }
    }

    // An integer's digits with C's sign, precision (minimum digits) and
    // width, which Java's %d cannot combine with a precision.
    private static String integer(String digits, boolean negative, String flags, String width, String precision) {
        if (precision != null) {
            int p = Integer.parseInt(precision);
            if (p == 0 && digits.equals("0")) {
                digits = "";
            }
            while (digits.length() < p) {
                digits = "0" + digits;
            }
        }
        String sign = negative ? "-" : flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
        boolean zeros = flags.contains("0") && !flags.contains("-") && precision == null && width != null;
        if (zeros) {
            int w = Integer.parseInt(width);
            while (sign.length() + digits.length() < w) {
                digits = "0" + digits;
            }
        }
        return java("%" + keep(flags, "-") + w(width) + "s", sign + digits);
    }

    // Java's own e, f and g; C's F and G by upper-casing; %g without the
    // trailing zeros Java keeps; nan and inf spelled as C does.
    private static String floating(double v, char conv, String flags, String width, String precision) {
        String sign = v < 0 || (v == 0 && 1 / v < 0) ? "-" : flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
        String body;
        if (Double.isNaN(v)) {
            body = "nan";
        } else if (Double.isInfinite(v)) {
            body = "inf";
        } else {
            char javaConv = Character.toLowerCase(conv);
            String spec = "%" + (precision == null ? "" : "." + precision) + javaConv;
            body = String.format(Locale.ROOT, spec, Math.abs(v));
            if (javaConv == 'g' && !flags.contains("#")) {
                body = stripZeros(body);
            }
        }
        if (Character.isUpperCase(conv)) {
            body = body.toUpperCase();
        }
        boolean zeros = flags.contains("0") && !flags.contains("-") && width != null && !body.equals("nan") && !body.equals("inf");
        if (zeros) {
            int w = Integer.parseInt(width);
            while (sign.length() + body.length() < w) {
                body = "0" + body;
            }
        }
        return java("%" + keep(flags, "-") + w(width) + "s", sign + body);
    }

    // The mantissa without trailing zeros, the exponent as it is.
    private static String stripZeros(String s) {
        int e = s.indexOf('e');
        String mantissa = e < 0 ? s : s.substring(0, e);
        String exponent = e < 0 ? "" : s.substring(e);
        if (mantissa.indexOf('.') >= 0) {
            int end = mantissa.length();
            while (mantissa.charAt(end - 1) == '0') {
                end--;
            }
            if (mantissa.charAt(end - 1) == '.') {
                end--;
            }
            mantissa = mantissa.substring(0, end);
        }
        return mantissa + exponent;
    }

    private static String java(String spec, Object arg) {
        return String.format(Locale.ROOT, spec, arg);
    }

    private static String keep(String flags, String wanted) {
        return flags.contains(wanted) ? wanted : "";
    }

    private static String w(String width) {
        return width == null ? "" : width;
    }

    private static String p(String precision) {
        return precision == null ? "" : "." + precision;
    }

    // The argument at the width its length modifier names: int by default.
    private static long narrow(long v, String length, boolean signed) {
        int width = switch (length) {
            case "hh" -> 8;
            case "h" -> 16;
            case "l", "ll", "j", "z", "t", "L" -> 64;
            default -> 32;
        };
        if (width == 64) {
            return v;
        }
        int shift = 64 - width;
        return signed ? (v << shift) >> shift : (v << shift) >>> shift;
    }

    private static long integer(List<VM.Value> args, int[] next, String fmt) {
        VM.Value v = argument(args, next, fmt);
        if (v instanceof VM.IntValue i) {
            return i.value();
        }
        throw new IllegalStateException("printf: an integer conversion got a floating argument in \"" + fmt + "\"");
    }

    private static double floating(List<VM.Value> args, int[] next, String fmt) {
        VM.Value v = argument(args, next, fmt);
        if (v instanceof VM.FloatValue d) {
            return d.value();
        }
        throw new IllegalStateException("printf: a floating conversion got an integer argument in \"" + fmt + "\"");
    }

    private static VM.Value argument(List<VM.Value> args, int[] next, String fmt) {
        if (next[0] >= args.size()) {
            throw new IllegalStateException("printf: not enough arguments for the format \"" + fmt + "\"");
        }
        return args.get(next[0]++);
    }
}
