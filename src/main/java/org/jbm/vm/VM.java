package org.jbm.vm;

import org.jbm.cc.tac.Block;
import org.jbm.cc.tac.Function;
import org.jbm.cc.tac.Instr;
import org.jbm.cc.tac.Module;
import org.jbm.cc.tac.Operand;
import org.jbm.cc.tac.Symbol;
import org.jbm.cc.tac.TacVisitor;
import org.jbm.cc.tac.Type;
import org.jbm.cc.tac.Var;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A virtual machine that executes C TAC IR as compiled from `Lower.lower()`
 */
public class VM implements TacVisitor<Void> {

    class SymbolTable {
        LinkedHashMap<String, Symbol> symbols = new LinkedHashMap<>();
    }

    SymbolTable symbolTable = new SymbolTable();

    // The block being executed and the index of the next instruction in
    // it; a terminator changes them, `ret` clears the block.
    private Block block;
    private int pc;

    // Per function frame
    private record Frame(Block block, int pc, LinkedHashMap<Var, Value> vars, Instr.Call caller) {}

    private final Deque<Frame> frames = new ArrayDeque<>();

    private LinkedHashMap<Var, Value> vars() {
        assert frames.peek() != null;
        return frames.peek().vars();
    }

    Memory memory = new Memory();

    private void load(Module mod) {
        for (var s : mod.symbols()) {
            symbolTable.symbols.put(s.name(), s);
        }
    }

    sealed interface Value permits IntValue, FloatValue {
    }

    /** An integer or pointer value, held extended per its variable's type. */
    record IntValue(long value) implements Value {
    }

    record FloatValue(double value) implements Value {
    }

    private Value value(Var v) {
        Value value = vars().get(v);
        if (value == null) {
            throw new IllegalStateException("unbound variable " + v);
        }
        return value;
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
        Symbol symbol = symbolTable.symbols.get(".file");
        if (!(symbol instanceof Function)) {
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
        result = null;
        frames.push(new Frame(null, 0, bind(function, args), null));
        jump(function.entry());
        while (block != null) {
            Instr inst = block.instrs.get(pc);
            pc++;
            inst.accept(this);
        }
        return result;
    }

    // A fresh register file for a call, with the parameters bound.
    private static LinkedHashMap<Var, Value> bind(Function function, List<Value> args) {
        var vars = new LinkedHashMap<Var, Value>();
        for (int i = 0; i < function.params.size(); i++) {
            vars.put(function.params.get(i), args.get(i));
        }
        return vars;
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
            vars().put(i.dst(), new IntValue(extend(v, mod.width(), mod.signed())));
        } else {
            Type.Float precision = (Type.Float) i.mod();
            vars().put(i.dst(), new FloatValue(round(getFloat(i.src()), precision)));
        }
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfVar i) {
        return null;
    }

    @Override
    public Void visit(Instr.AddrOfGlobal i) {
        return null;
    }

    // ---- arithmetic, comparison, conversion ----------------------------------------------------

    @Override
    public Void visit(Instr.Bin i) {
        if (i.mod() instanceof Type.Int mod) {
            long a = getInt(i.a());
            long b = getInt(i.b());
            vars().put(i.dst(), new IntValue(integerOp(i, a, b, mod)));
        } else {
            double a = getFloat(i.a());
            double b = getFloat(i.b());
            vars().put(i.dst(), new FloatValue(floatingOp(i, a, b, (Type.Float) i.mod())));
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
        vars().put(i.dst(), new IntValue(holds ? 1 : 0));
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
                vars().put(i.dst(), new FloatValue(round(d, i.precision())));
            }
            case U2F -> {
                double d = unsignedToDouble(integer(i.src()));
                vars().put(i.dst(), new FloatValue(round(d, i.precision())));
            }
            case F2I -> {
                long v = (long) floating(i.src());
                vars().put(i.dst(), new IntValue(extendTo(v, i.dst())));
            }
            case F2U -> {
                long v = doubleToUnsigned(floating(i.src()));
                vars().put(i.dst(), new IntValue(extendTo(v, i.dst())));
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
            vars().put(i.dst(), new FloatValue(memory.loadFloat(address, i.width())));
        } else {
            boolean signed = i.ext() == Instr.Ext.SIGNED;
            vars().put(i.dst(), new IntValue(memory.loadInt(address, i.width(), signed)));
        }
        return null;
    }

    @Override
    public Void visit(Instr.Store i) {
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
            // .file: the run ends and its value is what step returns
            result = retval;
            return null;
        }
        if (retval != null && caller.dst() != null) {
            vars().put(caller.dst(), retval);
        }
        return null;
    }

    @Override
    public Void visit(Instr.Trap i) {
        throw new IllegalStateException(i.message() + " at " + i.token().location());
    }

    // ---- calls ---------------------------------------------------------------------------------

    @Override
    public Void visit(Instr.Call c) {
        var callee = symbolTable.symbols.get(c.callee());
        if (!(callee instanceof Function target)) {
            throw new IllegalStateException("no definition for @" + c.callee() + " at " + c.token().location());
        }
        List<Value> args = new ArrayList<>();
        for (Operand o : c.args()) {
            args.add(operand(o));
        }
        frames.push(new Frame(block, pc, bind(target, args), c));
        jump(target.entry());
        return null;
    }

    @Override
    public Void visit(Instr.ICall i) {
        return null;
    }
}
