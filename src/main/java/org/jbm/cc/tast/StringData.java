package org.jbm.cc.tast;

import lombok.NonNull;
import org.jbm.cc.sema.Symbol;

/**
 * The contents of a string literal's anonymous object (6.4.5p7): its
 * symbol, whose type is the array of its element type, and its code
 * units including the terminating null. Lowering emits one of these per
 * literal; the tree refers to it through {@code VarRef(symbol)}.
 */
public record StringData(@NonNull Symbol symbol, int @NonNull [] units) {
}
