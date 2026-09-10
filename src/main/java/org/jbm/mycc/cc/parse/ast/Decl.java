package org.jbm.mycc.cc.parse.ast;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/** Declarations (C2y 6.7.1) and external definitions (6.9). */
public sealed interface Decl extends BlockItem
        permits Decl.Declaration, Decl.FunctionDefinition, Decl.AttributeDeclaration, Expr.StaticAssertion {

    <R> R accept(Visitor<R> visitor);

    /**
     * declaration-specifiers init-declarator-listopt ; - declarators is
     * empty for a declaration that only introduces a tag or enumerators.
     */
    record Declaration(@NonNull List<Attribute> attributes, @NonNull Specifiers specifiers,
                       @NonNull List<InitDeclarator> declarators) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /**
     * init-declarator: the declared name, its full type (absent only for
     * {@code auto} type inference), and the initializer if any.
     */
    record InitDeclarator(@NonNull Token name, @NonNull Optional<Type> type, @NonNull List<Attribute> attributes,
                          @NonNull Optional<Initializer> initializer) {
    }

    /** function-definition (6.9.2). type is the declarator's {@link Type.Function}. */
    record FunctionDefinition(@NonNull List<Attribute> attributes, @NonNull Specifiers specifiers, @NonNull Token name,
                              @NonNull Type.Function type, @NonNull Stmt.Compound body) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** attribute-declaration: attribute-specifier-sequence ; */
    record AttributeDeclaration(@NonNull List<Attribute> attributes) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
