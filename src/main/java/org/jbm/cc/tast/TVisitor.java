package org.jbm.cc.tast;

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

    // ---- constants ----
    R visit(TExpr.IntConst e);

    // ---- conversions ----
    R visit(TExpr.LvalueToRvalue e);
    R visit(TExpr.ArrayDecay e);
    R visit(TExpr.FunctionDecay e);
    R visit(TExpr.IntToInt e);

    // ---- arithmetic ----
    R visit(TExpr.Add e);
    R visit(TExpr.Sub e);
    R visit(TExpr.Mul e);
    R visit(TExpr.Div e);
}
