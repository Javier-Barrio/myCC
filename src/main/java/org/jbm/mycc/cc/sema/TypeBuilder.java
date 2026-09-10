package org.jbm.mycc.cc.sema;

import lombok.NonNull;
import org.jbm.mycc.cc.parse.ast.Expr;
import org.jbm.mycc.cc.parse.ast.Type;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.sema.tast.TExpr;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Layout;
import org.jbm.mycc.cc.sema.types.Quals;
import org.jbm.mycc.cc.sema.types.Types;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a syntactic {@link Type} into the semantic {@link CType} it means:
 * keyword types to their rank, typedef names to what the typedef was
 * declared as (through {@link Bindings}, so the typedef's type is computed
 * once at its declaration), declarators to pointer, array and function
 * types with parameters adjusted (6.7.7.4p7-8).
 * <p>
 * Not yet handled: struct/union/enum specifiers, {@code _BitInt},
 * {@code typeof(expression)}, array sizes that are not a plain integer
 * literal, and {@code _Complex}/decimal floating types.
 */
final class TypeBuilder {

    private final Types types;
    private final Bindings bindings;
    private @Nullable Hooks evaluator;

    /**
     * What a type can need from expressions: constant evaluation for sizes
     * and widths, and typing for {@code typeof}. Supplied by the typer.
     */
    interface Hooks {
        /** An integer constant expression's value; throws when it does not fold or is not an integer. */
        TExpr.IntConst evaluate(Expr e, Token at, String what);

        /** Empty when the expression does not fold; throws only on a typing error in it. */
        Optional<TExpr.IntConst> tryEvaluate(Expr e);

        /** The type of an unevaluated expression, without lvalue conversion (6.7.3.6p3). */
        CType typeOf(Expr e);

        /** Checks a static assertion among a struct's members. */
        void staticAssertion(Expr.StaticAssertion s);
    }

    TypeBuilder(@NonNull Types types, @NonNull Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
    }

    void setEvaluator(@NonNull Hooks evaluator) {
        this.evaluator = evaluator;
    }

    private TExpr.IntConst evaluate(Expr e, Token at, String what) {
        if (evaluator == null) throw new IllegalStateException("no constant evaluator");
        return evaluator.evaluate(e, at, what);
    }

    CType build(@NonNull Type t) {
        if (t instanceof Type.Basic b) return basic(b);
        if (t instanceof Type.Pointer p) return types.qualified(types.pointer(build(p.target())), quals(p.quals()));
        if (t instanceof Type.Array a) return array(a, false);
        if (t instanceof Type.Function f) return function(f);
        if (t instanceof Type.TypedefName n) return types.plusQuals(bindings.typedefOf(n).type(), quals(n.quals()));
        if (t instanceof Type.Typeof to) return typeof(to);
        if (t instanceof Type.BitInt bi) return types.qualified(bitInt(bi), quals(bi.quals()));
        if (t instanceof Type.Struct s) return types.plusQuals(recordType(s), quals(s.quals()));
        if (t instanceof Type.Enum e) return types.plusQuals(enumType(e), quals(e.quals()));
        throw new IllegalStateException(t.toString());
    }

    /**
     * A parameter's adjusted type (6.7.7.4p7): an array parameter becomes a
     * pointer carrying the qualifiers written inside its brackets, a
     * function parameter a pointer to function, and top-level qualifiers
     * are kept on the parameter's own type (they only drop out of
     * compatibility, which {@link Types#function} handles).
     */
    CType parameter(@NonNull Type.Parameter p) {
        Type t = p.type();
        if (t instanceof Type.Array a) {
            CType element = build(a.element());
            return types.qualified(types.pointer(element), quals(a.quals()));
        }
        if (t instanceof Type.Function) return types.pointer(build(t));
        return build(t);
    }

    private CType basic(Type.Basic b) {
        if (b.isComplex()) throw unsupported("_Complex", b.token());
        CType t = switch (b.kind()) {
            case VOID -> types.void_();
            case BOOL -> types.bool_();
            case CHAR -> types.char_();
            case SCHAR -> types.schar();
            case UCHAR -> types.uchar();
            case SHORT -> types.short_();
            case USHORT -> types.ushort();
            case INT -> types.int_();
            case UINT -> types.uint();
            case LONG -> types.long_();
            case ULONG -> types.ulong();
            case LLONG -> types.llong();
            case ULLONG -> types.ullong();
            case FLOAT -> types.float_();
            case DOUBLE -> types.double_();
            case LDOUBLE -> types.longDouble();
            case DECIMAL32, DECIMAL64, DECIMAL128 -> throw unsupported("decimal floating types", b.token());
        };
        return types.qualified(t, quals(b.quals()));
    }

    private CType array(Type.Array a, boolean inParameter) {
        if (a.isStar()) throw unsupported("variable length arrays", a.bracket());
        if (!inParameter && (a.isStatic() || !a.quals().isEmpty())) {
            throw new SemaException("static or type qualifiers in an array declarator are only allowed in a parameter",
                    a.bracket());
        }
        CType element = build(a.element());
        if (!element.isComplete()) {
            throw new SemaException("array has incomplete element type '" + element.spelling() + "'", a.bracket());
        }
        if (a.size().isEmpty()) return types.incompleteArray(element);
        return types.array(element, arraySize(a.size().get(), a.bracket()));
    }

    // An array size is an integer constant expression greater than zero
    // (6.7.7.3p1, p4); any other integer expression would make a variable
    // length array.
    private long arraySize(Expr size, Token at) {
        if (evaluator == null) throw new IllegalStateException("no constant evaluator");
        Optional<TExpr.IntConst> c = evaluator.tryEvaluate(size);
        if (c.isEmpty()) throw unsupported("variable length arrays", at);
        long n = c.get().value();
        boolean negative = types.isSigned(c.get().type()) && n < 0;
        if (negative || n == 0) throw new SemaException("array size must be positive", at);
        if (n < 0) throw new SemaException("array size is too large", at);
        return n;
    }

    // _BitInt(N) (6.7.3.1p5-6): N is an integer constant expression, at
    // least 1 for unsigned and 2 for signed; wider than 64 is not modeled.
    private CType bitInt(Type.BitInt t) {
        long width = evaluate(t.width(), t.token(), "_BitInt width").value();
        int min = t.isUnsigned() ? 1 : 2;
        if (width < min) throw new SemaException("_BitInt width must be at least " + min, t.token());
        if (width > 64) throw unsupported("_BitInt wider than 64 bits", t.token());
        return types.bitInt((int) width, t.isUnsigned());
    }

    private CType function(Type.Function f) {
        CType returnType = build(f.returnType());
        if (returnType.isArray() || returnType.isFunction()) {
            throw new SemaException("function cannot return " + (returnType.isArray() ? "an array" : "a function"),
                    f.paren());
        }
        var params = new ArrayList<CType>(f.parameters().size());
        for (var p : f.parameters()) {
            CType pt = parameter(p);
            if (pt.isVoid()) throw new SemaException("parameter has incomplete type 'void'", f.paren());
            // A named parameter, in a definition or a prototype, is a symbol
            // and gets its (unadjusted-qualifier) type here.
            if (p.name().isPresent()) bindings.symbolOf(p).setType(pt);
            params.add(pt);
        }
        return types.function(returnType, params, f.isVariadic());
    }

    // ---- structures and unions (6.7.3.2) ------------------------------------------------------

    /**
     * A struct/union specifier's type is the record type of its tag. A
     * specifier with a body lays the tag out, once: each member's type is
     * built, checked to be a complete object type (a flexible array
     * member may end a struct), anonymous struct/union members are
     * flattened, names must be unique. A reference yields the (possibly
     * still incomplete) record type.
     */
    private CType recordType(Type.Struct s) {
        TagSymbol tag = bindings.tagOf(s);
        CType.Record record = types.record(tag);
        if (!tag.hasType()) tag.setType(record);
        if (s.members().isEmpty() || tag.layout().isPresent()) return record;
        if (evaluator == null) throw new IllegalStateException("no expression hooks");

        var fields = new ArrayList<Layout.Field>();
        var names = new java.util.HashSet<String>();
        List<Type.MemberDecl> decls = s.members().get();
        for (int i = 0; i < decls.size(); i++) {
            if (decls.get(i) instanceof Expr.StaticAssertion sa) {
                evaluator.staticAssertion(sa);
                continue;
            }
            var m = (Type.Member) decls.get(i);
            Token at = m.name().orElse(s.keyword());
            CType type = build(m.type());
            if (m.bitWidth().isPresent()) {
                fields.add(bitField(m, type, at, names));
                continue;
            }
            if (type.isFunction()) throw new SemaException("member has function type", at);
            boolean flexible = type instanceof CType.Array a && !a.isComplete();
            if (flexible) {
                boolean last = i == decls.size() - 1;
                if (tag.isUnion() || !last || fields.isEmpty()) {
                    throw new SemaException("flexible array member must be the last of at least two members of a struct", at);
                }
            } else if (!type.isComplete()) {
                throw new SemaException("member has incomplete type '" + type.spelling() + "'", at);
            }
            if (m.name().isPresent()) {
                if (!names.add(m.name().get().text)) throw new SemaException("duplicate member '" + m.name().get().text + "'", at);
                fields.add(new Layout.Field(Optional.of(m.name().get().text), type, java.util.OptionalInt.empty()));
            } else if (type instanceof CType.Record r && r.tag().name().isEmpty()) {
                for (String inner : r.tag().layout().orElseThrow().members().keySet()) {
                    if (!names.add(inner)) throw new SemaException("duplicate member '" + inner + "'", at);
                }
                fields.add(new Layout.Field(Optional.empty(), type, java.util.OptionalInt.empty()));
            } else {
                throw new SemaException("declaration does not declare a member", at);
            }
        }
        if (fields.isEmpty()) throw new SemaException(s.keyword().text + " has no members", s.keyword());
        tag.setLayout(Layout.of(types, tag.isUnion(), fields));
        return record;
    }

    // A bit-field (6.7.3.2p4-5): an integer type, a width that is an
    // integer constant expression from 0 to the type's width (1 for
    // bool), and zero only when unnamed.
    private Layout.Field bitField(Type.Member m, CType type, Token at, java.util.Set<String> names) {
        if (!type.isInteger()) {
            throw new SemaException("bit-field has non-integer type '" + type.spelling() + "'", at);
        }
        long width = evaluate(m.bitWidth().get(), at, "bit-field width").value();
        int max = type.isBool() ? 1 : types.width(type);
        if (width < 0) throw new SemaException("negative bit-field width", at);
        if (width > max) throw new SemaException("width of bit-field exceeds its type ('" + type.spelling() + "')", at);
        if (width == 0 && m.name().isPresent()) throw new SemaException("named bit-field has zero width", at);
        if (m.name().isPresent() && !names.add(m.name().get().text)) {
            throw new SemaException("duplicate member '" + m.name().get().text + "'", at);
        }
        return new Layout.Field(m.name().map(n -> n.text), type, java.util.OptionalInt.of((int) width));
    }

    // ---- enumerations (6.7.3.3) --------------------------------------------------------------

    /**
     * An enum specifier's type is its underlying integer type: the fixed
     * one when spelled, otherwise the first of int, unsigned int, long,
     * unsigned long that holds every enumerator. The tag caches it; the
     * enumerators get their values here, in order, each visible to the
     * next.
     */
    private CType enumType(Type.Enum e) {
        TagSymbol tag = bindings.tagOf(e);
        if (tag.hasType() && (e.enumerators().isEmpty() || tag.definition().orElse(null) != e || enumeratorsTyped(e))) {
            return tag.type();
        }
        Optional<CType.Int> fixed = e.underlying().map(u -> fixedUnderlying(u, e.keyword()));
        if (e.enumerators().isEmpty()) {
            // A reference or forward declaration: complete only with a fixed type.
            if (fixed.isPresent()) {
                tag.setType(fixed.get());
                return fixed.get();
            }
            throw new SemaException("enum '" + tag.name.orElse("<anonymous>") + "' is incomplete", e.keyword());
        }
        var values = new ArrayList<BigInteger>();
        var symbols = new ArrayList<Symbol.Enumerator>();
        BigInteger next = BigInteger.ZERO;
        for (var en : e.enumerators().get()) {
            var symbol = (Symbol.Enumerator) bindings.enumerators.get(en);
            BigInteger value;
            if (en.value().isPresent()) {
                TExpr.IntConst c = evaluate(en.value().get(), en.name(), "enumerator value");
                value = ((CType.Int) c.type()).isUnsigned() && c.value() < 0
                        ? BigInteger.valueOf(c.value()).add(BigInteger.ONE.shiftLeft(64)) : BigInteger.valueOf(c.value());
            } else {
                value = next;
            }
            if (fixed.isPresent()) {
                if (!fits(value, fixed.get())) {
                    throw new SemaException("enumerator value " + value + " is not representable in '"
                            + fixed.get().spelling() + "'", en.name());
                }
                symbol.setType(fixed.get());
            } else {
                // While the list is processed an enumerator has type int
                // if it fits, else the narrowest type that holds it
                // (6.7.3.3p15); B = A + 1 sees A with that type.
                symbol.setType(narrowest(value, en.name()));
            }
            symbol.setValue(value.longValue());
            values.add(value);
            symbols.add(symbol);
            next = value.add(BigInteger.ONE);
        }
        CType.Int underlying = fixed.orElseGet(() -> {
            for (CType.Int candidate : List.of(types.int_(), types.uint(), types.long_(), types.ulong())) {
                if (values.stream().allMatch(v -> fits(v, candidate))) return candidate;
            }
            throw new SemaException("enumerator values do not fit any integer type", e.keyword());
        });
        // Once complete, every enumerator has the enumerated type (6.7.3.3p16).
        for (var symbol : symbols) symbol.setType(underlying);
        tag.setType(underlying);
        return underlying;
    }

    private boolean enumeratorsTyped(Type.Enum e) {
        var first = e.enumerators().get();
        return first.isEmpty() || bindings.enumerators.get(first.get(0)).hasType();
    }

    private CType.Int fixedUnderlying(Type underlying, Token at) {
        CType t = types.unqualified(build(underlying));
        if (!(t instanceof CType.Int i) || i.isBool()) {
            throw new SemaException("enum underlying type must be an integer type ('" + t.spelling() + "')", at);
        }
        return i;
    }

    private boolean fits(BigInteger v, CType.Int t) {
        int w = types.width(t);
        return types.isSigned(t) ? v.bitLength() < w && v.compareTo(BigInteger.ONE.shiftLeft(w - 1).negate()) >= 0
                : v.signum() >= 0 && v.bitLength() <= w;
    }

    private CType.Int narrowest(BigInteger v, Token at) {
        for (CType.Int candidate : List.of(types.int_(), types.uint(), types.long_(), types.ulong())) {
            if (fits(v, candidate)) return candidate;
        }
        throw new SemaException("enumerator value " + v + " does not fit any integer type", at);
    }

    // typeof / typeof_unqual (6.7.3.6): of a type-name or of an expression,
    // which is not evaluated and keeps its own type (an array stays an
    // array, an lvalue keeps its qualifiers). typeof_unqual strips the
    // qualifiers, those of the element type for an array.
    private CType typeof(Type.Typeof t) {
        if (evaluator == null) throw new IllegalStateException("no expression hooks");
        CType operand = t.type().isPresent() ? build(t.type().get()) : evaluator.typeOf(t.expr().orElseThrow());
        if (t.keyword().text.equals("typeof_unqual")) operand = types.unqualified(operand);
        return types.plusQuals(operand, quals(t.quals()));
    }

    static Quals quals(Type.Quals q) {
        return new Quals(q.isConst(), q.isVolatile(), q.isRestrict(), q.isAtomic());
    }

    private static SemaException unsupported(String what, Token at) {
        return new SemaException(what + " are not supported yet", at);
    }
}
