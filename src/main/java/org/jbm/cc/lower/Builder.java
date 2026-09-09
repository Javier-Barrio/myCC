package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.tac.Block;
import org.jbm.cc.tac.Function;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Operand;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final Set<String> blockNames = new HashSet<>();
    // Temporaries are declared, in allocation order, only if an emitted
    // instruction uses them, so ones allocated in dropped dead code never
    // appear.
    private final Set<Var> declared = new HashSet<>();
    private final List<Var> allocated = new ArrayList<>();
    private final Set<Var> used = new HashSet<>();

    Builder(@NonNull Function fn) {
        this.fn = fn;
        declared.addAll(fn.params);
    }

    /** A fresh variable {@code %tN}, declared at {@link #finish} if used. */
    Var temp(@NonNull Type type) {
        var v = new Var("t" + temps++, type);
        allocated.add(v);
        return v;
    }

    /** Declares the temporaries that were used; call once, after the body. */
    void finish() {
        for (Var v : allocated) if (used.contains(v)) fn.locals.add(v);
    }

    Var local(@NonNull String name, @NonNull Type type, boolean isVolatile) {
        var v = new Var(name, type, isVolatile);
        fn.locals.add(v);
        declared.add(v);
        return v;
    }

    /** A new block, not yet in the function, named uniquely from {@code base}. */
    Block block(@NonNull String base) {
        String name = base;
        for (int n = 2; !blockNames.add(name); n++) name = base + "." + n;
        return new Block(name);
    }

    /** Makes {@code b} the current block; it is appended to the function here, so blocks are in opening order. */
    void open(@NonNull Block b) {
        if (current != null && !current.isTerminated()) throw new IllegalStateException("opening ." + b.name + " while ." + current.name + " is open");
        if (fn.blocks.contains(b)) throw new IllegalStateException("." + b.name + " opened twice");
        fn.blocks.add(b);
        current = b;
    }

    /** Closes the current block without a terminator, for a block that turned out empty. */
    void dropIfEmpty() {
        if (current != null && current.instrs.isEmpty()) {
            fn.blocks.remove(current);
            current = null;
        }
    }

    boolean isOpen() {
        return current != null;
    }

    void emit(@NonNull Instr i) {
        if (current == null) return;
        for (Var v : varsOf(i)) {
            if (!declared.contains(v)) used.add(v);
        }
        current.instrs.add(i);
        if (i.isTerminator()) current = null;
    }

    private static List<Var> varsOf(Instr i) {
        var out = new ArrayList<Var>();
        if (i instanceof Instr.Mov m) add(out, m.dst(), m.src());
        else if (i instanceof Instr.AddrOfVar a) add(out, a.dst(), a.var());
        else if (i instanceof Instr.AddrOfGlobal a) add(out, a.dst());
        else if (i instanceof Instr.Bin b) add(out, b.dst(), b.a(), b.b());
        else if (i instanceof Instr.Cmp c) add(out, c.dst(), c.a(), c.b());
        else if (i instanceof Instr.Cvt c) add(out, c.dst(), c.src());
        else if (i instanceof Instr.Load l) add(out, l.dst(), l.ptr());
        else if (i instanceof Instr.Store st) add(out, st.ptr(), st.value());
        else if (i instanceof Instr.Copy c) add(out, c.dst(), c.src());
        else if (i instanceof Instr.Zero z) add(out, z.ptr());
        else if (i instanceof Instr.CondBr c) add(out, c.cond());
        else if (i instanceof Instr.Switch sw) add(out, sw.value());
        else if (i instanceof Instr.Ret r) add(out, r.value());
        else if (i instanceof Instr.Call c) {
            add(out, c.dst(), c.into());
            for (var a : c.args()) add(out, a);
        } else if (i instanceof Instr.ICall c) {
            add(out, c.dst(), c.callee(), c.into());
            for (var a : c.args()) add(out, a);
        }
        return out;
    }

    private static void add(List<Var> out, @Nullable Operand... operands) {
        for (var o : operands) if (o instanceof Var v) out.add(v);
    }
}
