package org.jbm.cc.ast;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/**
 * Types as the parser derives them from declaration specifiers and
 * declarators (C2y 6.7.3 - 6.7.9). These are syntactic: typedef names keep
 * their name and the type they were declared as, struct/enum tags are not
 * looked up, and typeof stays unresolved. Every variant carries its
 * qualifiers. Optional parts are {@link Optional}s; nothing is null.
 */
public sealed interface Type {

    Quals quals();

    Type withQuals(Quals quals);

    <R> R accept(Visitor<R> visitor);

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

    record Basic(@NonNull Token token,
                 @NonNull Kind kind,
                 boolean isComplex,
                 @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Basic(token, kind, isComplex, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** _BitInt ( constant-expression ), optionally unsigned (6.7.3.1). */
    record BitInt(@NonNull Token token,
                  boolean isUnsigned,
                  @NonNull Expr width,
                  @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new BitInt(token, isUnsigned, width, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Pointer(@NonNull Token star, @NonNull Type target, @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Pointer(star, target, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * array-declarator (6.7.7.1). size is absent for an incomplete array,
     * isStar marks {@code [*]}; isStatic and the qualifiers only occur in
     * parameter declarations.
     */
    record Array(@NonNull Token bracket, @NonNull Type element, @NonNull Optional<Expr> size,
                 boolean isStar, boolean isStatic,
                 @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Array(bracket, element, size, isStar, isStatic, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** function-declarator (6.7.7.1). {@code (void)} yields an empty parameter list. */
    record Function(@NonNull Token paren, @NonNull Type returnType, @NonNull List<Parameter> parameters,
                    boolean isVariadic,
                    @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Function(paren, returnType, parameters, isVariadic, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** parameter-declaration (6.7.7.1); name is absent for an abstract declarator. */
    record Parameter(@NonNull List<Attribute> attributes,
                     @NonNull List<Token> storageClasses,
                     @NonNull Type type,
                     @NonNull Optional<Token> name) {
    }

    /**
     * struct-or-union-specifier (6.7.3.2). keyword is {@code struct} or
     * {@code union}; members is absent when no member list is given.
     */
    record Struct(@NonNull Token keyword,
                  @NonNull Optional<Token> tag,
                  @NonNull Optional<List<MemberDecl>> members,
                  @NonNull Quals quals)
            implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Struct(keyword, tag, members, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** member-declaration (6.7.3.2): a member or a static assertion. */
    sealed interface MemberDecl permits Member, Expr.StaticAssertion {
    }

    /** member-declarator (6.7.3.2); name is absent for an anonymous member or unnamed bit-field. */
    record Member(@NonNull List<Attribute> attributes,
                  @NonNull Type type,
                  @NonNull Optional<Token> name,
                  @NonNull Optional<Expr> bitWidth)
            implements MemberDecl {
    }

    /** enum-specifier (6.7.3.3); enumerators is absent when no list is given. */
    record Enum(@NonNull Token keyword, @NonNull Optional<Token> tag, @NonNull Optional<Type> underlying,
                @NonNull Optional<List<Enumerator>> enumerators, @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Enum(keyword, tag, underlying, enumerators, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Enumerator(@NonNull Token name,
                      @NonNull List<Attribute> attributes,
                      @NonNull Optional<Expr> value) {
    }

    /** typedef-name (6.7.9), with the type it was declared as. */
    record TypedefName(@NonNull Token name, @NonNull Type aliased, @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new TypedefName(name, aliased, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** typeof / typeof_unqual (6.7.3.6). Exactly one of expr / type is present. */
    record Typeof(@NonNull Token keyword,
                  @NonNull Optional<Expr> expr,
                  @NonNull Optional<Type> type,
                  @NonNull Quals quals) implements Type {
        @Override
        public Type withQuals(Quals q) {
            return new Typeof(keyword, expr, type, q);
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
