package org.jbm.cc.ast;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/**
 * The folded result of a declaration-specifiers / specifier-qualifier-list
 * (C2y 6.7.1, 6.7.3.2): the storage classes and function specifiers that
 * appeared, the alignment specifier if any, and the type the type
 * specifiers and qualifiers fold to. type is absent only when {@code auto}
 * asks for the type to be inferred from the initializer.
 */
public record Specifiers(@NonNull Token token, @NonNull List<Token> storageClasses,
                         @NonNull List<Token> functionSpecifiers,
                         @NonNull Optional<Alignas> alignment, @NonNull Optional<Type> type) {

    /** alignment-specifier (6.7.6). Exactly one of type / expr is present. */
    public record Alignas(@NonNull Token keyword,
                          @NonNull Optional<Type> type,
                          @NonNull Optional<Expr> expr) {
    }

    public boolean has(String keyword) {
        return storageClasses.stream().anyMatch(t -> t.text.equals(keyword))
                || functionSpecifiers.stream().anyMatch(t -> t.text.equals(keyword));
    }

    public boolean isTypedef() {
        return has("typedef");
    }
}
