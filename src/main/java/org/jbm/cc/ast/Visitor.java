package org.jbm.cc.ast;

/**
 * One visitor over the whole tree: every {@link Expr}, {@link Stmt},
 * {@link Decl}, {@link Type} and {@link Initializer} node kind has a
 * {@code visit} method here and an {@code accept} that dispatches to it.
 * Adding a node kind is a compile error until every visitor handles it.
 * {@link AstWalker} implements this with default child traversal for
 * passes that only care about some node kinds.
 */
public interface Visitor<R> {

    // ---- expressions (6.5) ----
    R visit(Expr.Identifier e);
    R visit(Expr.Literal e);
    R visit(Expr.StringLiteral e);
    R visit(Expr.Generic e);
    R visit(Expr.Index e);
    R visit(Expr.Call e);
    R visit(Expr.Member e);
    R visit(Expr.Postfix e);
    R visit(Expr.CompoundLiteral e);
    R visit(Expr.Unary e);
    R visit(Expr.TypeOperator e);
    R visit(Expr.StaticAssertion e);
    R visit(Expr.Cast e);
    R visit(Expr.Binary e);
    R visit(Expr.Conditional e);
    R visit(Expr.Assign e);
    R visit(Expr.Comma e);

    // ---- statements (6.8) ----
    R visit(Stmt.Labeled s);
    R visit(Stmt.Compound s);
    R visit(Stmt.ExprStmt s);
    R visit(Stmt.If s);
    R visit(Stmt.Switch s);
    R visit(Stmt.While s);
    R visit(Stmt.DoWhile s);
    R visit(Stmt.For s);
    R visit(Stmt.Goto s);
    R visit(Stmt.Continue s);
    R visit(Stmt.Break s);
    R visit(Stmt.Return s);

    // ---- declarations (6.7, 6.9) ----
    R visit(Decl.Declaration d);
    R visit(Decl.FunctionDefinition d);
    R visit(Decl.AttributeDeclaration d);

    // ---- types (6.7.3 - 6.7.9) ----
    R visit(Type.Basic t);
    R visit(Type.BitInt t);
    R visit(Type.Pointer t);
    R visit(Type.Array t);
    R visit(Type.Function t);
    R visit(Type.Struct t);
    R visit(Type.Enum t);
    R visit(Type.TypedefName t);
    R visit(Type.Typeof t);

    // ---- initializers (6.7.11) ----
    R visit(Initializer.Expression i);
    R visit(Initializer.Braced i);
}
