package org.jbm.cc.sema.tast;

/**
 * One visitor over every typed node kind, with an {@code accept} on each
 * node: adding a node kind is a compile error until every consumer
 * handles it. Whole-tree consumers (the printer, constant evaluation,
 * lowering) implement this; {@code instanceof} on a sealed family is for
 * code that cares about one family only.
 */
public interface TVisitor<R> {

    // ---- lvalues and function designators ----
    R visit(TExpr.VarRef e);
    R visit(TExpr.FuncRef e);
    R visit(TExpr.Deref e);
    R visit(TExpr.FuncDeref e);
    R visit(TExpr.Member e);
    R visit(TExpr.Materialize e);
    R visit(TExpr.CompoundLit e);

    // ---- constants ----
    R visit(TExpr.IntConst e);
    R visit(TExpr.FloatConst e);
    R visit(TExpr.NullptrConst e);
    R visit(TExpr.AddrConst e);

    // ---- conversions ----
    R visit(TExpr.LvalueToRvalue e);
    R visit(TExpr.ArrayDecay e);
    R visit(TExpr.FunctionDecay e);
    R visit(TExpr.IntToInt e);
    R visit(TExpr.IntToFloat e);
    R visit(TExpr.FloatToInt e);
    R visit(TExpr.FloatToFloat e);
    R visit(TExpr.ToBool e);
    R visit(TExpr.ToVoid e);
    R visit(TExpr.PtrToPtr e);
    R visit(TExpr.IntToPtr e);
    R visit(TExpr.PtrToInt e);
    R visit(TExpr.NullToPtr e);

    // ---- pointer operators ----
    R visit(TExpr.AddrOf e);
    R visit(TExpr.PtrAdd e);
    R visit(TExpr.PtrDiff e);

    // ---- arithmetic ----
    R visit(TExpr.Add e);
    R visit(TExpr.Sub e);
    R visit(TExpr.Mul e);
    R visit(TExpr.Div e);
    R visit(TExpr.Rem e);
    R visit(TExpr.BitAnd e);
    R visit(TExpr.BitOr e);
    R visit(TExpr.BitXor e);

    // ---- shifts, comparisons, logical, unary ----
    R visit(TExpr.Shl e);
    R visit(TExpr.Shr e);
    R visit(TExpr.Eq e);
    R visit(TExpr.Ne e);
    R visit(TExpr.Lt e);
    R visit(TExpr.Le e);
    R visit(TExpr.Gt e);
    R visit(TExpr.Ge e);
    R visit(TExpr.And e);
    R visit(TExpr.Or e);
    R visit(TExpr.Neg e);
    R visit(TExpr.BitNot e);
    R visit(TExpr.Not e);

    // ---- calls ----
    R visit(TExpr.DirectCall e);
    R visit(TExpr.IndirectCall e);

    // ---- assignment ----
    R visit(TExpr.Assign e);
    R visit(TExpr.CompoundAssign e);
    R visit(TExpr.PostfixAssign e);
    R visit(TExpr.TargetValue e);

    // ---- conditional and comma ----
    R visit(TExpr.Cond e);
    R visit(TExpr.Comma e);
}
