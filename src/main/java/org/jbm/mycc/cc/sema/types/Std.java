package org.jbm.mycc.cc.sema.types;

import lombok.NonNull;

/**
 * The C standard a translation unit is compiled as. The language is
 * C2y's either way; the standard decides what an empty parameter list
 * means and whether an old-style definition is accepted, and what
 * {@code __STDC_VERSION__} says.
 */
public enum Std {
    /** {@code ()} declares no prototype; old-style definitions with an identifier list are accepted. */
    C17("c17", "201710L"),
    /** {@code ()} means {@code (void)}; every function type has a prototype. */
    C23("c23", "202311L");

    public final String name;
    public final String version;

    Std(String name, String version) {
        this.name = name;
        this.version = version;
    }

    /** The standard a {@code -std=} option names: c17 and its older siblings, or c23. */
    public static Std of(@NonNull String option) {
        switch (option.toLowerCase()) {
            case "c89", "c90", "c99", "c11", "c17", "c18", "gnu89", "gnu99", "gnu11", "gnu17" -> {
                return C17;
            }
            case "c23", "c2x", "gnu23", "gnu2x" -> {
                return C23;
            }
            default -> throw new IllegalArgumentException("unknown standard '" + option + "'; use c17 or c23");
        }
    }
}
