package org.jbm.mycc.cc.backend.arch.x86_64;

import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.RegClass;
import org.jbm.mycc.cc.backend.lower.tac.StructDef;
import org.jbm.mycc.cc.backend.lower.tac.Type;
import java.util.ArrayList;
import java.util.List;

/** The SysV calling convention's fixed facts: the argument registers and the register names by width. */
public final class X86Abi {

    private X86Abi() {
    }

    /** Integer and pointer arguments, in order. */
    public static final List<String> INT_ARGS = List.of("rdi", "rsi", "rdx", "rcx", "r8", "r9");

    /** Floating arguments, in order. */
    public static final List<String> FLOAT_ARGS = List.of("xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7");

    /** The name of a 64-bit register's part of {@code width} bits: rax, eax, ax, al; r8, r8d, r8w, r8b. */
    public static String part(String reg, int width) {
        if (width == 64) {
            return reg;
        }
        if (reg.startsWith("r") && Character.isDigit(reg.charAt(1))) {
            return reg + switch (width) {
                case 32 -> "d";
                case 16 -> "w";
                default -> "b";
            };
        }
        String base = reg.substring(1);   // "ax" of "rax", "di" of "rdi"
        return switch (width) {
            case 32 -> "e" + base;
            case 16 -> base;
            default -> base.endsWith("x") ? base.charAt(0) + "l" : base + "l";   // al, dl, sil, dil
        };
    }

    /** How an aggregate travels (SysV 3.2.3): in memory, or as one or two eightbytes each of a class. */
    public record Passing(boolean memory, List<RegClass> eightbytes) {
        public int ints() {
            return (int) eightbytes.stream().filter(c -> c == RegClass.INT).count();
        }

        public int floats() {
            return (int) eightbytes.stream().filter(c -> c == RegClass.FLOAT).count();
        }
    }

    /**
     * An aggregate's classification: larger than 16 bytes or holding an
     * x87 value it goes in memory; otherwise each eightbyte is INT when
     * any integer or pointer lies in it, else FLOAT.
     */
    public static Passing classify(Module module, Type t) {
        long size = module.sizeOf(t);
        if (size > 16 || size == 0) {
            return new Passing(true, List.of());
        }
        var fields = new ArrayList<Field>();
        flatten(module, t, 0, fields);
        int count = (int) ((size + 7) / 8);
        var classes = new ArrayList<RegClass>();
        for (int k = 0; k < count; k++) {
            RegClass klass = null;
            for (Field f : fields) {
                if (f.offset() / 8 != k && (f.offset() + f.size() - 1) / 8 != k) {
                    continue;
                }
                if (f.type() instanceof Type.Float x && x.width() > 64) {
                    return new Passing(true, List.of());
                }
                RegClass c = f.type() instanceof Type.Float ? RegClass.FLOAT : RegClass.INT;
                klass = klass == null || c == RegClass.INT ? c : klass;
            }
            classes.add(klass == null ? RegClass.INT : klass);
        }
        return new Passing(false, classes);
    }

    private record Field(Type type, long offset, long size) {
    }

    private static void flatten(Module module, Type t, long base, List<Field> out) {
        if (t instanceof Type.Struct s) {
            for (StructDef.Member m : module.struct(s.name()).members()) {
                flatten(module, m.type(), base + m.offset(), out);
            }
        } else if (t instanceof Type.Array a) {
            long elementSize = module.sizeOf(a.element());
            for (long i = 0; i < a.count(); i++) {
                flatten(module, a.element(), base + i * elementSize, out);
            }
        } else {
            out.add(new Field(t, base, module.sizeOf(t)));
        }
    }

    /** The mnemonic suffix for a width. */
    public static String suffix(int width) {
        return switch (width) {
            case 8 -> "b";
            case 16 -> "w";
            case 32 -> "l";
            default -> "q";
        };
    }
}
