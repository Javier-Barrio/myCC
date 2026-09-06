package org.jbm.cc.ast;

import java.util.List;

/**
 * A visitor over every AST hierarchy that, by default, just traverses the
 * children. Passes extend it and override the node kinds they care about;
 * an override that still wants the children walked calls {@code super}.
 * <p>
 * The {@code walk*} helpers are null-safe and are the way to descend into
 * child nodes; the non-sealed helper records (parameters, members,
 * enumerators, labels, headers, initializer items, specifiers) have their
 * own {@code walk} hook so a pass can intercept them too.
 * <p>
 * A {@link Type.TypedefName} is deliberately a leaf: descending into the
 * aliased type would re-visit the typedef's original struct/enum body at
 * every use.
 */
public abstract class AstWalker implements Visitor<Void> {

    // ---- entry points ----------------------------------------------------

    public void walkUnit(List<? extends Decl> unit) {
        for (Decl d : unit) walk(d);
    }

    public void walk(BlockItem item) {
        if (item instanceof Decl d) walk(d);
        else if (item != null) walk((Stmt) item);
    }

    public void walk(Decl d) {
        if (d != null) d.accept(this);
    }

    public void walk(Stmt s) {
        if (s != null) s.accept(this);
    }

    public void walk(Expr e) {
        if (e != null) e.accept(this);
    }

    public void walk(Type t) {
        if (t != null) t.accept(this);
    }

    public void walk(Initializer i) {
        if (i != null) i.accept(this);
    }

    // ---- non-sealed helper records ------------------------------------------

    protected void walkSpecifiers(Specifiers s) {
        walk(s.type());
        if (s.alignment() != null) {
            walk(s.alignment().type());
            walk(s.alignment().expr());
        }
    }

    protected void walkInitDeclarator(Decl.InitDeclarator d) {
        walk(d.type());
        walk(d.initializer());
    }

    protected void walkParameter(Type.Parameter p) {
        walk(p.type());
    }

    protected void walkMember(Type.MemberDecl m) {
        if (m instanceof Type.Member member) {
            walk(member.type());
            walk(member.bitWidth());
        } else {
            walk((Expr) m);
        }
    }

    protected void walkEnumerator(Type.Enumerator e) {
        walk(e.value());
    }

    protected void walkLabel(Stmt.Label label) {
        if (label instanceof Stmt.CaseLabel c) {
            walk(c.low());
            walk(c.high());
        }
    }

    protected void walkHeader(Stmt.Header h) {
        walk(h.declaration());
        walk(h.condition());
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
        walk(e.controllingExpr());
        walk(e.controllingType());
        for (var a : e.associations()) {
            walk(a.type());
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
        walk(e.message());
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
        walk(s.body());
        return null;
    }

    @Override
    public Void visit(Stmt.Compound s) {
        for (var item : s.items()) walk(item);
        return null;
    }

    @Override
    public Void visit(Stmt.ExprStmt s) {
        walk(s.expr());
        return null;
    }

    @Override
    public Void visit(Stmt.If s) {
        walkHeader(s.header());
        walk(s.thenBranch());
        walk(s.elseBranch());
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
        walk(s.initDecl());
        walk(s.initExpr());
        walk(s.condition());
        walk(s.step());
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
        walk(s.value());
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
        walk(t.size());
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
        if (t.members() != null) {
            for (var m : t.members()) walkMember(m);
        }
        return null;
    }

    @Override
    public Void visit(Type.Enum t) {
        walk(t.underlying());
        if (t.enumerators() != null) {
            for (var e : t.enumerators()) walkEnumerator(e);
        }
        return null;
    }

    @Override
    public Void visit(Type.TypedefName t) {
        return null;
    }

    @Override
    public Void visit(Type.Typeof t) {
        walk(t.expr());
        walk(t.type());
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
