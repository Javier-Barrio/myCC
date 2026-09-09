package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.tac.Var;
import org.jbm.cc.types.CType;
import org.jetbrains.annotations.Nullable;

/**
 * A lowered rvalue: the variable holding it, canonical for its C type,
 * or for an aggregate the {@code ptr} variable holding its address. A
 * {@code void} value has no variable.
 */
record Val(@Nullable Var var, @NonNull CType type) {

    public Var var() {
        if (var == null) throw new IllegalStateException("a void value has no variable");
        return var;
    }

    boolean isVoid() {
        return var == null;
    }
}
