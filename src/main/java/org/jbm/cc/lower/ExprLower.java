package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Operand;
import org.jbm.cc.tac.TargetDesc;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TInit;
import org.jbm.cc.tast.TVisitor;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Types;

import java.util.Map;
import java.util.Optional;

/**
 * Expressions to TAC variables ({@code lower-plan.md}, "Expressions"):
 * {@link #value} lowers an rvalue to a {@link Val}, {@link #place} an
 * lvalue to a {@link Place}, and {@link #read}, {@link #write} and
 * {@link #pointer} are the three things a place is used for.
 */
final class ExprLower implements TVisitor<Val> {

    private final Types types;
    private final TypeMap typeMap;
    private final Names names;
    private final TargetDesc target;
    private final Builder b;
    private final Map<Symbol, Var> vars;

    ExprLower(@NonNull Lower lower, @NonNull Builder b, @NonNull Map<Symbol, Var> vars) {
        this.types = lower.types;
        this.typeMap = lower.typeMap;
        this.names = lower.names;
        this.target = lower.module.target;
        this.b = b;
        this.vars = vars;
    }

    // ---- canonical form -------------------------------------------------------------------------

    private int classWidth(CType t) {
        return target.widthOf(target.classOf(typeMap.of(t)));
    }

    /**
     * Restores canonical form for {@code t} in {@code r}: a sign extension
     * from the type's width for a signed type narrower than its class, a
     * mask for an unsigned one, nothing for a type as wide as its class.
     */
    private void canon(Var r, CType t, Token at) {
        int n = types.width(t), c = classWidth(t);
        if (n >= c) return;
        if (types.isSigned(t)) {
            b.emit(new Instr.Bin(Instr.BinOp.SHL, r, r, new Operand.IntImm(c - n), at));
            b.emit(new Instr.Bin(Instr.BinOp.ASHR, r, r, new Operand.IntImm(c - n), at));
        } else {
            b.emit(new Instr.Bin(Instr.BinOp.AND, r, r, new Operand.IntImm((1L << n) - 1), at));
        }
    }

    /**
     * An integer value of C type {@code from}, canonical, to a variable of
     * TAC type {@code dest} holding C type {@code to} canonically
     * ({@code lower-plan.md}, the IntToInt rows).
     */
    private Val convertInt(Val v, CType to, Type dest, Token at) {
        CType from = v.type();
        if (typeMap.of(from).equals(dest)) return new Val(v.var(), to);
        int wf = types.width(from), wt = types.width(to);
        boolean sf = from.isInteger() && types.isSigned(from), st = types.isSigned(to);
        Var r = b.temp(dest);
        b.emit(new Instr.Mov(r, v.var(), at));
        int cf = classWidth(from), ct = classWidth(to);
        if (cf < ct) {
            if (sf) {
                b.emit(new Instr.Bin(Instr.BinOp.SHL, r, r, new Operand.IntImm(ct - cf), at));
                b.emit(new Instr.Bin(Instr.BinOp.ASHR, r, r, new Operand.IntImm(ct - cf), at));
            }
            return new Val(r, to);
        }
        boolean implied = cf == ct && (wt > wf && (!sf || st) || wt == wf && sf == st);
        if (!implied) canon(r, to, at);
        return new Val(r, to);
    }

    Val value(@NonNull TExpr.Rvalue e) {
        return e.accept(this);
    }

    private Var temp(CType t) {
        return b.temp(typeMap.of(t));
    }

    private static UnsupportedOperationException notYet(TExpr e) {
        return new UnsupportedOperationException("lowering of " + e.getClass().getSimpleName() + " is not implemented");
    }

    // ---- places ----------------------------------------------------------------------------------

    Place place(@NonNull TExpr.Lvalue e) {
        if (e instanceof TExpr.VarRef v) {
            Var local = vars.get(v.symbol());
            if (local != null && !v.type().isArray() && !v.type().isRecord()) return new Place.Variable(local, v.type());
            Var p = b.temp(Type.PTR);
            if (local != null) b.emit(new Instr.AddrOfVar(p, local, v.token()));
            else b.emit(new Instr.AddrOfGlobal(p, names.of(v.symbol()), v.token()));
            return memory(p, v.type());
        }
        if (e instanceof TExpr.Deref d) return memory(value(d.pointer()).var(), d.type());
        throw notYet(e);
    }

    private static Place.Memory memory(Var ptr, CType type) {
        return new Place.Memory(ptr, type, Optional.empty(), type.quals().isVolatile());
    }

    /** The value at a place: the variable itself, or a load. */
    Val read(@NonNull Place place, @NonNull Token at) {
        if (place instanceof Place.Variable v) return new Val(v.var(), v.type());
        var m = (Place.Memory) place;
        CType t = m.type();
        if (t.isArray() || t.isRecord()) return new Val(m.ptr(), t);
        Var r = temp(t);
        b.emit(new Instr.Load(r, m.ptr(), width(t), ext(t), m.isVolatile(), at));
        return new Val(r, t);
    }

    /** Stores a value at a place: a mov, or a store. */
    void write(@NonNull Place place, @NonNull Val v, @NonNull Token at) {
        if (place instanceof Place.Variable pv) {
            b.emit(new Instr.Mov(pv.var(), v.var(), at));
            return;
        }
        var m = (Place.Memory) place;
        CType t = m.type();
        if (t.isArray() || t.isRecord()) {
            b.emit(new Instr.Copy(typeMap.of(t), m.ptr(), v.var(), at));
            return;
        }
        b.emit(new Instr.Store(m.ptr(), v.var(), width(t), t.isFloating(), m.isVolatile(), at));
    }

    /** The address of a place, into a ptr variable. */
    Var pointer(@NonNull Place place, @NonNull Token at) {
        if (place instanceof Place.Memory m) return m.ptr();
        var v = (Place.Variable) place;
        Var p = b.temp(Type.PTR);
        b.emit(new Instr.AddrOfVar(p, v.var(), at));
        return p;
    }

    private int width(CType t) {
        if (t.isFloating()) {
            Type tt = typeMap.of(t);
            return ((Type.Float) tt).width();
        }
        return typeMap.integer(t.isPointer() || t.isNullptr() ? types.sizeT() : t).width();
    }

    private Instr.Ext ext(CType t) {
        if (t.isFloating()) return Instr.Ext.FLOAT;
        if (t.isPointer() || t.isNullptr()) return Instr.Ext.UNSIGNED;
        return types.isSigned(t) ? Instr.Ext.SIGNED : Instr.Ext.UNSIGNED;
    }

    // ---- lvalues and designators -------------------------------------------------------------------

    @Override
    public Val visit(TExpr.VarRef e) {
        throw new IllegalStateException("an lvalue is lowered through place()");
    }

    @Override
    public Val visit(TExpr.FuncRef e) {
        throw new IllegalStateException("a function designator is lowered through its decay");
    }

    @Override
    public Val visit(TExpr.Deref e) {
        throw new IllegalStateException("an lvalue is lowered through place()");
    }

    @Override
    public Val visit(TExpr.FuncDeref e) {
        throw new IllegalStateException("a function designator is lowered through its decay");
    }

    @Override
    public Val visit(TExpr.Member e) {
        throw new IllegalStateException("an lvalue is lowered through place()");
    }

    @Override
    public Val visit(TExpr.Materialize e) {
        throw new IllegalStateException("an lvalue is lowered through place()");
    }

    @Override
    public Val visit(TExpr.CompoundLit e) {
        throw new IllegalStateException("an lvalue is lowered through place()");
    }

    // ---- constants ---------------------------------------------------------------------------------

    @Override
    public Val visit(TExpr.IntConst e) {
        Var r = temp(e.type());
        b.emit(new Instr.Mov(r, new Operand.IntImm(e.value()), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.FloatConst e) {
        Var r = temp(e.type());
        b.emit(new Instr.Mov(r, new Operand.FloatImm(e.value()), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.NullptrConst e) {
        Var r = b.temp(Type.PTR);
        b.emit(new Instr.Mov(r, new Operand.IntImm(0), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.AddrConst e) {
        throw notYet(e);
    }

    // ---- conversions ---------------------------------------------------------------------------------

    @Override
    public Val visit(TExpr.LvalueToRvalue e) {
        return read(place(e.operand()), e.token());
    }

    @Override
    public Val visit(TExpr.ArrayDecay e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.FunctionDecay e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.IntToInt e) {
        return convertInt(value(e.operand()), e.type(), typeMap.of(e.type()), e.token());
    }

    @Override
    public Val visit(TExpr.IntToFloat e) {
        Val v = value(e.operand());
        Var r = temp(e.type());
        b.emit(new Instr.Cvt(types.isSigned(v.type()) ? Instr.CvtOp.I2F : Instr.CvtOp.U2F, r, v.var(), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.FloatToInt e) {
        Val v = value(e.operand());
        Var r = temp(e.type());
        b.emit(new Instr.Cvt(types.isSigned(e.type()) ? Instr.CvtOp.F2I : Instr.CvtOp.F2U, r, v.var(), e.token()));
        canon(r, e.type(), e.token());
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.FloatToFloat e) {
        Val v = value(e.operand());
        if (typeMap.of(v.type()).equals(typeMap.of(e.type()))) return new Val(v.var(), e.type());
        Var r = temp(e.type());
        b.emit(new Instr.Cvt(Instr.CvtOp.FCVT, r, v.var(), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.ToBool e) {
        Val v = value(e.operand());
        Var r = temp(e.type());
        if (v.type().isFloating()) b.emit(new Instr.Cmp(Instr.CmpOp.FNE, r, v.var(), new Operand.FloatImm(0.0), e.token()));
        else b.emit(new Instr.Cmp(Instr.CmpOp.NE, r, v.var(), new Operand.IntImm(0), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.ToVoid e) {
        value(e.operand());
        return new Val(null, e.type());
    }

    @Override
    public Val visit(TExpr.PtrToPtr e) {
        return new Val(value(e.operand()).var(), e.type());
    }

    // An integer to a pointer is the conversion to an unsigned integer of
    // the pointer width, into a ptr variable.
    @Override
    public Val visit(TExpr.IntToPtr e) {
        return new Val(convertInt(value(e.operand()), types.sizeT(), Type.PTR, e.token()).var(), e.type());
    }

    @Override
    public Val visit(TExpr.PtrToInt e) {
        return convertInt(value(e.operand()), e.type(), typeMap.of(e.type()), e.token());
    }

    @Override
    public Val visit(TExpr.NullToPtr e) {
        Var r = b.temp(Type.PTR);
        b.emit(new Instr.Mov(r, new Operand.IntImm(0), e.token()));
        return new Val(r, e.type());
    }

    // ---- operators --------------------------------------------------------------------------------

    @Override
    public Val visit(TExpr.AddrOf e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.PtrAdd e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.PtrDiff e) {
        throw notYet(e);
    }

    /**
     * A binary operation in the node's type: the wrapping form for an
     * unsigned type, the plain form for a signed one, the floating form
     * for a floating one; then canonical form for a {@code _BitInt}.
     */
    private Val binary(TExpr.Rvalue left, TExpr.Rvalue right, CType t, Token at,
                       Instr.BinOp wrapping, Instr.BinOp signed, Instr.BinOp floating) {
        Val l = value(left);
        Val r = value(right);
        Instr.BinOp op = t.isFloating() ? floating : types.isSigned(t) ? signed : wrapping;
        Var d = temp(t);
        b.emit(new Instr.Bin(op, d, l.var(), r.var(), at));
        if (t instanceof CType.BitInt) canon(d, t, at);
        return new Val(d, t);
    }

    @Override
    public Val visit(TExpr.Add e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.WADD, Instr.BinOp.ADD, Instr.BinOp.FADD);
    }

    @Override
    public Val visit(TExpr.Sub e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.WSUB, Instr.BinOp.SUB, Instr.BinOp.FSUB);
    }

    @Override
    public Val visit(TExpr.Mul e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.WMUL, Instr.BinOp.MUL, Instr.BinOp.FMUL);
    }

    @Override
    public Val visit(TExpr.Div e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.UDIV, Instr.BinOp.SDIV, Instr.BinOp.FDIV);
    }

    @Override
    public Val visit(TExpr.Rem e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.UREM, Instr.BinOp.SREM, Instr.BinOp.FDIV);
    }

    @Override
    public Val visit(TExpr.BitAnd e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.AND, Instr.BinOp.AND, Instr.BinOp.AND);
    }

    @Override
    public Val visit(TExpr.BitOr e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.OR, Instr.BinOp.OR, Instr.BinOp.OR);
    }

    @Override
    public Val visit(TExpr.BitXor e) {
        return binary(e.left(), e.right(), e.type(), e.token(), Instr.BinOp.XOR, Instr.BinOp.XOR, Instr.BinOp.XOR);
    }

    // The amount was promoted on its own and is in W; when the left is in
    // L it is moved into an L first, since a shift's operands share a class.
    private Val shift(TExpr.Rvalue left, TExpr.Rvalue right, CType t, Instr.BinOp op, Token at) {
        Val l = value(left);
        Val r = value(right);
        Var amount = r.var();
        if (target.classOf(typeMap.of(t)) != target.classOf(amount.type)) {
            amount = b.temp(typeMap.of(t));
            b.emit(new Instr.Mov(amount, r.var(), at));
        }
        Var d = temp(t);
        b.emit(new Instr.Bin(op, d, l.var(), amount, at));
        if (t instanceof CType.BitInt) canon(d, t, at);
        return new Val(d, t);
    }

    @Override
    public Val visit(TExpr.Shl e) {
        return shift(e.left(), e.right(), e.type(), Instr.BinOp.SHL, e.token());
    }

    @Override
    public Val visit(TExpr.Shr e) {
        return shift(e.left(), e.right(), e.type(), types.isSigned(e.type()) ? Instr.BinOp.ASHR : Instr.BinOp.LSHR, e.token());
    }

    /**
     * A comparison of two operands of one type into a W holding 0 or 1;
     * signed, unsigned (pointers included) or floating by the operand
     * type. Operands are evaluated left to right and only swapped in the
     * instruction, for {@code >} and {@code >=}.
     */
    private Val compare(TExpr.Rvalue left, TExpr.Rvalue right, CType t, Token at, boolean swap,
                        Instr.CmpOp signed, Instr.CmpOp unsigned, Instr.CmpOp floating) {
        Val l = value(left);
        Val r = value(right);
        CType ot = l.type();
        Instr.CmpOp op = ot.isFloating() ? floating : ot.isInteger() && types.isSigned(ot) ? signed : unsigned;
        Var d = temp(t);
        b.emit(new Instr.Cmp(op, d, swap ? r.var() : l.var(), swap ? l.var() : r.var(), at));
        return new Val(d, t);
    }

    @Override
    public Val visit(TExpr.Eq e) {
        return compare(e.left(), e.right(), e.type(), e.token(), false, Instr.CmpOp.EQ, Instr.CmpOp.EQ, Instr.CmpOp.FEQ);
    }

    @Override
    public Val visit(TExpr.Ne e) {
        return compare(e.left(), e.right(), e.type(), e.token(), false, Instr.CmpOp.NE, Instr.CmpOp.NE, Instr.CmpOp.FNE);
    }

    @Override
    public Val visit(TExpr.Lt e) {
        return compare(e.left(), e.right(), e.type(), e.token(), false, Instr.CmpOp.SLT, Instr.CmpOp.ULT, Instr.CmpOp.FLT);
    }

    @Override
    public Val visit(TExpr.Le e) {
        return compare(e.left(), e.right(), e.type(), e.token(), false, Instr.CmpOp.SLE, Instr.CmpOp.ULE, Instr.CmpOp.FLE);
    }

    @Override
    public Val visit(TExpr.Gt e) {
        return compare(e.left(), e.right(), e.type(), e.token(), true, Instr.CmpOp.SLT, Instr.CmpOp.ULT, Instr.CmpOp.FLT);
    }

    @Override
    public Val visit(TExpr.Ge e) {
        return compare(e.left(), e.right(), e.type(), e.token(), true, Instr.CmpOp.SLE, Instr.CmpOp.ULE, Instr.CmpOp.FLE);
    }

    // a && b: the result starts as 0 and becomes b when a is true; a || b
    // starts as 1 and becomes b when a is false. Both operands are bool.
    private Val logical(TExpr.Rvalue left, TExpr.Rvalue right, CType t, Token at, boolean isAnd) {
        Var r = temp(t);
        b.emit(new Instr.Mov(r, new Operand.IntImm(isAnd ? 0 : 1), at));
        Val l = value(left);
        var rhs = b.block(isAnd ? "and" : "or");
        var done = b.block(isAnd ? "and.done" : "or.done");
        b.emit(isAnd ? new Instr.CondBr(l.var(), rhs, done, at) : new Instr.CondBr(l.var(), done, rhs, at));
        b.open(rhs);
        Val rv = value(right);
        b.emit(new Instr.Mov(r, rv.var(), at));
        b.emit(new Instr.Br(done, at));
        b.open(done);
        return new Val(r, t);
    }

    @Override
    public Val visit(TExpr.And e) {
        return logical(e.left(), e.right(), e.type(), e.token(), true);
    }

    @Override
    public Val visit(TExpr.Or e) {
        return logical(e.left(), e.right(), e.type(), e.token(), false);
    }

    @Override
    public Val visit(TExpr.Neg e) {
        Val v = value(e.operand());
        CType t = e.type();
        Var d = temp(t);
        if (t.isFloating()) b.emit(new Instr.Bin(Instr.BinOp.FSUB, d, new Operand.FloatImm(-0.0), v.var(), e.token()));
        else b.emit(new Instr.Bin(types.isSigned(t) ? Instr.BinOp.SUB : Instr.BinOp.WSUB, d, new Operand.IntImm(0), v.var(), e.token()));
        if (t instanceof CType.BitInt) canon(d, t, e.token());
        return new Val(d, t);
    }

    @Override
    public Val visit(TExpr.BitNot e) {
        Val v = value(e.operand());
        Var d = temp(e.type());
        b.emit(new Instr.Bin(Instr.BinOp.XOR, d, v.var(), new Operand.IntImm(-1), e.token()));
        if (e.type() instanceof CType.BitInt) canon(d, e.type(), e.token());
        return new Val(d, e.type());
    }

    @Override
    public Val visit(TExpr.Not e) {
        Val v = value(e.operand());
        Var d = temp(e.type());
        b.emit(new Instr.Cmp(Instr.CmpOp.EQ, d, v.var(), new Operand.IntImm(0), e.token()));
        return new Val(d, e.type());
    }

    @Override
    public Val visit(TExpr.DirectCall e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.IndirectCall e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Assign e) {
        Place target = place(e.target());
        Val v = value(e.value());
        write(target, v, e.token());
        return v;
    }

    @Override
    public Val visit(TExpr.CompoundAssign e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.PostfixAssign e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.TargetValue e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Cond e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Comma e) {
        throw notYet(e);
    }

    // ---- initialization ----------------------------------------------------------------------------

    /** A scalar local's initializer: one item, moved into the variable. */
    void initialize(@NonNull Place.Variable v, @NonNull TInit init, @NonNull Token at) {
        if (init.items().size() != 1 || init.items().get(0).offset() != 0) throw new IllegalStateException("a scalar initializer has one item");
        write(v, value(init.items().get(0).value()), at);
    }
}
