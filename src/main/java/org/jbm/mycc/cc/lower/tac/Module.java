package org.jbm.mycc.cc.lower.tac;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Every symbol, in the order the writer prints them: globals, their declarations, function declarations, functions. */
    public List<Symbol> symbols() {
        List<Symbol> all = new ArrayList<>();
        all.addAll(globals);
        all.addAll(globalDecls);
        all.addAll(funcDecls);
        all.addAll(functions);
        return all;
    }

    /** The symbol of that name, defined or declared. */
    public Optional<Symbol> symbol(@NonNull String name) {
        for (Symbol s : symbols()) {
            if (s.name().equals(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** {@code declare @name sig}: a function defined elsewhere or by the consumer. */
    public record FuncDecl(@NonNull String name, @NonNull Type.Func sig) implements Symbol {
        @Override
        public Type type() {
            return sig;
        }

        @Override
        public boolean isDefined() {
            return false;
        }
    }

    /** {@code declare @name : type}: an object defined elsewhere. */
    public record GlobalDecl(@NonNull String name, @NonNull Type type) implements Symbol {
        @Override
        public boolean isDefined() {
            return false;
        }
    }
}
