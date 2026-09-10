package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.sema.Symbol;
import org.jbm.cc.tac.Block;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Operand;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;
import org.jbm.cc.tast.JumpTarget;
import org.jbm.cc.tast.TExpr;
import org.jbm.cc.tast.TStmt;
import org.jbm.cc.tast.TStmtVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Statements to blocks ({@code lower-plan.md}, "Statements"). Every
 * {@link JumpTarget} maps to a block, created on first sight; a loop's
 * target maps to a break block and a continue block, a switch's to its
 * break block.
 */
final class StmtLower implements TStmtVisitor<Void> {

    private final Builder b;
    private final ExprLower exprs;
    private final Map<Symbol, Var> vars;
    private final Map<JumpTarget, Block> labels = new HashMap<>();
    private final Map<JumpTarget, Block> breaks = new HashMap<>();
    private final Map<JumpTarget, Block> continues = new HashMap<>();

    StmtLower(@NonNull Builder b, @NonNull ExprLower exprs, @NonNull Map<Symbol, Var> vars) {
        this.b = b;
        this.exprs = exprs;
        this.vars = vars;
    }

    void lower(@NonNull TStmt s) {
        s.accept(this);
    }

    // A jump target's name may be the typer's "case 5" or "case 97...102";
    // a block name is an identifier with dots.
    private Block label(JumpTarget t) {
        return labels.computeIfAbsent(t, k -> b.block(k.name.replaceAll("[^A-Za-z0-9_]+", ".")));
    }

    private void br(Block target, Token at) {
        if (b.isOpen()) b.emit(new Instr.Br(target, at));
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
        if (s.symbol().type().isArray() || s.symbol().type().isRecord()) {
            Var p = b.temp(Type.PTR);
            b.emit(new Instr.AddrOfVar(p, v, s.token()));
            exprs.initialize(p, s.symbol().type(), s.init().get(), s.token());
            return null;
        }
        exprs.initialize(new Place.Variable(v, s.symbol().type()), s.init().get(), s.token());
        return null;
    }

    // if: the join block is opened only if a branch reaches it.
    @Override
    public Void visit(TStmt.If s) {
        Val c = exprs.value(s.condition());
        Block then = b.block("then");
        Block done = b.block("if.done");
        Block otherwise = s.elseBranch().isPresent() ? b.block("else") : done;
        b.emit(new Instr.CondBr(c.var(), then, otherwise, s.token()));
        b.open(then);
        s.thenBranch().accept(this);
        boolean reaches = b.isOpen();
        br(done, s.token());
        if (s.elseBranch().isPresent()) {
            b.open(otherwise);
            s.elseBranch().get().accept(this);
            reaches |= b.isOpen();
            br(done, s.token());
        } else {
            reaches = true;
        }
        if (reaches) b.open(done);
        return null;
    }

    @Override
    public Void visit(TStmt.While s) {
        Block cond = b.block("while.cond");
        Block body = b.block("while.body");
        Block done = b.block("while.done");
        breaks.put(s.target(), done);
        continues.put(s.target(), cond);
        br(cond, s.token());
        b.open(cond);
        Val c = exprs.value(s.condition());
        b.emit(new Instr.CondBr(c.var(), body, done, s.token()));
        b.open(body);
        s.body().accept(this);
        br(cond, s.token());
        b.open(done);
        return null;
    }

    @Override
    public Void visit(TStmt.DoWhile s) {
        Block body = b.block("do.body");
        Block cond = b.block("do.cond");
        Block done = b.block("do.done");
        breaks.put(s.target(), done);
        continues.put(s.target(), cond);
        br(body, s.token());
        b.open(body);
        s.body().accept(this);
        br(cond, s.token());
        b.open(cond);
        Val c = exprs.value(s.condition());
        b.emit(new Instr.CondBr(c.var(), body, done, s.token()));
        b.open(done);
        return null;
    }

    // for: the init statements, then a cond block when there is a
    // condition, the body, a step block that continue targets.
    @Override
    public Void visit(TStmt.For s) {
        for (TStmt init : s.init()) init.accept(this);
        Block cond = s.condition().isPresent() ? b.block("for.cond") : null;
        Block body = b.block("for.body");
        Block step = b.block("for.step");
        Block done = b.block("for.done");
        Block top = cond != null ? cond : body;
        breaks.put(s.target(), done);
        continues.put(s.target(), step);
        br(top, s.token());
        if (cond != null) {
            b.open(cond);
            Val c = exprs.value(s.condition().get());
            b.emit(new Instr.CondBr(c.var(), body, done, s.token()));
        }
        b.open(body);
        s.body().accept(this);
        br(step, s.token());
        b.open(step);
        s.step().ifPresent(exprs::effect);
        b.emit(new Instr.Br(top, s.token()));
        b.open(done);
        return null;
    }

    // switch: a comparison chain for the ranges, then a switch on the
    // single values; the body is closed until its first label.
    @Override
    public Void visit(TStmt.Switch s) {
        Val v = exprs.value(s.value());
        Block done = b.block("switch.done");
        breaks.put(s.target(), done);
        Block dflt = s.defaultTarget().map(this::label).orElse(done);
        for (TStmt.CaseLabel c : s.cases()) {
            if (!(c instanceof TStmt.CaseRange r)) continue;
            Type.Int t = (Type.Int) v.var().type;
            Var d = b.temp(t);
            Type.Int mod = new Type.Int(t.width(), false);
            b.emit(new Instr.Bin(Instr.BinOp.WSUB, d, v.var(), new Operand.IntImm(r.low()), mod, s.token()));
            Var in = b.temp(Type.U8);
            b.emit(new Instr.Cmp(Instr.CmpOp.ULE, in, d, new Operand.IntImm(r.high() - r.low()), s.token()));
            Block next = b.block("case.next");
            b.emit(new Instr.CondBr(in, label(r.target()), next, s.token()));
            b.open(next);
        }
        var cases = new ArrayList<Instr.Case>();
        for (TStmt.CaseLabel c : s.cases()) {
            if (c instanceof TStmt.Case single) cases.add(new Instr.Case(single.value(), label(single.target())));
        }
        b.emit(new Instr.Switch(v.var(), dflt, cases, s.token()));
        s.body().accept(this);
        br(done, s.token());
        b.open(done);
        return null;
    }

    @Override
    public Void visit(TStmt.Labeled s) {
        Block target = label(s.target());
        br(target, s.token());
        b.open(target);
        s.body().ifPresent(body -> body.accept(this));
        return null;
    }

    @Override
    public Void visit(TStmt.Goto s) {
        br(label(s.target()), s.token());
        return null;
    }

    @Override
    public Void visit(TStmt.Break s) {
        br(breaks.get(s.target()), s.token());
        return null;
    }

    @Override
    public Void visit(TStmt.Continue s) {
        br(continues.get(s.target()), s.token());
        return null;
    }

    @Override
    public Void visit(TStmt.Return s) {
        if (s.value().isEmpty()) {
            b.emit(new Instr.Ret(null, s.token()));
            return null;
        }
        TExpr.Rvalue value = s.value().get();
        Val v = exprs.value(value);
        b.emit(new Instr.Ret(v.var(), s.token()));
        return null;
    }
}
