package org.jbm.cc.parse.ast;

import lombok.NonNull;
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
    record Labeled(@NonNull Label label, @NonNull Optional<Stmt> body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    sealed interface Label {
    }

    /** identifier : */
    record NameLabel(@NonNull Token name) implements Label {
    }

    /** case constant-expression : / case lo ... hi : (high is absent for a single value). */
    record CaseLabel(@NonNull Token keyword,
                     @NonNull Expr low,
                     @NonNull Optional<Expr> high) implements Label {
    }

    /** default : */
    record DefaultLabel(@NonNull Token keyword) implements Label {
    }

    /** compound-statement (6.8.3). */
    record Compound(@NonNull Token brace, @NonNull List<BlockItem> items) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** expression-statement (6.8.4); expr is absent for the null statement {@code ;}. */
    record ExprStmt(@NonNull Token token, @NonNull Optional<Expr> expr) implements Stmt {
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
    record Header(@NonNull Optional<Decl.Declaration> declaration, @NonNull Optional<Expr> condition) {
    }

    record If(@NonNull Token keyword,
              @NonNull Header header,
              @NonNull Stmt thenBranch,
              @NonNull Optional<Stmt> elseBranch) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Switch(@NonNull Token keyword, @NonNull Header header, @NonNull Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record While(@NonNull Token keyword, @NonNull Expr condition, @NonNull Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record DoWhile(@NonNull Token keyword, @NonNull Stmt body, @NonNull Expr condition) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** for (6.8.6.1); at most one of initDecl / initExpr is present, any clause may be absent. */
    record For(@NonNull Token keyword, @NonNull Optional<Decl.Declaration> initDecl, @NonNull Optional<Expr> initExpr,
               @NonNull Optional<Expr> condition, @NonNull Optional<Expr> step, @NonNull Stmt body) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Goto(@NonNull Token keyword, @NonNull Token label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** continue identifieropt ; */
    record Continue(@NonNull Token keyword, @NonNull Optional<Token> label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** break identifieropt ; */
    record Break(@NonNull Token keyword, @NonNull Optional<Token> label) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    record Return(@NonNull Token keyword, @NonNull Optional<Expr> value) implements Stmt {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
