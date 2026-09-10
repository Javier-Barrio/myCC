package org.jbm.vm;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A function the VM provides under a name a program declares: the C
 * library, bound by the shell. Arguments arrive as the caller's values;
 * the result is a value, or null for void.
 */
@FunctionalInterface
public interface Builtin {
    @Nullable VM.Value call(VM vm, List<VM.Value> args);
}
