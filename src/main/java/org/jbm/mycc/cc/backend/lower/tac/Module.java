package org.jbm.mycc.cc.backend.lower.tac;

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

    /** The size in bytes of a memory type, as the compiler laid it out for this module's target. */
    public long sizeOf(@NonNull Type t) {
        if (t instanceof Type.Int i) {
            return i.width() / 8;
        }
        if (t instanceof Type.Float f) {
            return f.width() <= 64 ? f.width() / 8 : 16;
        }
        if (t instanceof Type.Ptr) {
            return target.pointerWidth() / 8;
        }
        if (t instanceof Type.Array a) {
            return sizeOf(a.element()) * a.count();
        }
        if (t instanceof Type.Struct st) {
            return struct(st.name()).size();
        }
        throw new IllegalArgumentException("no size for " + t.spelling());
    }

    /**
     * The bytes a global's storage takes: its type's size, or more when
     * the initializer reaches past it, as GNU's initialized flexible
     * array member does.
     */
    public long imageSize(@NonNull Global g) {
        long size = sizeOf(g.type());
        if (g.init() == null) {
            return size;
        }
        for (Global.Item item : g.init()) {
            size = Math.max(size, item.offset() + itemSize(item));
        }
        return size;
    }

    private long itemSize(Global.Item item) {
        if (item instanceof Global.IntItem x) {
            return sizeOf(x.type());
        }
        if (item instanceof Global.FloatItem x) {
            return sizeOf(x.type());
        }
        if (item instanceof Global.AddrItem) {
            return target.pointerWidth() / 8;
        }
        if (item instanceof Global.BytesItem x) {
            return x.bytes().length;
        }
        var bits = (Global.BitItem) item;
        return (bits.bit() + bits.width() + 7) / 8;
    }

    /** The alignment in bytes of a memory type. */
    public int alignOf(@NonNull Type t) {
        if (t instanceof Type.Array a) {
            return alignOf(a.element());
        }
        if (t instanceof Type.Struct st) {
            return struct(st.name()).align();
        }
        return (int) Math.min(sizeOf(t), 16);
    }

    public StructDef struct(@NonNull String name) {
        for (StructDef s : structs) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown structure %" + name);
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
