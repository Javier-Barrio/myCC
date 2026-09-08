package org.jbm.cc.tast;

import lombok.NonNull;
import org.jbm.cc.sema.Symbol;

import java.util.List;
import java.util.Optional;

/**
 * A typed translation unit, lowering's whole input: every object with
 * static storage duration (file-scope objects, block-scope statics,
 * block-scope externs) in declaration order with its initializer if it
 * has one, every function definition, and every string literal's
 * contents. Symbols carry their types and linkage; nothing here refers
 * to the AST.
 */
public record TUnit(@NonNull List<Global> globals, @NonNull List<TFunction> functions,
                    @NonNull List<StringData> strings) {

    public TUnit {
        globals = List.copyOf(globals);
        functions = List.copyOf(functions);
        strings = List.copyOf(strings);
    }

    public record Global(@NonNull Symbol symbol, @NonNull Optional<TInit> init) {
    }
}
