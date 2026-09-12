package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Module;

/** What a target provides to the driver: the assembly of one function. */
public interface Backend {

    /** The target this backend serves, as {@code TargetDesc.name} spells it. */
    String target();

    void function(Asm asm, Module module, Function function);
}
