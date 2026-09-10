package org.jbm.cc.lower.tac;

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

    public Var(@NonNull String name, @NonNull Type type, boolean isVolatile) {
        this.name = name;
        this.type = type;
        this.isVolatile = isVolatile;
    }

    public Var(@NonNull String name, @NonNull Type type) {
        this(name, type, false);
    }

    @Override
    public String toString() {
        return "%" + name;
    }
}
