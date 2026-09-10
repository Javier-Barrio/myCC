package org.jbm.mycc.cc.sema.tast;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;
import org.jbm.mycc.cc.sema.Symbol;
import org.jbm.mycc.cc.sema.tast.TExpr.Rvalue;

import java.util.List;
import java.util.Optional;

/**
 * Typed statements (C2y 6.8). Conditions are {@code bool} rvalues,
 * return values are converted to the function's return type, block-scope
 * declarations are {@link LocalDecl}s at their position, and every jump
 * holds the {@link JumpTarget} of the statement it goes to.
 */
public sealed interface TStmt {

    Token token();

    <R> R accept(TStmtVisitor<R> visitor);

    record Block(@NonNull List<TStmt> items, @NonNull Token token) implements TStmt {
        public Block {
            items = List.copyOf(items);
        }

        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** An expression evaluated for its effects; the value is discarded (6.8.4p2). */
    record ExprStmt(@NonNull Rvalue expr, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** A block-scope object with automatic storage, initialized here if at all. */
    record LocalDecl(@NonNull Symbol symbol, @NonNull Optional<TInit> init, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    record If(@NonNull Rvalue condition, @NonNull TStmt thenBranch, @NonNull Optional<TStmt> elseBranch,
              @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    record While(@NonNull Rvalue condition, @NonNull TStmt body, @NonNull JumpTarget target, @NonNull Token token)
            implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    record DoWhile(@NonNull TStmt body, @NonNull Rvalue condition, @NonNull JumpTarget target, @NonNull Token token)
            implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** {@code for}: init is the clause's declarations or expression statement, in the loop's own scope. */
    record For(@NonNull List<TStmt> init, @NonNull Optional<Rvalue> condition, @NonNull Optional<Rvalue> step,
               @NonNull TStmt body, @NonNull JumpTarget target, @NonNull Token token) implements TStmt {
        public For {
            init = List.copyOf(init);
        }

        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * {@code switch} (6.8.5.3): the controlling value is promoted; the
     * cases carry their values, converted to that type, and the targets
     * of the {@link Labeled} statements in the body that they land on.
     */
    record Switch(@NonNull Rvalue value, @NonNull List<CaseLabel> cases, @NonNull Optional<JumpTarget> defaultTarget,
                  @NonNull TStmt body, @NonNull JumpTarget target, @NonNull Token token) implements TStmt {
        public Switch {
            cases = List.copyOf(cases);
        }

        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    sealed interface CaseLabel permits Case, CaseRange {
        JumpTarget target();
    }

    record Case(long value, @NonNull JumpTarget target) implements CaseLabel {
    }

    /** {@code case low ... high:} */
    record CaseRange(long low, long high, @NonNull JumpTarget target) implements CaseLabel {
    }

    /** A named, case or default label; body is absent for a bare label at the end of a block. */
    record Labeled(@NonNull JumpTarget target, @NonNull Optional<TStmt> body, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    record Goto(@NonNull JumpTarget target, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Leaves the loop or switch whose target this is. */
    record Break(@NonNull JumpTarget target, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** Restarts the loop whose target this is. */
    record Continue(@NonNull JumpTarget target, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }

    /** The value, when present, is already converted to the function's return type. */
    record Return(@NonNull Optional<Rvalue> value, @NonNull Token token) implements TStmt {
        @Override
        public <R> R accept(TStmtVisitor<R> v) {
            return v.visit(this);
        }
    }
}
