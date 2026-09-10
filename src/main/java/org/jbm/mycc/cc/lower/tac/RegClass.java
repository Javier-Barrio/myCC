package org.jbm.mycc.cc.lower.tac;

/**
 * The register file a variable's value is held in: {@code INT} for
 * every integer type and {@code ptr}, {@code FLOAT} for every floating
 * type, {@code NONE} for an aggregate, which is storage and has no value.
 */
public enum RegClass {
    INT, FLOAT, NONE;

    public boolean isInteger() {
        return this == INT;
    }

    public boolean isFloating() {
        return this == FLOAT;
    }
}
