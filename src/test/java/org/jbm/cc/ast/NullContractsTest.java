package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;
import org.jbm.cc.cpp.CppTokenizer.TokenType;
import org.jbm.cc.parse.Parser;
import org.jbm.cc.sema.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lombok's {@code @NonNull} turns the non-null contract into a runtime
 * check: a null where a node or an API argument is required fails at
 * that point, with the parameter's name, rather than somewhere later.
 */
class NullContractsTest {

    private static final Token TOKEN = new Token(TokenType.IDENTIFIER, "x", 1, 1);
    private static final Expr X = new Expr.Identifier(TOKEN);

    @Test
    void aNodeCannotBeBuiltWithANullChild() {
        var e = assertThrows(NullPointerException.class, () -> new Expr.Binary(TOKEN, X, null));
        assertTrue(e.getMessage().contains("right"), e.getMessage());
        assertThrows(NullPointerException.class, () -> new Expr.Identifier(null));
        assertThrows(NullPointerException.class, () -> new Stmt.Return(TOKEN, null));
        assertThrows(NullPointerException.class, () -> new Type.Pointer(TOKEN, null, Type.Quals.NONE));
        assertThrows(NullPointerException.class, () -> new Initializer.Expression(null));
    }

    @Test
    void anOptionalComponentMustBeAnOptionalNotNull() {
        // Absence is Optional.empty(); null is a bug, even in an optional slot.
        assertThrows(NullPointerException.class, () -> new Stmt.If(TOKEN,
                new Stmt.Header(Optional.empty(), Optional.of(X)), new Stmt.ExprStmt(TOKEN, Optional.of(X)), null));
        new Stmt.If(TOKEN, new Stmt.Header(Optional.empty(), Optional.of(X)),
                new Stmt.ExprStmt(TOKEN, Optional.of(X)), Optional.empty());
    }

    @Test
    void apiEntryPointsRejectNull() {
        assertThrows(NullPointerException.class, () -> AstPrinter.print((Expr) null));
        assertThrows(NullPointerException.class, () -> new AstWalker() { }.walk((Stmt) null));
        assertThrows(NullPointerException.class, () -> new AstRewriter() { }.rewrite((Type) null));
        assertThrows(NullPointerException.class, () -> Parser.parse(null));
        assertThrows(NullPointerException.class, () -> Resolver.resolve(null));
        assertThrows(NullPointerException.class, () -> new Token(null, "x", 1, 1));
    }

    @Test
    void aListComponentMayNotBeNullEither() {
        assertThrows(NullPointerException.class, () -> new Expr.Call(TOKEN, X, null));
        new Expr.Call(TOKEN, X, List.of());
    }
}
