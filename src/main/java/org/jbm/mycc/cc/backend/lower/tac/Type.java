package org.jbm.mycc.cc.backend.lower.tac;

import lombok.NonNull;

import java.util.List;

/**
 * A TAC type: the memory shape of a variable, a global, a load or a
 * store, a member or a signature. Integers carry their width and
 * signedness, floats their format, {@code ptr} is an address of the
 * target's pointer width, aggregates are arrays and named structures.
 * Types are values: two types with the same spelling are equal.
 */
public sealed interface Type permits Type.Int, Type.Float, Type.Ptr, Type.Array, Type.Struct, Type.Func, Type.Void {

    Type I8 = new Int(8, true), I16 = new Int(16, true), I32 = new Int(32, true), I64 = new Int(64, true);
    Type U8 = new Int(8, false), U16 = new Int(16, false), U32 = new Int(32, false), U64 = new Int(64, false);
    Type F32 = new Float(32), F64 = new Float(64), F80 = new Float(80), F128 = new Float(128);
    Type PTR = new Ptr();
    Type VOID = new Void();

    String spelling();

    default boolean isAggregate() {
        return this instanceof Array || this instanceof Struct;
    }

    default boolean isScalar() {
        return this instanceof Int || this instanceof Float || this instanceof Ptr;
    }

    record Int(int width, boolean signed) implements Type {
        public Int {
            if (width != 8 && width != 16 && width != 32 && width != 64) throw new IllegalArgumentException("width " + width);
        }

        @Override
        public String spelling() {
            return (signed ? "i" : "u") + width;
        }
    }

    record Float(int width) implements Type {
        public Float {
            if (width != 32 && width != 64 && width != 80 && width != 128) throw new IllegalArgumentException("width " + width);
        }

        @Override
        public String spelling() {
            return "f" + width;
        }
    }

    record Ptr() implements Type {
        @Override
        public String spelling() {
            return "ptr";
        }
    }

    record Array(@NonNull Type element, long count) implements Type {
        @Override
        public String spelling() {
            return "[" + count + " x " + element.spelling() + "]";
        }
    }

    /** A reference to a structure defined in the module by name. */
    record Struct(@NonNull String name) implements Type {
        @Override
        public String spelling() {
            return "%" + name;
        }
    }

    record Func(@NonNull List<Type> params, boolean variadic, @NonNull Type ret) implements Type {
        public Func {
            params = List.copyOf(params);
        }

        @Override
        public String spelling() {
            var sb = new StringBuilder("(");
            for (int i = 0; i < params.size(); i++) sb.append(i > 0 ? ", " : "").append(params.get(i).spelling());
            if (variadic) sb.append(params.isEmpty() ? "..." : ", ...");
            return sb.append(") -> ").append(ret.spelling()).toString();
        }
    }

    record Void() implements Type {
        @Override
        public String spelling() {
            return "void";
        }
    }
}
