package org.jbm.mycc.cc.backend.lower;

import lombok.NonNull;
import org.jbm.mycc.cc.backend.lower.tac.Var;
import org.jbm.mycc.cc.sema.types.CType;
import org.jbm.mycc.cc.sema.types.Layout;

import java.util.Optional;

/**
 * A lowered lvalue: where an object is, in one of the two forms the TAC
 * has. A {@link Variable} is a scalar local or parameter used directly;
 * a {@link Memory} is a {@code ptr} to the object, with its C type, its
 * bit placement if it is a bit-field, and whether accesses are volatile.
 */
sealed interface Place permits Place.Variable, Place.Memory {

    CType type();

    record Variable(@NonNull Var var, @NonNull CType type) implements Place {
    }

    record Memory(@NonNull Var ptr, @NonNull CType type, @NonNull Optional<Layout.BitField> bits, boolean isVolatile)
            implements Place {
    }
}
