package org.jbm.mycc.cc.codegen;

import lombok.NonNull;
import org.jbm.mycc.cc.lower.arch.x86_64.X86Emitter;
import org.jbm.mycc.cc.lower.tac.Function;
import org.jbm.mycc.cc.lower.tac.Module;

import java.util.List;

/** A module to assembly text for its target; the backend is chosen by the target's name. */
public final class Codegen {

    private static final List<Backend> BACKENDS = List.of(new X86Emitter());

    private Codegen() {
    }

    public static String emit(@NonNull Module module, boolean annotate) {
        Backend backend = BACKENDS.stream()
                .filter(b -> b.target().equals(module.target.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no code generator for " + module.target.name()));
        Asm asm = new Asm(annotate);
        asm.directive(".text");
        for (Function f : module.functions) {
            backend.function(asm, module, f);
        }
        Data.emit(asm, module);
        asm.directive(".section .note.GNU-stack,\"\",@progbits");
        return asm.text();
    }
}
