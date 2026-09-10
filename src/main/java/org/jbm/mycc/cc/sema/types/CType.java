package org.jbm.mycc.cc.sema.types;

import java.util.List;
import java.util.OptionalLong;

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
public sealed interface CType
        permits CType.Void, CType.Int, CType.BitInt, CType.Float, CType.Pointer, CType.Nullptr, CType.Array,
        CType.Function, CType.Record {

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

    /** Arithmetic, pointer or {@code nullptr_t} (6.2.5p24). */
    default boolean isScalar() {
        return isArithmetic() || isPointer() || isNullptr();
    }

    default boolean isNullptr() {
        return false;
    }

    default boolean isBool() {
        return false;
    }

    default boolean isArray() {
        return false;
    }

    default boolean isRecord() {
        return false;
    }

    default boolean isFunction() {
        return false;
    }

    /** Object types have a size; incomplete arrays, void and functions do not (6.2.5p1). */
    default boolean isComplete() {
        return true;
    }

    record Void(Quals quals) implements CType {
        @Override
        public boolean isVoid() {
            return true;
        }

        @Override
        public boolean isComplete() {
            return false;
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

    /**
     * A bit-precise integer type {@code _BitInt(N)} (6.2.5p6): its width is
     * part of the type, it is never promoted, and its rank is below any
     * standard integer type of the same width (6.3.1.1p1).
     */
    record BitInt(int width, boolean isUnsigned, Quals quals) implements CType {
        public BitInt {
            if (width < (isUnsigned ? 1 : 2)) throw new IllegalArgumentException("_BitInt width " + width);
        }

        @Override
        public boolean isInteger() {
            return true;
        }

        @Override
        public String spelling() {
            return quals.prefix() + (isUnsigned ? "unsigned " : "") + "_BitInt(" + width + ")";
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
            return Spelling.of(this);
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /** {@code nullptr_t} (6.2.5p24 in C2y, 7.21p2): the type of {@code nullptr}. */
    record Nullptr(Quals quals) implements CType {
        @Override
        public boolean isNullptr() {
            return true;
        }

        @Override
        public String spelling() {
            return quals.prefix() + "nullptr_t";
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /**
     * Array of {@code size} elements of {@code element} (6.2.5p22); size is
     * absent for an incomplete array type. Qualifiers on an array type are
     * those of its element type (6.7.4.1p10), so {@code quals()} delegates.
     */
    record Array(CType element, OptionalLong size) implements CType {
        @Override
        public Quals quals() {
            return element.quals();
        }

        @Override
        public boolean isArray() {
            return true;
        }

        @Override
        public boolean isComplete() {
            return size.isPresent();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Array a && a.element == element && a.size.equals(size);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(element) * 31 + size.hashCode();
        }

        @Override
        public String spelling() {
            return Spelling.of(this);
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /**
     * Function type (6.2.5p23) with its parameters already adjusted
     * (6.7.7.4p7-8: arrays and functions become pointers, qualifiers are
     * dropped for compatibility). {@code (void)} is an empty parameter
     * list. Function types have no qualifiers.
     */
    record Function(CType returnType, List<CType> parameters, boolean isVariadic) implements CType {
        public Function {
            parameters = List.copyOf(parameters);
        }

        @Override
        public Quals quals() {
            return Quals.NONE;
        }

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Function f) || f.returnType != returnType || f.isVariadic != isVariadic
                    || f.parameters.size() != parameters.size()) return false;
            for (int i = 0; i < parameters.size(); i++) if (f.parameters.get(i) != parameters.get(i)) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(returnType);
            for (CType p : parameters) h = h * 31 + System.identityHashCode(p);
            return h * 2 + (isVariadic ? 1 : 0);
        }

        @Override
        public String spelling() {
            return Spelling.of(this);
        }

        @Override
        public String toString() {
            return spelling();
        }
    }

    /**
     * A struct or union type (6.2.5p22-23), identified by its tag: two
     * record types are the same type iff they have the same tag, so
     * equality is the tag's identity and interning never looks at the
     * members. Complete once the tag has a layout.
     */
    record Record(Tag tag, Quals quals) implements CType {
        @Override
        public boolean isRecord() {
            return true;
        }

        @Override
        public boolean isComplete() {
            return tag.layout().isPresent();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Record r && r.tag == tag && r.quals.equals(quals);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(tag) * 31 + quals.hashCode();
        }

        @Override
        public String spelling() {
            return quals.prefix() + tag.keyword() + " " + tag.name().orElse("<anonymous>");
        }

        @Override
        public String toString() {
            return spelling();
        }
    }
}
