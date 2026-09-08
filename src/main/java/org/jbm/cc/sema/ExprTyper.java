package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TExpr.Lvalue;
import org.jbm.cc.tast.TExpr.Rvalue;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;

/**
 * Types expressions (C2y 6.5): builds a {@link TExpr} bottom-up from an
 * AST expression, through helpers whose signatures are the rules of 6.3.
 * {@link #rvalue} is lvalue conversion and decay by narrowing on the
 * category; {@link #convert} inserts one {@link TExpr.Conversion} only
 * when the type changes; {@link Types#usualArithmetic} is pure type
 * arithmetic.
 */
final class ExprTyper {

    private final Types types;
    private final Bindings bindings;

    ExprTyper(@NonNull Types types, @NonNull Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
    }

    TExpr type(@NonNull Expr e) {
        if (e instanceof Expr.Identifier id) return identifier(id);
        if (e instanceof Expr.Literal l) return literal(l);
        if (e instanceof Expr.Binary b) return binary(b);
        throw unsupported(e.getClass().getSimpleName(), tokenOf(e));
    }

    // ---- primary expressions ------------------------------------------------------------

    private TExpr identifier(Expr.Identifier e) {
        Symbol s = bindings.symbolOf(e);
        if (s instanceof Symbol.Function) return new TExpr.FuncRef(s, s.type(), e.name());
        if (s instanceof Symbol.Enumerator) throw unsupported("enumeration constants", e.name());
        return new TExpr.VarRef(s, s.type(), e.name());
    }

    // Until Literals exists (step 7) only an unsuffixed decimal integer is
    // decoded: int, long or long long, the first that can hold it (6.4.5.2p6).
    private TExpr literal(Expr.Literal e) {
        Token t = e.token();
        if (t.type == TokenType.INTEGER_CONSTANT && t.text.matches("[0-9]+")) {
            long value;
            try {
                value = Long.parseLong(t.text);
            } catch (NumberFormatException ex) {
                throw new SemaException("integer constant is too large", t);
            }
            for (CType.Int candidate : new CType.Int[] {types.int_(), types.long_(), types.llong()}) {
                int bits = types.width(candidate) - 1;
                if (bits >= 63 || value < (1L << bits)) return new TExpr.IntConst(value, candidate, t);
            }
        }
        throw unsupported("this kind of literal", t);
    }

    // ---- binary operators ------------------------------------------------------------------

    private TExpr binary(Expr.Binary e) {
        Rvalue l = rvalue(type(e.left()));
        Rvalue r = rvalue(type(e.right()));
        return switch (e.op().text) {
            case "+" -> arithmetic(e.op(), l, r, TExpr.Add::new);
            case "-" -> arithmetic(e.op(), l, r, TExpr.Sub::new);
            case "*" -> arithmetic(e.op(), l, r, TExpr.Mul::new);
            case "/" -> arithmetic(e.op(), l, r, TExpr.Div::new);
            default -> throw unsupported("operator " + e.op().text, e.op());
        };
    }

    private interface ArithmeticNode {
        TExpr make(Rvalue left, Rvalue right, CType type, Token token);
    }

    // 6.5.6 - 6.5.7 for arithmetic operands: usual arithmetic conversions on
    // both, the result in the common type.
    private TExpr arithmetic(Token op, Rvalue l, Rvalue r, ArithmeticNode node) {
        if (!l.type().isArithmetic() || !r.type().isArithmetic()) {
            throw new SemaException("invalid operands to binary " + op.text + " ('" + l.type().spelling()
                    + "' and '" + r.type().spelling() + "')", op);
        }
        CType common = types.usualArithmetic(l.type(), r.type());
        return node.make(convert(l, common), convert(r, common), common, op);
    }

    // ---- the rules of 6.3 -------------------------------------------------------------------

    /**
     * Lvalue conversion, array decay and function decay (6.3.3.1p2-4): an
     * lvalue becomes the value it holds with qualifiers dropped, an array
     * a pointer to its first element, a function designator a pointer to
     * the function. An rvalue is returned unchanged.
     */
    Rvalue rvalue(TExpr x) {
        if (x instanceof Rvalue r) return r;
        if (x instanceof Lvalue lv) {
            if (lv.type() instanceof CType.Array a) {
                return new TExpr.ArrayDecay(lv, types.pointer(a.element()), x.token());
            }
            return new TExpr.LvalueToRvalue(lv, types.unqualified(lv.type()), x.token());
        }
        var fd = (TExpr.FunctionDesignator) x;
        return new TExpr.FunctionDecay(fd, types.pointer(fd.type()), x.token());
    }

    /** {@code x} converted to {@code to}: itself when the type already matches, else one conversion node. */
    Rvalue convert(Rvalue x, CType to) {
        if (x.type() == to) return x;
        if (x.type().isInteger() && to.isInteger()) return new TExpr.IntToInt(x, to, x.token());
        throw unsupported("conversion from '" + x.type().spelling() + "' to '" + to.spelling() + "'", x.token());
    }

    // ---- helpers -------------------------------------------------------------------------------

    static Token tokenOf(Expr e) {
        if (e instanceof Expr.Identifier i) return i.name();
        if (e instanceof Expr.Literal l) return l.token();
        if (e instanceof Expr.StringLiteral s) return s.parts().get(0);
        if (e instanceof Expr.Generic g) return g.keyword();
        if (e instanceof Expr.Index i) return i.bracket();
        if (e instanceof Expr.Call c) return c.paren();
        if (e instanceof Expr.Member m) return m.op();
        if (e instanceof Expr.Postfix p) return p.op();
        if (e instanceof Expr.CompoundLiteral c) return c.paren();
        if (e instanceof Expr.Unary u) return u.op();
        if (e instanceof Expr.TypeOperator t) return t.op();
        if (e instanceof Expr.StaticAssertion s) return s.keyword();
        if (e instanceof Expr.Cast c) return c.paren();
        if (e instanceof Expr.Binary b) return b.op();
        if (e instanceof Expr.Conditional c) return c.question();
        if (e instanceof Expr.Assign a) return a.op();
        if (e instanceof Expr.Comma c) return c.comma();
        throw new IllegalStateException(e.toString());
    }

    private static SemaException unsupported(String what, Token at) {
        return new SemaException(what + " not supported yet", at);
    }
}
