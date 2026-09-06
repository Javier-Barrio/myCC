package org.jbm.cc.sema;

import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;

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
    public final Type type;
    public final Token declaredAt;
    /** 0 for file scope. */
    public final int scopeDepth;

    Symbol(int id, Token declaredAt, Type type, int scopeDepth) {
        this.id = id;
        this.name = declaredAt.text;
        this.type = type;
        this.declaredAt = declaredAt;
        this.scopeDepth = scopeDepth;
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

    /** An object (6.2.4): a variable at file or block scope. */
    public static final class Variable extends Symbol {

        /** Storage duration (6.2.4). */
        public enum Storage { STATIC, AUTOMATIC }

        public final Storage storage;
        private final Linkage linkage;
        private boolean defined;

        Variable(int id, Token declaredAt, Type type, int scopeDepth, Storage storage, Linkage linkage,
                 boolean defined) {
            super(id, declaredAt, type, scopeDepth);
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
            super(id, declaredAt, type, scopeDepth);
        }
    }

    public static final class Function extends Symbol {
        private final Linkage linkage;
        private boolean defined;

        Function(int id, Token declaredAt, Type type, int scopeDepth, Linkage linkage, boolean defined) {
            super(id, declaredAt, type, scopeDepth);
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

    /** A typedef name (6.7.9); type is the type it stands for. */
    public static final class Typedef extends Symbol {
        Typedef(int id, Token declaredAt, Type type, int scopeDepth) {
            super(id, declaredAt, type, scopeDepth);
        }
    }

    /** An enumeration constant (6.7.3.3); type is the enum specifier that declares it. */
    public static final class Enumerator extends Symbol {
        public final Type.Enum enumType;

        Enumerator(int id, Token declaredAt, Type.Enum enumType, int scopeDepth) {
            super(id, declaredAt, enumType, scopeDepth);
            this.enumType = enumType;
        }
    }
}
