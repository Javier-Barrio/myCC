package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

/**
 * An instruction operand of the assembly IR, as an assembler would see
 * it: a register, an immediate, a memory operand of a base register
 * with a displacement, a symbol (a label or a global, plain or through
 * the PLT), a symbol addressed relative to the instruction pointer
 * (plain or through the GOT). An {@code icall} or {@code ijmp} takes
 * its target, a register or memory operand, as it is.
 */
public sealed interface Operand {

    record Reg(@NonNull String name) implements Operand {
    }

    record Imm(long value) implements Operand {
    }

    /** {@code displacement(base, index, scale)}; no index when {@code index} is null. */
    record Mem(long displacement, @NonNull String base, @Nullable String index, int scale) implements Operand {
    }

    /** How a symbol is reached: directly, or through the linker's tables for a symbol defined elsewhere. */
    enum Reloc { PLAIN, PLT, GOT }

    /** A symbol as a jump or call target, or in a data directive. */
    record Sym(@NonNull String name, long addend, @NonNull Reloc reloc) implements Operand {
    }

    /** A symbol's address relative to the instruction pointer: {@code name(%rip)} or {@code name@GOTPCREL(%rip)}. */
    record RipRel(@NonNull String name, @NonNull Reloc reloc) implements Operand {
    }

    static Reg reg(String name) {
        return new Reg(name);
    }

    static Imm imm(long value) {
        return new Imm(value);
    }

    static Mem mem(long displacement, String base) {
        return new Mem(displacement, base, null, 1);
    }

    static Sym sym(String name) {
        return new Sym(name, 0, Reloc.PLAIN);
    }

    static Sym sym(String name, long addend) {
        return new Sym(name, addend, Reloc.PLAIN);
    }

    static Sym plt(String name) {
        return new Sym(name, 0, Reloc.PLT);
    }

    static RipRel rip(String name) {
        return new RipRel(name, Reloc.PLAIN);
    }

    static RipRel got(String name) {
        return new RipRel(name, Reloc.GOT);
    }
}
