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
import org.jbm.cc.types.Layout;
import org.jbm.cc.types.Types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Expressions to TAC variables ({@code lower-plan.md}, "Expressions"):
 * {@link #value} lowers an rvalue to a {@link Val}, {@link #place} an
 * lvalue to a {@link Place}, and {@link #read}, {@link #write} and
 * {@link #pointer} are the three things a place is used for.
 */
final class ExprLower implements TVisitor<Val> {

    private final Lower lower;
    private final Types types;
    private final TypeMap typeMap;
    private final Names names;
    private final TargetDesc target;
    private final Builder b;
    private final Map<Symbol, Var> vars;
    private int callTemps;
    // The value a compound or postfix assignment's target held, keyed by
    // the target node the tree shares between the assignment and its
    // TargetValue.
    private final Map<TExpr.Lvalue, Val> targetValues = new IdentityHashMap<>();
    private int condTemps;

    ExprLower(@NonNull Lower lower, @NonNull Builder b, @NonNull Map<Symbol, Var> vars) {
        this.lower = lower;
        this.types = lower.types;
        this.typeMap = lower.typeMap;
        this.names = lower.names;
        this.target = lower.module.target;
        this.b = b;
        this.vars = vars;
    }

    // ---- modifiers -------------------------------------------------------------------------------

    private int registerWidth() {
        return target.registerWidth();
    }

    /**
     * The modifier of an operation performed in C type {@code t}: its
     * storage type when that is narrower than the register, the precision
     * for a floating type, none for a 64-bit integer.
     */
    private Type mod(CType t) {
        Type tt = typeMap.of(t);
        if (tt instanceof Type.Float) {
            return tt;
        }
        if (t.isPointer() || t.isNullptr()) {
            tt = typeMap.integer(types.sizeT());
        }
        Type.Int it = (Type.Int) tt;
        if (it.width() >= registerWidth()) {
            return null;
        }
        return it;
    }

    private Type.Float precision(CType floating) {
        return (Type.Float) typeMap.of(floating);
    }

    /** The modifier of a mov into a variable of C type {@code t}: its storage width and signedness, or its precision. */
    private Type movMod(CType t) {
        Type tt = typeMap.of(t);
        if (tt instanceof Type.Float) {
            return tt;
        }
        if (t.isPointer() || t.isNullptr()) {
            return new Type.Int(types.width(t), false);
        }
        return typeMap.integer(t);
    }

    private void mov(Var dst, Operand src, CType t, Token at) {
        b.emit(new Instr.Mov(dst, src, movMod(t), at));
    }

    /**
     * A {@code _BitInt} whose width is not its storage width is computed at
     * the storage width; this makes the result extended from its own width.
     */
    private void canon(Var r, CType t, Token at) {
        if (!(t instanceof CType.BitInt)) {
            return;
        }
        extend(r, types.isSigned(t), types.width(t), at);
    }

    /** Extends {@code r} from its low {@code n} bits, signed or not, over the whole register. */
    private void extend(Var r, boolean signed, int n, Token at) {
        int shift = registerWidth() - n;
        if (shift <= 0) {
            return;
        }
        if (signed) {
            b.emit(new Instr.Bin(Instr.BinOp.SHL, r, r, new Operand.IntImm(shift), at));
            b.emit(new Instr.Bin(Instr.BinOp.ASHR, r, r, new Operand.IntImm(shift), at));
        } else {
            b.emit(new Instr.Bin(Instr.BinOp.AND, r, r, new Operand.IntImm(mask(n)), at));
        }
    }

    /**
     * An integer value of C type {@code from}, canonical, to a variable of
     * TAC type {@code dest} holding C type {@code to} canonically
     * ({@code lower-plan.md}, the IntToInt rows).
     */
    private Val convertInt(Val v, CType to, Type dest, Token at) {
        CType from = v.type();
        if (isImplied(from, to)) {
            // The value is already held as the destination requires: the
            // same variable, read at the new type.
            return new Val(v.var(), to);
        }
        Var r = b.temp(dest);
        Type.Int narrow = new Type.Int(storageWidth(to), isSignedInteger(to));
        b.emit(new Instr.Mov(r, v.var(), narrow, at));
        canon(r, to, at);
        return new Val(r, to);
    }

    private int storageWidth(CType t) {
        if (t.isPointer() || t.isNullptr()) {
            return types.width(t);
        }
        return typeMap.integer(t).width();
    }

    // A pointer converts as an unsigned integer of its width.
    private boolean isSignedInteger(CType t) {
        if (!t.isInteger()) {
            return false;
        }
        return types.isSigned(t);
    }

    /**
     * Whether a value held as {@code from} implies is already held as
     * {@code to} requires: the destination fills the register, or is wider
     * than the source and the extension cannot differ, or has the same
     * width and signedness.
     */
    private boolean isImplied(CType from, CType to) {
        int fromWidth = types.width(from);
        int toWidth = types.width(to);
        boolean fromSigned = isSignedInteger(from);
        boolean toSigned = isSignedInteger(to);
        if (toWidth >= registerWidth()) {
            return true;
        }
        if (toWidth > fromWidth) {
            return !fromSigned || toSigned;
        }
        if (toWidth == fromWidth) {
            return fromSigned == toSigned;
        }
        return false;
    }

    Val value(@NonNull TExpr.Rvalue e) {
        return e.accept(this);
    }

    // Set for the top node of an expression statement: an assignment then
    // skips computing the value it would yield.
    private boolean discard;

    /** Lowers an expression whose value is not used. */
    void effect(@NonNull TExpr.Rvalue e) {
        TExpr.Rvalue top = e instanceof TExpr.ToVoid v ? v.operand() : e;
        discard = top instanceof TExpr.Assign || top instanceof TExpr.CompoundAssign || top instanceof TExpr.PostfixAssign;
        top.accept(this);
        discard = false;
    }

    private boolean discarded() {
        boolean d = discard;
        discard = false;
        return d;
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
        if (e instanceof TExpr.Member m) {
            Var base = pointer(place(m.base()), m.token());
            Var p = base;
            if (m.member().offset() != 0) {
                p = b.temp(Type.PTR);
                b.emit(new Instr.Bin(Instr.BinOp.WADD, p, base, new Operand.IntImm(m.member().offset()), m.token()));
            }
            return new Place.Memory(p, m.type(), m.member().bits(), m.type().quals().isVolatile());
        }
        if (e instanceof TExpr.Materialize m) return materialize(m);
        if (e instanceof TExpr.CompoundLit c) return compoundLiteral(c);
        throw notYet(e);
    }

    // A struct rvalue is copied into its temporary object; a call result
    // is written there directly.
    private Place materialize(TExpr.Materialize m) {
        Var p = b.temp(Type.PTR);
        b.emit(new Instr.AddrOfVar(p, vars.get(m.symbol()), m.token()));
        if (m.value() instanceof TExpr.Call call) {
            call(call, p);
        } else {
            Val v = value(m.value());
            b.emit(new Instr.Copy(typeMap.of(m.type()), p, v.var(), m.token()));
        }
        return memory(p, m.type());
    }

    // A compound literal's object is initialized each time the expression
    // is evaluated (6.5.3.6); a static one is a global, initialized once
    // by its items.
    private Place compoundLiteral(TExpr.CompoundLit c) {
        Var v = vars.get(c.symbol());
        if (v == null) {
            Var p = b.temp(Type.PTR);
            b.emit(new Instr.AddrOfGlobal(p, names.of(c.symbol()), c.token()));
            return memory(p, c.type());
        }
        if (c.type().isArray() || c.type().isRecord()) {
            Var p = b.temp(Type.PTR);
            b.emit(new Instr.AddrOfVar(p, v, c.token()));
            initialize(p, c.type(), c.init(), c.token());
            return memory(p, c.type());
        }
        var place = new Place.Variable(v, c.type());
        initialize(place, c.init(), c.token());
        return place;
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
        if (m.bits().isPresent()) return readBits(m, m.bits().get(), at);
        Var r = temp(t);
        b.emit(new Instr.Load(r, m.ptr(), width(t), ext(t), m.isVolatile(), at));
        return new Val(r, t);
    }

    // A bit-field: the storage unit is loaded zero-extended, then the
    // field is shifted down and masked (unsigned) or shifted up to the
    // top and arithmetically down (signed), which lands it canonical.
    private Val readBits(Place.Memory m, Layout.BitField bits, Token at) {
        CType t = m.type();
        int unit = width(t);
        int c = registerWidth();
        Var u = b.temp(typeMap.integer(types.unsignedOf(t)));
        b.emit(new Instr.Load(u, m.ptr(), unit, Instr.Ext.UNSIGNED, m.isVolatile(), at));
        Var r = temp(t);
        if (types.isSigned(t)) {
            b.emit(new Instr.Bin(Instr.BinOp.SHL, r, u, new Operand.IntImm(c - bits.bitOffset() - bits.width()), at));
            b.emit(new Instr.Bin(Instr.BinOp.ASHR, r, r, new Operand.IntImm(c - bits.width()), at));
        } else {
            Var shifted = u;
            if (bits.bitOffset() != 0) {
                b.emit(new Instr.Bin(Instr.BinOp.LSHR, r, u, new Operand.IntImm(bits.bitOffset()), at));
                shifted = r;
            }
            b.emit(new Instr.Bin(Instr.BinOp.AND, r, shifted, new Operand.IntImm(mask(bits.width())), at));
        }
        return new Val(r, t);
    }

    private static long mask(int width) {
        return width == 64 ? -1L : (1L << width) - 1;
    }

    /** Stores a value at a place: a mov, or a store. */
    void write(@NonNull Place place, @NonNull Val v, @NonNull Token at) {
        if (place instanceof Place.Variable pv) {
            mov(pv.var(), v.var(), pv.type(), at);
            return;
        }
        var m = (Place.Memory) place;
        CType t = m.type();
        if (t.isArray() || t.isRecord()) {
            b.emit(new Instr.Copy(typeMap.of(t), m.ptr(), v.var(), at));
            return;
        }
        if (m.bits().isPresent()) {
            writeBits(m, m.bits().get(), v, at);
            return;
        }
        b.emit(new Instr.Store(m.ptr(), v.var(), width(t), t.isFloating(), m.isVolatile(), at));
    }

    // A bit-field store is a read-modify-write of the storage unit: clear
    // the field's bits, mask and shift the value into place, or, store.
    private void writeBits(Place.Memory m, Layout.BitField bits, Val v, Token at) {
        CType t = m.type();
        int unit = width(t);
        Type ut = typeMap.integer(types.unsignedOf(t));
        Var u = b.temp(ut);
        b.emit(new Instr.Load(u, m.ptr(), unit, Instr.Ext.UNSIGNED, m.isVolatile(), at));
        b.emit(new Instr.Bin(Instr.BinOp.AND, u, u, new Operand.IntImm(~(mask(bits.width()) << bits.bitOffset())), at));
        Var f = b.temp(ut);
        b.emit(new Instr.Bin(Instr.BinOp.AND, f, v.var(), new Operand.IntImm(mask(bits.width())), at));
        if (bits.bitOffset() != 0) b.emit(new Instr.Bin(Instr.BinOp.SHL, f, f, new Operand.IntImm(bits.bitOffset()), at));
        b.emit(new Instr.Bin(Instr.BinOp.OR, u, u, f, at));
        b.emit(new Instr.Store(m.ptr(), u, unit, false, m.isVolatile(), at));
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
        mov(r, new Operand.IntImm(e.value()), e.type(), e.token());
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.FloatConst e) {
        Var r = temp(e.type());
        mov(r, new Operand.FloatImm(e.value()), e.type(), e.token());
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.NullptrConst e) {
        Var r = b.temp(Type.PTR);
        mov(r, new Operand.IntImm(0), e.type(), e.token());
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.AddrConst e) {
        Var r = b.temp(Type.PTR);
        if (e.base().isEmpty()) {
            mov(r, new Operand.IntImm(e.offset()), e.type(), e.token());
            return new Val(r, e.type());
        }
        Symbol base = e.base().get();
        if (base instanceof Symbol.Function) lower.referenced(base);
        b.emit(new Instr.AddrOfGlobal(r, names.of(base), e.token()));
        if (e.offset() != 0) b.emit(new Instr.Bin(Instr.BinOp.WADD, r, r, new Operand.IntImm(e.offset()), e.token()));
        return new Val(r, e.type());
    }

    // ---- conversions ---------------------------------------------------------------------------------

    @Override
    public Val visit(TExpr.LvalueToRvalue e) {
        return read(place(e.operand()), e.token());
    }

    @Override
    public Val visit(TExpr.ArrayDecay e) {
        return new Val(pointer(place(e.operand()), e.token()), e.type());
    }

    // A named function decays to its address; a dereferenced pointer to
    // function decays back to the pointer.
    @Override
    public Val visit(TExpr.FunctionDecay e) {
        if (e.operand() instanceof TExpr.FuncRef f) {
            lower.referenced(f.symbol());
            Var p = b.temp(Type.PTR);
            b.emit(new Instr.AddrOfGlobal(p, names.of(f.symbol()), e.token()));
            return new Val(p, e.type());
        }
        return new Val(value(((TExpr.FuncDeref) e.operand()).pointer()).var(), e.type());
    }

    @Override
    public Val visit(TExpr.IntToInt e) {
        return convertInt(value(e.operand()), e.type(), typeMap.of(e.type()), e.token());
    }

    @Override
    public Val visit(TExpr.IntToFloat e) {
        Val v = value(e.operand());
        Var r = temp(e.type());
        Instr.CvtOp op = types.isSigned(v.type()) ? Instr.CvtOp.I2F : Instr.CvtOp.U2F;
        b.emit(new Instr.Cvt(op, r, v.var(), precision(e.type()), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.FloatToInt e) {
        Val v = value(e.operand());
        Var r = temp(e.type());
        Instr.CvtOp op = types.isSigned(e.type()) ? Instr.CvtOp.F2I : Instr.CvtOp.F2U;
        b.emit(new Instr.Cvt(op, r, v.var(), precision(v.type()), e.token()));
        canon(r, e.type(), e.token());
        return new Val(r, e.type());
    }

    // A widening between floating formats is exact: nothing. A narrowing rounds.
    @Override
    public Val visit(TExpr.FloatToFloat e) {
        Val v = value(e.operand());
        Type.Float from = precision(v.type());
        Type.Float to = precision(e.type());
        if (to.width() >= from.width()) {
            return new Val(v.var(), e.type());
        }
        Var r = temp(e.type());
        b.emit(new Instr.Cvt(Instr.CvtOp.FCVT, r, v.var(), to, e.token()));
        return new Val(r, e.type());
    }

    // A comparison, a logical operator or ! already yields 0 or 1, which
    // is canonical for bool, so ToBool of one is the value itself.
    @Override
    public Val visit(TExpr.ToBool e) {
        Val v = value(e.operand());
        if (e.operand() instanceof TExpr.Comparison || e.operand() instanceof TExpr.Logical || e.operand() instanceof TExpr.Not) {
            return new Val(v.var(), e.type());
        }
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
        mov(r, new Operand.IntImm(0), e.type(), e.token());
        return new Val(r, e.type());
    }

    // ---- operators --------------------------------------------------------------------------------

    @Override
    public Val visit(TExpr.AddrOf e) {
        return new Val(pointer(place(e.operand()), e.token()), e.type());
    }

    private long elementSize(CType pointerType) {
        return types.size(((CType.Pointer) pointerType).target());
    }

    // The index is already ptrdiff_t, in the pointer's class: scale it by
    // the element size unless that is 1, then add.
    @Override
    public Val visit(TExpr.PtrAdd e) {
        Val p = value(e.pointer());
        Val i = value(e.index());
        Var offset = i.var();
        long size = elementSize(e.type());
        if (size != 1) {
            offset = b.temp(typeMap.of(i.type()));
            b.emit(new Instr.Bin(Instr.BinOp.WMUL, offset, i.var(), new Operand.IntImm(size), mod(i.type()), e.token()));
        }
        Var r = b.temp(Type.PTR);
        b.emit(new Instr.Bin(Instr.BinOp.WADD, r, p.var(), offset, mod(e.type()), e.token()));
        return new Val(r, e.type());
    }

    @Override
    public Val visit(TExpr.PtrDiff e) {
        Val l = value(e.left());
        Val r = value(e.right());
        Var d = temp(e.type());
        b.emit(new Instr.Bin(Instr.BinOp.WSUB, d, l.var(), r.var(), mod(e.type()), e.token()));
        long size = elementSize(l.type());
        if (size != 1) {
            b.emit(new Instr.Bin(Instr.BinOp.SDIV, d, d, new Operand.IntImm(size), mod(e.type()), e.token()));
        }
        return new Val(d, e.type());
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
        b.emit(new Instr.Bin(op, d, l.var(), r.var(), mod(t), at));
        canon(d, t, at);
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

    // The amount was promoted on its own by the typer and is used as it is.
    private Val shift(TExpr.Rvalue left, TExpr.Rvalue right, CType t, Instr.BinOp op, Token at) {
        Val l = value(left);
        Val r = value(right);
        Var d = temp(t);
        b.emit(new Instr.Bin(op, d, l.var(), r.var(), mod(t), at));
        canon(d, t, at);
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
        mov(r, new Operand.IntImm(isAnd ? 0 : 1), t, at);
        Val l = value(left);
        var rhs = b.block(isAnd ? "and" : "or");
        var done = b.block(isAnd ? "and.done" : "or.done");
        b.emit(isAnd ? new Instr.CondBr(l.var(), rhs, done, at) : new Instr.CondBr(l.var(), done, rhs, at));
        b.open(rhs);
        Val rv = value(right);
        mov(r, rv.var(), t, at);
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
        if (t.isFloating()) {
            b.emit(new Instr.Bin(Instr.BinOp.FSUB, d, new Operand.FloatImm(-0.0), v.var(), mod(t), e.token()));
        } else {
            Instr.BinOp op = types.isSigned(t) ? Instr.BinOp.SUB : Instr.BinOp.WSUB;
            b.emit(new Instr.Bin(op, d, new Operand.IntImm(0), v.var(), mod(t), e.token()));
        }
        canon(d, t, e.token());
        return new Val(d, t);
    }

    @Override
    public Val visit(TExpr.BitNot e) {
        Val v = value(e.operand());
        Var d = temp(e.type());
        b.emit(new Instr.Bin(Instr.BinOp.XOR, d, v.var(), new Operand.IntImm(-1), mod(e.type()), e.token()));
        canon(d, e.type(), e.token());
        return new Val(d, e.type());
    }

    @Override
    public Val visit(TExpr.Not e) {
        Val v = value(e.operand());
        Var d = temp(e.type());
        b.emit(new Instr.Cmp(Instr.CmpOp.EQ, d, v.var(), new Operand.IntImm(0), e.token()));
        return new Val(d, e.type());
    }

    /**
     * A call: the arguments in order, scalars by variable and aggregates
     * by pointer; the result in a fresh variable, or for an aggregate
     * written through {@code into}, a fresh temporary object when the
     * caller has none.
     */
    private Val call(TExpr.Call e, Var into) {
        Var callee = e instanceof TExpr.IndirectCall ic ? value(ic.callee()).var() : null;
        var args = new ArrayList<Operand>(e.arguments().size());
        for (TExpr.Rvalue a : e.arguments()) args.add(value(a).var());
        Type.Func sig = typeMap.func(e.signature());
        CType rt = e.type();
        Var dst = null;
        if (rt.isArray() || rt.isRecord()) {
            if (into == null) {
                Var object = b.local("call." + ++callTemps, typeMap.of(rt), false);
                into = b.temp(Type.PTR);
                b.emit(new Instr.AddrOfVar(into, object, e.token()));
            }
        } else if (!rt.isVoid()) {
            dst = temp(rt);
        }
        if (e instanceof TExpr.DirectCall dc) {
            lower.referenced(dc.callee());
            b.emit(new Instr.Call(dst, sig, names.of(dc.callee()), args, into, e.token()));
        } else {
            b.emit(new Instr.ICall(dst, sig, callee, args, into, e.token()));
        }
        return new Val(into != null ? into : dst, rt);
    }

    @Override
    public Val visit(TExpr.DirectCall e) {
        return call(e, null);
    }

    @Override
    public Val visit(TExpr.IndirectCall e) {
        return call(e, null);
    }

    @Override
    public Val visit(TExpr.Assign e) {
        boolean unused = discarded();
        Place target = place(e.target());
        Val v = value(e.value());
        write(target, v, e.token());
        return unused ? v : stored(target, v, e.token());
    }

    // What the target holds after a store: the value, the value clipped to
    // a bit-field's width, or the aggregate's pointer.
    private Val stored(Place target, Val v, Token at) {
        if (target instanceof Place.Memory m) {
            if (m.type().isArray() || m.type().isRecord()) return new Val(m.ptr(), m.type());
            if (m.bits().isPresent()) {
                Var r = temp(m.type());
                mov(r, v.var(), m.type(), at);
                extend(r, types.isSigned(m.type()), m.bits().get().width(), at);
                return new Val(r, m.type());
            }
        }
        return v;
    }

    // The target place is computed once; its old value is what TargetValue
    // yields inside newValue; then the new value is stored.
    private Val compound(TExpr.Lvalue target, TExpr.Rvalue newValue, Token at, boolean yieldOld) {
        boolean unused = discarded();
        Place a = place(target);
        Val old = read(a, at);
        if (yieldOld && a instanceof Place.Variable) {
            Var copy = temp(old.type());
            mov(copy, old.var(), old.type(), at);
            old = new Val(copy, old.type());
        }
        targetValues.put(target, old);
        Val n = value(newValue);
        targetValues.remove(target);
        write(a, n, at);
        return yieldOld ? old : unused ? n : stored(a, n, at);
    }

    @Override
    public Val visit(TExpr.CompoundAssign e) {
        return compound(e.target(), e.newValue(), e.token(), false);
    }

    @Override
    public Val visit(TExpr.PostfixAssign e) {
        return compound(e.target(), e.newValue(), e.token(), true);
    }

    @Override
    public Val visit(TExpr.TargetValue e) {
        Val old = targetValues.get(e.target());
        if (old == null) throw new IllegalStateException("TargetValue outside its assignment");
        return old;
    }

    // c ? t : e: a result variable each arm writes, or for an aggregate a
    // temporary object each arm copies into, or nothing for void.
    @Override
    public Val visit(TExpr.Cond e) {
        CType t = e.type();
        boolean aggregate = t.isArray() || t.isRecord();
        Var r = null;
        if (aggregate) {
            Var object = b.local("cond." + ++condTemps, typeMap.of(t), false);
            r = b.temp(Type.PTR);
            b.emit(new Instr.AddrOfVar(r, object, e.token()));
        } else if (!t.isVoid()) {
            r = temp(t);
        }
        Val c = value(e.condition());
        var then = b.block("then");
        var otherwise = b.block("else");
        var done = b.block("cond.done");
        b.emit(new Instr.CondBr(c.var(), then, otherwise, e.token()));
        b.open(then);
        arm(e.thenValue(), r, aggregate, t, e.token());
        b.emit(new Instr.Br(done, e.token()));
        b.open(otherwise);
        arm(e.elseValue(), r, aggregate, t, e.token());
        b.emit(new Instr.Br(done, e.token()));
        b.open(done);
        return new Val(r, t);
    }

    private void arm(TExpr.Rvalue arm, Var r, boolean aggregate, CType t, Token at) {
        Val v = value(arm);
        if (aggregate) {
            b.emit(new Instr.Copy(typeMap.of(t), r, v.var(), at));
        } else if (r != null) {
            mov(r, v.var(), t, at);
        }
    }

    @Override
    public Val visit(TExpr.Comma e) {
        effect(e.left());
        return value(e.right());
    }

    // ---- initialization ----------------------------------------------------------------------------

    /** A scalar local's initializer: one item, moved into the variable. */
    void initialize(@NonNull Place.Variable v, @NonNull TInit init, @NonNull Token at) {
        if (init.items().size() != 1 || init.items().get(0).offset() != 0) throw new IllegalStateException("a scalar initializer has one item");
        write(v, value(init.items().get(0).value()), at);
    }

    /**
     * An aggregate's initializer at run time: the object is zeroed, then
     * each item is stored at its offset in order, a later item overriding
     * an earlier one where they overlap.
     */
    void initialize(@NonNull Var ptr, @NonNull CType type, @NonNull TInit init, @NonNull Token at) {
        b.emit(new Instr.Zero(typeMap.of(type), ptr, at));
        for (TInit.Item item : init.items()) {
            Var q = ptr;
            if (item.offset() != 0) {
                q = b.temp(Type.PTR);
                b.emit(new Instr.Bin(Instr.BinOp.WADD, q, ptr, new Operand.IntImm(item.offset()), at));
            }
            Val v = value(item.value());
            CType t = types.unqualified(v.type());
            write(new Place.Memory(q, t, item.bits(), t.quals().isVolatile()), v, at);
        }
    }
}
