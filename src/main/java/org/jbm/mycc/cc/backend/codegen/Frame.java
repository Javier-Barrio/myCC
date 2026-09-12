package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.Var;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a function's variables live: each parameter and local gets a
 * slot below the frame pointer, at its type's size and alignment, in
 * declaration order; the frame is rounded to 16 bytes. Extra slots may
 * be reserved for what the calling convention needs.
 */
public final class Frame {

    private final Map<Var, Long> offsets = new IdentityHashMap<>();
    private final List<Var> order = new ArrayList<>();
    private long depth;

    public Frame(Module module, Function function) {
        for (Var v : function.params) {
            add(v, module.sizeOf(v.type), module.alignOf(v.type));
        }
        for (Var v : function.locals) {
            add(v, module.sizeOf(v.type), module.alignOf(v.type));
        }
    }

    private void add(Var v, long size, int align) {
        offsets.put(v, reserve(size, align));
        order.add(v);
    }

    /** A slot of that size and alignment; its offset from the frame pointer, negative. */
    public long reserve(long size, int align) {
        long a = Math.max(align, 1);
        depth = (depth + Math.max(size, 1) + a - 1) / a * a;
        return -depth;
    }

    /** The offset of a variable's slot from the frame pointer, negative. */
    public long offset(Var v) {
        Long o = offsets.get(v);
        if (o == null) {
            throw new IllegalArgumentException(v + " has no slot");
        }
        return o;
    }

    /** The variables with slots, in declaration order. */
    public List<Var> variables() {
        return List.copyOf(order);
    }

    /** The bytes to reserve below the frame pointer, a multiple of 16. */
    public long size() {
        return (depth + 15) / 16 * 16;
    }
}
