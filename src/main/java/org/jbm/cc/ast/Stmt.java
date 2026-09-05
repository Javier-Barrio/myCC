package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/** Statements (C2y 6.8). */
public sealed interface Stmt extends BlockItem {

    /**
     * label statement (6.8.2), or a bare label as a block-item (6.8.3) when
     * body is null.
     */
    record Labeled(Label label, Stmt body) implements Stmt {
    }

    sealed interface Label {
    }

    /** identifier : */
    record NameLabel(Token name) implements Label {
    }

    /** case constant-expression : / case lo ... hi : (high is null for a single value). */
    record CaseLabel(Token keyword, Expr low, Expr high) implements Label {
    }

    /** default : */
    record DefaultLabel(Token keyword) implements Label {
    }

    /** compound-statement (6.8.3). */
    record Compound(Token brace, List<BlockItem> items) implements Stmt {
    }

    /** expression-statement (6.8.4); expr is null for the null statement {@code ;}. */
    record ExprStmt(Token token, Expr expr) implements Stmt {
    }

    /**
     * selection-header (6.8.5.1): {@code expression} (declaration null),
     * {@code declaration expression}, or a {@code simple-declaration}
     * (condition null: the declared object is the controlling value).
     */
    record Header(Decl.Declaration declaration, Expr condition) {
    }

    record If(Token keyword, Header header, Stmt thenBranch, Stmt elseBranch) implements Stmt {
    }

    record Switch(Token keyword, Header header, Stmt body) implements Stmt {
    }

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {
    }

    record DoWhile(Token keyword, Stmt body, Expr condition) implements Stmt {
    }

    /** for (6.8.6.1); at most one of initDecl / initExpr is non-null, any clause may be null. */
    record For(Token keyword, Decl.Declaration initDecl, Expr initExpr, Expr condition, Expr step,
               Stmt body) implements Stmt {
    }

    record Goto(Token keyword, Token label) implements Stmt {
    }

    /** continue identifieropt ; */
    record Continue(Token keyword, Token label) implements Stmt {
    }

    /** break identifieropt ; */
    record Break(Token keyword, Token label) implements Stmt {
    }

    record Return(Token keyword, Expr value) implements Stmt {
    }
}
