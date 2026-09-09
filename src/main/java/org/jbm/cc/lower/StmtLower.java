package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.tac.Var;
import org.jbm.cc.tast.TStmt;
import org.jbm.cc.tast.TStmtVisitor;

import java.util.Map;

/** Statements to blocks ({@code lower-plan.md}, "Statements"). */
final class StmtLower implements TStmtVisitor<Void> {

    private final Builder b;
    private final ExprLower exprs;
    private final Map<Symbol, Var> vars;

    StmtLower(@NonNull Builder b, @NonNull ExprLower exprs, @NonNull Map<Symbol, Var> vars) {
        this.b = b;
        this.exprs = exprs;
        this.vars = vars;
    }

    void lower(@NonNull TStmt s) {
        s.accept(this);
    }

    private static UnsupportedOperationException notYet(TStmt s) {
        return new UnsupportedOperationException("lowering of " + s.getClass().getSimpleName() + " is not implemented");
    }

    @Override
    public Void visit(TStmt.Block s) {
        for (TStmt item : s.items()) item.accept(this);
        return null;
    }

    @Override
    public Void visit(TStmt.ExprStmt s) {
        exprs.effect(s.expr());
        return null;
    }

    @Override
    public Void visit(TStmt.LocalDecl s) {
        if (s.init().isEmpty()) return null;
        Var v = vars.get(s.symbol());
        if (s.symbol().type().isArray() || s.symbol().type().isRecord()) throw notYet(s);
        exprs.initialize(new Place.Variable(v, s.symbol().type()), s.init().get(), s.token());
        return null;
    }

    @Override
    public Void visit(TStmt.If s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.While s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.DoWhile s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.For s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Switch s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Labeled s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Goto s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Break s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Continue s) {
        throw notYet(s);
    }

    @Override
    public Void visit(TStmt.Return s) {
        throw notYet(s);
    }
}
