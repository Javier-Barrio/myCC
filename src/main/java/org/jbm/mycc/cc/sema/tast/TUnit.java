package org.jbm.mycc.cc.sema.tast;

import lombok.NonNull;
import org.jbm.mycc.cc.sema.Symbol;

import java.util.List;
import java.util.Optional;

/**
 * A typed translation unit, lowering's whole input: every object with
 * static storage duration (file-scope objects, block-scope statics,
 * anonymous literals) in declaration order, every function definition,
 * and every string literal's contents. A global that is a definition
 * gets storage here, zero unless it has an initializer (6.9.2p5); one
 * that is not (declared {@code extern} only) is a reference to another
 * unit's object. Symbols carry their types and linkage; nothing here
 * refers to the AST.
 */
public record TUnit(@NonNull List<Global> globals, @NonNull List<TFunction> functions,
                    @NonNull List<StringData> strings) {

    public TUnit {
        globals = List.copyOf(globals);
        functions = List.copyOf(functions);
        strings = List.copyOf(strings);
    }

    public record Global(@NonNull Symbol symbol, boolean isDefinition, @NonNull Optional<TInit> init) {
    }
}
