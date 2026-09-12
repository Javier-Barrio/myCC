package org.jbm.mycc.cc.backend.codegen;

import org.jbm.mycc.cc.backend.lower.tac.Block;
import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Instr;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.Operand;
import org.jbm.mycc.cc.backend.lower.tac.Var;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a function's variables live: each parameter and local gets a
 * slot below the frame pointer, at its type's size and alignment; the
 * frame is rounded to 16 bytes. Extra slots may be reserved for what
 * the calling convention needs.
 *
 * <p>A temporary is written once and read soon after, so temporaries
 * share slots: over the instructions in order, a temporary's slot is
 * free again after its last read, and the next temporary of the same
 * size and alignment takes it. A temporary whose address is taken,
 * that is written more than once, or that is read before it is
 * written keeps a slot of its own, as every named variable does.
 */
public final class Frame {

    private final Map<Var, Long> offsets = new IdentityHashMap<>();
    private final List<Var> order = new ArrayList<>();
    private long depth;

    public Frame(Module module, Function function) {
        for (Var v : function.params) {
            add(v, module.sizeOf(v.type), module.alignOf(v.type));
        }
        Set<Var> shared = sharable(function);
        for (Var v : function.locals) {
            if (!shared.contains(v)) {
                add(v, module.sizeOf(v.type), Math.max(module.alignOf(v.type), v.align));
            }
        }
        shareSlots(module, function, shared);
    }

    // The temporaries whose slots may be shared: written exactly once,
    // never read before that, never their address taken.
    private static Set<Var> sharable(Function function) {
        var writes = new HashMap<Var, Integer>();
        var pinned = new HashSet<Var>();
        var seen = new HashSet<Var>();
        for (Instr i : instructions(function)) {
            for (Var u : uses(i)) {
                if (!seen.contains(u)) {
                    pinned.add(u);
                }
            }
            if (i instanceof Instr.AddrOfVar a) {
                pinned.add(a.var());
            }
            Var d = def(i);
            if (d != null) {
                writes.merge(d, 1, Integer::sum);
                seen.add(d);
            }
        }
        var shared = new HashSet<Var>();
        for (Var v : function.locals) {
            if (v.isTemp && !pinned.contains(v) && writes.getOrDefault(v, 0) == 1) {
                shared.add(v);
            }
        }
        return shared;
    }

    // Each sharable temporary takes a free slot of its size and alignment
    // at its write, or a new one, and gives it back after its last read.
    private void shareSlots(Module module, Function function, Set<Var> shared) {
        List<Instr> all = instructions(function);
        var lastRead = new IdentityHashMap<Var, Integer>();
        for (int at = 0; at < all.size(); at++) {
            for (Var u : uses(all.get(at))) {
                if (shared.contains(u)) {
                    lastRead.put(u, at);
                }
            }
        }
        var free = new HashMap<String, Deque<Long>>();
        var releaseAt = new HashMap<Integer, List<Var>>();
        for (int at = 0; at < all.size(); at++) {
            for (Var v : releaseAt.getOrDefault(at, List.of())) {
                free.computeIfAbsent(slotClass(module, v), k -> new ArrayDeque<>()).push(offsets.get(v));
            }
            Var d = def(all.get(at));
            if (d == null || !shared.contains(d)) {
                continue;
            }
            Deque<Long> pool = free.get(slotClass(module, d));
            Long slot = pool == null || pool.isEmpty() ? null : pool.pop();
            if (slot == null) {
                slot = reserve(module.sizeOf(d.type), module.alignOf(d.type));
            }
            offsets.put(d, slot);
            order.add(d);
            int release = lastRead.getOrDefault(d, at) + 1;
            releaseAt.computeIfAbsent(release, k -> new ArrayList<>()).add(d);
        }
    }

    private static String slotClass(Module module, Var v) {
        return module.sizeOf(v.type) + "/" + module.alignOf(v.type);
    }

    private static List<Instr> instructions(Function function) {
        var all = new ArrayList<Instr>();
        for (Block b : function.blocks) {
            all.addAll(b.instrs);
        }
        return all;
    }

    /** The variable an instruction writes, if any. */
    static Var def(Instr i) {
        if (i instanceof Instr.Mov x) {
            return x.dst();
        }
        if (i instanceof Instr.AddrOfVar x) {
            return x.dst();
        }
        if (i instanceof Instr.AddrOfGlobal x) {
            return x.dst();
        }
        if (i instanceof Instr.Bin x) {
            return x.dst();
        }
        if (i instanceof Instr.Cmp x) {
            return x.dst();
        }
        if (i instanceof Instr.Cvt x) {
            return x.dst();
        }
        if (i instanceof Instr.Load x) {
            return x.dst();
        }
        if (i instanceof Instr.Call x) {
            return x.dst();
        }
        if (i instanceof Instr.ICall x) {
            return x.dst();
        }
        if (i instanceof Instr.VaArg x) {
            return x.dst();
        }
        return null;
    }

    /** The variables an instruction reads. */
    static List<Var> uses(Instr i) {
        var out = new ArrayList<Var>();
        if (i instanceof Instr.Mov x) {
            operand(x.src(), out);
        } else if (i instanceof Instr.Bin x) {
            operand(x.a(), out);
            operand(x.b(), out);
        } else if (i instanceof Instr.Cmp x) {
            operand(x.a(), out);
            operand(x.b(), out);
        } else if (i instanceof Instr.Cvt x) {
            out.add(x.src());
        } else if (i instanceof Instr.Load x) {
            out.add(x.ptr());
        } else if (i instanceof Instr.Store x) {
            out.add(x.ptr());
            operand(x.value(), out);
        } else if (i instanceof Instr.CondBr x) {
            operand(x.cond(), out);
        } else if (i instanceof Instr.Switch x) {
            out.add(x.value());
        } else if (i instanceof Instr.Ret x) {
            if (x.value() != null) {
                operand(x.value(), out);
            }
        } else if (i instanceof Instr.Call x) {
            for (Operand a : x.args()) {
                operand(a, out);
            }
            if (x.into() != null) {
                out.add(x.into());
            }
        } else if (i instanceof Instr.ICall x) {
            out.add(x.callee());
            for (Operand a : x.args()) {
                operand(a, out);
            }
            if (x.into() != null) {
                out.add(x.into());
            }
        } else if (i instanceof Instr.VaStart x) {
            out.add(x.ap());
        } else if (i instanceof Instr.VaArg x) {
            out.add(x.ap());
        }
        return out;
    }

    private static void operand(Operand o, List<Var> out) {
        if (o instanceof Var v) {
            out.add(v);
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

    /** The variables with slots, in the order they were placed. */
    public List<Var> variables() {
        return List.copyOf(order);
    }

    /** The bytes to reserve below the frame pointer, a multiple of 16. */
    public long size() {
        return (depth + 15) / 16 * 16;
    }
}
