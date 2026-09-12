package org.jbm.mycc.cc.parse.ast;

import lombok.NonNull;
import org.jbm.mycc.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

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

    /** [ constant-expression ], or GNU's range [ first ... last ] when {@code last} is present. */
    record ArrayDesignator(@NonNull Token bracket, @NonNull Expr index, @NonNull Optional<Expr> last) implements Designator {
    }

    /** . identifier */
    record MemberDesignator(@NonNull Token dot, @NonNull Token name) implements Designator {
    }
}
