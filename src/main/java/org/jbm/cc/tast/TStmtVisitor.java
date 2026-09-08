package org.jbm.cc.tast;

/** One visitor over every typed statement kind; see {@link TVisitor} for expressions. */
public interface TStmtVisitor<R> {
    R visit(TStmt.Block s);
    R visit(TStmt.ExprStmt s);
    R visit(TStmt.LocalDecl s);
    R visit(TStmt.If s);
    R visit(TStmt.While s);
    R visit(TStmt.DoWhile s);
    R visit(TStmt.For s);
    R visit(TStmt.Switch s);
    R visit(TStmt.Labeled s);
    R visit(TStmt.Goto s);
    R visit(TStmt.Break s);
    R visit(TStmt.Continue s);
    R visit(TStmt.Return s);
}
