package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Type;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Quals;
import org.jbm.cc.types.Types;

import java.util.ArrayList;
import java.util.List;

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

    TypeBuilder(@NonNull Types types, @NonNull Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
    }

    CType build(@NonNull Type t) {
        if (t instanceof Type.Basic b) return basic(b);
        if (t instanceof Type.Pointer p) return types.qualified(types.pointer(build(p.target())), quals(p.quals()));
        if (t instanceof Type.Array a) return array(a, false);
        if (t instanceof Type.Function f) return function(f);
        if (t instanceof Type.TypedefName n) return types.plusQuals(bindings.typedefOf(n).type(), quals(n.quals()));
        if (t instanceof Type.Typeof to) return typeof(to);
        if (t instanceof Type.BitInt bi) throw unsupported("_BitInt", bi.token());
        if (t instanceof Type.Struct s) throw unsupported(s.keyword().text + " types", s.keyword());
        if (t instanceof Type.Enum e) throw unsupported("enum types", e.keyword());
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

    // Until the constant evaluator exists (step 15) only a plain decimal
    // literal is accepted as an array size.
    private long arraySize(Expr size, Token at) {
        if (size instanceof Expr.Literal l && l.token().type == TokenType.INTEGER_CONSTANT
                && l.token().text.matches("[0-9]+")) {
            long n = Long.parseLong(l.token().text);
            if (n <= 0) throw new SemaException("array size must be positive", l.token());
            return n;
        }
        throw unsupported("array sizes other than an integer literal", at);
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

    private CType typeof(Type.Typeof t) {
        if (t.type().isEmpty()) throw unsupported("typeof of an expression", t.keyword());
        CType operand = build(t.type().get());
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
