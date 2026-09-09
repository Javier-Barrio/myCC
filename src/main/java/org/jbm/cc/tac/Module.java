package org.jbm.cc.tac;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The output of one compilation for one target: structure definitions,
 * globals, declarations of what is defined elsewhere, and functions.
 * Nothing in it depends on another module except through names.
 */
public final class Module {
    public final TargetDesc target;
    public final List<StructDef> structs = new ArrayList<>();
    public final List<Global> globals = new ArrayList<>();
    public final List<FuncDecl> funcDecls = new ArrayList<>();
    public final List<GlobalDecl> globalDecls = new ArrayList<>();
    public final List<Function> functions = new ArrayList<>();

    public Module(@NonNull TargetDesc target) {
        this.target = target;
    }

    /** {@code declare @name sig}: a function defined elsewhere or by the consumer. */
    public record FuncDecl(@NonNull String name, @NonNull Type.Func sig) {
    }

    /** {@code declare @name : type}: an object defined elsewhere. */
    public record GlobalDecl(@NonNull String name, @NonNull Type type) {
    }
}
