package org.jbm.cc.types;

/**
 * A semantic type (C2y 6.2.5): what a declaration means once typedef
 * names, {@code typeof} and tags are resolved. Every CType is immutable
 * and interned by {@link Types}, so two equal types are the same object
 * and equality is identity. A CType records only what the standard
 * defines (rank, signedness, structure, qualifiers); every width,
 * alignment or other implementation-defined property is a question to
 * {@link Types}, which asks the {@link Target}.
 * <p>
 * The predicates here answer questions about one type's own structure.
 * Anything that produces a new type or compares two types lives on
 * {@link Types}, which is what keeps the interning airtight.
 */
public sealed interface CType permits CType.Void, CType.Int, CType.Float, CType.Pointer {

    Quals quals();

    /** The C spelling, for diagnostics and the typed-tree printer. */
    String spelling();

    default boolean isVoid() {
        return false;
    }

    default boolean isInteger() {
        return false;
    }

    default boolean isFloating() {
        return false;
    }

    default boolean isArithmetic() {
        return isInteger() || isFloating();
    }

    default boolean isPointer() {
        return false;
    }

    /** Arithmetic or pointer (6.2.5p24). */
    default boolean isScalar() {
        return isArithmetic() || isPointer();
    }

    default boolean isBool() {
        return false;
    }

    record Void(Quals quals) implements CType {
        @Override
        public boolean isVoid() {
            return true;
        }

        @Override
        public String spelling() {
            return quals.prefix() + "void";
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /**
     * The standard integer types (6.2.5p4-9) including {@code bool} and the
     * three character types. Plain {@code char} is its own type, distinct
     * from {@code signed char} and {@code unsigned char} (6.2.5p20), whose
     * signedness the target decides; it is {@link Sign#PLAIN}.
     */
    record Int(Rank rank, Sign sign, Quals quals) implements CType {

        /** Integer conversion rank (6.3.1.1p1), in increasing order. */
        public enum Rank {
            BOOL("bool"), CHAR("char"), SHORT("short"), INT("int"), LONG("long"), LLONG("long long");

            public final String spelling;

            Rank(String spelling) {
                this.spelling = spelling;
            }
        }

        public enum Sign { SIGNED, UNSIGNED, PLAIN }

        public Int {
            if (sign == Sign.PLAIN && rank != Rank.CHAR) throw new IllegalArgumentException("only char is PLAIN");
            if (rank == Rank.BOOL && sign != Sign.UNSIGNED) throw new IllegalArgumentException("bool is unsigned");
        }

        @Override
        public boolean isInteger() {
            return true;
        }

        @Override
        public boolean isBool() {
            return rank == Rank.BOOL;
        }

        public boolean isUnsigned() {
            return sign == Sign.UNSIGNED;
        }

        @Override
        public String spelling() {
            String base = switch (sign) {
                case PLAIN -> "char";
                case SIGNED -> rank == Rank.CHAR ? "signed char" : rank.spelling;
                case UNSIGNED -> rank == Rank.BOOL ? "bool" : "unsigned " + rank.spelling;
            };
            return quals.prefix() + base;
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /** The real floating types (6.2.5p14). */
    record Float(Rank rank, Quals quals) implements CType {

        public enum Rank {
            FLOAT("float"), DOUBLE("double"), LDOUBLE("long double");

            public final String spelling;

            Rank(String spelling) {
                this.spelling = spelling;
            }
        }

        @Override
        public boolean isFloating() {
            return true;
        }

        @Override
        public String spelling() {
            return quals.prefix() + rank.spelling;
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /**
     * Pointer to {@code target} (6.2.5p26). Equality and hashing use the
     * target's identity: targets are interned, so that is structural
     * equality at O(1) without walking the pointee.
     */
    record Pointer(CType target, Quals quals) implements CType {
        @Override
        public boolean isPointer() {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pointer p && p.target == target && p.quals.equals(quals);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(target) * 31 + quals.hashCode();
        }

        @Override
        public String spelling() {
            String q = quals.isEmpty() ? "" : " " + quals.prefix().trim();
            return target.spelling() + " *" + q;
        }

        @Override
        public String toString() {
            return spelling();
        }
    }
}
