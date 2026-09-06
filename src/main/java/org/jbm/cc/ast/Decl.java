package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/** Declarations (C2y 6.7.1) and external definitions (6.9). */
public sealed interface Decl extends BlockItem
        permits Decl.Declaration, Decl.FunctionDefinition, Decl.AttributeDeclaration, Expr.StaticAssertion {

    <R> R accept(Visitor<R> visitor);

    /**
     * declaration-specifiers init-declarator-listopt ; - declarators is
     * empty for a declaration that only introduces a tag or enumerators.
     */
    record Declaration(List<Attribute> attributes, Specifiers specifiers,
                       List<InitDeclarator> declarators) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** init-declarator: the declared name, its full type, and the initializer if any. */
    record InitDeclarator(Token name, Type type, List<Attribute> attributes, Initializer initializer) {
    }

    /** function-definition (6.9.2). type is the declarator's {@link Type.Function}. */
    record FunctionDefinition(List<Attribute> attributes, Specifiers specifiers, Token name,
                              Type.Function type, Stmt.Compound body) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** attribute-declaration: attribute-specifier-sequence ; */
    record AttributeDeclaration(List<Attribute> attributes) implements Decl {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }
}
