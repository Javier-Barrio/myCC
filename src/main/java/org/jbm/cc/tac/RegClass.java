package org.jbm.cc.tac;

/**
 * The register class a variable's value is held in: {@code W} the width
 * of {@code int}, {@code L} the width of {@code long long}, {@code S} and
 * {@code D} single and double precision, {@code X} extended precision,
 * {@code NONE} for an aggregate, which is storage and has no value.
 */
public enum RegClass {
    W, L, S, D, X, NONE;

    public boolean isInteger() {
        return this == W || this == L;
    }

    public boolean isFloating() {
        return this == S || this == D || this == X;
    }
}
