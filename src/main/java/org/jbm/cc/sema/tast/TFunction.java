package org.jbm.cc.sema.tast;

import lombok.NonNull;
import org.jbm.cc.sema.Symbol;

import java.util.List;

/**
 * A function definition (6.9.2): its symbol (whose type is the function
 * type), its named parameters in order, every automatic object declared
 * anywhere in its body (so lowering can lay out a frame in one pass),
 * and the typed body.
 */
public record TFunction(@NonNull Symbol symbol, @NonNull List<Symbol> parameters, @NonNull List<Symbol> locals,
                        @NonNull TStmt.Block body) {
    public TFunction {
        parameters = List.copyOf(parameters);
        locals = List.copyOf(locals);
    }
}
