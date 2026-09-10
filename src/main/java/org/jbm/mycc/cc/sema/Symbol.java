package org.jbm.mycc.cc.sema;

import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.sema.types.CType;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * An entity in the ordinary-identifier namespace (C2y 6.2.3). One Symbol
 * per entity; redeclarations at file scope bind to the same Symbol. The
 * subclasses are the five kinds of entity an ordinary identifier can
 * denote, each carrying only the properties that apply to it: objects
 * have a storage duration and linkage, functions a linkage, and typedef
 * names, enumeration constants and parameters neither.
 */
public sealed abstract class Symbol
        permits Symbol.Variable, Symbol.Parameter, Symbol.Function, Symbol.Typedef, Symbol.Enumerator {

    /** Linkage (6.2.2). */
    public enum Linkage { EXTERNAL, INTERNAL, NONE }

    public final int id;
    public final String name;
    /**
     * The syntactic type of the first declaration - absent only for an
     * {@code auto} object whose type the typing pass infers. Kept for
     * "previously declared as" diagnostics; the semantic type, composed
     * across all declarations, is {@link #type()}.
     */
    public final Optional<Type> declaredType;
    public final Token declaredAt;
    /** 0 for file scope. */
    public final int scopeDepth;

    // The semantic type, set by the typing pass: the composite of every
    // declaration seen so far (6.2.7p3), or what a typedef stands for.
    private @Nullable CType type;

    Symbol(int id, Token declaredAt, Optional<Type> declaredType, int scopeDepth) {
        this.id = id;
        this.name = declaredAt.text;
        this.declaredType = declaredType;
        this.declaredAt = declaredAt;
        this.scopeDepth = scopeDepth;
    }

    /**
     * The semantic type. Reading it before the typing pass has set it is a
     * bug in the caller, not a lookup miss, so it throws rather than
     * returning an absent value.
     */
    public CType type() {
        if (type == null) throw new IllegalStateException("type of '" + name + "' has not been computed");
        return type;
    }

    public boolean hasType() {
        return type != null;
    }

    void setType(CType type) {
        this.type = type;
    }

    public Linkage linkage() {
        return Linkage.NONE;
    }

    /** A function with a body, or an object declaration with an initializer. */
    public boolean isDefined() {
        return false;
    }

    void markDefined() {
    }

    @Override
    public String toString() {
        return getClass().getSimpleName().toUpperCase() + " " + name + "@" + declaredAt.line + ":" + declaredAt.column;
    }

    /**
     * An anonymous object with static storage duration and no linkage: the
     * array a string literal denotes (6.4.5p7). Its name is the literal's
     * spelling, for the printer and diagnostics.
     */
    static Variable anonymousStatic(int id, Token at) {
        return new Variable(id, at, Optional.empty(), 0, Variable.Storage.STATIC, Linkage.NONE, true);
    }

    /** An anonymous automatic object: a temporary holding a struct rvalue, or a compound literal in a block. */
    static Variable anonymousAutomatic(int id, Token at, int scopeDepth) {
        return new Variable(id, at, Optional.empty(), scopeDepth, Variable.Storage.AUTOMATIC, Linkage.NONE, true);
    }

    /** An object (6.2.4): a variable at file or block scope. */
    public static final class Variable extends Symbol {

        /** Storage duration (6.2.4). */
        public enum Storage { STATIC, AUTOMATIC }

        public final Storage storage;
        private final Linkage linkage;
        private boolean defined;

        Variable(int id, Token declaredAt, Optional<Type> declaredType, int scopeDepth, Storage storage,
                 Linkage linkage, boolean defined) {
            super(id, declaredAt, declaredType, scopeDepth);
            this.storage = storage;
            this.linkage = linkage;
            this.defined = defined;
        }

        @Override
        public Linkage linkage() {
            return linkage;
        }

        @Override
        public boolean isDefined() {
            return defined;
        }

        @Override
        void markDefined() {
            defined = true;
        }
    }

    /** A function parameter: automatic storage, no linkage, block scope of the body (6.2.1p4). */
    public static final class Parameter extends Symbol {
        Parameter(int id, Token declaredAt, Type type, int scopeDepth) {
            super(id, declaredAt, Optional.of(type), scopeDepth);
        }
    }

    public static final class Function extends Symbol {
        private final Linkage linkage;
        private boolean defined;

        Function(int id, Token declaredAt, Type type, int scopeDepth, Linkage linkage, boolean defined) {
            super(id, declaredAt, Optional.of(type), scopeDepth);
            this.linkage = linkage;
            this.defined = defined;
        }

        @Override
        public Linkage linkage() {
            return linkage;
        }

        @Override
        public boolean isDefined() {
            return defined;
        }

        @Override
        void markDefined() {
            defined = true;
        }
    }

    /** A typedef name (6.7.9); declaredType is the type it stands for. */
    public static final class Typedef extends Symbol {
        Typedef(int id, Token declaredAt, Type type, int scopeDepth) {
            super(id, declaredAt, Optional.of(type), scopeDepth);
        }
    }

    /**
     * An enumeration constant (6.7.3.3); enumType is the enum specifier
     * that declares it. Its value is computed once, when the enum is
     * typed, and every use folds to it.
     */
    public static final class Enumerator extends Symbol {
        public final Type.Enum enumType;
        private long value;

        Enumerator(int id, Token declaredAt, Type.Enum enumType, int scopeDepth) {
            super(id, declaredAt, Optional.of(enumType), scopeDepth);
            this.enumType = enumType;
        }

        public long value() {
            type(); // the value is set together with the type
            return value;
        }

        void setValue(long value) {
            this.value = value;
        }
    }
}
