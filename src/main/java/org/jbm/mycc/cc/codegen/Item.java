package org.jbm.mycc.cc.codegen;

import lombok.NonNull;

import java.util.List;

/**
 * One line of the assembly IR: an instruction, a label, a section
 * change, a data directive, or an annotation. The printer spells
 * them; an assembler would encode the instructions and lay out the
 * data from the same objects.
 */
public sealed interface Item {

    record Insn(@NonNull String mnemonic, @NonNull List<Operand> operands) implements Item {
        public Insn {
            operands = List.copyOf(operands);
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
