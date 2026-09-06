package org.jbm.cc.sema;

import org.jbm.cc.ast.AstRewriter;
import org.jbm.cc.ast.Decl;
import org.jbm.cc.ast.Expr;
import org.jbm.cc.ast.Stmt;
import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;

import java.util.List;

/**
 * Purely syntactic rewrites that need no types and no temporaries, so
 * later passes see fewer node kinds:
 * <ul>
 * <li>{@code ++e} becomes {@code e += 1} and {@code --e} becomes
 *     {@code e -= 1}: same value, same type, the lvalue evaluated once
 *     (6.5.4.1p2 defines prefix increment exactly this way).</li>
 * <li>{@code e++} / {@code e--} in a <em>void context</em> - an expression
 *     statement, a {@code for} clause, the left operand of a comma, or the
 *     arms of such a conditional - become the same compound assignments,
 *     since the old value is discarded.</li>
 * </ul>
 * Postfix increments whose value is used ({@code x = i++}) stay: expanding
 * them needs a temporary of {@code i}'s type, which is lowering's job.
 * Likewise {@code a += b} is not expanded to {@code a = a + b}, which would
 * evaluate the lvalue twice.
 */
public final class Desugar extends AstRewriter {

    private Desugar() {
    }

    public static List<Decl> desugar(List<? extends Decl> unit) {
        return new Desugar().rewriteUnit(unit);
    }

    // ---- prefix ++ / -- anywhere ------------------------------------------------

    @Override
    public Object visit(Expr.Unary e) {
        if (isIncrement(e.op())) {
            return compoundAssignment(e.op(), rewrite(e.operand()));
        }
        return super.visit(e);
    }

    // ---- postfix ++ / -- where the value is discarded -----------------------------

    @Override
    public Object visit(Stmt.ExprStmt s) {
        Expr expr = rewriteVoid(s.expr());
        return expr == s.expr() ? s : new Stmt.ExprStmt(s.token(), expr);
    }

    @Override
    public Object visit(Stmt.For s) {
        var initDecl = (Decl.Declaration) rewrite(s.initDecl());
        Expr initExpr = rewriteVoid(s.initExpr());
        Expr cond = rewrite(s.condition());
        Expr step = rewriteVoid(s.step());
        Stmt body = rewrite(s.body());
        if (initDecl == s.initDecl() && initExpr == s.initExpr() && cond == s.condition()
                && step == s.step() && body == s.body()) return s;
        return new Stmt.For(s.keyword(), initDecl, initExpr, cond, step, body);
    }

    @Override
    public Object visit(Expr.Comma e) {
        // The left operand's value is always discarded (6.5.18p2).
        Expr left = rewriteVoid(e.left());
        Expr right = rewrite(e.right());
        return left == e.left() && right == e.right() ? e : new Expr.Comma(e.comma(), left, right);
    }

    /** Rewrites an expression whose value nobody uses. */
    private Expr rewriteVoid(Expr e) {
        if (e instanceof Expr.Postfix p && isIncrement(p.op())) {
            return compoundAssignment(p.op(), rewrite(p.operand()));
        }
        if (e instanceof Expr.Comma c) {
            Expr left = rewriteVoid(c.left());
            Expr right = rewriteVoid(c.right());
            return left == c.left() && right == c.right() ? c : new Expr.Comma(c.comma(), left, right);
        }
        if (e instanceof Expr.Conditional c) {
            Expr cond = rewrite(c.condition());
            Expr thenExpr = rewriteVoid(c.thenExpr());
            Expr elseExpr = rewriteVoid(c.elseExpr());
            if (cond == c.condition() && thenExpr == c.thenExpr() && elseExpr == c.elseExpr()) return c;
            return new Expr.Conditional(c.question(), cond, thenExpr, elseExpr);
        }
        return rewrite(e);
    }

    // ---- helpers ------------------------------------------------------------------

    private static boolean isIncrement(Token op) {
        return op.type == TokenType.PUNCTUATOR && (op.text.equals("++") || op.text.equals("--"));
    }

    // `operand += 1` / `operand -= 1`, with synthesized tokens at the
    // operator's position so diagnostics still point at the source.
    private static Expr compoundAssignment(Token op, Expr operand) {
        String assignOp = op.text.equals("++") ? "+=" : "-=";
        return new Expr.Assign(new Token(TokenType.PUNCTUATOR, assignOp, op.line, op.column), operand,
                new Expr.Literal(new Token(TokenType.INTEGER_CONSTANT, "1", op.line, op.column)));
    }
}
