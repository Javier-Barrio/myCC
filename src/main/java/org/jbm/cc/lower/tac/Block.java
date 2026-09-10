package org.jbm.cc.lower.tac;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/** A basic block: a name and instructions, the last of which is the terminator. */
public final class Block {
    public final String name;
    public final List<Instr> instrs = new ArrayList<>();

    public Block(@NonNull String name) {
        this.name = name;
    }

    public boolean isTerminated() {
        return !instrs.isEmpty() && instrs.get(instrs.size() - 1).isTerminator();
    }

    @Override
    public String toString() {
        return "." + name;
    }
}
