package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.tac.Block;
import org.jbm.cc.tac.Function;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The function under construction: its variables, its blocks in the
 * order they are opened, and the current block. After a terminator the
 * builder is closed and {@code emit} discards until a block is opened,
 * which is how dead code is dropped.
 */
final class Builder {

    final Function fn;
    private @Nullable Block current;
    private int temps;
    private final Map<String, Integer> blockNames = new HashMap<>();

    Builder(@NonNull Function fn) {
        this.fn = fn;
    }

    /** A fresh variable {@code %tN}. */
    Var temp(@NonNull Type type) {
        var v = new Var("t" + temps++, type);
        fn.locals.add(v);
        return v;
    }

    Var local(@NonNull String name, @NonNull Type type, boolean isVolatile) {
        var v = new Var(name, type, isVolatile);
        fn.locals.add(v);
        return v;
    }

    /** A new block, not yet in the function, named uniquely from {@code base}. */
    Block block(@NonNull String base) {
        int n = blockNames.merge(base, 1, Integer::sum);
        return new Block(n == 1 ? base : base + "." + n);
    }

    /** Makes {@code b} the current block; it is appended to the function here, so blocks are in opening order. */
    void open(@NonNull Block b) {
        if (current != null && !current.isTerminated()) throw new IllegalStateException("opening ." + b.name + " while ." + current.name + " is open");
        if (fn.blocks.contains(b)) throw new IllegalStateException("." + b.name + " opened twice");
        fn.blocks.add(b);
        current = b;
    }

    boolean isOpen() {
        return current != null;
    }

    void emit(@NonNull Instr i) {
        if (current == null) return;
        current.instrs.add(i);
        if (i.isTerminator()) current = null;
    }
}
