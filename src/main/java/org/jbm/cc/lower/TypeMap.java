package org.jbm.cc.lower;

import lombok.NonNull;
import org.jbm.cc.tac.Module;
import org.jbm.cc.tac.StructDef;
import org.jbm.cc.tac.Type;
import org.jbm.cc.types.CType;
import org.jbm.cc.types.Layout;
import org.jbm.cc.types.Tag;
import org.jbm.cc.types.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * C types to TAC types, for the target the typer used. Structures are
 * registered in the module the first time they are seen, in dependency
 * order, under their tag's name or a generated one.
 */
final class TypeMap {

    private final Types types;
    private final Module module;
    private final Map<Tag, String> structNames = new IdentityHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<Tag> defined = new HashSet<>();
    private int anonymous;

    TypeMap(@NonNull Types types, @NonNull Module module) {
        this.types = types;
        this.module = module;
    }

    Type of(@NonNull CType t) {
        if (t instanceof CType.Int || t instanceof CType.BitInt) return integer(t);
        if (t instanceof CType.Float f) {
            return switch (f.rank()) {
                case FLOAT -> Type.F32;
                case DOUBLE -> Type.F64;
                case LDOUBLE -> types.size(f) > 8 ? Type.F80 : Type.F64;
            };
        }
        if (t instanceof CType.Pointer || t instanceof CType.Nullptr) return Type.PTR;
        if (t instanceof CType.Array a) return new Type.Array(of(a.element()), a.size().orElse(0));
        if (t instanceof CType.Record r) return new Type.Struct(struct(r.tag()));
        if (t instanceof CType.Function f) return func(f);
        return Type.VOID;
    }

    Type.Int integer(@NonNull CType t) {
        int w = types.width(t);
        int storage = w <= 8 ? 8 : w <= 16 ? 16 : w <= 32 ? 32 : 64;
        return new Type.Int(storage, types.isSigned(t));
    }

    Type.Func func(@NonNull CType.Function f) {
        var params = new ArrayList<Type>(f.parameters().size());
        for (var p : f.parameters()) params.add(of(p));
        return new Type.Func(params, f.isVariadic(), of(f.returnType()));
    }

    /** The module's name for a tag, defining the structure on first sight of a complete one. */
    private String struct(Tag tag) {
        String name = structNames.get(tag);
        if (name == null) {
            name = tag.name().orElseGet(() -> "anon." + ++anonymous);
            String base = name;
            for (int n = 2; !usedNames.add(name); n++) name = base + "." + n;
            structNames.put(tag, name);
        }
        if (!defined.contains(tag) && tag.layout().isPresent()) {
            defined.add(tag);
            Layout layout = tag.layout().get();
            var members = new ArrayList<StructDef.Member>();
            for (var m : layout.slots()) members.add(new StructDef.Member(of(m.type()), m.offset()));
            module.structs.add(new StructDef(name, members, layout.size(), layout.align()));
        }
        return name;
    }
}
