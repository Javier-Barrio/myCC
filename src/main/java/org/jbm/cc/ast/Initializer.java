package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/** initializer (C2y 6.7.11). */
public sealed interface Initializer {

    <R> R accept(Visitor<R> visitor);

    record Expression(Expr expr) implements Initializer {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** braced-initializer: { initializer-list ,opt } or { }. */
    record Braced(Token brace, List<Item> items) implements Initializer {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** designationopt initializer. */
    record Item(List<Designator> designators, Initializer initializer) {
    }

    sealed interface Designator {
    }

    /** [ constant-expression ] */
    record ArrayDesignator(Token bracket, Expr index) implements Designator {
    }

    /** . identifier */
    record MemberDesignator(Token dot, Token name) implements Designator {
    }
}
