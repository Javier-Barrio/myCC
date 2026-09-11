package org.jbm.mycc.cc.codegen;

import org.jbm.mycc.cc.lower.tac.Global;
import org.jbm.mycc.cc.lower.tac.Linkage;
import org.jbm.mycc.cc.lower.tac.Module;

/** The globals as directives; step 6 fills in the initializers. */
final class Data {

    private Data() {
    }

    static void emit(Asm asm, Module module) {
        for (Global g : module.globals) {
            if (g.linkage() == Linkage.EXTERNAL) {
                asm.directive(".globl " + g.name());
            }
            asm.directive(".bss");
            asm.directive(".balign " + Math.max(g.align(), 1));
            asm.label(g.name());
            asm.directive(".zero " + module.sizeOf(g.type()));
        }
    }
}
