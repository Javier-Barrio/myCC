package org.jbm.mycc.cc.backend.lower.tac;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A defined function: its signature, its parameters and other variables,
 * and its blocks, the first of which is the entry.
 */
public final class Function implements Symbol {
    public final String name;
    public final Linkage linkage;
    public final Type.Func sig;
    public final List<Var> params;
    public final List<Var> locals = new ArrayList<>();
    public final List<Block> blocks = new ArrayList<>();

    public Function(@NonNull String name, @NonNull Linkage linkage, @NonNull Type.Func sig, @NonNull List<Var> params) {
        if (params.size() != sig.params().size()) throw new IllegalArgumentException("parameter count");
        this.name = name;
        this.linkage = linkage;
        this.sig = sig;
        this.params = List.copyOf(params);
    }

    public Block entry() {
        return blocks.get(0);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Type type() {
        return sig;
    }

    @Override
    public boolean isDefined() {
        return true;
    }
}
