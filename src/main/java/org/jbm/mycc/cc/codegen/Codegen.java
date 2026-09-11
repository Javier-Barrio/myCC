package org.jbm.mycc.cc.codegen;

import lombok.NonNull;
import org.jbm.mycc.cc.lower.arch.x86_64.X86Emitter;
import org.jbm.mycc.cc.lower.tac.Function;
import org.jbm.mycc.cc.lower.tac.Module;

import java.util.List;

/** A module to assembly text for its target; the backend is chosen by the target's name. */
public final class Codegen {

    // A backend is made per module: its state, such as constant labels, is one file's.
    private static final List<java.util.function.Supplier<Backend>> BACKENDS = List.of(X86Emitter::new);

    private Codegen() {
    }

    public static String emit(@NonNull Module module, boolean annotate) {
        return AttPrinter.print(build(module, annotate));
    }

    /** The assembly IR of a module: what the printer spells and an assembler would encode. */
    public static List<Item> build(@NonNull Module module, boolean annotate) {
        Backend backend = backend(module);
        Asm asm = new Asm(annotate);
        asm.section(".text");
        for (Function f : module.functions) {
            backend.function(asm, module, f);
        }
        Data.emit(asm, module);
        asm.section(".section .note.GNU-stack,\"\",@progbits");
        return asm.items();
    }

    /** One function's assembly alone, for a reader. */
    public static String emit(@NonNull Module module, @NonNull Function function, boolean annotate) {
        Asm asm = new Asm(annotate);
        backend(module).function(asm, module, function);
        return asm.text();
    }

    private static Backend backend(Module module) {
        return BACKENDS.stream()
                .map(java.util.function.Supplier::get)
                .filter(b -> b.target().equals(module.target.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no code generator for " + module.target.name()));
    }
}
