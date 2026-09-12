package org.jbm.mycc.cc.backend.codegen;

import lombok.NonNull;

import java.util.List;
import java.util.Optional;

/**
 * What a target provides to the assembler: the bytes of an instruction
 * and the places in them a symbol's address still has to be written.
 */
public interface Encoder {

    /** How a symbol enters an instruction's 32-bit field. */
    enum Fix { PC32, PLT32, GOTPCREL }

    /** A 4-byte field at {@code offset} of the encoding, to hold {@code symbol + addend} per {@code fix}. */
    record Fixup(int offset, @NonNull Fix fix, @NonNull String symbol, long addend) {
    }

    record Encoded(byte @NonNull [] bytes, @NonNull List<Fixup> fixups) {
    }

    /**
     * A jump to a label with its two forms: the opcode bytes followed by
     * an 8-bit displacement, and the opcode bytes followed by a 32-bit
     * one. The assembler picks the shortest that reaches.
     */
    record Jump(byte @NonNull [] shortOpcode, byte @NonNull [] longOpcode, @NonNull String target) {
    }

    /** The encoding of an instruction that is not a jump to a label. */
    Encoded encode(@NonNull Item.Insn insn);

    /** Present when the instruction is a jump to a label. */
    Optional<Jump> jump(@NonNull Item.Insn insn);
}
