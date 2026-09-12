package org.jbm.mycc.cc.parse.ast;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/**
 * Expressions (C2y 6.5). Every node keeps the token it is anchored at, for
 * diagnostics; literals keep their spelling and are decoded by sema.
 * Optional parts of the grammar are {@link Optional}s; nothing is null.
 */
public sealed interface Expr {

    <R> R accept(Visitor<R> visitor);

    /** identifier (6.5.2). */
    record Identifier(@NonNull Token name) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * integer-literal, floating-literal, character-literal, or one of the
     * predefined constants {@code true}, {@code false}, {@code nullptr}
     * (6.4.5). The token's type and text say which.
     */
    record Literal(@NonNull Token token) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** Adjacent string literals, concatenated in translation phase 6. */
    record StringLiteral(@NonNull List<Token> parts) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * generic-selection (6.5.2.1). The controlling operand is an expression
     * or (C2y) a type-name: exactly one of the two is present.
     */
    record Generic(@NonNull Token keyword, @NonNull Optional<Expr> controllingExpr,
                   @NonNull Optional<Type> controllingType,
                   @NonNull List<Association> associations) implements Expr {
        /** type is absent for the {@code default} association. */
        public record Association(@NonNull Optional<Type> type, @NonNull Expr expr) {
        }

        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** postfix-expression [ expression ] (6.5.3.1). */
    record Index(@NonNull Token bracket, @NonNull Expr array, @NonNull Expr index) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** postfix-expression ( argument-expression-list ) (6.5.3.1). */
    record Call(@NonNull Token paren, @NonNull Expr callee, @NonNull List<Expr> arguments) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** postfix-expression . identifier / -> identifier; op is the punctuator. */
    record Member(@NonNull Token op, @NonNull Expr object, @NonNull Token name) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** postfix ++ / -- (6.5.3.1). */
    record Postfix(@NonNull Token op, @NonNull Expr operand) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** compound-literal (6.5.3.6): ( storage-class-specifiers type-name ) braced-initializer. */
    record CompoundLiteral(@NonNull Token paren, @NonNull List<Token> storageClasses, @NonNull Type type,
                           @NonNull Initializer.Braced initializer) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * Prefix ++ / --, the unary operators {@code & * + - ~ !}, and the
     * expression forms of {@code sizeof} and {@code _Countof} (6.5.4.1).
     */
    record Unary(@NonNull Token op, @NonNull Expr operand) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** sizeof ( type-name ), alignof ( type-name ), _Countof ( type-name ). */
    record TypeOperator(@NonNull Token op, @NonNull Type type) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** static-assertion used as a unary-expression (6.5.4.6) or as a declaration (6.7.1). */
    record StaticAssertion(@NonNull Token keyword,
                           @NonNull Expr condition,
                           @NonNull Optional<StringLiteral> message)
            implements Expr, Decl, Type.MemberDecl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** ( type-name ) cast-expression (6.5.5). */
    record Cast(@NonNull Token paren, @NonNull Type type, @NonNull Expr operand) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** The binary operators of 6.5.6 - 6.5.15. */
    record Binary(@NonNull Token op, @NonNull Expr left, @NonNull Expr right) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** conditional-expression (6.5.16). */
    record Conditional(@NonNull Token question,
                       @NonNull Expr condition,
                       @NonNull Expr thenExpr,
                       @NonNull Expr elseExpr) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** assignment-expression (6.5.17.1); op is one of = *= /= %= += -= <<= >>= &= ^= |=. */
    record Assign(@NonNull Token op, @NonNull Expr target, @NonNull Expr value) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** __builtin_va_start(ap, last): starts the argument list ap after the named parameter last. */
    record VaStart(@NonNull Token token, @NonNull Expr ap, @NonNull Expr last) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** __builtin_va_arg(ap, type-name): the next unnamed argument, as the type. */
    record VaArg(@NonNull Token token, @NonNull Expr ap, @NonNull Type type) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** ({ compound-statement }), GNU's statement expression: the value of the last expression statement. */
    record StmtExpr(@NonNull Token paren, @NonNull Stmt.Compound body) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** expression , assignment-expression (6.5.18). */
    record Comma(@NonNull Token comma, @NonNull Expr left, @NonNull Expr right) implements Expr {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
