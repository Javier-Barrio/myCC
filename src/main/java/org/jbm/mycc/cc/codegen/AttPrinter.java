package org.jbm.mycc.cc.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * The assembly IR in AT&T syntax for the GNU assembler: registers with
 * {@code %}, immediates with {@code $}, memory as {@code disp(base,
 * index, scale)}, source before destination as the emitter ordered
 * the operands.
 */
public final class AttPrinter {

    private AttPrinter() {
    }

    public static String print(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            sb.append(line(item)).append('\n');
        }
        return sb.toString();
    }

    static String line(Item item) {
        if (item instanceof Item.Insn i) {
            List<String> ops = new ArrayList<>();
            for (Operand o : i.operands()) {
                ops.add(target(i.mnemonic(), o));
            }
            String mnemonic = switch (i.mnemonic()) {
                case "icall" -> "call";
                case "ijmp" -> "jmp";
                default -> i.mnemonic();
            };
            return ops.isEmpty() ? "  " + mnemonic : "  " + mnemonic + " " + String.join(", ", ops);
        }
        if (item instanceof Item.Label l) {
            return l.name() + ":";
        }
        if (item instanceof Item.Section s) {
            return "  " + s.name();
        }
        if (item instanceof Item.Global g) {
            return "  .globl " + g.name();
        }
        if (item instanceof Item.Align a) {
            return "  .balign " + a.bytes();
        }
        if (item instanceof Item.Bytes b) {
            List<String> values = new ArrayList<>();
            for (byte x : b.bytes()) {
                values.add(Integer.toString(x & 0xff));
            }
            return "  .byte " + String.join(", ", values);
        }
        if (item instanceof Item.Address a) {
            return "  .quad " + symbol(a.symbol());
        }
        if (item instanceof Item.Word w) {
            return "  .quad " + w.value() + "   # " + w.comment();
        }
        if (item instanceof Item.Zero z) {
            return "  .zero " + z.bytes();
        }
        if (item instanceof Item.Comment c) {
            return "  # " + c.text();
        }
        Item.Note n = (Item.Note) item;
        return "# " + n.text();
    }

    // The IR's `icall` and `ijmp` are AT&T's `call *target` and `jmp *target`.
    private static String target(String mnemonic, Operand o) {
        boolean indirect = mnemonic.equals("icall") || mnemonic.equals("ijmp");
        return indirect ? "*" + operand(o) : operand(o);
    }

    static String operand(Operand o) {
        if (o instanceof Operand.Reg r) {
            return "%" + r.name();
        }
        if (o instanceof Operand.Imm i) {
            return "$" + i.value();
        }
        if (o instanceof Operand.Mem m) {
            String disp = m.displacement() == 0 ? "" : Long.toString(m.displacement());
            String inside = m.index() == null ? "%" + m.base() : "%" + m.base() + ", %" + m.index() + ", " + m.scale();
            return disp + "(" + inside + ")";
        }
        if (o instanceof Operand.Sym s) {
            return symbol(s);
        }
        Operand.RipRel r = (Operand.RipRel) o;
        return r.reloc() == Operand.Reloc.GOT ? r.name() + "@GOTPCREL(%rip)" : r.name() + "(%rip)";
    }

    private static String symbol(Operand.Sym s) {
        String addend = s.addend() == 0 ? "" : s.addend() > 0 ? "+" + s.addend() : Long.toString(s.addend());
        return s.name() + addend + (s.reloc() == Operand.Reloc.PLT ? "@PLT" : "");
    }
}
