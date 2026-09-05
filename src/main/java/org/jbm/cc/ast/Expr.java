package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/**
 * Expressions (C2y 6.5). Every node keeps the token it is anchored at, for
 * diagnostics; literals keep their spelling and are decoded by sema.
 */
public sealed interface Expr {

    /** identifier (6.5.2). */
    record Identifier(Token name) implements Expr {
    }

    /**
     * integer-literal, floating-literal, character-literal, or one of the
     * predefined constants {@code true}, {@code false}, {@code nullptr}
     * (6.4.5). The token's type and text say which.
     */
    record Literal(Token token) implements Expr {
    }

    /** Adjacent string literals, concatenated in translation phase 6. */
    record StringLiteral(List<Token> parts) implements Expr {
    }

    /**
     * generic-selection (6.5.2.1). Exactly one of controllingExpr /
     * controllingType is non-null.
     */
    record Generic(Token keyword, Expr controllingExpr, Type controllingType,
                   List<Association> associations) implements Expr {
        /** type is null for the {@code default} association. */
        public record Association(Type type, Expr expr) {
        }
    }

    /** postfix-expression [ expression ] (6.5.3.1). */
    record Index(Token bracket, Expr array, Expr index) implements Expr {
    }

    /** postfix-expression ( argument-expression-list ) (6.5.3.1). */
    record Call(Token paren, Expr callee, List<Expr> arguments) implements Expr {
    }

    /** postfix-expression . identifier / -> identifier; op is the punctuator. */
    record Member(Token op, Expr object, Token name) implements Expr {
    }

    /** postfix ++ / -- (6.5.3.1). */
    record Postfix(Token op, Expr operand) implements Expr {
    }

    /** compound-literal (6.5.3.6): ( storage-class-specifiers type-name ) braced-initializer. */
    record CompoundLiteral(Token paren, List<Token> storageClasses, Type type,
                           Initializer.Braced initializer) implements Expr {
    }

    /**
     * Prefix ++ / --, the unary operators {@code & * + - ~ !}, and the
     * expression forms of {@code sizeof} and {@code _Countof} (6.5.4.1).
     */
    record Unary(Token op, Expr operand) implements Expr {
    }

    /** sizeof ( type-name ), alignof ( type-name ), _Countof ( type-name ). */
    record TypeOperator(Token op, Type type) implements Expr {
    }

    /** static-assertion used as a unary-expression (6.5.4.6) or as a declaration (6.7.1). */
    record StaticAssertion(Token keyword, Expr condition, StringLiteral message)
            implements Expr, Decl, Type.MemberDecl {
    }

    /** ( type-name ) cast-expression (6.5.5). */
    record Cast(Token paren, Type type, Expr operand) implements Expr {
    }

    /** The binary operators of 6.5.6 - 6.5.15. */
    record Binary(Token op, Expr left, Expr right) implements Expr {
    }

    /** conditional-expression (6.5.16). */
    record Conditional(Token question, Expr condition, Expr thenExpr, Expr elseExpr) implements Expr {
    }

    /** assignment-expression (6.5.17.1); op is one of = *= /= %= += -= <<= >>= &= ^= |=. */
    record Assign(Token op, Expr target, Expr value) implements Expr {
    }

    /** expression , assignment-expression (6.5.18). */
    record Comma(Token comma, Expr left, Expr right) implements Expr {
    }
}
