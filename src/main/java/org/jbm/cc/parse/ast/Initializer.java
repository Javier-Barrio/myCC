package org.jbm.cc.parse.ast;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/** initializer (C2y 6.7.11). */
public sealed interface Initializer {

    <R> R accept(Visitor<R> visitor);

    record Expression(@NonNull Expr expr) implements Initializer {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** braced-initializer: { initializer-list ,opt } or { }. */
    record Braced(@NonNull Token brace, @NonNull List<Item> items) implements Initializer {
        @Override
        public <R> R accept(Visitor<R> v) {
            return v.visit(this);
        }
    }

    /** designationopt initializer. */
    record Item(@NonNull List<Designator> designators, @NonNull Initializer initializer) {
    }

    sealed interface Designator {
    }

    /** [ constant-expression ] */
    record ArrayDesignator(@NonNull Token bracket, @NonNull Expr index) implements Designator {
    }

    /** . identifier */
    record MemberDesignator(@NonNull Token dot, @NonNull Token name) implements Designator {
    }
}
