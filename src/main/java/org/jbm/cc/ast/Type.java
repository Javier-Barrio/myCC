package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/**
 * Types as the parser derives them from declaration specifiers and
 * declarators (C2y 6.7.3 - 6.7.9). These are syntactic: typedef names keep
 * their name and the type they were declared as, struct/enum tags are not
 * looked up, and typeof stays unresolved. Every variant carries its
 * qualifiers.
 */
public sealed interface Type {

    Quals quals();

    Type withQuals(Quals quals);

    /** type-qualifier set (6.7.4.1). */
    record Quals(boolean isConst, boolean isVolatile, boolean isRestrict, boolean isAtomic) {
        public static final Quals NONE = new Quals(false, false, false, false);

        public boolean isEmpty() {
            return !isConst && !isVolatile && !isRestrict && !isAtomic;
        }

        public Quals plus(Quals o) {
            return new Quals(isConst || o.isConst, isVolatile || o.isVolatile,
                    isRestrict || o.isRestrict, isAtomic || o.isAtomic);
        }

        public Quals plus(String qualifier) {
            return switch (qualifier) {
                case "const" -> new Quals(true, isVolatile, isRestrict, isAtomic);
                case "volatile" -> new Quals(isConst, true, isRestrict, isAtomic);
                case "restrict" -> new Quals(isConst, isVolatile, true, isAtomic);
                case "_Atomic" -> new Quals(isConst, isVolatile, isRestrict, true);
                default -> throw new IllegalArgumentException(qualifier);
            };
        }
    }

    /** The arithmetic and void types spelled with keywords (6.7.3.1). */
    enum Kind {
        VOID("void"), BOOL("bool"),
        CHAR("char"), SCHAR("signed char"), UCHAR("unsigned char"),
        SHORT("short"), USHORT("unsigned short"),
        INT("int"), UINT("unsigned int"),
        LONG("long"), ULONG("unsigned long"),
        LLONG("long long"), ULLONG("unsigned long long"),
        FLOAT("float"), DOUBLE("double"), LDOUBLE("long double"),
        DECIMAL32("_Decimal32"), DECIMAL64("_Decimal64"), DECIMAL128("_Decimal128");

        public final String spelling;

        Kind(String spelling) {
            this.spelling = spelling;
        }
    }

    record Basic(Token token, Kind kind, boolean isComplex, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Basic(token, kind, isComplex, q);
        }
    }

    /** _BitInt ( constant-expression ), optionally unsigned (6.7.3.1). */
    record BitInt(Token token, boolean isUnsigned, Expr width, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new BitInt(token, isUnsigned, width, q);
        }
    }

    record Pointer(Token star, Type target, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Pointer(star, target, q);
        }
    }

    /**
     * array-declarator (6.7.7.1). size is null for an incomplete array,
     * isStar marks {@code [*]}; isStatic and the qualifiers only occur in
     * parameter declarations.
     */
    record Array(Token bracket, Type element, Expr size, boolean isStar, boolean isStatic,
                 Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Array(bracket, element, size, isStar, isStatic, q);
        }
    }

    /** function-declarator (6.7.7.1). {@code (void)} yields an empty parameter list. */
    record Function(Token paren, Type returnType, List<Parameter> parameters, boolean isVariadic,
                    Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Function(paren, returnType, parameters, isVariadic, q);
        }
    }

    /** parameter-declaration (6.7.7.1); name is null for an abstract declarator. */
    record Parameter(List<Attribute> attributes, List<Token> storageClasses, Type type, Token name) {
    }

    /**
     * struct-or-union-specifier (6.7.3.2). keyword is {@code struct} or
     * {@code union}; members is null when no member list is given.
     */
    record Struct(Token keyword, Token tag, List<MemberDecl> members, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Struct(keyword, tag, members, q);
        }
    }

    /** member-declaration (6.7.3.2): a member or a static assertion. */
    sealed interface MemberDecl permits Member, Expr.StaticAssertion {
    }

    /** member-declarator (6.7.3.2); name is null for an anonymous member or unnamed bit-field. */
    record Member(List<Attribute> attributes, Type type, Token name, Expr bitWidth) implements MemberDecl {
    }

    /** enum-specifier (6.7.3.3); enumerators is null when no list is given. */
    record Enum(Token keyword, Token tag, Type underlying, List<Enumerator> enumerators,
                Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Enum(keyword, tag, underlying, enumerators, q);
        }
    }

    record Enumerator(Token name, List<Attribute> attributes, Expr value) {
    }

    /** typedef-name (6.7.9), with the type it was declared as. */
    record TypedefName(Token name, Type aliased, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new TypedefName(name, aliased, q);
        }
    }

    /** typeof / typeof_unqual (6.7.3.6). Exactly one of expr / type is non-null. */
    record Typeof(Token keyword, Expr expr, Type type, Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Typeof(keyword, expr, type, q);
        }
    }
}
