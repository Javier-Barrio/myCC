package org.jbm.cc.tac;

import lombok.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The well-formedness rules of {@code tac-plan.md}, checked on a whole
 * module: variables declared once and used in the classes their
 * instructions require, blocks terminated exactly once with targets in
 * the function, names defined or declared, calls matching their
 * signatures, memory widths the target has. A violation is an
 * {@link IllegalStateException} naming the function and the instruction.
 */
public final class TacInvariants implements TacVisitor<Void> {

    private final Module module;
    private final Set<String> structs = new HashSet<>();
    private final Set<String> globalNames = new HashSet<>();
    private final Map<String, Type.Func> functionSigs = new HashMap<>();
    private Function function;
    private Set<Var> declared;
    private Set<Block> blocks;
    private Instr current;

    private TacInvariants(Module module) {
        this.module = module;
    }

    /** Checks every function and returns the number of instructions seen. */
    public static int check(@NonNull Module module) {
        var c = new TacInvariants(module);
        return c.module();
    }

    private int module() {
        for (var s : module.structs) {
            if (!structs.add(s.name())) throw new IllegalStateException("struct %" + s.name() + " defined twice");
        }
        for (var g : module.globals) require(globalNames.add(g.name()), "@" + g.name() + " defined twice");
        for (var d : module.globalDecls) require(globalNames.add(d.name()), "@" + d.name() + " declared and defined");
        for (var f : module.functions) {
            require(functionSigs.put(f.name, f.sig) == null && !globalNames.contains(f.name), "@" + f.name + " defined twice");
        }
        for (var d : module.funcDecls) require(functionSigs.put(d.name(), d.sig()) == null, "@" + d.name() + " declared and defined");
        for (var g : module.globals) global(g);
        int count = 0;
        for (var f : module.functions) count += function(f);
        return count;
    }

    private void global(Global g) {
        require(!(g.type() instanceof Type.Void) && !(g.type() instanceof Type.Func), "@" + g.name() + " has no object type");
        if (g.init() == null) {
            require(!g.readonly(), "@" + g.name() + " is readonly without an initializer");
            return;
        }
        long size = sizeOf(g.type());
        for (var item : g.init()) {
            require(item.offset() >= 0 && item.offset() < size, "@" + g.name() + ": item at " + item.offset() + " outside the object");
            if (item instanceof Global.BytesItem b) {
                require(item.offset() + b.bytes().length <= size, "@" + g.name() + ": bytes item past the end");
            }
            if (item instanceof Global.AddrItem a) {
                require(globalNames.contains(a.name()) || functionSigs.containsKey(a.name()), "@" + g.name() + ": addr of unknown @" + a.name());
            }
        }
    }

    private long sizeOf(Type t) {
        if (t instanceof Type.Int i) return i.width() / 8;
        if (t instanceof Type.Float f) return f.width() == 80 ? 16 : f.width() / 8;
        if (t instanceof Type.Ptr) return module.target.pointerWidth() / 8;
        if (t instanceof Type.Array a) return a.count() * sizeOf(a.element());
        if (t instanceof Type.Struct s) {
            for (var d : module.structs) if (d.name().equals(s.name())) return d.size();
            throw new IllegalStateException("unknown struct %" + s.name());
        }
        throw new IllegalStateException("no size: " + t.spelling());
    }

    private int function(Function f) {
        function = f;
        declared = new HashSet<>();
        for (var v : f.params) require(declared.add(v), "parameter " + v + " declared twice");
        for (var v : f.locals) require(declared.add(v), "variable " + v + " declared twice");
        for (var v : declared) {
            if (v.type instanceof Type.Struct s) require(structs.contains(s.name()), v + ": unknown struct %" + s.name());
            require(!(v.type instanceof Type.Void) && !(v.type instanceof Type.Func), v + " has no object type");
        }
        require(!f.blocks.isEmpty(), "no blocks");
        blocks = new HashSet<>(f.blocks);
        require(blocks.size() == f.blocks.size(), "a block appears twice");
        int count = 0;
        for (var b : f.blocks) {
            require(!b.instrs.isEmpty(), "." + b.name + " is empty");
            for (int i = 0; i < b.instrs.size(); i++) {
                current = b.instrs.get(i);
                boolean last = i == b.instrs.size() - 1;
                require(current.isTerminator() == last, "." + b.name + ": " + (last ? "no terminator" : "terminator before the end"));
                current.accept(this);
                count++;
            }
        }
        var entry = f.entry();
        for (var b : f.blocks) for (var i : b.instrs) {
            if (i instanceof Instr.Br br) require(br.target() != entry, "the entry block has a predecessor");
            if (i instanceof Instr.CondBr cb) require(cb.then() != entry && cb.otherwise() != entry, "the entry block has a predecessor");
            if (i instanceof Instr.Switch sw) {
                require(sw.dflt() != entry, "the entry block has a predecessor");
                for (var c : sw.cases()) require(c.target() != entry, "the entry block has a predecessor");
            }
        }
        return count;
    }

    private void require(boolean ok, String message) {
        if (!ok) {
            String where = function == null ? "" : "@" + function.name + ": ";
            String at = current == null ? "" : " in `" + TacWriter.print(current) + "`";
            throw new IllegalStateException(where + message + at);
        }
    }

    private RegClass classOf(Var v) {
        require(declared.contains(v), v + " is not declared");
        return module.target.classOf(v.type);
    }

    private RegClass classOf(Operand o) {
        if (o instanceof Var v) return classOf(v);
        return o instanceof Operand.IntImm ? null : RegClass.NONE;  // an immediate takes the class of its instruction
    }

    private void sameClass(RegClass c, Operand o) {
        if (o instanceof Var v) {
            require(classOf(v) == c, v + " is not of class " + c);
        } else if (o instanceof Operand.FloatImm) {
            require(c.isFloating(), "a floating immediate in an integer instruction");
        } else {
            require(c.isInteger(), "an integer immediate in a floating instruction");
        }
    }

    // An integer modifier is an Int no wider than the register: .s8 .u8 ... .s64 .u64.
    private void integerModifier(Type mod) {
        require(mod instanceof Type.Int i && i.width() <= module.target.registerWidth(), "an integer instruction needs .sN or .uN");
    }

    // A floating modifier is a precision the target computes in.
    private void precision(Type mod) {
        require(mod instanceof Type.Float f && module.target.hasPrecision(f.width()), "a floating instruction needs a precision the target has");
    }

    private void isPointer(Var v) {
        require(classOf(v) != RegClass.NONE && v.type instanceof Type.Ptr, v + " is not a ptr");
    }

    private void target(Block b) {
        require(blocks.contains(b), "branch to " + b + " outside the function");
    }

    @Override
    public Void visit(Instr.Mov i) {
        RegClass c = classOf(i.dst());
        require(c != RegClass.NONE, i.dst() + " is an aggregate");
        if (i.src() instanceof Var s) {
            RegClass sc = classOf(s);
            require(sc == c, "mov between " + sc + " and " + c);
        } else {
            sameClass(c, i.src());
        }
        if (c.isInteger()) {
            integerModifier(i.mod());
        } else {
            precision(i.mod());
        }
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfVar i) {
        isPointer(i.dst());
        require(declared.contains(i.var()), i.var() + " is not declared");
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfGlobal i) {
        isPointer(i.dst());
        require(globalNames.contains(i.name()) || functionSigs.containsKey(i.name()), "unknown @" + i.name());
        return null;
    }

    @Override
    public Void visit(Instr.Bin i) {
        RegClass c = classOf(i.dst());
        require(i.op().isFloating() ? c.isFloating() : c.isInteger(), i.op().spelling() + " on a " + c + " variable");
        sameClass(c, i.a());
        sameClass(c, i.b());
        if (i.op().isFloating()) {
            precision(i.mod());
        } else {
            integerModifier(i.mod());
        }
        return null;
    }

    @Override
    public Void visit(Instr.Cmp i) {
        require(classOf(i.dst()) == RegClass.INT, "a comparison result must be an integer");
        RegClass c = i.a() instanceof Var v ? classOf(v) : i.b() instanceof Var w ? classOf(w) : null;
        require(c != null, "a comparison of two immediates");
        require(i.op().isFloating() ? c.isFloating() : c.isInteger(), i.op().spelling() + " on " + c + " operands");
        sameClass(c, i.a());
        sameClass(c, i.b());
        return null;
    }

    @Override
    public Void visit(Instr.Cvt i) {
        RegClass d = classOf(i.dst());
        RegClass s = classOf(i.src());
        precision(i.precision());
        switch (i.op()) {
            case I2F, U2F -> require(s.isInteger() && d.isFloating(), "i2f/u2f needs an integer source and a floating destination");
            case F2I, F2U -> require(s.isFloating() && d.isInteger(), "f2i/f2u needs a floating source and an integer destination");
            case FCVT -> require(s.isFloating() && d.isFloating(), "fcvt needs floating operands");
        }
        return null;
    }

    @Override
    public Void visit(Instr.Load i) {
        isPointer(i.ptr());
        RegClass c = classOf(i.dst());
        int w = i.width();
        if (i.ext() == Instr.Ext.FLOAT) {
            require(c.isFloating(), "load.f into a " + c);
            require(module.target.hasPrecision(w), "load.f" + w);
        } else {
            require(c.isInteger(), "an integer load into a " + c);
            require(w == 8 || w == 16 || w == 32 || w == 64, "load." + w);
            require(w <= module.target.registerWidth(), "load." + w + " is wider than the register");
        }
        return null;
    }

    @Override
    public Void visit(Instr.Store i) {
        isPointer(i.ptr());
        int w = i.width();
        if (i.isFloat()) {
            require(module.target.hasPrecision(w), "store.f" + w);
            if (i.value() instanceof Var v) {
                require(classOf(v).isFloating(), "store.f" + w + " of " + v);
            } else {
                require(i.value() instanceof Operand.FloatImm, "store.f of an integer immediate");
            }
        } else {
            require(w == 8 || w == 16 || w == 32 || w == 64, "store." + w);
            if (i.value() instanceof Var v) {
                require(classOf(v).isInteger(), "store." + w + " of " + v);
            } else {
                require(i.value() instanceof Operand.IntImm, "store of a floating immediate");
            }
        }
        return null;
    }

    @Override
    public Void visit(Instr.Copy i) {
        require(i.type().isAggregate(), "copy of a scalar type");
        isPointer(i.dst());
        isPointer(i.src());
        return null;
    }

    @Override
    public Void visit(Instr.Zero i) {
        require(i.type().isAggregate(), "zero of a scalar type");
        isPointer(i.ptr());
        return null;
    }

    @Override
    public Void visit(Instr.Br i) {
        target(i.target());
        return null;
    }

    @Override
    public Void visit(Instr.CondBr i) {
        if (i.cond() instanceof Var v) require(classOf(v).isInteger(), "condbr on a " + classOf(v));
        else require(i.cond() instanceof Operand.IntImm, "condbr on a floating immediate");
        target(i.then());
        target(i.otherwise());
        return null;
    }

    @Override
    public Void visit(Instr.Switch i) {
        require(classOf(i.value()).isInteger(), "switch on a " + classOf(i.value()));
        target(i.dflt());
        var seen = new HashSet<Long>();
        for (var c : i.cases()) {
            require(seen.add(c.value()), "duplicate case " + c.value());
            target(c.target());
        }
        return null;
    }

    @Override
    public Void visit(Instr.Ret i) {
        Type ret = function.sig.ret();
        if (ret instanceof Type.Void) {
            require(i.value() == null, "ret with a value in a void function");
        } else {
            require(i.value() != null, "ret without a value");
            if (ret.isAggregate()) {
                require(i.value() instanceof Var v && v.type instanceof Type.Ptr, "an aggregate ret must be a ptr");
            } else {
                sameClass(module.target.classOf(ret), i.value());
            }
        }
        return null;
    }

    @Override
    public Void visit(Instr.Trap i) {
        return null;
    }

    @Override
    public Void visit(Instr.Call i) {
        Type.Func known = functionSigs.get(i.callee());
        require(known != null, "call of unknown @" + i.callee());
        call(i.sig(), i.args(), i.dst(), i.into());
        return null;
    }

    @Override
    public Void visit(Instr.ICall i) {
        isPointer(i.callee());
        call(i.sig(), i.args(), i.dst(), i.into());
        return null;
    }

    private void call(Type.Func sig, List<Operand> args, Var dst, Var into) {
        require(args.size() >= sig.params().size(), "too few arguments");
        require(sig.variadic() || args.size() == sig.params().size(), "too many arguments");
        for (int k = 0; k < sig.params().size(); k++) {
            Type p = sig.params().get(k);
            Operand a = args.get(k);
            if (p.isAggregate()) require(a instanceof Var v && v.type instanceof Type.Ptr, "aggregate argument " + (k + 1) + " must be a ptr");
            else sameClass(module.target.classOf(p), a);
        }
        Type ret = sig.ret();
        if (ret instanceof Type.Void) {
            require(dst == null && into == null, "a void call with a result");
        } else if (ret.isAggregate()) {
            require(dst == null && into != null, "an aggregate call needs `into` and no result variable");
            isPointer(into);
        } else {
            require(into == null, "`into` on a scalar call");
            if (dst != null) require(classOf(dst) == module.target.classOf(ret), "call result " + dst + " is not of class " + module.target.classOf(ret));
        }
    }
}
