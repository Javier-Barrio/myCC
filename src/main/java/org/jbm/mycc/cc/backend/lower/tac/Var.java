package org.jbm.mycc.cc.backend.lower.tac;

import lombok.NonNull;

/**
 * A declared variable of a function: a parameter or a local, with its
 * type. Where it lives is the consumer's decision; the compiler only
 * says its type and whether it is {@code volatile}. Identity is the
 * object: two variables are the same only if they are the same
 * declaration.
 */
public final class Var implements Operand {
    public final String name;
    public final Type type;
    public final boolean isVolatile;
    /** The alignment the declaration asked for, or 0 for the type's own. */
    public final int align;
    /** A temporary the lowering made for one value, written once; its storage may be shared with others. */
    public final boolean isTemp;

    public Var(@NonNull String name, @NonNull Type type, boolean isVolatile, int align) {
        this(name, type, isVolatile, align, false);
    }

    public Var(@NonNull String name, @NonNull Type type, boolean isVolatile, int align, boolean isTemp) {
        this.name = name;
        this.type = type;
        this.isVolatile = isVolatile;
        this.align = align;
        this.isTemp = isTemp;
    }

    public Var(@NonNull String name, @NonNull Type type, boolean isVolatile) {
        this(name, type, isVolatile, 0);
    }

    public Var(@NonNull String name, @NonNull Type type) {
        this(name, type, false);
    }

    @Override
    public String toString() {
        return "%" + name;
    }
}
