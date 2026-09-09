package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Operand;
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
    private final Builder b;
    private final Map<Symbol, Var> vars;

    ExprLower(@NonNull Lower lower, @NonNull Builder b, @NonNull Map<Symbol, Var> vars) {
        this.types = lower.types;
        this.typeMap = lower.typeMap;
        this.names = lower.names;
        this.b = b;
        this.vars = vars;
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
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.IntToFloat e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.FloatToInt e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.FloatToFloat e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.ToBool e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.ToVoid e) {
        value(e.operand());
        return new Val(null, e.type());
    }

    @Override
    public Val visit(TExpr.PtrToPtr e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.IntToPtr e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.PtrToInt e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.NullToPtr e) {
        throw notYet(e);
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

    @Override
    public Val visit(TExpr.Add e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Sub e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Mul e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Div e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Rem e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.BitAnd e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.BitOr e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.BitXor e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Shl e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Shr e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Eq e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Ne e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Lt e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Le e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Gt e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Ge e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.And e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Or e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Neg e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.BitNot e) {
        throw notYet(e);
    }

    @Override
    public Val visit(TExpr.Not e) {
        throw notYet(e);
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
