package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.tast.StringData;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TExpr.Lvalue;
import org.jbm.cc.tast.TExpr.Rvalue;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;

import java.util.ArrayList;
import java.util.List;

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
    private final Literals literals;

    /** The string literals typed so far, in order; each one is its own object (6.4.5p7). */
    final List<StringData> strings = new ArrayList<>();
    private int nextId;

    ExprTyper(@NonNull Types types, @NonNull Bindings bindings) {
        this.types = types;
        this.bindings = bindings;
        this.literals = new Literals(types);
        this.nextId = bindings.symbolCount;
    }

    TExpr type(@NonNull Expr e) {
        if (e instanceof Expr.Identifier id) return identifier(id);
        if (e instanceof Expr.Literal l) return literals.constant(l.token());
        if (e instanceof Expr.StringLiteral s) return string(s);
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

    // A string literal denotes an anonymous array object with static
    // storage (6.4.5p7): one symbol per literal, named by its spelling,
    // referenced as an lvalue of array type.
    private TExpr string(Expr.StringLiteral e) {
        Literals.StringValue value = literals.string(e.parts());
        Token first = e.parts().get(0);
        String spelling = e.parts().stream().map(t -> t.text).collect(java.util.stream.Collectors.joining(" "));
        var name = new Token(TokenType.STRING_LITERAL, spelling, first.line, first.column);
        Symbol symbol = Symbol.anonymousStatic(nextId++, name);
        symbol.setType(types.array(value.elementType(), value.units().length));
        strings.add(new StringData(symbol, value.units()));
        return new TExpr.VarRef(symbol, symbol.type(), first);
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
        CType from = x.type();
        if (from == to) return x;
        if (from.isInteger() && to.isInteger()) return new TExpr.IntToInt(x, to, x.token());
        if (from.isInteger() && to.isFloating()) return new TExpr.IntToFloat(x, to, x.token());
        if (from.isFloating() && to.isInteger()) return new TExpr.FloatToInt(x, to, x.token());
        if (from.isFloating() && to.isFloating()) return new TExpr.FloatToFloat(x, to, x.token());
        throw unsupported("conversion from '" + from.spelling() + "' to '" + to.spelling() + "'", x.token());
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
