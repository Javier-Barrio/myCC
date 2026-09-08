package org.jbm.cc.sema;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TExpr.Constant;
import org.jbm.cc.tast.TExpr.Rvalue;
import org.jbm.cc.tast.TVisitor;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Constant expressions (C2y 6.6), evaluated on the typed tree because
 * folding needs the result type: integer arithmetic wraps at the width
 * the target gives an unsigned type and is an error when it overflows a
 * signed one, conversions narrow the way 6.3.2.3 says, comparisons yield
 * {@code int}. A visit returns the folded {@link Constant} or null when
 * the node is not a constant expression; nothing is memoized, since each
 * constant-required site folds its own expression once.
 * <p>
 * Address constants (6.6p9): the address of a static-storage object or a
 * function, a member or element of one, and pointer arithmetic on such
 * an address fold to {@link TExpr.AddrConst}; a null pointer constant is
 * the address constant with no base.
 */
final class ConstEval implements TVisitor<@Nullable Constant> {

    private final Types types;

    ConstEval(@NonNull Types types) {
        this.types = types;
    }

    Optional<Constant> fold(@NonNull Rvalue x) {
        return Optional.ofNullable(x.accept(this));
    }

    /** The folded constant, or a diagnostic naming what needed one. */
    Constant require(@NonNull Rvalue x, @NonNull Token at, @NonNull String what) {
        Constant c = x.accept(this);
        if (c == null) throw new SemaException(what + " is not a constant expression", at);
        return c;
    }

    /** An integer constant expression's value (6.6p6). */
    long requireInteger(@NonNull Rvalue x, @NonNull Token at, @NonNull String what) {
        Constant c = require(x, at, what);
        if (!(c instanceof TExpr.IntConst i)) {
            throw new SemaException(what + " must be an integer constant expression", at);
        }
        return i.value();
    }

    // ---- integer representation ----------------------------------------------------------

    // The mathematical value of an integer constant: its bits read as
    // signed or unsigned per its type.
    private BigInteger big(TExpr.IntConst c) {
        long v = c.value();
        if (types.isSigned(c.type()) || v >= 0) return BigInteger.valueOf(v);
        return BigInteger.valueOf(v).add(BigInteger.ONE.shiftLeft(64));
    }

    // A value reduced to a type's width: modulo 2^w for unsigned types
    // (6.3.2.3p2) and for conversions to signed types, which C leaves
    // implementation-defined and every two's-complement target wraps.
    private long wrap(BigInteger v, CType t) {
        int w = t.isBool() ? 1 : types.width(t);
        BigInteger mask = BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
        BigInteger bits = v.and(mask);
        if (types.isSigned(t) && bits.testBit(w - 1)) bits = bits.subtract(BigInteger.ONE.shiftLeft(w));
        return bits.longValue();
    }

    // An arithmetic result: wrapped for unsigned types, an error when it
    // does not fit a signed one (6.6p4, 6.5p5).
    private TExpr.IntConst result(BigInteger v, CType t, Token at) {
        if (types.isSigned(t)) {
            int w = types.width(t);
            BigInteger min = BigInteger.ONE.shiftLeft(w - 1).negate();
            BigInteger max = BigInteger.ONE.shiftLeft(w - 1).subtract(BigInteger.ONE);
            if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
                throw new SemaException("integer overflow in constant expression", at);
            }
        }
        return new TExpr.IntConst(wrap(v, t), t, at);
    }

    private TExpr.IntConst intResult(long v, Token at) {
        return new TExpr.IntConst(v, types.int_(), at);
    }

    private static boolean isZero(Constant c) {
        if (c instanceof TExpr.IntConst i) return i.value() == 0;
        if (c instanceof TExpr.FloatConst f) return f.value() == 0.0;
        if (c instanceof TExpr.AddrConst a) return a.isNull();
        return true; // nullptr
    }

    // The address an lvalue designates, when it is a constant: an object
    // with static storage duration (6.6p9), a member or element of one.
    private TExpr.@Nullable AddrConst address(TExpr.Lvalue lv, CType pointerType, Token at) {
        if (lv instanceof TExpr.VarRef v) {
            if (!(v.symbol() instanceof Symbol.Variable var) || var.storage != Symbol.Variable.Storage.STATIC) return null;
            return new TExpr.AddrConst(Optional.of(v.symbol()), 0, pointerType, at);
        }
        if (lv instanceof TExpr.Member m) {
            if (m.member().bits().isPresent()) return null;
            TExpr.AddrConst base = address(m.base(), pointerType, at);
            return base == null ? null : new TExpr.AddrConst(base.base(), base.offset() + m.member().offset(), pointerType, at);
        }
        if (lv instanceof TExpr.Deref d) {
            Constant p = d.pointer().accept(this);
            return p instanceof TExpr.AddrConst a ? new TExpr.AddrConst(a.base(), a.offset(), pointerType, at) : null;
        }
        if (lv instanceof TExpr.CompoundLit c && ((Symbol.Variable) c.symbol()).storage == Symbol.Variable.Storage.STATIC) {
            return new TExpr.AddrConst(Optional.of(c.symbol()), 0, pointerType, at);
        }
        return null;
    }

    // ---- leaves ------------------------------------------------------------------------------

    @Override
    public Constant visit(TExpr.VarRef e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.FuncRef e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.Deref e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.FuncDeref e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.Member e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.Materialize e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.CompoundLit e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.IntConst e) {
        return e;
    }

    @Override
    public Constant visit(TExpr.FloatConst e) {
        return e;
    }

    @Override
    public Constant visit(TExpr.NullptrConst e) {
        return e;
    }

    @Override
    public Constant visit(TExpr.AddrConst e) {
        return e;
    }

    // ---- conversions ---------------------------------------------------------------------------

    @Override
    public Constant visit(TExpr.LvalueToRvalue e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.ArrayDecay e) {
        return address(e.operand(), e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.FunctionDecay e) {
        if (e.operand() instanceof TExpr.FuncRef f) return new TExpr.AddrConst(Optional.of(f.symbol()), 0, e.type(), e.token());
        Constant p = ((TExpr.FuncDeref) e.operand()).pointer().accept(this);
        return p instanceof TExpr.AddrConst a ? new TExpr.AddrConst(a.base(), a.offset(), e.type(), e.token()) : null;
    }

    @Override
    public Constant visit(TExpr.IntToInt e) {
        Constant c = e.operand().accept(this);
        if (!(c instanceof TExpr.IntConst i)) return null;
        return new TExpr.IntConst(wrap(big(i), e.type()), e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.IntToFloat e) {
        Constant c = e.operand().accept(this);
        if (!(c instanceof TExpr.IntConst i)) return null;
        double v = big(i).doubleValue();
        if (e.type() == types.float_()) v = (float) v;
        return new TExpr.FloatConst(v, e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.FloatToInt e) {
        Constant c = e.operand().accept(this);
        if (!(c instanceof TExpr.FloatConst f)) return null;
        double truncated = f.value() < 0 ? Math.ceil(f.value()) : Math.floor(f.value());
        if (Double.isNaN(truncated) || Double.isInfinite(truncated)) {
            throw new SemaException("floating constant cannot be converted to an integer", e.token());
        }
        BigInteger v = new java.math.BigDecimal(truncated).toBigInteger();
        CType t = e.type();
        int w = types.width(t);
        BigInteger min = types.isSigned(t) ? BigInteger.ONE.shiftLeft(w - 1).negate() : BigInteger.ZERO;
        BigInteger max = types.isSigned(t) ? BigInteger.ONE.shiftLeft(w - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw new SemaException("floating constant is out of range for '" + t.spelling() + "'", e.token());
        }
        return new TExpr.IntConst(wrap(v, t), t, e.token());
    }

    @Override
    public Constant visit(TExpr.FloatToFloat e) {
        Constant c = e.operand().accept(this);
        if (!(c instanceof TExpr.FloatConst f)) return null;
        double v = e.type() == types.float_() ? (float) f.value() : f.value();
        return new TExpr.FloatConst(v, e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.ToBool e) {
        Constant c = e.operand().accept(this);
        if (c == null) return null;
        return new TExpr.IntConst(isZero(c) ? 0 : 1, e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.ToVoid e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.PtrToPtr e) {
        Constant c = e.operand().accept(this);
        return c instanceof TExpr.AddrConst a ? new TExpr.AddrConst(a.base(), a.offset(), e.type(), e.token()) : null;
    }

    // An integer cast to a pointer is an address constant in the
    // implementation-defined sense of 6.6p10: the absolute address.
    @Override
    public Constant visit(TExpr.IntToPtr e) {
        Constant c = e.operand().accept(this);
        return c instanceof TExpr.IntConst i ? new TExpr.AddrConst(Optional.empty(), i.value(), e.type(), e.token()) : null;
    }

    // A pointer's integer value is not a constant expression (6.6p10 lets
    // an implementation accept it; this one does not).
    @Override
    public Constant visit(TExpr.PtrToInt e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.NullToPtr e) {
        Constant c = e.operand().accept(this);
        return c == null ? null : new TExpr.AddrConst(Optional.empty(), 0, e.type(), e.token());
    }

    // ---- pointer operators ---------------------------------------------------------------------

    @Override
    public Constant visit(TExpr.AddrOf e) {
        return address(e.operand(), e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.PtrAdd e) {
        Constant p = e.pointer().accept(this);
        Constant i = e.index().accept(this);
        if (!(p instanceof TExpr.AddrConst a) || !(i instanceof TExpr.IntConst n)) return null;
        long element = types.size(((CType.Pointer) e.type()).target());
        return new TExpr.AddrConst(a.base(), a.offset() + n.value() * element, e.type(), e.token());
    }

    @Override
    public Constant visit(TExpr.PtrDiff e) {
        return null;
    }

    // ---- arithmetic ------------------------------------------------------------------------------

    private interface IntOp {
        BigInteger apply(BigInteger a, BigInteger b);
    }

    private interface FloatOp {
        double apply(double a, double b);
    }

    // Both operands have the node's type, so one representation applies.
    private @Nullable Constant arithmetic(TExpr.Arithmetic e, IntOp intOp, @Nullable FloatOp floatOp) {
        Constant l = e.left().accept(this);
        Constant r = e.right().accept(this);
        if (l == null || r == null) return null;
        if (l instanceof TExpr.IntConst a && r instanceof TExpr.IntConst b) {
            return result(intOp.apply(big(a), big(b)), e.type(), e.token());
        }
        if (floatOp != null && l instanceof TExpr.FloatConst a && r instanceof TExpr.FloatConst b) {
            double v = floatOp.apply(a.value(), b.value());
            if (e.type() == types.float_()) v = (float) v;
            return new TExpr.FloatConst(v, e.type(), e.token());
        }
        return null;
    }

    private BigInteger checkedDivisor(BigInteger b, Token at) {
        if (b.signum() == 0) throw new SemaException("division by zero in constant expression", at);
        return b;
    }

    @Override
    public Constant visit(TExpr.Add e) {
        return arithmetic(e, BigInteger::add, Double::sum);
    }

    @Override
    public Constant visit(TExpr.Sub e) {
        return arithmetic(e, BigInteger::subtract, (a, b) -> a - b);
    }

    @Override
    public Constant visit(TExpr.Mul e) {
        return arithmetic(e, BigInteger::multiply, (a, b) -> a * b);
    }

    @Override
    public Constant visit(TExpr.Div e) {
        // Integer division truncates toward zero (6.5.6p6), as BigInteger's does.
        return arithmetic(e, (a, b) -> a.divide(checkedDivisor(b, e.token())), (a, b) -> a / b);
    }

    @Override
    public Constant visit(TExpr.Rem e) {
        return arithmetic(e, (a, b) -> a.remainder(checkedDivisor(b, e.token())), null);
    }

    @Override
    public Constant visit(TExpr.BitAnd e) {
        return arithmetic(e, BigInteger::and, null);
    }

    @Override
    public Constant visit(TExpr.BitOr e) {
        return arithmetic(e, BigInteger::or, null);
    }

    @Override
    public Constant visit(TExpr.BitXor e) {
        return arithmetic(e, BigInteger::xor, null);
    }

    // ---- shifts ------------------------------------------------------------------------------------

    private @Nullable Constant shift(TExpr.Shift e, boolean left) {
        Constant l = e.left().accept(this);
        Constant r = e.right().accept(this);
        if (!(l instanceof TExpr.IntConst a) || !(r instanceof TExpr.IntConst b)) return null;
        CType t = e.type();
        BigInteger amount = big(b);
        if (amount.signum() < 0 || amount.compareTo(BigInteger.valueOf(types.width(t))) >= 0) {
            throw new SemaException("shift amount out of range", e.token());
        }
        int n = amount.intValue();
        // A left shift of a signed operand wraps like the two's-complement
        // multiplication it is; a right shift of a negative value is
        // arithmetic (6.5.8p4-5, as every supported target defines it).
        BigInteger v = left ? big(a).shiftLeft(n) : big(a).shiftRight(n);
        return new TExpr.IntConst(wrap(v, t), t, e.token());
    }

    @Override
    public Constant visit(TExpr.Shl e) {
        return shift(e, true);
    }

    @Override
    public Constant visit(TExpr.Shr e) {
        return shift(e, false);
    }

    // ---- comparisons and logical ---------------------------------------------------------------

    private @Nullable Constant compare(TExpr.Comparison e, java.util.function.IntPredicate test) {
        Constant l = e.left().accept(this);
        Constant r = e.right().accept(this);
        if (l == null || r == null) return null;
        int cmp;
        if (l instanceof TExpr.IntConst a && r instanceof TExpr.IntConst b) cmp = big(a).compareTo(big(b));
        else if (l instanceof TExpr.FloatConst a && r instanceof TExpr.FloatConst b) cmp = Double.compare(a.value(), b.value());
        else if (l instanceof TExpr.NullptrConst && r instanceof TExpr.NullptrConst) cmp = 0;
        else if (l instanceof TExpr.AddrConst a && r instanceof TExpr.AddrConst b) {
            // Addresses compare when they share a base; distinct objects
            // only for equality, where they are never equal (6.5.10p6).
            boolean sameBase = a.base().isEmpty() && b.base().isEmpty()
                    || a.base().isPresent() && b.base().isPresent() && a.base().get() == b.base().get();
            if (sameBase) cmp = Long.compare(a.offset(), b.offset());
            else if (e instanceof TExpr.Eq || e instanceof TExpr.Ne) cmp = 1;
            else return null;
        } else return null;
        return intResult(test.test(cmp) ? 1 : 0, e.token());
    }

    @Override
    public Constant visit(TExpr.Eq e) {
        return compare(e, c -> c == 0);
    }

    @Override
    public Constant visit(TExpr.Ne e) {
        return compare(e, c -> c != 0);
    }

    @Override
    public Constant visit(TExpr.Lt e) {
        return compare(e, c -> c < 0);
    }

    @Override
    public Constant visit(TExpr.Le e) {
        return compare(e, c -> c <= 0);
    }

    @Override
    public Constant visit(TExpr.Gt e) {
        return compare(e, c -> c > 0);
    }

    @Override
    public Constant visit(TExpr.Ge e) {
        return compare(e, c -> c >= 0);
    }

    @Override
    public Constant visit(TExpr.And e) {
        Constant l = e.left().accept(this);
        Constant r = e.right().accept(this);
        if (l == null || r == null) return null;
        return intResult(!isZero(l) && !isZero(r) ? 1 : 0, e.token());
    }

    @Override
    public Constant visit(TExpr.Or e) {
        Constant l = e.left().accept(this);
        Constant r = e.right().accept(this);
        if (l == null || r == null) return null;
        return intResult(!isZero(l) || !isZero(r) ? 1 : 0, e.token());
    }

    // ---- unary -------------------------------------------------------------------------------------

    @Override
    public Constant visit(TExpr.Neg e) {
        Constant c = e.operand().accept(this);
        if (c instanceof TExpr.IntConst i) return result(big(i).negate(), e.type(), e.token());
        if (c instanceof TExpr.FloatConst f) return new TExpr.FloatConst(-f.value(), e.type(), e.token());
        return null;
    }

    @Override
    public Constant visit(TExpr.BitNot e) {
        Constant c = e.operand().accept(this);
        if (!(c instanceof TExpr.IntConst i)) return null;
        CType t = e.type();
        return new TExpr.IntConst(wrap(big(i).not(), t), t, e.token());
    }

    @Override
    public Constant visit(TExpr.Not e) {
        Constant c = e.operand().accept(this);
        return c == null ? null : intResult(isZero(c) ? 1 : 0, e.token());
    }

    // ---- the rest ------------------------------------------------------------------------------------

    @Override
    public Constant visit(TExpr.Call e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.Assign e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.CompoundAssign e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.PostfixAssign e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.TargetValue e) {
        return null;
    }

    @Override
    public Constant visit(TExpr.Cond e) {
        // Only the chosen arm is evaluated (6.6p3 allows anything in the other).
        Constant c = e.condition().accept(this);
        if (c == null) return null;
        return (isZero(c) ? e.elseValue() : e.thenValue()).accept(this);
    }

    @Override
    public Constant visit(TExpr.Comma e) {
        return null;
    }
}
