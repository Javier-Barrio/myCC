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
        if (e instanceof Expr.Unary u) return unary(u);
        if (e instanceof Expr.Conditional c) return conditional(c);
        if (e instanceof Expr.Comma c) return comma(c);
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
        Token op = e.op();
        Rvalue l = rvalue(type(e.left()));
        Rvalue r = rvalue(type(e.right()));
        return switch (op.text) {
            case "+" -> arithmetic(op, l, r, false, TExpr.Add::new);
            case "-" -> arithmetic(op, l, r, false, TExpr.Sub::new);
            case "*" -> arithmetic(op, l, r, false, TExpr.Mul::new);
            case "/" -> arithmetic(op, l, r, false, TExpr.Div::new);
            case "%" -> arithmetic(op, l, r, true, TExpr.Rem::new);
            case "&" -> arithmetic(op, l, r, true, TExpr.BitAnd::new);
            case "|" -> arithmetic(op, l, r, true, TExpr.BitOr::new);
            case "^" -> arithmetic(op, l, r, true, TExpr.BitXor::new);
            case "<<" -> shift(op, l, r, TExpr.Shl::new);
            case ">>" -> shift(op, l, r, TExpr.Shr::new);
            case "==" -> comparison(op, l, r, TExpr.Eq::new);
            case "!=" -> comparison(op, l, r, TExpr.Ne::new);
            case "<" -> comparison(op, l, r, TExpr.Lt::new);
            case "<=" -> comparison(op, l, r, TExpr.Le::new);
            case ">" -> comparison(op, l, r, TExpr.Gt::new);
            case ">=" -> comparison(op, l, r, TExpr.Ge::new);
            case "&&" -> logical(op, l, r, TExpr.And::new);
            case "||" -> logical(op, l, r, TExpr.Or::new);
            default -> throw unsupported("operator " + op.text, op);
        };
    }

    private interface BinaryNode {
        TExpr make(Rvalue left, Rvalue right, CType type, Token token);
    }

    // 6.5.6 - 6.5.7, 6.5.11 - 6.5.13 for arithmetic operands: usual
    // arithmetic conversions on both, the result in the common type;
    // %, &, ^ and | need integer operands.
    private TExpr arithmetic(Token op, Rvalue l, Rvalue r, boolean integerOnly, BinaryNode node) {
        boolean ok = integerOnly ? l.type().isInteger() && r.type().isInteger()
                : l.type().isArithmetic() && r.type().isArithmetic();
        if (!ok) throw invalidOperands(op, l, r);
        CType common = types.usualArithmetic(l.type(), r.type());
        return node.make(convert(l, common), convert(r, common), common, op);
    }

    // 6.5.8: integer operands, each promoted on its own; the result has
    // the promoted left operand's type.
    private TExpr shift(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (!l.type().isInteger() || !r.type().isInteger()) throw invalidOperands(op, l, r);
        Rvalue value = promote(l);
        return node.make(value, promote(r), value.type(), op);
    }

    // 6.5.9 - 6.5.10 for arithmetic operands; pointer comparisons come later.
    private TExpr comparison(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (!l.type().isArithmetic() || !r.type().isArithmetic()) {
            if (l.type().isPointer() || r.type().isPointer()) throw unsupported("pointer comparison", op);
            throw invalidOperands(op, l, r);
        }
        CType common = types.usualArithmetic(l.type(), r.type());
        return node.make(convert(l, common), convert(r, common), types.int_(), op);
    }

    // 6.5.14 - 6.5.15: scalar operands, each tested against zero.
    private TExpr logical(Token op, Rvalue l, Rvalue r, BinaryNode node) {
        if (!l.type().isScalar() || !r.type().isScalar()) throw invalidOperands(op, l, r);
        return node.make(toBool(l), toBool(r), types.int_(), op);
    }

    private static SemaException invalidOperands(Token op, Rvalue l, Rvalue r) {
        return new SemaException("invalid operands to binary " + op.text + " ('" + l.type().spelling()
                + "' and '" + r.type().spelling() + "')", op);
    }

    // ---- unary operators (6.5.4) --------------------------------------------------------------

    private TExpr unary(Expr.Unary e) {
        Token op = e.op();
        switch (op.text) {
            case "+", "-", "~", "!" -> {
                Rvalue x = rvalue(type(e.operand()));
                return switch (op.text) {
                    case "+" -> requireArithmetic(op, x) ? promote(x) : null;
                    case "-" -> requireArithmetic(op, x) ? new TExpr.Neg(promote(x), types.promote(x.type()), op) : null;
                    case "~" -> {
                        if (!x.type().isInteger()) throw invalidOperand(op, x);
                        yield new TExpr.BitNot(promote(x), types.promote(x.type()), op);
                    }
                    default -> {
                        if (!x.type().isScalar()) throw invalidOperand(op, x);
                        yield new TExpr.Not(toBool(x), types.int_(), op);
                    }
                };
            }
            default -> throw unsupported("unary operator " + op.text, op);
        }
    }

    private static boolean requireArithmetic(Token op, Rvalue x) {
        if (!x.type().isArithmetic()) throw invalidOperand(op, x);
        return true;
    }

    private static SemaException invalidOperand(Token op, Rvalue x) {
        return new SemaException("invalid operand to unary " + op.text + " ('" + x.type().spelling() + "')", op);
    }

    // ---- conditional and comma (6.5.16, 6.5.18) ------------------------------------------------

    private TExpr conditional(Expr.Conditional e) {
        Rvalue c = rvalue(type(e.condition()));
        if (!c.type().isScalar()) {
            throw new SemaException("condition of ?: must be scalar ('" + c.type().spelling() + "')", e.question());
        }
        Rvalue t = rvalue(type(e.thenExpr()));
        Rvalue f = rvalue(type(e.elseExpr()));
        CType result;
        if (t.type().isArithmetic() && f.type().isArithmetic()) {
            result = types.usualArithmetic(t.type(), f.type());
        } else if (t.type().isVoid() && f.type().isVoid()) {
            result = types.void_();
        } else {
            throw unsupported("?: with operands '" + t.type().spelling() + "' and '" + f.type().spelling() + "'",
                    e.question());
        }
        return new TExpr.Cond(toBool(c), convert(t, result), convert(f, result), result, e.question());
    }

    private TExpr comma(Expr.Comma e) {
        Rvalue left = toVoid(rvalue(type(e.left())));
        Rvalue right = rvalue(type(e.right()));
        return new TExpr.Comma(left, right, right.type(), e.comma());
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

    /** Integer promotions (6.3.2.1) applied to a value. */
    Rvalue promote(Rvalue x) {
        return convert(x, types.promote(x.type()));
    }

    Rvalue toBool(Rvalue x) {
        return convert(x, types.bool_());
    }

    Rvalue toVoid(Rvalue x) {
        return convert(x, types.void_());
    }

    /** {@code x} converted to {@code to}: itself when the type already matches, else one conversion node. */
    Rvalue convert(Rvalue x, CType to) {
        CType from = x.type();
        if (from == to) return x;
        if (to.isVoid()) return new TExpr.ToVoid(x, to, x.token());
        // Conversion to bool is a comparison against zero (6.3.2.2p1), not a
        // truncation, so it precedes the integer case.
        if (to.isBool() && from.isScalar()) return new TExpr.ToBool(x, to, x.token());
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
