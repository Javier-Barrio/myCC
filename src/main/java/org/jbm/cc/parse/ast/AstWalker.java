package org.jbm.cc.parse.ast;

import lombok.NonNull;
import java.util.List;

/**
 * A visitor over every AST hierarchy that, by default, just traverses the
 * children. Passes extend it and override the node kinds they care about;
 * an override that still wants the children walked calls {@code super}.
 * <p>
 * The {@code walk*} helpers are the way to descend into child nodes; the
 * non-sealed helper records (parameters, members, enumerators, labels,
 * headers, initializer items, specifiers) have their own {@code walk}
 * hook so a pass can intercept them too. Optional children are walked
 * with {@code ifPresent}; a required child is dereferenced.
 * <p>
 * A {@link Type.TypedefName} is deliberately a leaf: descending into the
 * aliased type would re-visit the typedef's original struct/enum body at
 * every use.
 */
public abstract class AstWalker implements Visitor<Void> {

    // ---- entry points ----------------------------------------------------

    public void walkUnit(@NonNull List<? extends Decl> unit) {
        for (Decl d : unit) walk(d);
    }

    public void walk(@NonNull BlockItem item) {
        if (item instanceof Decl d) walk(d);
        else walk((Stmt) item);
    }

    public void walk(@NonNull Decl d) {
        d.accept(this);
    }

    public void walk(@NonNull Stmt s) {
        s.accept(this);
    }

    public void walk(@NonNull Expr e) {
        e.accept(this);
    }

    public void walk(@NonNull Type t) {
        t.accept(this);
    }

    public void walk(@NonNull Initializer i) {
        i.accept(this);
    }

    // ---- non-sealed helper records ------------------------------------------

    protected void walkSpecifiers(Specifiers s) {
        s.type().ifPresent(this::walk);
        s.alignment().ifPresent(a -> {
            a.type().ifPresent(this::walk);
            a.expr().ifPresent(this::walk);
        });
    }

    protected void walkInitDeclarator(Decl.InitDeclarator d) {
        d.type().ifPresent(this::walk);
        d.initializer().ifPresent(this::walk);
    }

    protected void walkParameter(Type.Parameter p) {
        walk(p.type());
    }

    protected void walkMember(Type.MemberDecl m) {
        if (m instanceof Type.Member member) {
            walk(member.type());
            member.bitWidth().ifPresent(this::walk);
        } else {
            walk((Expr) m);
        }
    }

    protected void walkEnumerator(Type.Enumerator e) {
        e.value().ifPresent(this::walk);
    }

    protected void walkLabel(Stmt.Label label) {
        if (label instanceof Stmt.CaseLabel c) {
            walk(c.low());
            c.high().ifPresent(this::walk);
        }
    }

    protected void walkHeader(Stmt.Header h) {
        h.declaration().ifPresent(this::walk);
        h.condition().ifPresent(this::walk);
    }

    protected void walkItem(Initializer.Item item) {
        for (var d : item.designators()) {
            if (d instanceof Initializer.ArrayDesignator a) walk(a.index());
        }
        walk(item.initializer());
    }

    // ---- expressions ---------------------------------------------------------

    @Override
    public Void visit(Expr.Identifier e) {
        return null;
    }

    @Override
    public Void visit(Expr.Literal e) {
        return null;
    }

    @Override
    public Void visit(Expr.StringLiteral e) {
        return null;
    }

    @Override
    public Void visit(Expr.Generic e) {
        e.controllingExpr().ifPresent(this::walk);
        e.controllingType().ifPresent(this::walk);
        for (var a : e.associations()) {
            a.type().ifPresent(this::walk);
            walk(a.expr());
        }
        return null;
    }

    @Override
    public Void visit(Expr.Index e) {
        walk(e.array());
        walk(e.index());
        return null;
    }

    @Override
    public Void visit(Expr.Call e) {
        walk(e.callee());
        for (var a : e.arguments()) walk(a);
        return null;
    }

    @Override
    public Void visit(Expr.Member e) {
        walk(e.object());
        return null;
    }

    @Override
    public Void visit(Expr.Postfix e) {
        walk(e.operand());
        return null;
    }

    @Override
    public Void visit(Expr.CompoundLiteral e) {
        walk(e.type());
        walk(e.initializer());
        return null;
    }

    @Override
    public Void visit(Expr.Unary e) {
        walk(e.operand());
        return null;
    }

    @Override
    public Void visit(Expr.TypeOperator e) {
        walk(e.type());
        return null;
    }

    @Override
    public Void visit(Expr.StaticAssertion e) {
        walk(e.condition());
        e.message().ifPresent(this::walk);
        return null;
    }

    @Override
    public Void visit(Expr.Cast e) {
        walk(e.type());
        walk(e.operand());
        return null;
    }

    @Override
    public Void visit(Expr.Binary e) {
        walk(e.left());
        walk(e.right());
        return null;
    }

    @Override
    public Void visit(Expr.Conditional e) {
        walk(e.condition());
        walk(e.thenExpr());
        walk(e.elseExpr());
        return null;
    }

    @Override
    public Void visit(Expr.Assign e) {
        walk(e.target());
        walk(e.value());
        return null;
    }

    @Override
    public Void visit(Expr.Comma e) {
        walk(e.left());
        walk(e.right());
        return null;
    }

    // ---- statements ------------------------------------------------------------

    @Override
    public Void visit(Stmt.Labeled s) {
        walkLabel(s.label());
        s.body().ifPresent(this::walk);
        return null;
    }

    @Override
    public Void visit(Stmt.Compound s) {
        for (var item : s.items()) walk(item);
        return null;
    }

    @Override
    public Void visit(Stmt.ExprStmt s) {
        s.expr().ifPresent(this::walk);
        return null;
    }

    @Override
    public Void visit(Stmt.If s) {
        walkHeader(s.header());
        walk(s.thenBranch());
        s.elseBranch().ifPresent(this::walk);
        return null;
    }

    @Override
    public Void visit(Stmt.Switch s) {
        walkHeader(s.header());
        walk(s.body());
        return null;
    }

    @Override
    public Void visit(Stmt.While s) {
        walk(s.condition());
        walk(s.body());
        return null;
    }

    @Override
    public Void visit(Stmt.DoWhile s) {
        walk(s.body());
        walk(s.condition());
        return null;
    }

    @Override
    public Void visit(Stmt.For s) {
        s.initDecl().ifPresent(this::walk);
        s.initExpr().ifPresent(this::walk);
        s.condition().ifPresent(this::walk);
        s.step().ifPresent(this::walk);
        walk(s.body());
        return null;
    }

    @Override
    public Void visit(Stmt.Goto s) {
        return null;
    }

    @Override
    public Void visit(Stmt.Continue s) {
        return null;
    }

    @Override
    public Void visit(Stmt.Break s) {
        return null;
    }

    @Override
    public Void visit(Stmt.Return s) {
        s.value().ifPresent(this::walk);
        return null;
    }

    // ---- declarations ------------------------------------------------------------

    @Override
    public Void visit(Decl.Declaration d) {
        walkSpecifiers(d.specifiers());
        for (var id : d.declarators()) walkInitDeclarator(id);
        return null;
    }

    @Override
    public Void visit(Decl.FunctionDefinition d) {
        walkSpecifiers(d.specifiers());
        walk(d.type());
        walk(d.body());
        return null;
    }

    @Override
    public Void visit(Decl.AttributeDeclaration d) {
        return null;
    }

    // ---- types ---------------------------------------------------------------------

    @Override
    public Void visit(Type.Basic t) {
        return null;
    }

    @Override
    public Void visit(Type.BitInt t) {
        walk(t.width());
        return null;
    }

    @Override
    public Void visit(Type.Pointer t) {
        walk(t.target());
        return null;
    }

    @Override
    public Void visit(Type.Array t) {
        walk(t.element());
        t.size().ifPresent(this::walk);
        return null;
    }

    @Override
    public Void visit(Type.Function t) {
        walk(t.returnType());
        for (var p : t.parameters()) walkParameter(p);
        return null;
    }

    @Override
    public Void visit(Type.Struct t) {
        t.members().ifPresent(members -> {
            for (var m : members) walkMember(m);
        });
        return null;
    }

    @Override
    public Void visit(Type.Enum t) {
        t.underlying().ifPresent(this::walk);
        t.enumerators().ifPresent(enumerators -> {
            for (var e : enumerators) walkEnumerator(e);
        });
        return null;
    }

    @Override
    public Void visit(Type.TypedefName t) {
        return null;
    }

    @Override
    public Void visit(Type.Typeof t) {
        t.expr().ifPresent(this::walk);
        t.type().ifPresent(this::walk);
        return null;
    }

    // ---- initializers --------------------------------------------------------------

    @Override
    public Void visit(Initializer.Expression i) {
        walk(i.expr());
        return null;
    }

    @Override
    public Void visit(Initializer.Braced i) {
        for (var item : i.items()) walkItem(item);
        return null;
    }
}
