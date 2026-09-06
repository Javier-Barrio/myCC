package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/** Statements (C2y 6.8). Optional parts of the grammar are {@link Optional}s; nothing is null. */
public sealed interface Stmt extends BlockItem {

    <R> R accept(Visitor<R> visitor);

    /**
     * label statement (6.8.2), or a bare label as a block-item (6.8.3) when
     * body is absent.
     */
    record Labeled(Label label, Optional<Stmt> body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    sealed interface Label {
    }

    /** identifier : */
    record NameLabel(Token name) implements Label {
    }

    /** case constant-expression : / case lo ... hi : (high is absent for a single value). */
    record CaseLabel(Token keyword, Expr low, Optional<Expr> high) implements Label {
    }

    /** default : */
    record DefaultLabel(Token keyword) implements Label {
    }

    /** compound-statement (6.8.3). */
    record Compound(Token brace, List<BlockItem> items) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** expression-statement (6.8.4); expr is absent for the null statement {@code ;}. */
    record ExprStmt(Token token, Optional<Expr> expr) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * selection-header (6.8.5.1): {@code expression} (no declaration),
     * {@code declaration expression}, or a {@code simple-declaration}
     * (no condition: the declared object is the controlling value).
     */
    record Header(Optional<Decl.Declaration> declaration, Optional<Expr> condition) {
    }

    record If(Token keyword, Header header, Stmt thenBranch, Optional<Stmt> elseBranch) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Switch(Token keyword, Header header, Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record While(Token keyword, Expr condition, Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record DoWhile(Token keyword, Stmt body, Expr condition) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** for (6.8.6.1); at most one of initDecl / initExpr is present, any clause may be absent. */
    record For(Token keyword, Optional<Decl.Declaration> initDecl, Optional<Expr> initExpr,
               Optional<Expr> condition, Optional<Expr> step, Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Goto(Token keyword, Token label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** continue identifieropt ; */
    record Continue(Token keyword, Optional<Token> label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** break identifieropt ; */
    record Break(Token keyword, Optional<Token> label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Return(Token keyword, Optional<Expr> value) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
