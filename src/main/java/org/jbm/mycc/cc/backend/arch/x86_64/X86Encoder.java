package org.jbm.mycc.cc.backend.arch.x86_64;

import org.jbm.mycc.cc.backend.codegen.Encoder;
import org.jbm.mycc.cc.backend.codegen.Item;
import org.jbm.mycc.cc.backend.codegen.Operand;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The x86-64 encoding of the instruction forms the emitter produces:
 * {@code [prefix] [REX] opcode [ModR/M] [SIB] [disp] [imm]}. The
 * choices among equivalent encodings are GNU as's, so the bytes match
 * what it assembles from the printed text.
 */
public final class X86Encoder implements Encoder {

    // ---- registers -----------------------------------------------------------------------------

    private record Reg(int number, int width, boolean xmm, boolean rexOnly) {
    }

    private static final String[] NAMES64 = {"rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi"};
    private static final String[] NAMES32 = {"eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi"};
    private static final String[] NAMES16 = {"ax", "cx", "dx", "bx", "sp", "bp", "si", "di"};
    private static final String[] NAMES8 = {"al", "cl", "dl", "bl", "spl", "bpl", "sil", "dil"};

    static Reg register(String name) {
        if (name.startsWith("xmm")) {
            return new Reg(Integer.parseInt(name.substring(3)), 128, true, false);
        }
        for (int k = 0; k < 8; k++) {
            if (NAMES64[k].equals(name)) {
                return new Reg(k, 64, false, false);
            }
            if (NAMES32[k].equals(name)) {
                return new Reg(k, 32, false, false);
            }
            if (NAMES16[k].equals(name)) {
                return new Reg(k, 16, false, false);
            }
            if (NAMES8[k].equals(name)) {
                return new Reg(k, 8, false, k >= 4);
            }
        }
        if (name.startsWith("r") && Character.isDigit(name.charAt(1))) {
            String digits = name.replaceAll("[^0-9]", "");
            int n = Integer.parseInt(digits);
            int width = name.endsWith("d") ? 32 : name.endsWith("w") ? 16 : name.endsWith("b") ? 8 : 64;
            return new Reg(n, width, false, false);
        }
        throw new IllegalArgumentException("unknown register " + name);
    }

    // ---- one instruction -----------------------------------------------------------------------

    // The encoding being built: prefixes, the REX fields, then everything after.
    private static final class Out {
        final List<Byte> prefixes = new ArrayList<>();
        boolean rexW;
        boolean rexR;
        boolean rexX;
        boolean rexB;
        boolean rexNeeded;
        final ByteArrayOutputStream rest = new ByteArrayOutputStream();
        final List<Fixup> fixups = new ArrayList<>();

        void op(int... bytes) {
            for (int b : bytes) {
                rest.write(b);
            }
        }

        void imm(long value, int size) {
            for (int k = 0; k < size; k++) {
                rest.write((int) (value >> (8 * k)));
            }
        }

        Encoded finish() {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte p : prefixes) {
                all.write(p);
            }
            int prefixLength = all.size();
            if (rexW || rexR || rexX || rexB || rexNeeded) {
                all.write(0x40 | (rexW ? 8 : 0) | (rexR ? 4 : 0) | (rexX ? 2 : 0) | (rexB ? 1 : 0));
            }
            int shift = all.size();
            byte[] tail = rest.toByteArray();
            all.write(tail, 0, tail.length);
            List<Fixup> moved = new ArrayList<>();
            for (Fixup f : fixups) {
                moved.add(new Fixup(f.offset() + shift, f.fix(), f.symbol(), f.addend()));
            }
            return new Encoded(all.toByteArray(), moved);
        }
    }

    private Out out;

    @Override
    public Encoded encode(Item.Insn insn) {
        out = new Out();
        String m = insn.mnemonic();
        List<Operand> ops = insn.operands();
        switch (m) {
            case "pushq" -> pushq(reg(ops, 0));
            case "movq", "movl", "movw", "movb" -> mov(m, ops);
            case "movabsq" -> {
                Reg r = reg(ops, 1);
                out.rexW = true;
                out.rexB = r.number() >= 8;
                out.op(0xB8 + (r.number() & 7));
                out.imm(imm(ops, 0), 8);
            }
            case "movslq" -> extension(ops, true, 0x63);
            case "movsbq" -> extension(ops, true, 0x0F, 0xBE);
            case "movswq" -> extension(ops, true, 0x0F, 0xBF);
            case "movzbq" -> extension(ops, true, 0x0F, 0xB6);
            case "movzwq" -> extension(ops, true, 0x0F, 0xB7);
            case "leaq" -> {
                out.rexW = true;
                out.op(0x8D);
                modrm(reg(ops, 1).number(), ops.get(0));
            }
            case "addq", "subq", "andq", "orq", "xorq", "cmpq", "addl", "subl", "andl", "orl", "xorl", "cmpl" -> alu(m, ops);
            case "andb", "orb" -> {
                out.op(m.equals("andb") ? 0x20 : 0x08);
                modrm(reg(ops, 0), ops.get(1));
            }
            case "testq" -> {
                out.rexW = true;
                out.op(0x85);
                modrm(reg(ops, 0), ops.get(1));
            }
            case "imulq" -> {
                out.rexW = true;
                out.op(0x0F, 0xAF);
                modrm(reg(ops, 1), ops.get(0));
            }
            case "shlq", "shrq", "sarq" -> shift(m, ops);
            case "btcq" -> {
                out.rexW = true;
                out.op(0x0F, 0xBA);
                modrm(7, ops.get(1));
                out.imm(imm(ops, 0), 1);
            }
            case "cqto" -> {
                out.rexW = true;
                out.op(0x99);
            }
            case "idivq", "divq" -> {
                out.rexW = true;
                out.op(0xF7);
                modrm(m.equals("idivq") ? 7 : 6, ops.get(0));
            }
            case "sete", "setne", "setl", "setle", "setb", "setbe", "seta", "setae", "setp", "setnp" -> {
                out.op(0x0F, SETCC.get(m));
                modrm(0, ops.get(0));
            }
            case "leave" -> out.op(0xC9);
            case "ret" -> out.op(0xC3);
            case "ud2" -> out.op(0x0F, 0x0B);
            case "rep movsb" -> out.op(0xF3, 0xA4);
            case "rep stosb" -> out.op(0xF3, 0xAA);
            case "call" -> {
                Operand.Sym s = (Operand.Sym) ops.get(0);
                out.op(0xE8);
                out.fixups.add(new Fixup(out.rest.size(), Fix.PLT32, s.name(), s.addend() - 4));
                out.imm(0, 4);
            }
            case "icall" -> {
                out.op(0xFF);
                modrm(2, ops.get(0));
            }
            case "ijmp" -> {
                out.op(0xFF);
                modrm(4, ops.get(0));
            }
            case "movsd", "movss" -> {
                out.prefixes.add((byte) (m.equals("movsd") ? 0xF2 : 0xF3));
                if (ops.get(1) instanceof Operand.Reg) {
                    out.op(0x0F, 0x10);
                    modrm(reg(ops, 1), ops.get(0));
                } else {
                    out.op(0x0F, 0x11);
                    modrm(reg(ops, 0), ops.get(1));
                }
            }
            case "cvtss2sd", "cvtsd2ss", "addsd", "subsd", "mulsd", "divsd", "ucomisd" -> {
                out.prefixes.add((byte) (m.equals("cvtss2sd") ? 0xF3 : m.equals("ucomisd") ? 0x66 : 0xF2));
                out.op(0x0F, SSE.get(m));
                modrm(reg(ops, 1), ops.get(0));
            }
            case "cvtsi2sdq" -> {
                out.prefixes.add((byte) 0xF2);
                out.rexW = true;
                out.op(0x0F, 0x2A);
                modrm(reg(ops, 1), ops.get(0));
            }
            case "cvttsd2siq" -> {
                out.prefixes.add((byte) 0xF2);
                out.rexW = true;
                out.op(0x0F, 0x2C);
                modrm(reg(ops, 1), ops.get(0));
            }
            default -> throw new IllegalArgumentException("cannot encode " + m + " " + ops);
        }
        return out.finish();
    }

    private static final Map<String, Integer> ALU = Map.of("add", 0, "or", 1, "and", 2, "sub", 3, "xor", 4, "cmp", 5);

    private static final Map<String, Integer> SETCC = Map.of(
            "sete", 0x94, "setne", 0x95, "setl", 0x9C, "setle", 0x9E, "setb", 0x92,
            "setbe", 0x96, "seta", 0x97, "setae", 0x93, "setp", 0x9A, "setnp", 0x9B);

    private static final Map<String, Integer> SSE = Map.of(
            "cvtss2sd", 0x5A, "cvtsd2ss", 0x5A, "addsd", 0x58, "subsd", 0x5C, "mulsd", 0x59, "divsd", 0x5E, "ucomisd", 0x2E);

    private static final Map<String, int[]> JUMPS = Map.of(
            "jmp", new int[] {0xEB, 0xE9}, "je", new int[] {0x74, 0x84}, "jne", new int[] {0x75, 0x85},
            "js", new int[] {0x78, 0x88}, "jae", new int[] {0x73, 0x83});

    @Override
    public Optional<Jump> jump(Item.Insn insn) {
        int[] codes = JUMPS.get(insn.mnemonic());
        if (codes == null || !(insn.operands().get(0) instanceof Operand.Sym s)) {
            return Optional.empty();
        }
        byte[] longOpcode = insn.mnemonic().equals("jmp") ? new byte[] {(byte) codes[1]} : new byte[] {0x0F, (byte) codes[1]};
        return Optional.of(new Jump(new byte[] {(byte) codes[0]}, longOpcode, s.name()));
    }

    // ---- the forms -----------------------------------------------------------------------------

    private void pushq(Reg r) {
        out.rexB = r.number() >= 8;
        out.op(0x50 + (r.number() & 7));
    }

    // mov by suffix: register to register or memory (89/88), memory to
    // register (8B/8A), immediate to register (C7 for 64 bits, B8+r for 32).
    private void mov(String m, List<Operand> ops) {
        int width = switch (m) {
            case "movq" -> 64;
            case "movl" -> 32;
            case "movw" -> 16;
            default -> 8;
        };
        size(width);
        Operand src = ops.get(0);
        Operand dst = ops.get(1);
        if (src instanceof Operand.Imm i) {
            Reg r = (Reg) reg(ops, 1);
            if (width == 64) {
                out.op(0xC7);
                modrm(0, dst);
                out.imm(i.value(), 4);
            } else {
                out.rexB = r.number() >= 8;
                out.op((width == 8 ? 0xB0 : 0xB8) + (r.number() & 7));
                out.imm(i.value(), width == 8 ? 1 : width == 16 ? 2 : 4);
            }
        } else if (src instanceof Operand.Reg) {
            // as spells register to register with the 89 form too
            out.op(width == 8 ? 0x88 : 0x89);
            modrm(reg(ops, 0), dst);
        } else {
            out.op(width == 8 ? 0x8A : 0x8B);
            modrm(reg(ops, 1), src);
        }
    }

    // movs/movz: the source register or memory in r/m, the destination in reg.
    private void extension(List<Operand> ops, boolean wide, int... opcode) {
        out.rexW = wide;
        out.op(opcode);
        modrm(reg(ops, 1), ops.get(0));
    }

    // add sub and or xor cmp: register to register or memory (01 29 21 09
    // 31 39), an immediate as 83 /n ib when it fits a byte, else 81 /n id,
    // or the accumulator's own form 05 2D 25 0D 35 3D.
    private void alu(String m, List<Operand> ops) {
        size(m.endsWith("q") ? 64 : 32);
        int index = ALU.get(m.substring(0, m.length() - 1));
        int[] regForm = {0x01, 0x09, 0x21, 0x29, 0x31, 0x39};
        int[] accForm = {0x05, 0x0D, 0x25, 0x2D, 0x35, 0x3D};
        int[] slash = {0, 1, 4, 5, 6, 7};
        Operand src = ops.get(0);
        Operand dst = ops.get(1);
        if (src instanceof Operand.Imm i) {
            if (i.value() == (byte) i.value()) {
                out.op(0x83);
                modrm(slash[index], dst);
                out.imm(i.value(), 1);
            } else if (dst instanceof Operand.Reg r && register(r.name()).number() == 0) {
                out.op(accForm[index]);
                out.imm(i.value(), 4);
            } else {
                out.op(0x81);
                modrm(slash[index], dst);
                out.imm(i.value(), 4);
            }
        } else {
            out.op(regForm[index]);
            modrm(reg(ops, 0), dst);
        }
    }

    // shl shr sar by %cl (D3 /n), by 1 (D1 /n), by an immediate (C1 /n ib).
    private void shift(String m, List<Operand> ops) {
        out.rexW = true;
        int slash = switch (m) {
            case "shlq" -> 4;
            case "shrq" -> 5;
            default -> 7;
        };
        if (ops.get(0) instanceof Operand.Imm i) {
            if (i.value() == 1) {
                out.op(0xD1);
                modrm(slash, ops.get(1));
            } else {
                out.op(0xC1);
                modrm(slash, ops.get(1));
                out.imm(i.value(), 1);
            }
        } else {
            out.op(0xD3);
            modrm(slash, ops.get(1));
        }
    }

    // ---- operand pieces ------------------------------------------------------------------------

    private void size(int width) {
        if (width == 64) {
            out.rexW = true;
        } else if (width == 16) {
            out.prefixes.add((byte) 0x66);
        }
    }

    private static Reg reg(List<Operand> ops, int k) {
        return register(((Operand.Reg) ops.get(k)).name());
    }

    private static long imm(List<Operand> ops, int k) {
        return ((Operand.Imm) ops.get(k)).value();
    }

    private void modrm(Reg field, Operand rm) {
        out.rexR = field.number() >= 8;
        out.rexNeeded |= field.rexOnly();
        modrm(field.number() & 7, rm);
    }

    // ModR/M for a register or memory operand, with the SIB byte, the
    // displacement, or the rip-relative fixup it needs.
    private void modrm(int field, Operand rm) {
        if (rm instanceof Operand.Reg r) {
            Reg x = register(r.name());
            out.rexB = x.number() >= 8;
            out.rexNeeded |= x.rexOnly();
            out.op(0xC0 | (field << 3) | (x.number() & 7));
            return;
        }
        if (rm instanceof Operand.RipRel rip) {
            out.op((field << 3) | 5);
            Fix fix = rip.reloc() == Operand.Reloc.GOT ? Fix.GOTPCREL : Fix.PC32;
            out.fixups.add(new Fixup(out.rest.size(), fix, rip.name(), -4));
            out.imm(0, 4);
            return;
        }
        Operand.Mem m = (Operand.Mem) rm;
        Reg base = register(m.base());
        out.rexB = base.number() >= 8;
        boolean sib = m.index() != null || (base.number() & 7) == 4;
        long disp = m.displacement();
        int mod;
        if (disp == 0 && (base.number() & 7) != 5) {
            mod = 0;
        } else if (disp == (byte) disp) {
            mod = 1;
        } else {
            mod = 2;
        }
        out.op((mod << 6) | (field << 3) | (sib ? 4 : base.number() & 7));
        if (sib) {
            int index = 4;
            int scale = 0;
            if (m.index() != null) {
                Reg ix = register(m.index());
                out.rexX = ix.number() >= 8;
                index = ix.number() & 7;
                scale = Integer.numberOfTrailingZeros(m.scale());
            }
            out.op((scale << 6) | (index << 3) | (base.number() & 7));
        }
        if (mod == 1) {
            out.imm(disp, 1);
        } else if (mod == 2) {
            out.imm(disp, 4);
        }
    }
}
