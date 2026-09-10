package org.jbm.repl;

import org.jbm.cc.sema.types.CType;
import org.jbm.cc.sema.types.Layout;
import org.jbm.cc.sema.types.Types;
import org.jbm.repl.vm.Memory;
import org.jbm.repl.vm.VM;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Formats an object in the VM's memory by its C type: integers decimal,
 * {@code char} as {@code 'a' (97)}, {@code bool} as {@code true}, floating
 * values shortest, pointers hex with the string behind a {@code char *}
 * and the name behind a pointer to function, arrays as {@code {1, 2}},
 * structs as {@code {.x = 1}}, nested past a depth limit as {@code ...}.
 */
public final class ValuePrinter {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_ELEMENTS = 32;
    private static final int MAX_STRING = 64;

    private final VM vm;
    private final Types types;

    public ValuePrinter(VM vm, Types types) {
        this.vm = vm;
        this.types = types;
    }

    /** The named global's value. */
    public String print(String name, CType type) {
        return format(vm.addressOf(name), type, 0);
    }

    /** The object of that type at an address. */
    public String printAt(long address, CType type) {
        return format(address, type, 0);
    }

    private String format(long address, CType type, int depth) {
        Memory memory = vm.memory();
        if (type instanceof CType.Int i) {
            long v = memory.loadInt(address, types.width(type), types.isSigned(type));
            return integer(v, i, types.isSigned(type));
        }
        if (type instanceof CType.BitInt) {
            long v = memory.loadInt(address, types.width(type), types.isSigned(type));
            return types.isSigned(type) ? Long.toString(v) : Long.toUnsignedString(v);
        }
        if (type instanceof CType.Float) {
            return floating(memory.loadFloat(address, (int) types.size(type) * 8));
        }
        if (type instanceof CType.Pointer p) {
            return pointer(memory.loadInt(address, (int) types.size(type) * 8, false), p);
        }
        if (type instanceof CType.Nullptr) {
            return "nullptr";
        }
        if (type instanceof CType.Array a) {
            return array(address, a, depth);
        }
        if (type instanceof CType.Record r) {
            return record(address, r, depth);
        }
        return "?";
    }

    private static String integer(long v, CType.Int i, boolean signed) {
        if (i.rank() == CType.Int.Rank.BOOL) {
            return v != 0 ? "true" : "false";
        }
        if (i.rank() == CType.Int.Rank.CHAR) {
            String shown = v >= 32 && v < 127 ? "'" + (char) v + "'" : "'\\x" + Long.toHexString(v & 0xff) + "'";
            return shown + " (" + v + ")";
        }
        return signed ? Long.toString(v) : Long.toUnsignedString(v);
    }

    // The shortest text that reads back to the same double, as %g would
    // shape it: no exponent for ordinary magnitudes, no trailing zeros.
    static String floating(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.format("%.1f", d);
        }
        String s = Double.toString(d);
        if (s.contains("E")) {
            String mantissa = s.substring(0, s.indexOf('E'));
            int exponent = Integer.parseInt(s.substring(s.indexOf('E') + 1));
            if (mantissa.endsWith(".0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 2);
            }
            return mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        return s;
    }

    private String pointer(long v, CType.Pointer p) {
        if (v == 0) {
            return "nullptr";
        }
        String hex = "0x" + Long.toHexString(v);
        Optional<String> function = vm.functionName(v);
        if (function.isPresent()) {
            return hex + " (" + function.get() + ")";
        }
        if (p.target() instanceof CType.Int c && c.rank() == CType.Int.Rank.CHAR) {
            Optional<String> text = string(v);
            if (text.isPresent()) {
                return hex + " " + text.get();
            }
        }
        return hex;
    }

    // The NUL-terminated string at the address if it is readable and printable.
    private Optional<String> string(long address) {
        Memory memory = vm.memory();
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < MAX_STRING; i++) {
            long a = address + i;
            if (a < Memory.NULL_PAGE || a >= memory.size()) {
                return Optional.empty();
            }
            long c = memory.loadInt(a, 8, false);
            if (c == 0) {
                return Optional.of(sb.append('"').toString());
            }
            if (c == '\n') {
                sb.append("\\n");
            } else if (c < 32 || c >= 127) {
                return Optional.empty();
            } else {
                sb.append((char) c);
            }
        }
        return Optional.of(sb.append("...\"").toString());
    }

    private String array(long address, CType.Array a, int depth) {
        if (a.size().isEmpty()) {
            return "{}";
        }
        long count = a.size().getAsLong();
        CType element = a.element();
        long elementSize = types.size(element);
        if (element instanceof CType.Int c && c.rank() == CType.Int.Rank.CHAR) {
            Optional<String> text = string(address);
            if (text.isPresent()) {
                return text.get();
            }
        }
        if (depth >= MAX_DEPTH) {
            return "{...}";
        }
        List<String> items = new ArrayList<>();
        for (long i = 0; i < Math.min(count, MAX_ELEMENTS); i++) {
            items.add(format(address + i * elementSize, element, depth + 1));
        }
        if (count > MAX_ELEMENTS) {
            items.add("...");
        }
        return "{" + String.join(", ", items) + "}";
    }

    private String record(long address, CType.Record r, int depth) {
        Optional<Layout> layout = r.tag().layout();
        if (layout.isEmpty()) {
            return "{?}";
        }
        if (depth >= MAX_DEPTH) {
            return "{...}";
        }
        List<String> items = new ArrayList<>();
        for (Layout.Member m : layout.get().slots()) {
            String value;
            if (m.bits().isPresent()) {
                value = bitField(address + m.offset(), m);
            } else {
                value = format(address + m.offset(), m.type(), depth + 1);
            }
            String name = m.isAnonymous() ? "" : "." + m.name() + " = ";
            items.add(name + value);
            if (r.tag().keyword().equals("union")) {
                break;
            }
        }
        return "{" + String.join(", ", items) + "}";
    }

    private String bitField(long address, Layout.Member m) {
        Layout.BitField bits = m.bits().get();
        long unit = vm.memory().loadInt(address, types.width(m.type()), false);
        long v = (unit >>> bits.bitOffset()) & (bits.width() == 64 ? -1L : (1L << bits.width()) - 1);
        if (types.isSigned(m.type())) {
            int shift = 64 - bits.width();
            v = (v << shift) >> shift;
        }
        return integer(v, (CType.Int) m.type(), types.isSigned(m.type()));
    }
}
