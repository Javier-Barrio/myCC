package org.jbm.mycc.repl.vm;

import org.jbm.mycc.cc.backend.lower.tac.Block;
import org.jbm.mycc.cc.backend.lower.tac.Function;
import org.jbm.mycc.cc.backend.lower.tac.Global;
import org.jbm.mycc.cc.backend.lower.tac.Instr;
import org.jbm.mycc.cc.backend.lower.tac.Module;
import org.jbm.mycc.cc.backend.lower.tac.Operand;
import org.jbm.mycc.cc.backend.lower.tac.RegClass;
import org.jbm.mycc.cc.backend.lower.tac.StructDef;
import org.jbm.mycc.cc.backend.lower.tac.Symbol;
import org.jbm.mycc.cc.backend.lower.tac.TacVisitor;
import org.jbm.mycc.cc.backend.lower.tac.TargetDesc;
import org.jbm.mycc.cc.backend.lower.tac.Type;
import org.jbm.mycc.cc.backend.lower.tac.Var;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A virtual machine that executes C TAC IR as compiled from `Lower.lower()`
 */
public class VM implements TacVisitor<Void> {

    class SymbolTable {
        LinkedHashMap<String, Symbol> symbols = new LinkedHashMap<>();
    }

    SymbolTable symbolTable = new SymbolTable();

    // Functions the VM provides under a name, called when a program calls
    // a declared function it does not define.
    private final LinkedHashMap<String, Builtin> builtins = new LinkedHashMap<>();

    // Where the program's output goes: the builtins write here, and the
    // shell points it at its console.
    private java.io.PrintStream out = System.out;

    public java.io.PrintStream out() {
        return out;
    }

    public void out(java.io.PrintStream stream) {
        out = stream;
    }

    /** Provides {@code name} as an object with these bytes, for a declaration such as {@code extern FILE *stdout}. */
    public void bindObject(String name, byte[] bytes) {
        Long existing = addresses.get(name);
        long address = existing != null ? existing : memory.allocate(bytes.length, 8);
        memory.write(address, bytes);
        addresses.put(name, address);
    }

    // State a builtin keeps for the life of the VM, such as open files.
    private final Map<String, Object> attachments = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T attachment(String key, java.util.function.Supplier<T> initial) {
        return (T) attachments.computeIfAbsent(key, k -> initial.get());
    }

    /** Provides {@code name} as a builtin; a later module defining it takes precedence. */
    public void bind(String name, Builtin builtin) {
        builtins.put(name, builtin);
    }

    /** Withdraws a builtin. */
    public void unbind(String name) {
        builtins.remove(name);
    }

    /** Unbinds a name; its storage stays for whatever still points at it. */
    public void drop(String name) {
        symbolTable.symbols.remove(name);
    }

    /** Every name loaded so far, defined or declared, with what it is. */
    public Map<String, Symbol> symbols() {
        return Collections.unmodifiableMap(symbolTable.symbols);
    }

    public Memory memory() {
        return memory;
    }

    // The block being executed and the index of the next instruction in
    // it; a terminator changes them, `ret` clears the block.
    private Block block;
    private int pc;

    // Per function frame: the caller's return point, the address of each
    // of the callee's variables in the arena, the stack pointer to
    // restore, and the call being served. Every variable, parameter or
    // local, scalar or aggregate, has storage; a scalar is read and
    // written at its type's width, which keeps it extended per its type.
    private record Frame(Block block, int pc, LinkedHashMap<Var, Long> slots, long savedSp, Instr.Call caller,
                         long varargs) {}

    private final Deque<Frame> frames = new ArrayDeque<>();

    Memory memory = new Memory();

    // The target the loaded modules were compiled for, and their
    // structure layouts by name: what a store of a pointer or of an
    // aggregate needs to know its size.
    private TargetDesc target;
    private final LinkedHashMap<String, StructDef> structs = new LinkedHashMap<>();

    // Where each name lives: a global's storage, or a function's code
    // address, a slot that stands for it so a pointer to function is a
    // number like any other. Names keep their address across reloads.
    private final LinkedHashMap<String, Long> addresses = new LinkedHashMap<>();
    private final LinkedHashMap<Long, String> functionAt = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> allocated = new LinkedHashMap<>();

    private void load(Module mod) {
        if (target != null && !target.equals(mod.target)) {
            throw new IllegalStateException("module for " + mod.target.name() + " loaded into a VM running " + target.name());
        }
        target = mod.target;
        for (var s : mod.structs) {
            structs.put(s.name(), s);
        }
        // A global already bound with the same type keeps its storage and
        // contents; any other is placed and initialized. Functions are
        // always rebound; declarations bind nothing.
        List<Global> placed = new ArrayList<>();
        for (Symbol s : mod.symbols()) {
            if (s instanceof Global g) {
                Symbol previous = symbolTable.symbols.get(g.name());
                long size = mod.imageSize(g);
                // the same type by name may have a new layout: the size tells
                boolean keep = previous instanceof Global old && old.type().equals(g.type())
                        && allocated.getOrDefault(g.name(), -1L) == size;
                if (!keep) {
                    addresses.put(g.name(), memory.allocate(size, g.align()));
                    allocated.put(g.name(), size);
                    placed.add(g);
                }
            } else if (s instanceof Function || s instanceof Module.FuncDecl) {
                long address = addresses.computeIfAbsent(s.name(), n -> memory.allocate(8, 8));
                functionAt.put(address, s.name());
            }
            symbolTable.symbols.put(s.name(), s);
        }
        for (Global g : placed) {
            initialize(g, addresses.get(g.name()), allocated.get(g.name()));
        }
    }

    // The initializer items applied in order over zeros.
    private void initialize(Global g, long address, long size) {
        memory.fill(address, size, (byte) 0);
        if (g.init() == null) {
            return;
        }
        for (Global.Item item : g.init()) {
            long at = address + item.offset();
            if (item instanceof Global.IntItem x) {
                memory.storeInt(at, x.type().width(), x.value());
            } else if (item instanceof Global.FloatItem x) {
                memory.storeFloat(at, x.type().width(), x.value());
            } else if (item instanceof Global.BytesItem x) {
                memory.write(at, x.bytes());
            } else if (item instanceof Global.AddrItem x) {
                memory.storeInt(at, target.pointerWidth(), addressOf(x.name()) + x.addend());
            } else if (item instanceof Global.BitItem x) {
                for (int k = 0; k < x.width(); k++) {
                    long bitIndex = x.bit() + k;
                    long byteAt = at + bitIndex / 8;
                    int bit = (int) (bitIndex % 8);
                    long b = memory.loadInt(byteAt, 8, false);
                    b = (b & ~(1L << bit)) | (((x.value() >> k) & 1) << bit);
                    memory.storeInt(byteAt, 8, b);
                }
            }
        }
    }

    /** The function whose code address this is, if any: what a pointer to function names. */
    public Optional<String> functionName(long address) {
        return Optional.ofNullable(functionAt.get(address));
    }

    /** The address of a global or function by name. */
    public long addressOf(String name) {
        Long address = addresses.get(name);
        if (address == null) {
            throw new IllegalStateException("no definition for @" + name);
        }
        return address;
    }

    private int align(Type t) {
        if (t instanceof Type.Array a) {
            return align(a.element());
        }
        if (t instanceof Type.Struct st) {
            return structs.get(st.name()).align();
        }
        return (int) Math.min(size(t), 16);
    }

    // The size in bytes of a memory type, as the compiler laid it out.
    long size(Type t) {
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
            return size(a.element()) * a.count();
        }
        if (t instanceof Type.Struct st) {
            StructDef def = structs.get(st.name());
            if (def == null) {
                throw new IllegalStateException("unknown structure %" + st.name());
            }
            return def.size();
        }
        throw new IllegalStateException("no size for " + t.spelling());
    }

    public sealed interface Value permits IntValue, FloatValue {
    }

    /** An integer or pointer value, held extended per its variable's type. */
    public record IntValue(long value) implements Value {
    }

    public record FloatValue(double value) implements Value {
    }

    // The address of a variable of the running function.
    private long slot(Var v) {
        Long slot = frames.peek().slots().get(v);
        if (slot == null) {
            throw new IllegalStateException(v + " is not a variable of the running function");
        }
        return slot;
    }

    private Value value(Var v) {
        return loadVar(slot(v), v.type);
    }

    private void set(Var v, Value value) {
        storeVar(slot(v), v.type, value);
    }

    private Value loadVar(long address, Type type) {
        if (type instanceof Type.Int t) {
            return new IntValue(memory.loadInt(address, t.width(), t.signed()));
        }
        if (type instanceof Type.Ptr) {
            return new IntValue(memory.loadInt(address, target.pointerWidth(), false));
        }
        if (type instanceof Type.Float f) {
            return new FloatValue(memory.loadFloat(address, f.width()));
        }
        throw new IllegalStateException("an aggregate has no value; take its address");
    }

    private void storeVar(long address, Type type, Value value) {
        if (type instanceof Type.Int t) {
            memory.storeInt(address, t.width(), ((IntValue) value).value());
        } else if (type instanceof Type.Ptr) {
            memory.storeInt(address, target.pointerWidth(), ((IntValue) value).value());
        } else if (type instanceof Type.Float f) {
            memory.storeFloat(address, f.width(), ((FloatValue) value).value());
        } else {
            throw new IllegalStateException("an aggregate has no value; store through its address");
        }
    }

    private long integer(Var v) {
        Value value = value(v);
        if (value instanceof IntValue i) {
            return i.value();
        }
        throw new IllegalStateException(v + " holds a floating value where an integer is needed");
    }

    private double floating(Var v) {
        Value value = value(v);
        if (value instanceof FloatValue f) {
            return f.value();
        }
        throw new IllegalStateException(v + " holds an integer value where a floating one is needed");
    }

    private Value operand(Operand o) {
        if (o instanceof Operand.IntImm imm) {
            return new IntValue(imm.value());
        }
        if (o instanceof Operand.FloatImm imm) {
            return new FloatValue(imm.value());
        }
        return value((Var) o);
    }

    // What the `ret` of the function `call` started returned: null for void.
    private Value result;

    // Loads the module and runs its `.file`, the shell's line; nothing
    // when it has none.
    public Value step(Module m) {
        load(m);
        boolean hasFile = m.functions.stream().anyMatch(f -> f.name.equals(".file"));
        if (!hasFile) {
            return null;
        }
        return call(".file", List.of());
    }

    // Runs the named function with the arguments bound to its parameters:
    // from the entry block, one instruction after another, each dispatched
    // to its handler below, until its `ret`; returns what that `ret`
    // carried, or null for void.
    public Value call(String name, List<Value> args) {
        Symbol symbol = symbolTable.symbols.get(name);
        if (!(symbol instanceof Function function)) {
            throw new IllegalStateException("no definition for @" + name);
        }
        if (args.size() != function.params.size()) {
            throw new IllegalStateException("@" + name + " takes " + function.params.size() + " arguments, given " + args.size());
        }
        frames.clear();
        memory.stackPointer(memory.size());
        result = null;
        frames.push(enter(function, args, null, 0, null));
        jump(function.entry());
        while (block != null) {
            Instr inst = block.instrs.get(pc);
            pc++;
            inst.accept(this);
        }
        return result;
    }

    private static final int MAX_FRAMES = 100_000;

    // A frame for a call: a zeroed slot on the stack for every parameter
    // and local, and the parameters bound; an aggregate parameter's bytes
    // are copied from the pointer the caller passed.
    private Frame enter(Function function, List<Value> args, Block returnBlock, int returnPc, Instr.Call caller) {
        if (frames.size() >= MAX_FRAMES) {
            throw new IllegalStateException("stack overflow: " + MAX_FRAMES + " frames deep in @" + function.name);
        }
        long savedSp = memory.stackPointer();
        var slots = new LinkedHashMap<Var, Long>();
        List<Var> all = new ArrayList<>(function.params);
        all.addAll(function.locals);
        for (Var v : all) {
            long size = size(v.type);
            long slot = memory.push(size, Math.max(align(v.type), v.align));
            memory.fill(slot, size, (byte) 0);
            slots.put(v, slot);
        }
        for (int i = 0; i < function.params.size(); i++) {
            Var p = function.params.get(i);
            long slot = slots.get(p);
            if (target.classOf(p.type) == RegClass.NONE) {
                long from = ((IntValue) args.get(i)).value();
                memory.copy(slot, from, size(p.type));
            } else {
                storeVar(slot, p.type, args.get(i));
            }
        }
        // The unnamed arguments of a variadic call, spilled in order, 8
        // bytes each, where va_start finds them.
        long varargs = 0;
        int extra = args.size() - function.params.size();
        if (function.sig.variadic() && extra > 0) {
            varargs = memory.push(8L * extra, 16);
            for (int k = 0; k < extra; k++) {
                Value v = args.get(function.params.size() + k);
                long at = varargs + 8L * k;
                if (v instanceof FloatValue f) {
                    memory.storeFloat(at, 64, f.value());
                } else {
                    memory.storeInt(at, 64, ((IntValue) v).value());
                }
            }
        }
        return new Frame(returnBlock, returnPc, slots, savedSp, caller, varargs);
    }

    /** The pointer width of the target the loaded modules were compiled for. */
    public int pointerWidth() {
        return target.pointerWidth();
    }

    // Where a va_list keeps the address of the next argument: SysV's
    // overflow_arg_area, at offset 8 after the two 32-bit offsets.
    private static long nextField(long ap) {
        return ap + 8;
    }

    @Override
    public Void visit(Instr.VaStart i) {
        long ap = integer(i.ap());
        memory.storeInt(nextField(ap), target.pointerWidth(), frames.peek().varargs());
        return null;
    }

    // The next argument at its type's width, from its 8-byte slot.
    @Override
    public Void visit(Instr.VaArg i) {
        long field = nextField(integer(i.ap()));
        long at = memory.loadInt(field, target.pointerWidth(), false);
        Type t = i.dst().type;
        if (t instanceof Type.Float) {
            set(i.dst(), new FloatValue(memory.loadFloat(at, 64)));
        } else {
            boolean signed = t instanceof Type.Int n && n.signed();
            set(i.dst(), new IntValue(memory.loadInt(at, 64, signed)));
        }
        memory.storeInt(field, target.pointerWidth(), at + 8);
        return null;
    }

    private void jump(Block target) {
        block = target;
        pc = 0;
    }

    // `condbr` takes `then` when the condition is nonzero.
    private boolean isConditionTrue(Instr.CondBr i) {
        return getInt(i.cond()) != 0;
    }

    // An integer operand's value: the immediate, or the variable's register.
    private long getInt(Operand o) {
        if (o instanceof Operand.IntImm imm) {
            return imm.value();
        }
        return integer((Var) o);
    }

    // A floating operand's value: the immediate, or the variable's register.
    private double getFloat(Operand o) {
        if (o instanceof Operand.FloatImm imm) {
            return imm.value();
        }
        return floating((Var) o);
    }

    // ---- variables and addresses ---------------------------------------------------------------

    @Override
    public Void visit(Instr.Mov i) {
        if (i.mod() instanceof Type.Int mod) {
            long v = getInt(i.src());
            set(i.dst(), new IntValue(extend(v, mod.width(), mod.signed())));
        } else {
            Type.Float precision = (Type.Float) i.mod();
            set(i.dst(), new FloatValue(round(getFloat(i.src()), precision)));
        }
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfVar i) {
        set(i.dst(), new IntValue(slot(i.var())));
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfGlobal i) {
        Long address = addresses.get(i.name());
        if (address == null) {
            throw new IllegalStateException("no definition for @" + i.name() + " at " + i.token().location());
        }
        set(i.dst(), new IntValue(address));
        return null;
    }

    // ---- arithmetic, comparison, conversion ----------------------------------------------------

    @Override
    public Void visit(Instr.Bin i) {
        if (i.mod() instanceof Type.Int mod) {
            long a = getInt(i.a());
            long b = getInt(i.b());
            set(i.dst(), new IntValue(integerOp(i, a, b, mod)));
        } else {
            double a = getFloat(i.a());
            double b = getFloat(i.b());
            set(i.dst(), new FloatValue(floatingOp(i, a, b, (Type.Float) i.mod())));
        }
        return null;
    }

    // An integer operation at the width and signedness of its modifier:
    // computed on the register, then the low N bits extended as the
    // modifier says, which is how every value is held.
    private long integerOp(Instr.Bin i, long a, long b, Type.Int mod) {
        long result = switch (i.op()) {
            case WADD, ADD -> a + b;
            case WSUB, SUB -> a - b;
            case WMUL, MUL -> a * b;
            case SDIV -> divide(i, a, b, true, mod);
            case SREM -> remainder(i, a, b, true, mod);
            case UDIV -> divide(i, a, b, false, mod);
            case UREM -> remainder(i, a, b, false, mod);
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SHL -> a << b;
            case LSHR -> extend(a, mod.width(), false) >>> b;
            case ASHR -> extend(a, mod.width(), true) >> b;
            default -> throw new IllegalStateException(i.op() + " is not an integer operation at " + i.token().location());
        };
        return extend(result, mod.width(), mod.signed());
    }

    private long divide(Instr.Bin i, long a, long b, boolean signed, Type.Int mod) {
        if (b == 0) {
            throw new IllegalStateException("division by zero at " + i.token().location());
        }
        if (signed) {
            return a / b;
        }
        return Long.divideUnsigned(extend(a, mod.width(), false), extend(b, mod.width(), false));
    }

    private long remainder(Instr.Bin i, long a, long b, boolean signed, Type.Int mod) {
        if (b == 0) {
            throw new IllegalStateException("division by zero at " + i.token().location());
        }
        if (signed) {
            return a % b;
        }
        return Long.remainderUnsigned(extend(a, mod.width(), false), extend(b, mod.width(), false));
    }

    // The low `width` bits of a value, sign- or zero-extended to 64.
    private static long extend(long v, int width, boolean signed) {
        int shift = 64 - width;
        if (shift == 0) {
            return v;
        }
        if (signed) {
            return (v << shift) >> shift;
        }
        return (v << shift) >>> shift;
    }

    // A floating operation at the precision of its modifier: computed as
    // a double, rounded to single precision when the modifier is f32.
    private double floatingOp(Instr.Bin i, double a, double b, Type.Float precision) {
        double result = switch (i.op()) {
            case FADD -> a + b;
            case FSUB -> a - b;
            case FMUL -> a * b;
            case FDIV -> a / b;
            default -> throw new IllegalStateException(i.op() + " is not a floating operation at " + i.token().location());
        };
        return round(result, precision);
    }

    private static double round(double v, Type.Float precision) {
        if (precision.width() == 32) {
            return (float) v;
        }
        return v;
    }

    @Override
    public Void visit(Instr.Cmp i) {
        boolean holds;
        if (i.op().isFloating()) {
            holds = floatingCompare(i, getFloat(i.a()), getFloat(i.b()));
        } else {
            holds = integerCompare(i, getInt(i.a()), getInt(i.b()));
        }
        set(i.dst(), new IntValue(holds ? 1 : 0));
        return null;
    }

    // Operands are held extended per their types, so a signed comparison
    // is a plain one on the registers and an unsigned one compares them
    // as unsigned 64-bit values.
    private boolean integerCompare(Instr.Cmp i, long a, long b) {
        return switch (i.op()) {
            case EQ -> a == b;
            case NE -> a != b;
            case SLT -> a < b;
            case SLE -> a <= b;
            case ULT -> Long.compareUnsigned(a, b) < 0;
            case ULE -> Long.compareUnsigned(a, b) <= 0;
            default -> throw new IllegalStateException(i.op() + " is not an integer comparison at " + i.token().location());
        };
    }

    // Java's floating comparisons are IEEE: every one but != is false
    // when either side is NaN, as in C.
    private boolean floatingCompare(Instr.Cmp i, double a, double b) {
        return switch (i.op()) {
            case FEQ -> a == b;
            case FNE -> a != b;
            case FLT -> a < b;
            case FLE -> a <= b;
            default -> throw new IllegalStateException(i.op() + " is not a floating comparison at " + i.token().location());
        };
    }

    @Override
    public Void visit(Instr.Cvt i) {
        switch (i.op()) {
            case I2F -> {
                double d = (double) integer(i.src());
                set(i.dst(), new FloatValue(round(d, i.precision())));
            }
            case U2F -> {
                double d = unsignedToDouble(integer(i.src()));
                set(i.dst(), new FloatValue(round(d, i.precision())));
            }
            case F2I -> {
                long v = (long) floating(i.src());
                set(i.dst(), new IntValue(extendTo(v, i.dst())));
            }
            case F2U -> {
                long v = doubleToUnsigned(floating(i.src()));
                set(i.dst(), new IntValue(extendTo(v, i.dst())));
            }
        }
        return null;
    }

    // A 64-bit register as an unsigned number: values with the top bit
    // set are 2^63 and above.
    private static double unsignedToDouble(long v) {
        if (v >= 0) {
            return (double) v;
        }
        double half = (double) (v >>> 1);
        return half * 2.0 + (v & 1);
    }

    // Truncation toward zero into an unsigned 64-bit value; out of range
    // is undefined in C, so a value at or above 2^63 goes through the
    // signed range.
    private static long doubleToUnsigned(double d) {
        double twoTo63 = 9223372036854775808.0;
        if (d < twoTo63) {
            return (long) d;
        }
        return ((long) (d - twoTo63)) ^ Long.MIN_VALUE;
    }

    // A converted integer held extended per the destination's own type.
    private static long extendTo(long v, Var dst) {
        Type.Int type = (Type.Int) dst.type;
        return extend(v, type.width(), type.signed());
    }

    // ---- memory --------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Load i) {
        long address = integer(i.ptr());
        if (i.ext() == Instr.Ext.FLOAT) {
            set(i.dst(), new FloatValue(memory.loadFloat(address, i.width())));
        } else {
            boolean signed = i.ext() == Instr.Ext.SIGNED;
            set(i.dst(), new IntValue(memory.loadInt(address, i.width(), signed)));
        }
        return null;
    }

    @Override
    // The type on the instruction says what is written: an integer or
    // pointer's bits, a floating value, or for an aggregate the bytes
    // found at the pointer the value holds, or zeros for the immediate 0.
    public Void visit(Instr.Store i) {
        long address = integer(i.ptr());
        Type type = i.type();
        if (type instanceof Type.Int t) {
            memory.storeInt(address, t.width(), getInt(i.value()));
        } else if (type instanceof Type.Ptr) {
            memory.storeInt(address, target.pointerWidth(), getInt(i.value()));
        } else if (type instanceof Type.Float f) {
            memory.storeFloat(address, f.width(), getFloat(i.value()));
        } else if (i.value() instanceof Operand.IntImm) {
            memory.fill(address, size(type), (byte) 0);
        } else {
            memory.copy(address, integer((Var) i.value()), size(type));
        }
        return null;
    }

    // ---- control -------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Br i) {
        jump(i.target());
        return null;
    }

    @Override
    public Void visit(Instr.CondBr i) {
        if (isConditionTrue(i)) {
            jump(i.then());
        } else {
            jump(i.otherwise());
        }
        return null;
    }

    @Override
    // The case whose value matches takes its block; none, the default.
    // Cases are distinct, so the first match is the only one.
    public Void visit(Instr.Switch i) {
        long value = integer(i.value());
        for (Instr.Case c : i.cases()) {
            if (c.value() == value) {
                jump(c.target());
                return null;
            }
        }
        jump(i.dflt());
        return null;
    }

    @Override
    public Void visit(Instr.Ret i) {
        Value retval = null;
        if (i.value() != null) {
            retval = operand(i.value());
        }
        Frame frame = frames.pop();
        block = frame.block();
        pc = frame.pc();
        Instr.Call caller = frame.caller();
        if (caller == null) {
            // the function `call` started: the run ends and its value is the result
            memory.stackPointer(frame.savedSp());
            result = retval;
            return null;
        }
        if (caller.into() != null) {
            // an aggregate result: its bytes, possibly in the callee's frame, copied out before the frame goes
            memory.copy(integer(caller.into()), ((IntValue) retval).value(), size(caller.sig().ret()));
        } else if (retval != null && caller.dst() != null) {
            set(caller.dst(), retval);
        }
        memory.stackPointer(frame.savedSp());
        return null;
    }

    @Override
    public Void visit(Instr.Trap i) {
        throw new IllegalStateException(i.message() + " at " + i.token().location());
    }

    // ---- calls ---------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Call c) {
        callByName(c.callee(), c);
        return null;
    }

    // A defined function runs in a new frame; a declared one goes to its
    // builtin, whose result is written like a returned value.
    private void callByName(String name, Instr.Call c) {
        Symbol callee = symbolTable.symbols.get(name);
        if (callee instanceof Function target) {
            invoke(target, c);
            return;
        }
        Builtin builtin = builtins.get(name);
        if (builtin == null) {
            throw new IllegalStateException("no definition for @" + name + " at " + c.token().location());
        }
        if (c.into() != null) {
            throw new IllegalStateException("builtin @" + name + " cannot return an aggregate at " + c.token().location());
        }
        List<Value> args = new ArrayList<>();
        for (Operand o : c.args()) {
            args.add(operand(o));
        }
        Value result = builtin.call(this, args);
        if (c.dst() != null) {
            if (result == null) {
                throw new IllegalStateException("builtin @" + name + " returned nothing at " + c.token().location());
            }
            set(c.dst(), result);
        }
    }

    // Through a pointer to function: the address names the function.
    @Override
    public Void visit(Instr.ICall i) {
        long address = integer(i.callee());
        String name = functionAt.get(address);
        if (name == null) {
            throw new IllegalStateException("call through a bad function pointer 0x" + Long.toHexString(address) + " at " + i.token().location());
        }
        callByName(name, new Instr.Call(i.dst(), i.sig(), name, i.args(), i.into(), i.token()));
        return null;
    }

    private void invoke(Function target, Instr.Call c) {
        List<Value> args = new ArrayList<>();
        for (Operand o : c.args()) {
            args.add(operand(o));
        }
        frames.push(enter(target, args, block, pc, c));
        jump(target.entry());
    }
}
