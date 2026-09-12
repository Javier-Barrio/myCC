package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;

import java.util.List;

/**
 * One line of the assembly IR: an instruction, a label, a section
 * change, a data directive, or an annotation. The printer spells
 * them; an assembler would encode the instructions and lay out the
 * data from the same objects.
 */
public sealed interface Item {

    /**
     * The mnemonics are the target's, with two of the IR's own: {@code icall}
     * and {@code ijmp} transfer to the value in their register or memory
     * operand, where {@code call} and {@code jmp} take a symbol. A
     * transfer with the other kind of operand is invalid.
     */
    record Insn(@NonNull String mnemonic, @NonNull List<Operand> operands) implements Item {
        public Insn {
            operands = List.copyOf(operands);
            boolean indirect = mnemonic.equals("icall") || mnemonic.equals("ijmp");
            boolean direct = mnemonic.equals("call") || mnemonic.equals("jmp");
            if ((indirect || direct) && operands.size() == 1) {
                Operand target = operands.get(0);
                boolean value = target instanceof Operand.Reg || target instanceof Operand.Mem;
                if (indirect != value) {
                    throw new IllegalArgumentException(mnemonic + " with " + target);
                }
            }
        }
    }

    record Label(@NonNull String name) implements Item {
    }

    /** {@code .text}, {@code .data}, {@code .bss}, {@code .section name,...}. */
    record Section(@NonNull String name) implements Item {
    }

    /** {@code .globl name}: the symbol is visible outside the file. */
    record Global(@NonNull String name) implements Item {
    }

    record Align(int bytes) implements Item {
    }

    record Bytes(byte @NonNull [] bytes) implements Item {
    }

    /** An 8-byte word holding a symbol's address plus an addend. */
    record Address(@NonNull Operand.Sym symbol) implements Item {
    }

    /** An 8-byte word holding a value; the comment says what it means, such as a floating constant. */
    record Word(long value, @NonNull String comment) implements Item {
    }

    record Zero(long bytes) implements Item {
    }

    /** An annotation on its own line, indented like an instruction. */
    record Comment(@NonNull String text) implements Item {
    }

    /** An annotation at the margin, before a function. */
    record Note(@NonNull String text) implements Item {
    }
}
